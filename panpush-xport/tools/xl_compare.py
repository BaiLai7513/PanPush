#!/data/data/com.termux/files/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对比实验：同一文件走「个人云盘直链」的速度（对照分享直链 0.69 MB/s）。
只读，不转存、不写云盘。"""
import re, os, json, time, html, threading
import urllib.request, urllib.error

SP = '/data/data/com.xunlei.downloadprovider/shared_prefs/'
CLIENT_ID = 'Xqp0kJBXWhwaTpB6'
CLIENT_VERSION = '1.93.8'
UA_DL = 'okhttp/4.9.0'
TARGET = 'Smart Tool v3.0.exe'

def parse_pref(path):
    txt = open(path, encoding='utf-8', errors='ignore').read()
    d = {}
    for m in re.finditer(r'<string name="([^"]+)">([^<]*)</string>', txt):
        d[m.group(1)] = html.unescape(m.group(2))
    for m in re.finditer(r'<(?:long|int) name="([^"]+)" value="([^"]*)"', txt):
        d[m.group(1)] = m.group(2)
    return d

# --- 凭据：每次调用前重新读取（APP 会轮换令牌）---
def creds():
    pr = parse_pref(SP + 'xl-acc-captcha-token.xml')
    toks = []
    for k, v in pr.items():
        if k.endswith('captchatoken') and v.startswith('ck0.'):
            p = k[:-len('captchatoken')]
            try: exp = int(pr.get(p + 'expiresat', '0') or 0)
            except: exp = 0
            toks.append((exp, v))
    toks.sort()
    cap = toks[-1][1]
    dv = parse_pref(SP + '__device_info_new__.xml').get('device_id_new')
    au = json.loads(parse_pref(SP + 'auth_pref.xml').get('credentials_Xp6vsxz_7IYVw2BB'))['access_token']
    return cap, dv, au

_c, _d, _a = creds()
print('[cred] captcha=%s... device=%s auth=%s...' % (_c[:12], _d[:12], _a[:12]))

def api(method, path, body=None):
    cap, dv, au = creds()
    url = 'https://api-pan.xunlei.com/drive/v1' + path
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header('X-Client-Id', CLIENT_ID)
    req.add_header('X-Client-Version', CLIENT_VERSION)
    req.add_header('X-Device-Id', dv)
    req.add_header('X-Captcha-Token', cap)
    req.add_header('Authorization', 'Bearer ' + au)
    if data: req.add_header('Content-Type', 'application/json')
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()[:200]
    except Exception as e:
        return -1, str(e)

# --- 递归列个人云盘，找目标文件 ---
def listdir(parent=None):
    p = '/files?limit=200' + ('&parent_id=' + parent if parent else '')
    st, js = api('GET', p)
    if st != 200:
        return []
    return js.get('files', js if isinstance(js, list) else [])

hit = None
def walk(parent=None, d=0):
    global hit
    if hit or d > 4: return
    for f in listdir(parent):
        if f.get('name') == TARGET:
            hit = f; return
        isdir = (f.get('kind') == 'drive#folder') or (not f.get('file_extension') and int(f.get('size', 0)) == 0)
        if isdir:
            walk(f['id'], d + 1)

walk()
if not hit:
    print('[files] 递归未找到目标 %r' % TARGET); raise SystemExit(1)
fid = hit['id']
print('[files] 命中 %s  id=%s  size=%.1f MB  parent=%s' % (hit['name'], fid, int(hit.get('size', 0))/1048576, hit.get('parent_id', '')))

# --- 个人云盘直链 ---
st, js = api('GET', '/files/%s?usage=CONSUME' % fid)
if st != 200:
    print('[file_info] HTTP', st, js); raise SystemExit(1)
url = js.get('web_content_link')
if not url:
    print('[file_info] 无 web_content_link，字段:', list(js.keys())); raise SystemExit(1)
print('[link] host=%s' % url.split('/')[2])

def get_size(u):
    req = urllib.request.Request(u); req.add_header('User-Agent', UA_DL)
    req.add_header('Range', 'bytes=0-0')
    with urllib.request.urlopen(req, timeout=25) as r:
        cr = r.headers.get('Content-Range')
        return int(cr.split('/')[-1]) if cr else int(r.headers.get('Content-Length', 0))

def speed(u, conns, budget=10):
    total = get_size(u)
    stop = time.time() + budget
    cnt = {'n': 0}; lock = threading.Lock()
    def worker(i):
        chunk = total // conns
        start = i * chunk
        end = (start + chunk - 1) if i < conns - 1 else total - 1
        req = urllib.request.Request(u); req.add_header('User-Agent', UA_DL)
        req.add_header('Range', 'bytes=%d-%d' % (start, end))
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                while time.time() <= stop:
                    b = r.read(65536)
                    if not b: break
                    with lock: cnt['n'] += len(b)
        except Exception:
            pass
    ths = [threading.Thread(target=worker, args=(i,)) for i in range(conns)]
    t0 = time.time()
    for t in ths: t.start()
    for t in ths: t.join()
    dt = time.time() - t0
    return cnt['n'], dt

for n in (1, 4, 16):
    b, dt = speed(url, n)
    print('  [个人云盘] %2d 连接: %6.2f MB / %4.1fs → %.2f MB/s (%.1f Mbps)' %
          (n, b/1048576, dt, (b/1048576)/dt if dt else 0, (b*8/1e6)/dt if dt else 0))