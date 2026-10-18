#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
xunlei.py —— panpush 的迅雷云盘解析器（只读：列目录 + 取直链）

设计要点（2026-10 实测定稿，详见 ../xunlei-ref/协议分析.md）：
  * 迅雷分享 API 需要三件套：X-Client-Id / X-Device-Id / X-Captcha-Token(+Authorization)。
  * 官方网页端已把 captcha 签名换成「白盒混淆的公钥签名」，算法不可移植 →
    本实现**不复现签名**，而是直接从迅雷 APP 私有目录 root 读取现成凭据。
  * captcha 令牌寿命只有几分钟，且只有 APP 处理「分享深链」时才会刷新 →
    解析前校验新鲜度，过期则用**当前分享链接**触发一次 APP 刷新再重读。
  * 直链下载必须 `User-Agent: okhttp/4.9.0`（其它 UA 一律 403）。
  * 分享直链被服务端限速（~6 Mbps）；个人云盘直链不限速（~48 Mbps）。

本文件零第三方依赖，可独立运行自测：
    python3 xunlei.py '<分享链接>' --list-only
    python3 xunlei.py --creds          # 只看凭据状态
"""

import os
import re
import sys
import json
import time
import html
import glob
import subprocess
import urllib.parse
import urllib.request
import urllib.error

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

API_BASE = "https://api-pan.xunlei.com/drive/v1"
CLIENT_ID = "Xqp0kJBXWhwaTpB6"        # WEB 端 client id（APP 的 aud 亦可，两者都能用）
CLIENT_VERSION = "1.93.8"             # 网页端当前版本；变了改这里即可
UA_APP = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
UA_DL = "okhttp/4.9.0"                # 直链下载首选 UA（迅猛雷分享链实测唯一可用）
# 不同来源的直链认的 UA 不同（例：X网盘助手平台用 netdiskP2SP/3.0.20.138）。
# 这里做候选 + 自动探测，避免写死一个 UA 后某类链接全 403。
UA_DL_CANDIDATES = [
    "okhttp/4.9.0",
    "netdiskP2SP/3.0.20.138",
    "Thunder/1.0",
    "Mozilla/5.0",
]
PAGE_SIZE = 100
MAX_PAGES = 100

PKG = "com.xunlei.downloadprovider"
PREFS = "/data/data/%s/shared_prefs" % PKG
COOKIE_FILE = "/sdcard/Make/Mk/panpush/.cookie.xunlei"
COOKIE_FILE_ALT = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cookie.xunlei")

COOKIE_GUIDE = (
    "迅雷云盘需要从 APP 读取三件套凭据（Authorization / Captcha / DeviceId）。\n"
    "      A) root 直读（推荐，本机已 root）：\n"
    "           确保迅雷 APP 已登录，然后直接解析即可；本模块会自动读取。\n"
    "      B) 手动落盘：把下列内容存成 .cookie.xunlei（内容示例）\n"
    "           authorization=Bearer <jwt>\n"
    "           captcha_token=ck0.<...>\n"
    "           device_id=<APP 的 device_id_new>\n"
    "      C) 校验：python3 xunlei.py --creds"
)


# ---------------------------------------------------------------------------
# 通用小工具（自包含，避免依赖 panpush 主模块）
# ---------------------------------------------------------------------------

def _http(method, url, headers=None, timeout=20):
    req = urllib.request.Request(url, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, str(e)


def _get(url, headers=None, timeout=20):
    return _http("GET", url, headers, timeout)


def _parse_json(text):
    try:
        return json.loads(text)
    except Exception:
        return None


def human_size(n):
    try:
        n = float(n)
    except Exception:
        return str(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return "%.2f %s" % (n, unit)
        n /= 1024.0


def extract_passcode(url):
    """从分享链接 query / fragment 提取提取码（pwd= / pass_code=）。"""
    m = re.search(r"[?&#](?:pwd|pass_code|password)=([^&#]+)", url or "")
    return urllib.parse.unquote(m.group(1)) if m else ""


def extract_share_id(url):
    m = re.search(r"pan\.xunlei\.com/s/([0-9A-Za-z_\-]+)", url or "")
    return m.group(1) if m else None


# ---------------------------------------------------------------------------
# 凭据：root 直读迅雷 APP 私有目录
# ---------------------------------------------------------------------------

class XunleiCredsError(RuntimeError):
    pass


class XunleiCaptchaError(RuntimeError):
    pass


def _parse_pref(path):
    """把 Android shared_prefs XML 解析成 {name: value}（含 html 反转义）。"""
    with open(path, encoding="utf-8", errors="ignore") as fh:
        txt = fh.read()
    d = {}
    for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', txt):
        d[m.group(1)] = html.unescape(m.group(2))
    for m in re.finditer(r'<(?:long|int|boolean) name="([^"]+)" value="([^"]*)"', txt):
        d[m.group(1)] = m.group(2)
    return d


def _latest_captcha(pref):
    """取有效期最长的 captcha 令牌，返回 (token, expiresat_epoch)。

    ★ 易错点：键名后缀 'captchatoken' 长度是 12，前缀 = k[:-12]；
      用错长度会导致 expiresat 取不到、排序失效、一直拿旧令牌。
    """
    best = (0, None)
    for k, v in pref.items():
        if k.endswith("captchatoken") and v.startswith("ck0."):
            try:
                exp = int(int(pref.get(k[:-12] + "expiresat", "0") or 0) / 1000)
            except Exception:
                exp = 0
            if exp > best[0]:
                best = (exp, v)
    return best[1], best[0]


def read_app_creds():
    """（需 root/可读 /data/data）直接从 APP 读三件套。失败返回 None。"""
    try:
        cap_pref = _parse_pref(os.path.join(PREFS, "xl-acc-captcha-token.xml"))
        dev_pref = _parse_pref(os.path.join(PREFS, "__device_info_new__.xml"))
        auth_pref = _parse_pref(os.path.join(PREFS, "auth_pref.xml"))
    except Exception:
        return None

    captcha, cap_exp = _latest_captcha(cap_pref)
    device_id = dev_pref.get("device_id_new")

    token = None
    for k, v in auth_pref.items():
        if "credential" in k.lower() and v.strip().startswith("{"):
            try:
                token = json.loads(v).get("access_token")
            except Exception:
                pass
            if token:
                break

    if not (captcha and device_id and token):
        return None
    if not token.startswith("Bearer "):
        token = "Bearer " + token
    return {
        "authorization": token,
        "captcha_token": captcha,
        "device_id": device_id,
        "captcha_expiresat": cap_exp,
        "source": "app(%s)" % PREFS,
    }


def read_saved_creds():
    """读取 .cookie.xunlei（key=value 行）。"""
    for p in (COOKIE_FILE, COOKIE_FILE_ALT):
        try:
            d = {}
            with open(p) as fh:
                for line in fh:
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    d[k.strip()] = v.strip()
            if d.get("authorization") and d.get("captcha_token") and d.get("device_id"):
                d["captcha_expiresat"] = int(d.get("captcha_expiresat", "0") or 0)
                d["source"] = p
                return d
        except Exception:
            pass
    return None


def load_creds():
    """优先实时直读（最新），其次读落盘文件。"""
    return read_app_creds() or read_saved_creds()


def captcha_fresh(creds, margin=5):
    if not creds:
        return False
    exp = creds.get("captcha_expiresat") or 0
    if exp <= 0:
        return False
    return exp > time.time() + margin


def refresh_captcha(share_url, wait=6.0, tries=3):
    """用分享深链把 APP 拉到前台，触发它申请新的 captcha 令牌。

    仅 root/有 shell 权限时可用；失败返回 False（不抛异常）。
    """
    if not share_url or os.environ.get("XUNLEI_NO_AUTO_REFRESH"):
        return False
    old = (read_app_creds() or {}).get("captcha_token")
    for _ in range(tries):
        try:
            subprocess.run(
                ["am", "start", "--user", "0",
                 "-a", "android.intent.action.VIEW",
                 "-d", share_url,
                 "-p", PKG],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        except Exception:
            return False
        time.sleep(wait)
        new = (read_app_creds() or {}).get("captcha_token")
        if new and new != old:
            return True
    return False

def probe_download_ua(url, timeout=10, candidates=None):
    """用 Range 探测该直链可用哪个 UA，返回第一个返回 200/206 的。

    不同来源的迅雷直链认的 UA 不同，写死单一 UA 会某类链接全 403。
    可用环境变量 XUNLEI_UA 强制指定（跳过探测）。
    """
    forced = os.environ.get("XUNLEI_UA")
    if forced:
        return forced
    for ua in (candidates or UA_DL_CANDIDATES):
        st, _ = _http("GET", url, {"User-Agent": ua, "Range": "bytes=0-0"},
                      timeout or 10)
        if st in (200, 206):
            return ua
    return UA_DL


# ---------------------------------------------------------------------------
# 解析器
# ---------------------------------------------------------------------------


class XunleiParser:
    """接口与 panpush 其它解析器一致：
       __init__(url, passcode, cookie, timeout, token=...)
       parse(list_only=False) -> {"site","files","downloads"}
    注意：cookie/token 参数仅为兼容签名，迅雷凭据走 load_creds()。
    """

    name = "xunlei"
    label = "迅雷云盘"

    ERR_HINT = {
        9: "验证码无效：captcha 令牌已过期。打开一次迅雷 APP 的分享页即可自动刷新。",
    }

    def __init__(self, url, passcode="", cookie=None, timeout=20, token=None):
        self.url = url
        self.passcode = (passcode or "").strip() or extract_passcode(url)
        self.timeout = timeout
        self.share_id = extract_share_id(url)
        self._creds = None
        self._pass_code_token = None
        self._ua_cache = {}

    # ---- 凭据与请求 ----

    def _creds_now(self, allow_refresh=True):
        if self._creds is None:
            self._creds = load_creds()
        if not self._creds:
            raise XunleiCredsError(COOKIE_GUIDE)
        if allow_refresh and not captcha_fresh(self._creds):
            if refresh_captcha(self.url):
                self._creds = read_app_creds() or self._creds
        return self._creds

    def _headers(self):
        c = self._creds_now()
        return {
            "Accept": "application/json, text/plain, */*",
            "User-Agent": UA_APP,
            "X-Client-Id": CLIENT_ID,
            "X-Client-Version": CLIENT_VERSION,
            "X-Device-Id": c["device_id"],
            "X-Captcha-Token": c["captcha_token"],
            "Referer": "https://pan.xunlei.com/",
            "Authorization": c["authorization"],
        }

    def _api(self, path, params, _retry=True):
        url = "%s%s?%s" % (API_BASE, path, urllib.parse.urlencode(params))
        st, txt = _get(url, self._headers(), self.timeout)
        j = _parse_json(txt) or {}
        if st != 200 or j.get("error"):
            code = j.get("error_code")
            desc = j.get("error_description") or j.get("message") or txt[:160]
            if code == 9 or "captcha" in str(desc).lower():
                if _retry and refresh_captcha(self.url):
                    self._creds = None
                    return self._api(path, params, _retry=False)
                raise XunleiCaptchaError("迅雷验证码失效：%s\n      ↳ %s"
                                         % (desc, self.ERR_HINT.get(9, "")))
            hint = self.ERR_HINT.get(code)
            raise RuntimeError("迅雷接口失败 [%s]: %s%s"
                               % (code, desc, ("\n      ↳ %s" % hint) if hint else ""))
        return j

    # ---- 结构解析辅助 ----

    @staticmethod
    def _unwrap(d):
        if (isinstance(d, dict) and "files" not in d and "file_list" not in d
                and isinstance(d.get("data"), dict)):
            return d["data"]
        return d or {}

    @staticmethod
    def _items(d):
        p = XunleiParser._unwrap(d)
        return [x for x in (p.get("files") or []) if isinstance(x, dict)]

    @staticmethod
    def _is_folder(it):
        return (it.get("kind") in ("drive#folder", "folder")
                or it.get("is_dir") is True or it.get("file_category") == "FOLDER")

    @staticmethod
    def _inline_url(it):
        u = it.get("web_content_link") or it.get("download_url") or it.get("play_url")
        if u:
            return u
        links = it.get("links") or {}
        for k in ("download", "download_url", "web_content_link", "url", "play_url"):
            v = links.get(k)
            if isinstance(v, str) and v.startswith("http"):
                return v
        return ""

    # ---- 列表 ----

    def list_files(self, parent_id=None, out=None, _seen=None, _depth=0):
        if out is None:
            out = []
        if _seen is None:
            _seen = set()
        if _depth > 8:
            return out

        page_token = ""
        for _ in range(MAX_PAGES):
            if parent_id:
                params = {"share_id": self.share_id, "parent_id": parent_id,
                          "pass_code_token": self._pass_code_token,
                          "limit": PAGE_SIZE, "space": "",
                          "thumbnail_size": "SIZE_MEDIUM", "scene": "NORMAL"}
                path = "/share/detail"
            else:
                params = {"share_id": self.share_id, "limit": PAGE_SIZE, "space": "",
                          "thumbnail_size": "SIZE_MEDIUM", "scene": "NORMAL",
                          "pass_code": self.passcode}
                path = "/share"
            if self._pass_code_token:
                params["pass_code_token"] = self._pass_code_token
            if page_token:
                params["page_token"] = page_token

            j = self._api(path, params)
            if not self._pass_code_token:
                self._pass_code_token = (
                    j.get("pass_code_token")
                    or (j.get("share_info") or {}).get("pass_code_token") or "")

            items = self._items(j)
            for it in items:
                fid = it.get("id") or it.get("file_id") or it.get("fileId")
                nm = it.get("name") or it.get("file_name") or "未命名"
                if not fid:
                    continue
                if self._is_folder(it):
                    if fid not in _seen:
                        _seen.add(fid)
                        self.list_files(fid, out, _seen, _depth + 1)
                else:
                    out.append({
                        "fid": fid,
                        "name": nm,
                        "size": int(it.get("size") or it.get("file_size") or 0),
                        "dir": False,
                        "_raw": it,
                    })
            nxt = j.get("next_page_token") or j.get("page_token_next") or ""
            if not nxt or nxt == page_token or not items:
                break
            page_token = nxt
        return out

    # ---- 取直链 ----

    def _ua_for(self, url):
        """每个 host 只探测一次，结果缓存。"""
        host = url.split("/")[2] if "//" in url else url
        if host not in self._ua_cache:
            self._ua_cache[host] = probe_download_ua(url, min(self.timeout or 10, 10))
        return self._ua_cache[host]

    def get_downloads(self, files, folder_id=""):
        res = []
        for f in files:
            url = self._inline_url(f.get("_raw") or {})
            size = f.get("size")
            if not url:
                params = {"share_id": self.share_id, "file_id": f["fid"],
                          "pass_code": self.passcode,
                          "pass_code_token": self._pass_code_token,
                          "folder_id": folder_id, "space": "", "scene": "NORMAL"}
                j = self._api("/share/file_info", params)
                payload = self._unwrap(j)
                info = payload.get("file_info") or payload.get("info") or payload
                url = self._inline_url(info)
                size = int(info.get("size") or size or 0)
            if not url:
                continue
            res.append({
                "name": f["name"],
                "size": size,
                "url": url,
                # ★ UA 自动探测：不同来源直链认的 UA 不同（分享链=okhttp，平台链可能其它）
                "headers": {"User-Agent": self._ua_for(url)},
            })
        return res

    # ---- 对外 ----

    def parse(self, list_only=False):
        if not self.share_id:
            raise RuntimeError("无法从链接中识别迅雷分享 ID（形如 pan.xunlei.com/s/<ID>）")
        files = self.list_files()
        if not files:
            raise RuntimeError("分享内没有可下载的文件（或分享已失效 / 提取码错误）")
        if list_only:
            return {"site": self.name, "files": files, "downloads": []}
        return {"site": self.name, "files": files,
                "downloads": self.get_downloads(files)}


# ---------------------------------------------------------------------------
# 独立 CLI（自测用；并入 panpush 后可不使用）
# ---------------------------------------------------------------------------

def _main(argv):
    import argparse
    ap = argparse.ArgumentParser(description="迅雷分享解析器（自测）")
    ap.add_argument("url", nargs="?", help="分享链接")
    ap.add_argument("--list-only", action="store_true")
    ap.add_argument("--creds", action="store_true", help="只看凭据状态")
    ap.add_argument("--no-refresh", action="store_true", help="不自动触发 APP 刷新")
    a = ap.parse_args(argv)

    if a.no_refresh:
        os.environ["XUNLEI_NO_AUTO_REFRESH"] = "1"

    if a.creds or not a.url:
        c = load_creds()
        if not c:
            print("未找到凭据。\n" + COOKIE_GUIDE)
            return 1
        d = c.get("captcha_expiresat", 0) - int(time.time())
        print("来源: %s" % c.get("source"))
        print("device_id: %s" % c["device_id"])
        print("authorization: %s..." % c["authorization"][:24])
        print("captcha: %s...  剩余 %ds" % (c["captcha_token"][:14], d))
        return 0

    p = XunleiParser(a.url)
    r = p.parse(list_only=a.list_only)
    print("站点: %s" % r["site"])
    for f in r["files"]:
        print("  - %s  (%s)" % (f["name"], human_size(f["size"])))
    for d in r["downloads"]:
        print("直链: %s  (%s)" % (d["name"], human_size(d["size"])))
        print("      %s" % d["url"])
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))