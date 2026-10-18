#!/data/data/com.termux/files/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 panpush 合并补丁：迅雷 + 蓝奏 + 阿里云盘（一次应用）。

做法：先复用 genpatch.py 的 11 处迅雷改动得到 out1，再在 out1 上依次施加
蓝奏、阿里改动，最后对「原始 panpush.py」做统一 diff，产出一个可干净应用的
合并补丁（三站 27 处改动）。
"""
import difflib
import genpatch  # 会执行一次迅雷-only 生成（无害）

BS = chr(92)
LPAT = 'lanzo[a-z0-9-]*' + BS + '.(?:com|cn)'   # 覆盖 lanzo*/lanzou*/lanzn* 等域名
ALIPAT = 'aliyundrive' + BS + '.com|alipan' + BS + '.com'

SRC = genpatch.SRC
OUT = '/sdcard/Make/panpush-xunlei-export/panpush-xunlei-lanzou-aliyun.patch'

# 迅雷改动（来自 genpatch）
changes = list(genpatch.changes)
N_XUNLEI = len(genpatch.changes)

# ======================= 蓝奏改动 =======================
# L1 文档串
changes.append((
"已落地:  quark (夸克) / uc (UC) / baidu (百度) / 123 (123云盘) / xunlei (迅雷)",
"已落地:  quark (夸克) / uc (UC) / baidu (百度) / 123 (123云盘) / xunlei (迅雷) / lanzou (蓝奏云)"))

# L2 导入
changes.append((
"from xunlei import XunleiParser, COOKIE_GUIDE as XUNLEI_GUIDE",
"from xunlei import XunleiParser, COOKIE_GUIDE as XUNLEI_GUIDE\n"
"from lanzou import LanzouParser, COOKIE_GUIDE as LANZOU_GUIDE"))

# L3 路由表
changes.append((
'    (r"' + genpatch.XPAT + '", XunleiParser),',
'    (r"' + genpatch.XPAT + '", XunleiParser),\n'
'    (r"' + LPAT + '", LanzouParser),'))

# L4 detect_site
_dyn = "\n".join([
    '    if re.search(r"pan' + BS + '.xunlei' + BS + '.com", url, re.I):',
    '        return "xunlei"',
    '    if re.search(r"' + LPAT + '", url, re.I):',
    '        return "lanzou"',
    '    return None',
])
changes.append((
"""    if re.search(r"pan""" + BS + """.xunlei""" + BS + """.com", url, re.I):
        return "xunlei"
    return None""",
_dyn))

# L5 --site choices
changes.append((
'''choices=["quark", "uc", "baidu", "123", "xunlei"],''',
'''choices=["quark", "uc", "baidu", "123", "xunlei", "lanzou"],'''))

# L6 COOKIE_GUIDE 注册
changes.append((
'COOKIE_GUIDE["xunlei"] = XUNLEI_GUIDE',
'COOKIE_GUIDE["xunlei"] = XUNLEI_GUIDE\nCOOKIE_GUIDE["lanzou"] = LANZOU_GUIDE'))

# L7 _load_saved_cookie：蓝奏匿名，早退
changes.append((
"""    if site == "xunlei":
        # 迅雷凭据（captcha 令牌 / device_id / Authorization）由 xunlei 模块
        # 自行从 APP 私有目录读取；这里不做 Cookie 文本兜底，避免误判。
        return None, None""",
"""    if site == "xunlei":
        # 迅雷凭据（captcha 令牌 / device_id / Authorization）由 xunlei 模块
        # 自行从 APP 私有目录读取；这里不做 Cookie 文本兜底，避免误判。
        return None, None
    if site == "lanzou":
        # 蓝奏云全匿名，无需 Cookie
        return None, None"""))

N_LANZOU = len(changes) - N_XUNLEI

# ======================= 阿里云盘改动 =======================
# A1 文档串：标注阿里已落地，去掉“预留”行
changes.append((
"已落地:  quark (夸克) / uc (UC) / baidu (百度) / 123 (123云盘) / xunlei (迅雷) / lanzou (蓝奏云)\n"
"预留:    ali(阿里云盘)",
"已落地:  quark (夸克) / uc (UC) / baidu (百度) / 123 (123云盘) / xunlei (迅雷) / lanzou (蓝奏云) / aliyun (阿里云盘)"))

# A2 导入
changes.append((
"from lanzou import LanzouParser, COOKIE_GUIDE as LANZOU_GUIDE",
"from lanzou import LanzouParser, COOKIE_GUIDE as LANZOU_GUIDE\n"
"from aliyun import AliyunParser, COOKIE_GUIDE as ALIYUN_GUIDE"))

# A3 路由表：阿里从 UnsupportedParser 换成 AliyunParser
changes.append((
'    (r"' + ALIPAT + '", lambda *a, **k: UnsupportedParser(*a, label="阿里云盘", **k)),',
'    (r"' + ALIPAT + '", AliyunParser),'))

# A4 detect_site：新增 aliyun 分支
_dyn_a = "\n".join([
    '    if re.search(r"' + LPAT + '", url, re.I):',
    '        return "lanzou"',
    '    if re.search(r"' + ALIPAT + '", url, re.I):',
    '        return "aliyun"',
    '    return None',
])
changes.append((
"""    if re.search(r\"""" + LPAT + """", url, re.I):
        return "lanzou"
    return None""",
_dyn_a))

# A5 --site choices
changes.append((
'''choices=["quark", "uc", "baidu", "123", "xunlei", "lanzou"],''',
'''choices=["quark", "uc", "baidu", "123", "xunlei", "lanzou", "aliyun"],'''))

# A6 COOKIE_GUIDE 注册
changes.append((
'COOKIE_GUIDE["lanzou"] = LANZOU_GUIDE',
'COOKIE_GUIDE["lanzou"] = LANZOU_GUIDE\nCOOKIE_GUIDE["aliyun"] = ALIYUN_GUIDE'))

# A7 _load_saved_cookie：阿里早退（凭据来自 APP 私有目录 / .cookie.aliyun）
changes.append((
"""    if site == "lanzou":
        # 蓝奏云全匿名，无需 Cookie
        return None, None""",
"""    if site == "lanzou":
        # 蓝奏云全匿名，无需 Cookie
        return None, None
    if site == "aliyun":
        # 阿里云盘凭据（access/refresh token）由 aliyun 模块从 APP 私有目录
        # 读取，或以 .cookie.aliyun 形式提供；这里不做通用 Cookie 兜底。
        return None, None"""))

# A8 按站点默认并发：加入 aliyun=64
changes.append((
'DEFAULT_CONNECTIONS = {"xunlei": 16}',
'DEFAULT_CONNECTIONS = {"xunlei": 16, "aliyun": 64}'))

# A9 check_cookie：阿里无需 Cookie 校验
changes.append((
'''    if site == "xunlei":
        return None, "迅雷凭据来自本机 APP（captcha 令牌），无需 Cookie 校验"
    return None, "该站点暂不支持在线校验"''',
'''    if site == "xunlei":
        return None, "迅雷凭据来自本机 APP（captcha 令牌），无需 Cookie 校验"
    if site == "aliyun":
        return None, "阿里云盘凭据来自本机 APP（明文 token DB），无需 Cookie 校验"
    return None, "该站点暂不支持在线校验"'''))

# A10 --connections 帮助文案更新
changes.append((
'''    ap.add_argument("--connections", type=int, default=None,
                    help="下载并发连接数（默认按站点自动：迅雷 16，其余 512）")''',
'''    ap.add_argument("--connections", type=int, default=None,
                    help="下载并发连接数（默认按站点自动：迅雷 16 / 阿里 64，其余 512）")'''))

N_ALIYUN = len(changes) - N_XUNLEI - N_LANZOU

src = open(SRC, encoding='utf-8').read()
out = src
for i, (old, new) in enumerate(changes, 1):
    c = out.count(old)
    assert c == 1, "change #%d 命中 %d 次（应为 1）" % (i, c)
    out = out.replace(old, new)

diff = ''.join(difflib.unified_diff(
    src.splitlines(keepends=True), out.splitlines(keepends=True),
    fromfile='a/panpush.py', tofile='b/panpush.py'))
open(OUT, 'w', encoding='utf-8').write(diff)

add = sum(1 for l in diff.splitlines() if l.startswith('+') and not l.startswith('+++'))
rem = sum(1 for l in diff.splitlines() if l.startswith('-') and not l.startswith('---'))
print("合并改动数:", len(changes),
      "（迅雷 %d + 蓝奏 %d + 阿里 %d）" % (N_XUNLEI, N_LANZOU, N_ALIYUN))
print("added:", add, "removed:", rem, "bytes:", len(diff.encode('utf-8')))
print("written:", OUT)