#!/data/data/com.termux/files/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 panpush 迅雷接入补丁（11 处改动，含按站点默认并发）。"""
import difflib

BS = chr(92)  # 反斜杠，避免源码里出现裸反斜杠
XPAT = "pan" + BS + ".xunlei" + BS + ".com"

SRC = '/sdcard/Make/Mk/panpush/panpush.py'
OUT = '/sdcard/Make/panpush-xunlei-export/panpush-xunlei.patch'

src = open(SRC, encoding='utf-8').read()

changes = []

# ---- R1: 文档串标注 xunlei 已落地 ----
changes.append((
"""已落地:  quark (夸克) / uc (UC) / baidu (百度) / 123 (123云盘)
预留:    ali(阿里云盘) / xunlei(迅雷)""",
"""已落地:  quark (夸克) / uc (UC) / baidu (百度) / 123 (123云盘) / xunlei (迅雷)
预留:    ali(阿里云盘)"""))

# ---- R2: 导入迅雷解析器 ----
changes.append((
"""import urllib.error
import urllib.parse
import urllib.request""",
"""import urllib.error
import urllib.parse
import urllib.request

from xunlei import XunleiParser, COOKIE_GUIDE as XUNLEI_GUIDE"""))

# ---- R3: 路由表接入 ----
changes.append((
'    (r"' + XPAT + '", lambda *a, **k: UnsupportedParser(*a, label="迅雷云盘", **k)),',
'    (r"' + XPAT + '", XunleiParser),'))

# ---- R4: detect_site 增加 xunlei 分支 ----
_new4 = "\n".join([
    '    if re.search(r"123pan|123684|123865|123912|123952", url, re.I):',
    '        return "123"',
    '    if re.search(r"' + XPAT + '", url, re.I):',
    '        return "xunlei"',
    '    return None',
])
changes.append((
"""    if re.search(r"123pan|123684|123865|123912|123952", url, re.I):
        return "123"
    return None""",
_new4))

# ---- R5: --site 可选值 ----
changes.append((
'''choices=["quark", "uc", "baidu", "123"],''',
'''choices=["quark", "uc", "baidu", "123", "xunlei"],'''))

# ---- R6: _load_saved_cookie 对 xunlei 早退（不用 Cookie 文本兜底）----
changes.append((
"""    names = []
    if site:
        names.append(".cookie.%s" % site)""",
"""    if site == "xunlei":
        # 迅雷凭据（captcha 令牌 / device_id / Authorization）由 xunlei 模块
        # 自行从 APP 私有目录读取；这里不做 Cookie 文本兜底，避免误判。
        return None, None
    names = []
    if site:
        names.append(".cookie.%s" % site)"""))

# ---- R7: 注册 COOKIE_GUIDE ----
changes.append((
"""    ),
}


def cookie_file_for(site):""",
"""    ),
}

COOKIE_GUIDE["xunlei"] = XUNLEI_GUIDE


def cookie_file_for(site):"""))

# ---- R8: check_cookie 增加 xunlei 分支 ----
changes.append((
'''    return None, "该站点暂不支持在线校验"''',
'''    if site == "xunlei":
        return None, "迅雷凭据来自本机 APP（captcha 令牌），无需 Cookie 校验"
    return None, "该站点暂不支持在线校验"'''))

# ---- R9: 按站点默认并发 ----
changes.append((
'''def make_parser(url, passcode, cookie, timeout, token=None):''',
'''# 各站点默认下载并发。
# 迅雷分享链实测约 6 Mbps 封顶（8~16 连接即饱和，高并发无收益且更易触发风控）；
# 其余站点沿用 512。
DEFAULT_CONNECTIONS = {"xunlei": 16}


def default_connections(site):
    return DEFAULT_CONNECTIONS.get(site, 512)


def make_parser(url, passcode, cookie, timeout, token=None):'''))

# ---- R10: --connections 默认改为按站点自动 ----
changes.append((
'''    ap.add_argument("--connections", type=int, default=512, help="下载并发连接数")''',
'''    ap.add_argument("--connections", type=int, default=None,
                    help="下载并发连接数（默认按站点自动：迅雷 16，其余 512）")'''))

# ---- R11: 解析出 site 后套用默认并发 ----
changes.append((
'''    site = args.site or (detect_site(args.url) if args.url else None)''',
'''    site = args.site or (detect_site(args.url) if args.url else None)
    if args.connections is None:
        args.connections = default_connections(site)'''))

out = src
for i, (old, new) in enumerate(changes, 1):
    c = out.count(old)
    assert c == 1, "change #%d 命中 %d 次（应为 1）" % (i, c)
    out = out.replace(old, new)

a = src.splitlines(keepends=True)
b = out.splitlines(keepends=True)
diff = ''.join(difflib.unified_diff(a, b, fromfile='a/panpush.py', tofile='b/panpush.py'))
open(OUT, 'w', encoding='utf-8').write(diff)

add = sum(1 for l in diff.splitlines() if l.startswith('+') and not l.startswith('+++'))
rem = sum(1 for l in diff.splitlines() if l.startswith('-') and not l.startswith('---'))
print("changes applied:", len(changes))
print("added lines:", add, "removed lines:", rem)
print("patch bytes:", len(diff.encode('utf-8')))
print("written to:", OUT)