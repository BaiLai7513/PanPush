#!/data/data/com.termux/files/usr/bin/env python3
# -*- coding: utf-8 -*-
"""webview_ck.py — 用 root 读取「任意 App」WebView 的 Cookie / LocalStorage。

设计要点（对应已知的三个坑）：
  1. 只读 WebView profile：<pkg>/app_webview/Default/{Cookies, Local Storage/leveldb}
     —— 不要再去 shared_prefs / databases 找 token（本类 App 根本没放那儿）。
  2. 用 `su -c`（不是 run-as）；经 base64 管道回传，绕开 Termux 与 app_data 的
     SELinux / 权限限制。
  3. 读取「活的」数据库前先经 base64 快照；连同 Cookies-journal 一起落盘，
     由 sqlite3 自行决定是否 rollback，避免读到空/旧数据。
  4. value 列是明文；encrypted_value 非空才算加密（此处跨应用无法解，直接标注）。

用法：
  python3 webview_ck.py com.panpush.app
  python3 webview_ck.py com.panpush.app --host quark
  python3 webview_ck.py com.panpush.app --json > ck.json
  python3 webview_ck.py com.panpush.app --ls            # 只看有哪些 profile 文件
  python3 webview_ck.py com.panpush.app --localstorage  # 附带 dump localStorage 字符串
"""
import argparse
import base64
import json
import os
import sqlite3
import subprocess
import sys
import tempfile
import time

COOKIE_FILES = ("Cookies", "Cookies-journal", "Cookies-wal")


def _su(cmd, timeout=30):
    """以 root 执行命令，返回 (rc, stdout_bytes)."""
    p = subprocess.run(["su", "-c", cmd], capture_output=True, timeout=timeout)
    return p.returncode, p.stdout


def _su_cat(path):
    """以 root 把文件 base64 后回传；文件不存在返回 None。"""
    rc, out = _su('if [ -f "%s" ]; then base64 -w0 "%s"; fi' % (path, path))
    if rc != 0 or not out.strip():
        return None
    try:
        return base64.b64decode(out.strip())
    except Exception:
        return None


def _su_cat_text(path):
    rc, out = _su('if [ -f "%s" ]; then base64 -w0 "%s"; fi' % (path, path))
    if rc != 0 or not out.strip():
        return None
    try:
        return base64.b64decode(out.strip()).decode("utf-8", "replace")
    except Exception:
        return None


def app_data_dir(pkg):
    """优先 /data/user/0，回退 /data/data。"""
    rc, out = _su('if [ -d /data/user/0/%s ]; then echo /data/user/0/%s; '
                  'else echo /data/data/%s; fi' % (pkg, pkg, pkg))
    d = out.decode().strip()
    return d or ("/data/data/%s" % pkg)


def read_cookies(pkg):
    """返回 (profile_dir, {name: bytes} 已落盘的 cookie 文件快照)。"""
    base = app_data_dir(pkg)
    prof = base + "/app_webview/Default"
    snaps = {}
    for fn in COOKIE_FILES:
        b = _su_cat(prof + "/" + fn)
        if b is not None:
            snaps[fn] = b
    return prof, snaps


def query_cookies(snaps, host_filter=None):
    """把快照写到临时目录 → sqlite3 只读打开（带 journal，自动 rollback）。"""
    if "Cookies" not in snaps:
        return [], "未找到 Cookies 库（可能该 App 没用 WebView，或尚未产生 cookie）"
    td = tempfile.mkdtemp(prefix="wvck_")
    for fn, data in snaps.items():
        with open(os.path.join(td, fn), "wb") as fh:
            fh.write(data)
    db = sqlite3.connect(os.path.join(td, "Cookies"))
    rows = []
    try:
        cur = db.execute(
            "select host_key,name,value,encrypted_value,is_secure,is_httponly,"
            "is_persistent,expires_utc from cookies order by host_key,name")
        for r in cur.fetchall():
            host, name, value, enc, sec, http, pers, exp = r
            if host_filter and host_filter.lower() not in (host or "").lower():
                continue
            enc_len = len(enc) if enc else 0
            rows.append({
                "host": host, "name": name,
                "value": value if value else "",
                "encrypted": bool(enc_len),
                "encrypted_len": enc_len,
                "secure": bool(sec), "httponly": bool(http),
                "persistent": bool(pers), "expires_utc": exp,
            })
    except Exception as e:
        return [], "查询 cookies 表失败：%s" % e
    finally:
        db.close()
    return rows, None


def dump_localstorage(pkg):
    base = app_data_dir(pkg)
    prof = base + "/app_webview/Default"
    ls_dir = prof + "/Local Storage/leveldb"
    rc, out = _su('if [ -d "%s" ]; then ls "%s"; fi' % (ls_dir, ls_dir))
    files = [x for x in out.decode().splitlines() if x.strip()]
    blobs = {}
    for fn in files:
        b = _su_cat(ls_dir + "/" + fn)
        if b is not None:
            blobs[fn] = b
    return ls_dir, files, blobs


def _printable(blob, minlen=4):
    """从 leveldb 文件里筛出可打印串。"""
    res, cur = [], bytearray()
    for by in blob:
        if 32 <= by < 127 or by >= 0x80:
            cur.append(by)
        else:
            if len(cur) >= minlen:
                res.append(cur.decode("utf-8", "replace"))
            cur = bytearray()
    if len(cur) >= minlen:
        res.append(cur.decode("utf-8", "replace"))
    return res


def main(argv=None):
    ap = argparse.ArgumentParser(description="用 root 读取任意 App 的 WebView cookie/localStorage")
    ap.add_argument("package")
    ap.add_argument("--host", default=None, help="只显示 host 含该子串的 cookie")
    ap.add_argument("--json", action="store_true", help="JSON 输出")
    ap.add_argument("--ls", action="store_true", help="只列出 profile 文件，不解析")
    ap.add_argument("--localstorage", action="store_true", help="附带 dump localStorage 可打印串")
    args = ap.parse_args(argv)

    prof, snaps = read_cookies(args.package)
    if args.ls:
        print("profile:", prof)
        print("存在的 cookie 文件:", list(snaps.keys()) or "无")
        ls_dir, files, _ = dump_localstorage(args.package)
        print("localStorage 目录:", ls_dir)
        print("localStorage 文件:", files or "无")
        return 0

    rows, err = query_cookies(snaps, args.host)

    result = {
        "package": args.package,
        "profile": prof,
        "cookie_files": list(snaps.keys()),
        "cookies": rows,
        "error": err,
    }

    if args.localstorage:
        ls_dir, files, blobs = dump_localstorage(args.package)
        ls_out = {}
        for fn, b in blobs.items():
            ls_out[fn] = [s for s in _printable(b) if len(s) >= 6][:200]
        result["localstorage_dir"] = ls_dir
        result["localstorage_files"] = files
        result["localstorage_dump"] = ls_out

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0

    print("package :", args.package)
    print("profile :", prof)
    print("cookie 文件:", list(snaps.keys()) or "无")
    if err:
        print("!! ", err)
    print("cookie 条数: %d" % len(rows))
    for r in rows:
        mark = " [ENCRYPTED]" if r["encrypted"] else ""
        flag = "".join([("S" if r["secure"] else "-"), ("H" if r["httponly"] else "-")])
        print("  %-24s %-18s = %s   (%s)%s" %
              (r["host"], r["name"], (r["value"] or "")[:60], flag, mark))

    if args.localstorage:
        print("\n== localStorage ==")
        for fn, strs in result.get("localstorage_dump", {}).items():
            print("--", fn)
            for s in strs:
                print("   ", s[:100])
    return 0


if __name__ == "__main__":
    sys.exit(main())
