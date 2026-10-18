#!/data/data/com.termux/files/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打印迅雷 captcha 令牌有效期（按 expiresat 排序）。"""
import re, html, time
SP = '/data/data/com.xunlei.downloadprovider/shared_prefs/'

def pp(p):
    t = open(p, encoding='utf-8', errors='ignore').read()
    d = {}
    for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', t):
        d[m.group(1)] = html.unescape(m.group(2))
    for m in re.finditer(r'<(?:long|int) name="([^"]+)" value="([^"]*)"', t):
        d[m.group(1)] = m.group(2)
    return d

d = pp(SP + 'xl-acc-captcha-token.xml')
now = int(time.time())
ex = []
for k, v in d.items():
    if k.endswith('captchatoken') and v.startswith('ck0.'):
        e = int(int(d.get(k[:-12] + 'expiresat', '0') or 0) / 1000)
        ex.append((e, v[4:14]))
ex.sort()
print('now =', now)
for e, tag in ex:
    print('  %s  delta=%+ds' % (tag, e - now))
print('最新令牌剩余:', (ex[-1][0] - now) if ex else 'N/A', 's')