#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""阿里云盘直链探测 + 多线程测速探针（只读，不落地）。
用法: python3 ali_probe.py <file_id> <size_bytes> [conns...]
依赖: 本地 /tmp/ali_cred.json (access_token)
"""
import json, sys, time, subprocess, threading

CRED = json.load(open('/tmp/ali_cred.json'))
AT = CRED['access_token']
DRIVE = CRED['drive_id']
UA = 'AliApp(AP/6.18.0) AliApp(AppLink/6.18.0) Android/35'
API = 'https://api.aliyundrive.com/v2/file/get_download_url'


def get_url(fid, retries=8, gap=15):
    for i in range(retries):
        p = subprocess.run(
            ['curl', '-s', '-m', '15', '-X', 'POST', API,
             '-H', 'User-Agent: ' + UA,
             '-H', 'Content-Type: application/json',
             '-H', 'Authorization: Bearer ' + AT,
             '-d', json.dumps({"drive_id": DRIVE, "file_id": fid, "expire_sec": 900})],
            capture_output=True, text=True)
        try:
            u = json.loads(p.stdout).get('url', '')
        except Exception:
            u = ''
        if u:
            return u
        print('  [retry %d] url empty, wait %ds' % (i + 1, gap))
        time.sleep(gap)
    return ''


def head(url):
    p = subprocess.run(['curl', '-s', '-m', '20', '-I', '-H', 'User-Agent: ' + UA, url],
                       capture_output=True, text=True)
    return p.stdout


def range_probe(url):
    p = subprocess.run(['curl', '-s', '-m', '20', '-D', '-', '-o', '/dev/null',
                        '-H', 'User-Agent: ' + UA, '-H', 'Range: bytes=0-99', url],
                       capture_output=True, text=True)
    return p.stdout


def timed_conn(url, start, dur, out):
    p = subprocess.run(['curl', '-s', '-m', str(dur), '-o', '/dev/null',
                        '-H', 'User-Agent: ' + UA,
                        '-H', 'Range: bytes=%d-' % start,
                        '-w', '%{size_download}', url],
                       capture_output=True, text=True)
    try:
        out.append(int(p.stdout.strip() or 0))
    except Exception:
        out.append(0)


def mt_speed(url, size, conns, dur=6):
    seg = max(size // conns, 1)
    out = []
    ths = []
    t0 = time.time()
    for i in range(conns):
        t = threading.Thread(target=timed_conn, args=(url, i * seg, dur, out))
        t.start(); ths.append(t)
    for t in ths:
        t.join()
    el = time.time() - t0
    total = sum(out)
    return total / el / 1024 / 1024, total, el


def main():
    fid = sys.argv[1]
    size = int(sys.argv[2])
    conns = [int(x) for x in sys.argv[3:]] or [1, 4, 16]
    print('=== 取直链 ===')
    url = get_url(fid)
    if not url:
        print('FAILED: 始终拿不到直链'); return
    host = url.split('/')[2]
    print('  host =', host)
    print('=== HEAD ===')
    h = head(url)
    for ln in h.splitlines():
        if ln.lower().startswith(('http/', 'accept-ranges', 'content-length', 'server',
                                   'x-oss-', 'content-type', 'etag')):
            print('  ', ln.strip()[:110])
    print('=== Range bytes=0-99 ===')
    rp = range_probe(url)
    for ln in rp.splitlines()[:8]:
        print('  ', ln.strip()[:110])
    print('=== 多线程测速 (每档 %ds) ===' % 6)
    for c in conns:
        sp, tot, el = mt_speed(url, size, c)
        print('  conns=%-3d  %.2f MB/s   (%.1f MB / %.1fs)' % (c, sp, tot / 1024 / 1024, el))


if __name__ == '__main__':
    main()
