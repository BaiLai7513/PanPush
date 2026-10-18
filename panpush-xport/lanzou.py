#!/data/data/com.termux/files/usr/bin/env python3
# -*- coding: utf-8 -*-
"""蓝奏云（lanzou）解析器 —— panpush 站点插件（零第三方依赖，自包含）。

设计要点
--------
* 全匿名：无需任何账号/Cookie（官网承诺"不限速、免登录分享"）。
* 域名无关：蓝奏域名会轮换（lanzoui/lanzoux/lanzouw/lanzouq/lanzouf/lanzn…），
  本模块统一按 host 正则识别，页面内相对地址一律相对分享页 host 解析。
* 两种页面形态：
    - 经典文件页：页面内联 `isngis` / `wp_sign` + 提交端点 ajax*.php
    - 新样式文件页：主页含 `<iframe src="/fn?...">`，真正的 sign 在 /fn 页里
* 文件夹页（`/b…`）：走 `/filemoreajax.php` 拿 JSON 文件表，再逐个解析。
* 下载 CDN 可能带 `acw_sc__v2`（阿里云 WAF）JS 挑战：本模块内置求解器，
  算出 `acw_sc__v2` Cookie 后随直链一起下发（Gopeed 会带上 Cookie 头）。

接口与 panpush 其它解析器一致：
    p = LanzouParser(url, passcode, cookie, timeout)
    res = p.parse(list_only=True)   # -> {"site","files","downloads"}
"""
import gzip
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36')

# 蓝奏域名族（会轮换，用宽松匹配）
DOMAIN_RE = re.compile(
    r'https?://(?:[a-z0-9-]+\.)?(?:lanzo[a-z0-9-]*|lanzou[a-z0-9-]*|lanzn[a-z0-9-]*)\.(?:com|cn)/',
    re.I)

# acw_sc__v2 求解参数（阿里云 WAF 固定算法）
_ACW_POS = [0xf, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x1, 0x26, 0xa, 0x9, 0x13, 0x1f,
            0x28, 0x1b, 0x16, 0x17, 0x19, 0xd, 0x6, 0xb, 0x27, 0x12, 0x14, 0x8,
            0xe, 0x15, 0x20, 0x1a, 0x2, 0x1e, 0x7, 0x4, 0x11, 0x5, 0x3, 0x1c,
            0x22, 0x25, 0xc, 0x24]
_ACW_KEY = '3000176000856006061501533003690027800375'

COOKIE_GUIDE = (
    "蓝奏云无需登录即可解析（免 Cookie）。\n"
    "      直接：python3 panpush.py 'https://xxx.lanzoui.com/xxxx' （带密码时再跟提取码）"
)


def solve_acw_sc_v2(arg1):
    """解阿里云 WAF 的 acw_sc__v2 挑战，返回 Cookie 值。"""
    q = [None] * len(arg1)
    for x in range(len(arg1)):
        for z in range(len(_ACW_POS)):
            if _ACW_POS[z] == x + 1:
                q[z] = arg1[x]
    u = ''.join(q)
    v = ''
    for x in range(0, min(len(u), len(_ACW_KEY)), 2):
        val = int(u[x:x + 2], 16) ^ int(_ACW_KEY[x:x + 2], 16)
        h = format(val, 'x')
        if len(h) == 1:
            h = '0' + h
        v += h
    return v


def parse_size(s):
    """把蓝奏的 '10.3 M' / '1.2 G' / '500 K' 转成字节数（int）；失败返回 0。"""
    if s is None:
        return 0
    if isinstance(s, (int, float)):
        return int(s)
    m = re.match(r'^\s*([0-9.]+)\s*([KMGTP]?)B?\s*$', str(s), re.I)
    if not m:
        return 0
    val = float(m.group(1))
    mult = {'': 1, 'K': 1024, 'M': 1024 ** 2, 'G': 1024 ** 3,
            'T': 1024 ** 4, 'P': 1024 ** 5}.get(m.group(2).upper(), 1)
    return int(val * mult)


def human_size(n):
    try:
        n = float(n)
    except Exception:
        return str(n)
    for u in ('B', 'KB', 'MB', 'GB', 'TB'):
        if n < 1024:
            return '%.2f %s' % (n, u) if u != 'B' else '%d B' % n
        n /= 1024.0
    return '%.2f PB' % n


def is_lanzou_url(url):
    return bool(DOMAIN_RE.search(url or ''))


class LanzouError(RuntimeError):
    pass


class LanzouParser:
    def __init__(self, url, passcode='', cookie=None, timeout=20, token=None):
        self.url = url.strip()
        if not re.match(r'^https?://', self.url):
            self.url = 'https://' + self.url
        self.passcode = (passcode or '').strip()
        self.cookie = cookie or ''
        self.timeout = timeout or 20
        self.base = re.match(r'(https?://[^/]+)', self.url).group(1)

    # ---------------- HTTP ----------------
    def _req(self, url, data=None, headers=None, allow_error=False):
        h = {'User-Agent': UA, 'Accept': '*/*',
             'Accept-Language': 'zh-CN,zh;q=0.9'}
        if self.cookie:
            h['Cookie'] = self.cookie
        if headers:
            h.update(headers)
        body = None
        if data is not None:
            body = data if isinstance(data, bytes) else urllib.parse.urlencode(data).encode()
        r = urllib.request.Request(url, data=body, headers=h)
        try:
            resp = urllib.request.urlopen(r, timeout=self.timeout)
            code, hdrs, raw = resp.status, resp.headers, resp.read()
        except urllib.error.HTTPError as e:
            if not allow_error:
                raise
            code, hdrs, raw = e.code, e.headers, e.read()
        if raw[:2] == b'\x1f\x8b':
            try:
                raw = gzip.decompress(raw)
            except Exception:
                pass
        return code, raw, hdrs

    @staticmethod
    def _text(raw):
        for enc in ('utf-8', 'gbk', 'gb18030'):
            try:
                return raw.decode(enc)
            except Exception:
                pass
        return raw.decode('utf-8', 'replace')

    # ---------------- 列表 ----------------
    def list_files(self):
        """返回 [{"id","name","size"}]；单文件页返回单元素。"""
        code, raw, _ = self._req(self.url)
        html = self._text(raw)
        path = urllib.parse.urlparse(self.url).path
        # 文件夹页：/b<id>
        if re.search(r'/b0?[A-Za-z0-9]+', path) or 'filemoreajax.php' in html:
            return self._list_folder(html)
        # 单文件页
        m = re.search(r'<title>(.*?)</title>', html, re.S)
        name = (m.group(1).strip() if m else '')
        name = re.sub(r'\s*-\s*蓝奏云.*$', '', name)
        return [{'id': path.lstrip('/'), 'name': name, 'size': ''}]

    def _list_folder(self, html):
        fid = re.search(r'filemoreajax\.php\?file=(\d+)', html)
        if not fid:
            raise LanzouError('文件夹页解析失败（未找到 filemoreajax）')
        fid = fid.group(1)
        def pick(*pats):
            for p in pats:
                m = re.search(p, html)
                if m:
                    return m.group(1)
            return ''
        uid = pick(r"'uid':'?(\d+)'?", r"uid=(\d+)")
        puid = pick(r"'puid':'([^']+)'")
        t = pick(r"var ibd0x5 = '([^']+)'")
        k = pick(r"var _hbcyy = '([^']+)'")
        out = []
        pg = 1
        while pg <= 50:
            data = {'lx': 2, 'fid': fid, 'uid': uid, 'puid': puid,
                    'pg': pg, 'rep': '0', 't': t, 'k': k, 'up': 1}
            code, raw, _ = self._req(
                self.base + '/filemoreajax.php?file=' + fid, data=data,
                headers={'Referer': self.url},
                allow_error=True)
            try:
                j = json.loads(self._text(raw))
            except Exception:
                break
            if j.get('zt') != 1:
                break
            arr = j.get('text') or []
            for it in arr:
                out.append({'id': it.get('id', ''),
                            'name': it.get('name_all', ''),
                            'size': it.get('size', '')})
            if len(arr) < 50:
                break
            pg += 1
        return out

    # ---------------- 直链 ----------------
    def _page_for_file(self, file_id):
        """取「含 sign 的那一页」html + 该 file id。"""
        page = self.base + '/' + file_id.lstrip('/')
        code, raw, _ = self._req(page)
        html = self._text(raw)
        fn = re.search(r'src="(/fn\?[^"]+)"', html)
        if fn:
            fn_url = urllib.parse.urljoin(page, fn.group(1))
            code, raw, _ = self._req(fn_url, headers={'Referer': page})
            html = self._text(raw)
        else:
            fn_url = page
        return html, fn_url

    def _direct(self, file_id):
        html, referer = self._page_for_file(file_id)
        sign = None
        for pat in (r"wp_sign = '([^']+)'", r"var\s+isngis\s*=\s*'([^']+)'",
                    r"isngis\s*=\s*'([^']+)'"):
            m = re.search(pat, html)
            if m:
                sign = m.group(1)
                break
        ep = re.search(r"(https?://[^'\"]*ajax(?:file|m)\.php\?file=\d+)", html)
        if not sign or not ep:
            raise LanzouError('未能提取 sign/提交端点（页面结构可能变化）')
        data = {'action': 'downprocess', 'sign': sign, 'kd': '1', 'p': self.passcode}
        ad = re.search(r"ajaxdata = '([^']+)'", html)
        if ad:
            data.update({'websignkey': ad.group(1), 'signs': ad.group(1),
                         'websign': '', 'ves': '1'})
        code, raw, _ = self._req(ep.group(1), data=data,
                                 headers={'Referer': referer,
                                          'Origin': self.base,
                                          'X-Requested-With': 'XMLHttpRequest'})
        j = json.loads(self._text(raw))
        if j.get('zt') != 1:
            info = j.get('inf') or j.get('info') or ''
            if '密码' in info:
                raise LanzouError('需要正确的提取码（当前：%r）→ 请把提取码作为第二个参数'
                                  % self.passcode)
            raise LanzouError('取直链失败: %s' % info)
        direct = j['dom'] + '/file/' + str(j['url'])
        headers = self._download_headers(direct)
        return {'url': direct, 'headers': headers}

    def _download_headers(self, direct):
        """探测 acw_sc__v2 挑战并求解；只发一次探测请求（避免触发 CDN 频控）。

        注意：下载 CDN（如 developer4.lanrar.com）在短时间被反复访问时会返回
        "网络异常需验证" 的落地页。因此这里只发 1 次探测，解出 Cookie 后即返回，
        真正的下载请求交给 Gopeed（它会带上这里下发的 Cookie/Referer/UA）。
        """
        base = {'Referer': self.base + '/', 'User-Agent': UA}
        probe = dict(base)
        probe['Range'] = 'bytes=0-0'
        try:
            code, raw, hh = self._req(direct, headers=probe, allow_error=True)
        except Exception:
            return base
        m = re.search(rb"arg1='([0-9A-F]+)'", raw)
        if not m:
            return base
        ck = 'acw_sc__v2=%s' % solve_acw_sc_v2(m.group(1).decode())
        tc = re.search(r'acw_tc=([^;]+)', hh.get('Set-Cookie', '') or '')
        if tc:
            ck += '; acw_tc=%s' % tc.group(1)
        headers = dict(base)
        headers['Cookie'] = ck
        return headers

    # ---------------- 对外 ----------------
    def parse(self, list_only=False):
        items = self.list_files()
        files = []
        downloads = []
        for it in items:
            nbytes = parse_size(it['size'])
            files.append({'name': it['name'],
                          'size': nbytes,
                          'size_h': human_size(nbytes) if nbytes else (it['size'] or '')})
            if list_only:
                continue
            d = self._direct(it['id'])
            downloads.append({'url': d['url'],
                              'name': it['name'],
                              'headers': d['headers']})
        return {'site': 'lanzou', 'files': files, 'downloads': downloads}


# ---------------- 独立 CLI（自测用）----------------
def main(argv=None):
    import argparse
    ap = argparse.ArgumentParser(description='蓝奏云解析（panpush 插件自测）')
    ap.add_argument('url')
    ap.add_argument('passcode', nargs='?', default='')
    ap.add_argument('--list-only', action='store_true')
    ap.add_argument('--json', action='store_true')
    ap.add_argument('--timeout', type=int, default=20)
    a = ap.parse_args(argv)
    p = LanzouParser(a.url, a.passcode, None, a.timeout)
    try:
        res = p.parse(list_only=a.list_only)
    except LanzouError as e:
        print('解析失败: %s' % e, file=sys.stderr)
        return 2
    if a.json:
        print(json.dumps(res, ensure_ascii=False, indent=2))
        return 0
    print('站点: %s' % res['site'])
    print('文件清单:')
    for f in res['files']:
        print('  - %s (%s)' % (f['name'], f['size_h']))
    for d in res['downloads']:
        print('直链: %s' % d['url'][:110])
        print('  头: %s' % {k: v for k, v in d['headers'].items() if k != 'Cookie'})
    return 0


if __name__ == '__main__':
    sys.exit(main())