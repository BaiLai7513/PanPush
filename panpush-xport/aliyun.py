#!/data/data/com.termux/files/usr/bin/env python3
# -*- coding: utf-8 -*-
"""aliyun.py — 阿里云盘解析器（panpush 站点插件，零第三方依赖，自包含）。

一句话：分享链接 → 匿名列目录 → **转存到我的云盘** → 取 OSS 直链（可多线程）→ 回收副本。

为什么是这套流程（2026-10 真机实测，详见 docs/阿里云盘接入设计与实现.md）
--------------------------------------------------------------------
* 匿名分享令牌（get_share_token）能列目录，但 **第三方直接下载分享文件被禁**
  （`Forbidden` / `ForbiddenNoPermission.File`）；
* 登录态取 **个人文件** 直链正常（`/v2/file/get_download_url` + Authorization）；
* 所以正确姿势：把分享文件 `POST /v2/file/copy` 转存到自己的云盘，再对副本取直链，
  拿到直链后 `POST /v2/recyclebin/trash` 回收副本（实测：回收后直链仍可用，
  STS 签名独立于文件是否在列）。个人云盘文件可跳过转存一步。
* 直链是阿里 OSS（STS 临时凭证，约 15 分钟有效），`Accept-Ranges: bytes`，
  **按连接限速**（实测 1→128 连接：0.12→4.45 MB/s）→ 多连接能显著提速。

两个硬门槛
----------
1. **体积门控**：第三方直链只对"小文件"发放。实测 ≤66MB 可取到直链，
   ≥127MB 一律返回空 `url`（HTTP 200 软拒，疑似 100MB 阈值；免费账号）。
   本模块对 ≥ SIZE_GATE 的文件直接跳过并给出说明（`notes`）。
2. **取直链频控**：短时间连续请求返回空 `url`，间隔 ~20s 恢复 →
   本模块内建"空 url 退避重试"。

凭据（root 直读 + 自动续期）
--------------------------
阿里云盘 APP 把 token **明文**存在 SQLite：
    /data/data/com.alicloud.databox/databases/0.db  →  表 tb_user_info
字段：access_token / refresh_token / expire_time / drive_id / user_id
本模块直接读该库（自动处理 WAL），access_token 过期或快过期时用 refresh_token
调 `POST /v2/account/token` 续期（实测 auth.aliyundrive.com / auth.alipan.com 均可）。

独立自测：
    python3 aliyun.py --creds
    python3 aliyun.py '<分享链接>' [提取码] --list-only
"""
import json
import os
import re
import shutil
import sqlite3
import sys
import tempfile
import time
import calendar
import urllib.error
import urllib.parse
import urllib.request

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------
API = "https://api.aliyundrive.com"
AUTH = "https://auth.aliyundrive.com"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
X_CANARY = "client=web,app=adrive,version=v2.3.1"
REFERER = "https://www.alipan.com/"

PKG = "com.alicloud.databox"
DB = "/data/data/%s/databases/0.db" % PKG

COOKIE_FILE = "/sdcard/Make/Mk/panpush/.cookie.aliyun"
COOKIE_FILE_ALT = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cookie.aliyun")

# 第三方直链体积门控（实测 66MB 可 / 127MB 不可；此处取 100MB 作保守阈值）
SIZE_GATE = 100 * 1024 * 1024
# 直链默认并发（按连接限速；实测 64~128 连接最佳）
DEFAULT_CONNECTIONS = 64

# 分享链接识别（aliyundrive.com / alipan.com）
DOMAIN_RE = re.compile(r"https?://(?:[a-z0-9-]+\.)?(?:aliyundrive|alipan)\.com/s/", re.I)

COOKIE_GUIDE = (
    "阿里云盘需要登录态：\n"
    "      A) root 直读（推荐，本机已 root）：\n"
    "           确保【阿里云盘 APP 已登录】，本模块会自动读其数据库（无需手动配置）。\n"
    "      B) 手动落盘：把下列内容存成 .cookie.aliyun\n"
    "           access_token=eyJ...\n"
    "           refresh_token=xxxx\n"
    "           drive_id=123456789\n"
    "      C) 只给 access_token 也可：python3 panpush.py '<分享链接>' --cookie '<access_token>'\n"
    "      校验：python3 aliyun.py --creds"
)


# ---------------------------------------------------------------------------
# 通用小工具（自包含）
# ---------------------------------------------------------------------------
def _req(method, url, headers=None, body=None, timeout=20):
    """返回 (status, text)。HTTP 错误也返回状态码而非抛出。"""
    data = None
    h = {"User-Agent": UA, "Accept": "application/json, text/plain, */*"}
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        h["Content-Type"] = "application/json"
    if headers:
        h.update(headers)
    req = urllib.request.Request(url, data=data, headers=h)
    req.get_method = lambda: method
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)


def _parse_json(text):
    try:
        return json.loads(text)
    except Exception:
        return None


def _parse_iso(s):
    """'2026-10-11T15:04:29Z' → epoch 秒；失败返回 0。"""
    if not s:
        return 0
    try:
        return calendar.timegm(time.strptime(s[:19], "%Y-%m-%dT%H:%M:%S"))
    except Exception:
        return 0


def human_size(n):
    try:
        n = float(n)
    except Exception:
        return str(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return "%.2f %s" % (n, unit)
        n /= 1024.0


def is_aliyun_url(url):
    return bool(DOMAIN_RE.search(url or ""))


def extract_share_id(url):
    m = re.search(r"/s/([0-9A-Za-z_\-]+)", url or "")
    return m.group(1) if m else None


def extract_passcode(url):
    m = re.search(r"[?&#](?:pwd|pass_code|password)=([^&#]+)", url or "")
    return urllib.parse.unquote(m.group(1)) if m else ""


# ---------------------------------------------------------------------------
# 凭据：root 直读 APP 数据库 + 自动续期
# ---------------------------------------------------------------------------
class AliyunError(RuntimeError):
    pass


def read_app_creds():
    """从阿里云盘 APP 的 SQLite（tb_user_info）读明文 token。失败返回 None。"""
    if not os.path.exists(DB):
        return None
    td = None
    try:
        td = tempfile.mkdtemp(prefix="ali_db_")
        for suffix in ("", "-wal", "-shm"):
            src = DB + suffix
            if os.path.exists(src):
                shutil.copy2(src, os.path.join(td, os.path.basename(src)))
        con = sqlite3.connect(os.path.join(td, os.path.basename(DB)))
        con.row_factory = sqlite3.Row
        rows = [dict(r) for r in con.execute("select * from tb_user_info")]
        con.close()
        if not rows:
            return None
        rows.sort(key=lambda r: _parse_iso(r.get("expire_time")), reverse=True)
        r = rows[0]
        if not r.get("access_token") or not r.get("refresh_token"):
            return None
        return {
            "access_token": r["access_token"],
            "refresh_token": r["refresh_token"],
            "drive_id": str(r.get("drive_id") or ""),
            "expire_time": r.get("expire_time") or "",
            "source": "app(%s)" % DB,
        }
    except Exception:
        return None
    finally:
        if td:
            shutil.rmtree(td, ignore_errors=True)


def read_saved_creds():
    """读取 .cookie.aliyun（key=value 行；或整行即 access_token）。"""
    for p in (COOKIE_FILE, COOKIE_FILE_ALT):
        try:
            with open(p) as fh:
                txt = fh.read().strip()
        except Exception:
            continue
        if not txt:
            continue
        d = {}
        for line in txt.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
        if not d:  # 整行就是 token
            d = {"access_token": txt}
        if d.get("access_token"):
            d.setdefault("drive_id", "")
            d["source"] = p
            return d
    return None


def load_creds():
    """优先实时直读（最新），其次落盘文件。"""
    return read_app_creds() or read_saved_creds()


def creds_from_text(text):
    """把 --cookie 传入的文本解析成凭据 dict。"""
    if not text:
        return None
    d = {}
    for line in re.split(r"[;\n]+", text):
        line = line.strip()
        if not line:
            continue
        if "=" in line:
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
    if not d.get("access_token"):
        m = re.search(r"access_token=([A-Za-z0-9._\-]+)", text)
        if m:
            d["access_token"] = m.group(1)
    if not d.get("access_token") and re.match(r"^[A-Za-z0-9._\-]{20,}$", text.strip()):
        d = {"access_token": text.strip()}
    if d.get("access_token"):
        d.setdefault("drive_id", "")
        d["source"] = "cli"
        return d
    return None


def creds_expire_soon(creds, margin=120):
    exp = _parse_iso(creds.get("expire_time"))
    if not exp:
        return False  # 没有过期时间信息 → 当作可用，出错再刷新
    return exp - time.time() <= margin


def refresh_creds(refresh_token, drive_id=None):
    """用 refresh_token 换新 token。成功返回 dict，失败返回 None。"""
    if not refresh_token:
        return None
    st, txt = _req("POST", AUTH + "/v2/account/token",
                   body={"grant_type": "refresh_token", "refresh_token": refresh_token})
    j = _parse_json(txt) or {}
    if not j.get("access_token"):
        return None
    return {
        "access_token": j["access_token"],
        "refresh_token": j.get("refresh_token") or refresh_token,
        "drive_id": str(j.get("default_drive_id") or j.get("drive_id") or drive_id or ""),
        "expire_time": j.get("expire_time") or "",
        "source": "refresh",
    }


# ---------------------------------------------------------------------------
# 解析器
# ---------------------------------------------------------------------------
class AliyunParser:
    """接口与 panpush 其它解析器一致：
       __init__(url, passcode, cookie, timeout, token=...)
       parse(list_only=False) -> {"site","files","downloads","notes"}
    说明：cookie/token 仅作为"显式凭据"覆盖；默认走 load_creds()（root 直读）。
    """

    name = "aliyun"
    label = "阿里云盘"

    def __init__(self, url, passcode="", cookie=None, timeout=20, token=None):
        self.url = (url or "").strip()
        self.passcode = (passcode or "").strip() or extract_passcode(self.url)
        self.timeout = timeout or 20
        self.share_id = extract_share_id(self.url)
        self._explicit = creds_from_text(cookie) or ({"access_token": token, "drive_id": "", "source": "cli-token"} if token else None)
        self._creds = None
        self._drive_id = ""
        self._share_token = None
        self.auto_clean = not os.environ.get("ALIYUN_NO_CLEAN")

    # ---- 凭据 ----
    def _ensure_fresh(self):
        c = self._creds
        if c and c.get("refresh_token") and creds_expire_soon(c):
            nc = refresh_creds(c["refresh_token"], c.get("drive_id"))
            if nc:
                if not nc.get("drive_id"):
                    nc["drive_id"] = c.get("drive_id")
                nc["refresh_token"] = nc.get("refresh_token") or c["refresh_token"]
                self._creds = nc

    def _creds_now(self):
        if self._creds is None:
            self._creds = self._explicit or load_creds()
        if not self._creds:
            raise AliyunError(COOKIE_GUIDE)
        self._ensure_fresh()
        return self._creds

    def _touch_auth_error(self, j):
        """若返回 token 失效类错误，尝试续期一次，返回是否已刷新。"""
        code = str(j.get("code") or "")
        if "AccessToken" in code or "Token" in code and "Invalid" in code:
            c = self._creds or {}
            nc = refresh_creds(c.get("refresh_token"), c.get("drive_id"))
            if nc:
                if not nc.get("drive_id"):
                    nc["drive_id"] = c.get("drive_id")
                self._creds = nc
                return True
        return False

    def _auth_hdr(self, share=False):
        c = self._creds_now()
        h = {"Authorization": "Bearer " + c["access_token"], "X-Canary": X_CANARY}
        if share and self._share_token:
            h["x-share-token"] = self._share_token
        return h

    # ---- 分享令牌 ----
    def _get_share_token(self):
        if self._share_token:
            return self._share_token
        st, txt = _req("POST", API + "/v2/share_link/get_share_token",
                       headers={"X-Canary": X_CANARY},
                       body={"share_id": self.share_id, "share_pwd": self.passcode},
                       timeout=self.timeout)
        j = _parse_json(txt) or {}
        if not j.get("share_token"):
            code = str(j.get("code") or "")
            msg = j.get("message") or txt[:160]
            if "Cancelled" in code:
                raise AliyunError("分享已被作者取消")
            if "ShareLink" in code:
                raise AliyunError("分享链接不存在或已失效：%s" % msg)
            if "密码" in msg or "password" in msg.lower():
                raise AliyunError("需要提取码（当前：%r）→ 把提取码作为第二个参数" % self.passcode)
            raise AliyunError("获取分享令牌失败：%s" % msg)
        self._share_token = j["share_token"]
        return self._share_token

    # ---- 列目录 ----
    def list_files(self):
        self._get_share_token()
        out, seen = [], set()
        self._walk("root", out, seen, 0)
        return out

    def _walk(self, parent, out, seen, depth):
        if depth > 12:
            return
        marker = ""
        for _ in range(100):
            body = {"share_id": self.share_id, "parent_file_id": parent, "limit": 100,
                    "order_by": "name", "order_direction": "ASC", "fields": "*"}
            if marker:
                body["marker"] = marker
            st, txt = _req("POST", API + "/adrive/v3/file/list",
                           headers={"x-share-token": self._share_token, "X-Canary": X_CANARY},
                           body=body, timeout=self.timeout)
            j = _parse_json(txt) or {}
            items = j.get("items") or []
            for it in items:
                fid = it.get("file_id")
                nm = it.get("name") or "未命名"
                if not fid:
                    continue
                if it.get("type") == "folder":
                    if fid not in seen:
                        seen.add(fid)
                        self._walk(fid, out, seen, depth + 1)
                else:
                    out.append({"fid": fid, "name": nm,
                                "size": int(it.get("size") or 0), "dir": False})
            marker = j.get("next_marker") or ""
            if not marker or not items:
                break

    # ---- 转存 / 取直链 / 回收 ----
    def copy_file(self, file_id):
        body = {"file_id": file_id, "share_id": self.share_id,
                "to_drive_id": self._drive_id, "to_parent_file_id": "root",
                "overwrite": True}
        st, txt = _req("POST", API + "/v2/file/copy",
                       headers=self._auth_hdr(share=True), body=body, timeout=self.timeout)
        j = _parse_json(txt) or {}
        if not j.get("file_id"):
            raise AliyunError("转存失败：%s" % (j.get("message") or txt[:160]))
        return j["file_id"]

    def download_url(self, file_id, retries=3, gap=15):
        body = {"drive_id": self._drive_id, "file_id": file_id, "expire_sec": 900}
        last = ""
        for i in range(retries):
            st, txt = _req("POST", API + "/v2/file/get_download_url",
                           headers=self._auth_hdr(), body=body, timeout=self.timeout)
            j = _parse_json(txt) or {}
            u = j.get("url") or j.get("cdn_url")
            if u:
                return u
            last = j.get("message") or txt[:160]
            if j.get("code") and self._touch_auth_error(j):
                continue
            if j.get("code"):
                raise AliyunError("取直链失败：%s" % last)
            if i < retries - 1:      # 空 url → 频控，退避重试
                time.sleep(gap)
        raise AliyunError("取直链失败（频控/体积门控）：%s" % last)

    def trash(self, file_id):
        try:
            _req("POST", API + "/v2/recyclebin/trash", headers=self._auth_hdr(),
                 body={"drive_id": self._drive_id, "file_id": file_id}, timeout=self.timeout)
        except Exception:
            pass

    # ---- 对外 ----
    def parse(self, list_only=False):
        if not self.share_id:
            raise AliyunError("无法从链接识别阿里分享 ID（形如 aliyundrive.com/s/<ID>）")
        c = self._creds_now()
        self._drive_id = self._drive_id or c.get("drive_id") or ""

        items = self.list_files()
        files = [{"name": it["name"], "size": it["size"]} for it in items]
        notes, downloads = [], []

        if list_only:
            for it in items:
                if it["size"] >= SIZE_GATE:
                    notes.append("%s（%.1f MB）超过第三方体积门控，可能无法取直链"
                                 % (it["name"], it["size"] / 1048576.0))
            return {"site": self.name, "files": files, "downloads": [], "notes": notes}

        if not self._drive_id:
            raise AliyunError("缺少 drive_id（凭据不完整）")

        for it in items:
            size = it["size"]
            if size >= SIZE_GATE:
                notes.append("[跳过] %s（%.1f MB）超体积门控（第三方直链被阿里限制，疑似会员可解除）"
                             % (it["name"], size / 1048576.0))
                continue
            try:
                nfid = self.copy_file(it["fid"])
            except AliyunError as e:
                notes.append("[跳过] %s：%s" % (it["name"], e))
                continue
            url = None
            try:
                url = self.download_url(nfid)
            except AliyunError as e:
                notes.append("[跳过] %s：%s" % (it["name"], e))
            finally:
                if self.auto_clean:
                    self.trash(nfid)
            if url:
                downloads.append({
                    "name": it["name"], "size": size, "url": url,
                    "headers": {"User-Agent": UA, "Referer": REFERER},
                })
        return {"site": self.name, "files": files, "downloads": downloads, "notes": notes}


# ---------------------------------------------------------------------------
# 独立 CLI（自测用）
# ---------------------------------------------------------------------------
def _main(argv):
    import argparse
    ap = argparse.ArgumentParser(description="阿里云盘解析器（自测）")
    ap.add_argument("url", nargs="?", help="分享链接")
    ap.add_argument("passcode", nargs="?", default="", help="提取码（可选）")
    ap.add_argument("--list-only", action="store_true")
    ap.add_argument("--creds", action="store_true", help="只看凭据状态")
    ap.add_argument("--no-clean", action="store_true", help="不回收转存副本")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--timeout", type=int, default=20)
    a = ap.parse_args(argv)

    if a.no_clean:
        os.environ["ALIYUN_NO_CLEAN"] = "1"

    if a.creds or not a.url:
        c = load_creds()
        if not c:
            print("未找到凭据。\n" + COOKIE_GUIDE)
            return 1
        exp = _parse_iso(c.get("expire_time"))
        left = int(exp - time.time()) if exp else -1
        print("来源: %s" % c.get("source"))
        print("drive_id: %s" % c.get("drive_id"))
        print("access_token: %s..." % c["access_token"][:24])
        print("refresh_token: %s..." % (c.get("refresh_token") or "")[:16])
        print("expire_time: %s（剩余 %ss）" % (c.get("expire_time"), left))
        return 0

    p = AliyunParser(a.url, a.passcode, None, a.timeout)
    try:
        r = p.parse(list_only=a.list_only)
    except AliyunError as e:
        print("解析失败: %s" % e, file=sys.stderr)
        return 2
    if a.json:
        print(json.dumps(r, ensure_ascii=False, indent=2))
        return 0
    print("站点: %s" % r["site"])
    for f in r["files"]:
        print("  - %s  (%s)" % (f["name"], human_size(f["size"])))
    for n in r.get("notes", []):
        print("  [!] %s" % n)
    for d in r["downloads"]:
        print("直链: %s  (%s)" % (d["name"], human_size(d["size"])))
        print("      %s" % d["url"][:120])
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
