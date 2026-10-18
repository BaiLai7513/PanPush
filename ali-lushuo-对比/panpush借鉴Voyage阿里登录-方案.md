# panpush 如何借鉴 Voyage 的阿里云盘登录

> 对象：panpush 的阿里云盘解析器（`panpush-xunlei-export/aliyun.py`）与登录界面
> （`/sdcard/Make/aliyun-login/AliyunLoginActivity.kt`）。
> 参照：`com.lx.lushuo.gjx.xjg`（内部 Voyage / `com.chuanyi.voyage`）的阿里登录实现（静态逆向所得）。
> 适用问题：**"panpush 自己怎么借 Voyage 的做法把阿里登录做稳、不再卡人机校验"**。

---

## 0. 一句话结论

Voyage 稳，不是因为它签名算得好，而是因为——
**它让"阿里官方网页"去承担登录（设备校验 / 滑块 / 短信），App 只在旁边把 token 捞出来，再拿 token 调官方 API。**
panpush 该借的正是这条**战略**和随之而来的**请求头细节**；而它自研的 `x-signature`、自有中转 `/userres/v1/*`、UA 服务端下发**都是私货，抄了没用**。

---

## 可借鉴的三件事（速览 1 / 2 / 3）

1. **请求头身份对齐** —— `X-Canary` 从 `v2.3.1` 升到 `v6.8.12`，并补齐 `Origin` / `Referer`（`https://www.aliyundrive.com`）/ `x-device-id`。 → 详见 §2.1
2. **让"网页"扛风控** —— 把已有的 `AliyunLoginActivity`（WebView 登录）作为 `load_creds()` 的**兜底**，让"没装官方 App"的机器也能登录。 → 详见 §2.2
3. **token 续期双端点** —— `/v2/account/token` 优先、`/token/refresh` 回退，防端点变更导致前功尽弃。 → 详见 §2.3

> 三条都是**降风控 / 补登录通路**，不涉及破解阿里风控（网页登录仍需用户自行完成短信/滑块）。
>
> 📌 另：桌面 UA 的**「完全伪装」清单**见 **§2.4**；harness「一进来就是扫码/短信验证」是否更容易被风控，见 **文末附节**。

---

## 1. 现状：Voyage vs panpush

| 维度 | Voyage | panpush 现状 | 结论 |
|---|---|---|---|
| 登录入口 | WebView 打开 `www.alipan.com`（桌面 UA + 指纹） | **root 直读官方 App 的 SQLite**（`com.alicloud.databox/databases/0.db`） | 两条并存，root 优先 |
| 桌面伪装 | 桌面 UA + JS 指纹 + Client-Hints | `AliyunLoginActivity.kt` **同样已实现** | ✅ 已对齐 |
| 取凭据 | 存密文 `cred_aliyun` | WebView 版从 localStorage `token` 抽 `refresh_token` | ✅ 已对齐 |
| API 头 `X-Canary` | `client=web,app=adrive,version=v6.8.12` | `client=web,app=adrive,version=v2.3.1` | ⚠️ **版本太旧，应升级** |
| `Origin` / `Referer` | `https://www.aliyundrive.com` | 无 `Origin`；`Referer=https://www.alipan.com/` | ⚠️ **应补齐** |
| `x-device-id` | 有（稳定设备标识） | 无 | ⚠️ **应补** |
| token 续期 | `POST api.aliyundrive.com/v2/account/token`（`grant_type=refresh_token`） | `POST api.aliyundrive.com/token/refresh`（`client_id=25dzX3vbYqktVxyX`） | 建议**双端点兼容** |
| `x-signature` | 有（盐 + `voyage:alipan:` 前缀） | 无 | ❌ **别抄**（见 §4） |
| 自有中转 | `/userres/v1/*` | 无 | ❌ **别抄** |
| UA 机制 | 服务端响应头 `X-Voyage-Retro-UA` 动态下发 | 固定桌面 UA | ❌ 无此条件，保持固定即可 |

> panpush 的 `AliyunParser` 目前**完全不自己登录**：`load_creds()` = `read_app_creds()`（root 直读）`or read_saved_creds()`（读 `.cookie.aliyun`）。
> 也就是说，本机没装「阿里云盘官方 App」或没登录时，panpush 阿里站点直接不可用——这正是要借鉴 Voyage 补齐的点。

---

## 2. 可借鉴的三件事（按性价比排序）

### 2.1 请求头身份对齐（改动最小、收益最直接）

阿里云盘的 `list` / `get_download_url` 接口**不校验签名**，但头部越接近"正常网页客户端"越不容易被风控盯上。
Voyage 用 `X-Canary ...v6.8.12`，panpush 用 `v2.3.1`（明显偏旧），且缺 `Origin`、`x-device-id`。

**`aliyun.py` 改动示意：**

```python
# —— 常量区 ——
API     = "https://api.aliyundrive.com"
AUTH    = "https://auth.aliyundrive.com"

# 旧：
# X_CANARY = "client=web,app=adrive,version=v2.3.1"
# REFERER  = "https://www.alipan.com/"

# 新（对齐 Voyage，更贴近当前官方网页客户端）：
X_CANARY  = "client=web,app=adrive,version=v6.8.12"
REFERER   = "https://www.aliyundrive.com"
ORIGIN    = "https://www.aliyundrive.com"

# 稳定的设备标识：首次生成 UUID 后持久化，全局复用
DEVICE_ID_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".device_id")

def _device_id():
    try:
        if os.path.exists(DEVICE_ID_FILE):
            v = open(DEVICE_ID_FILE).read().strip()
            if v:
                return v
        import uuid
        v = uuid.uuid4().hex
        with open(DEVICE_ID_FILE, "w") as fh:
            fh.write(v)
        return v
    except Exception:
        return "00000000000000000000000000000000"
```

```python
# —— AliyunParser._auth_hdr ——
def _auth_hdr(self, share=False):
    c = self._creds_now()
    h = {
        "Authorization": "Bearer " + c["access_token"],
        "X-Canary": X_CANARY,
        "Origin":   ORIGIN,            # ★ 新增
        "Referer":  REFERER,           # ★ 新增
        "x-device-id": _device_id(),   # ★ 新增
    }
    if share and self._share_token:
        h["x-share-token"] = self._share_token
    return h
```

> 说明：这是**降风控**，不是"解锁功能"。滑块只出现在**登录**环节，改头不会让滑块消失；它影响的是登录后调接口时被软拒/频控的概率。

---

### 2.2 "让网页扛风控"这条战略（Voyage 的精髓）

Voyage 把设备校验、滑块、短信**全部交给阿里官方网页**去跑，App 只负责把 `refresh_token` 抠出来。panpush 应把已有的 `AliyunLoginActivity`（WebView 登录）**作为兜底接进凭据加载链路**：

```python
def load_creds():
    """凭据来源优先级（借鉴 Voyage：网页负责登录，本地负责取 token）"""
    return (
        read_app_creds()          # 1) root 直读官方 App：最新、零成本（现状）
        or read_saved_creds()     # 2) .cookie.aliyun 落盘文件（现状）
        or read_webview_creds()   # 3) 应用内 WebView 登录结果（借鉴 Voyage，新增兜底）
    )
```

`read_webview_creds()` 读取 Android 侧 `SharedPreferences("panpush")` 中由
`AliyunLoginActivity` 写入的 `cred_aliyun / cred_aliyun_at / cred_aliyun_drive`——
**这套存储约定在 `AliyunLoginActivity.kt` 里已经实现**，只要加一个读取函数即可闭环。

至此 panpush 的阿里登录变成：

```
装官方 App  → root 直读（最快）
没装官方 App → .cookie.aliyun 或 应用内 WebView 登录（兜底）
```

---

### 2.3 token 续期双端点兼容

Voyage 用官方通用端点 `/v2/account/token`；panpush 现用 `/token/refresh` + `client_id`。建议**优先官方端点、失败回退**：

```python
def refresh_creds(refresh_token, drive_id=None):
    if not refresh_token:
        return None
    candidates = (
        (API + "/v2/account/token",   {"grant_type": "refresh_token",
                                       "refresh_token": refresh_token}),
        (API + "/token/refresh",      {"client_id": "25dzX3vbYqktVxyX",
                                       "refresh_token": refresh_token}),
    )
    for url, body in candidates:
        st, txt = _req("POST", url, body=body)
        j = _parse_json(txt) or {}
        if j.get("access_token"):
            return {
                "access_token": j["access_token"],
                "refresh_token": j.get("refresh_token") or refresh_token,
                "drive_id": str(j.get("default_drive_id") or j.get("drive_id") or drive_id or ""),
                "expire_time": j.get("expire_time") or "",
                "source": "refresh",
            }
    return None
```

---

### 2.4 电脑 UA「完全伪装」清单（把 WebView 彻底伪装成 Windows 桌面 Chrome）

> 原则：**UA 说什么，其它指纹就必须一致。** 只把 `User-Agent` 改成 Windows，却留着
> `platform=Linux armv8l`、触摸点数 > 0、Adreno/Mali GPU、手机分辨率——风控一眼看穿。
> 所以关键是「整体自洽」，而不是单点改 UA。

**① 会「露馅」的 Android 面 → 目标桌面值**

| 面 | Android WebView 默认（露馅） | Windows 桌面 Chrome 目标值 |
|---|---|---|
| `User-Agent` | `…Android…; wv)…Mobile Safari…` | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36` |
| `Sec-CH-UA*`（请求头） | `?1`…Android…带 model | `?0`…Windows…无 model |
| `navigator.platform` | `Linux armv8l` | `Win32` |
| `navigator.maxTouchPoints` | 5（>0） | 0 |
| `window.ontouchstart` | 存在 | 删除 → `undefined` |
| `navigator.vendor` | 有时为空 | `Google Inc.` |
| `navigator.hardwareConcurrency` | 8 | 8 / 16 |
| `navigator.deviceMemory` | 4 或缺失 | 8 |
| `screen.width × height` | 1080×2400 等 | 1920×1080 |
| `window.devicePixelRatio` | ≈2.75 | 1 |
| `window.outerWidth / outerHeight` | 0 / 0 | 1920 / 1080 |
| WebGL `VENDOR/RENDERER`（37445/37446） | Qualcomm / Adreno / Mali | `Google Inc. (Intel)` / `ANGLE (Intel, …D3D11…)` |
| `navigator.userAgentData` | `mobile:true, platform:Android` | `mobile:false, platform:Windows` |
| `navigator.plugins` | 空 / 少量 | 5 个 PDF Viewer |
| `navigator.languages` | `['zh-CN']` | `['zh-CN','zh','en']` |
| `navigator.webdriver` | false | false |
| `RTCPeerConnection` | 可能暴露局域网 IP | 建议屏蔽 / 伪造 |

**② 请求头（`loadUrl(url, headers)` 带头 + 覆盖主框架）** —— 在现有 `DESKTOP_HEADERS` 基础上再补：

```kotlin
"Sec-CH-UA-WoW64"            to "?0",
"Upgrade-Insecure-Requests"  to "1",
"Accept"                     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
```

**③ JS 指纹加固（在现有 `FINGERPRINT_JS` 基础上前补全）**

```javascript
(function(){
  var D=function(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}};
  // 屏幕 / 视口：手机分辨率 → 桌面 1920x1080
  D(screen,'width',1920);        D(screen,'height',1080);
  D(screen,'availWidth',1920);   D(screen,'availHeight',1040);
  D(screen,'colorDepth',24);     D(screen,'pixelDepth',24);
  D(window,'devicePixelRatio',1);
  D(window,'outerWidth',1920);   D(window,'outerHeight',1080);
  // 触摸彻底抹掉
  try{ delete window.ontouchstart; }catch(e){}
  D(navigator,'maxTouchPoints',0);
  // 客户端提示（JS 侧）
  D(navigator,'userAgentData',{
    brands:[{brand:'Chromium',version:'120'},{brand:'Google Chrome',version:'120'}],
    mobile:false, platform:'Windows',
    getHighEntropyValues:function(){return Promise.resolve({architecture:'x86',bitness:'64',platform:'Windows',
      platformVersion:'10.0.0',uaFullVersion:'120.0.0.0',wow64:false});},
    toJSON:function(){return {brands:this.brands,mobile:this.mobile,platform:this.platform};}});
  // 媒体设备：伪装成桌面（有摄像头/麦克风，非移动）
  try{ if(navigator.mediaDevices && navigator.mediaDevices.enumerateDevices){
    navigator.mediaDevices.enumerateDevices=function(){
      return Promise.resolve([
        {deviceId:'default',kind:'audioinput',label:'默认 - 麦克风 (Realtek)'},
        {deviceId:'default',kind:'videoinput',label:'默认 - 摄像头 (Integrated Webcam)'},
        {deviceId:'default',kind:'audiooutput',label:'默认 - 扬声器 (Realtek)'}
      ]);};}}catch(e){}
  // 网络类型：口径与桌面常用值一致
  D(navigator,'connection',{effectiveType:'4g',rtt:50,downlink:10,saveData:false});
})();
```

**④ 注意事项**
- **自洽优先**：以上每一项都必须与 `DESKTOP_UA` 指向的「Windows + Chrome/120」一致；漏一处就可能前功尽弃。
- WebView 的 JS 引擎本就是 **Blink**（与桌面 Chrome 同源），所以 JS 面的差异**主要是 Android 特征**，逐个抹平即可。
- `loadUrl(url, headers)` 只覆盖**主文档**请求头；若风控很严，可在 `WebViewClient.shouldInterceptRequest` 里对**子资源**一并改写（成本更高，按需）。
- `screen` / `devicePixelRatio` 若被网页用 `matchMedia` / `visualViewport` 交叉核对，也要一并处理，否则"1920×1080 的屏 + 3 倍像素比"这类矛盾反而更可疑。
- 过犹不及：**不要**伪造得比真机更"完美"或相互冲突（例如 UA 说 Chrome/120 但 `userAgentData` 报 Chrome/141）。

---

## 3. 顺带一处可优化：`AliyunLoginActivity.kt` 的续期端点

`AliyunLoginActivity.kt` 里 `AliyunApi.refresh()` 现在打的是
`https://api.aliyundrive.com/token/refresh`（`client_id=25dzX3vbYqktVxyX`）。
与 Voyage 对齐、也更通用的是 `https://api.aliyundrive.com/v2/account/token`。
建议**同样做双端点**，或在客户端失败后回退——保证"网页登录成功但刷新端点变更"时不至于前功尽弃。

---

## 4. **不要抄**的三件事（Voyage 私货）

| 项 | 为什么别抄 |
|---|---|
| **`x-signature`** | 算法 = `sha256(固定盐 + sha256("voyage:alipan:"+user_id)[:32] + ":" + user_id + ":0")`；盐固定、且带 `voyage:alipan:` 前缀 → 是给 **Voyage 自有服务**签的，**阿里官方不认**。panpush 登录的是阿里官方，只认 `Authorization: Bearer`。 |
| **`/userres/v1/*` 中转后端** | Voyage 自家服务器（同 dex 有 `com.chuanyi.voyage.weiqi.WeiqiUserResponse` 佐证），panpush 没有也不该有。 |
| **UA 服务端下发**（`X-Voyage-Retro-UA`） | 靠它自己后端返回响应头动态替换 UA；panpush 无此条件，用固定桌面 UA 即可。 |

> 关键区分：Voyage 的 `x-signature` 是**登录它自己的后端**用的；panpush 登录的是**阿里官方**。两者认证体系不同，不能混用。

---

## 附：harness 直接进「扫码 / 短信验证」——是不是更容易被风控？

**现象**：harness 跑起来后，登录页**一进来就是扫码 / 短信验证**，没有出现"账号密码"或正常首页。

**先给结论：**
- 出现「扫码 / 短信验证」**本身不代表被风控**。阿里网页端对新会话默认就走这类验证路径，很常规。
- 真正「被风控」的表现是：**验证过不了**——滑块反复失败、提示"环境异常 / 请使用官方客户端"、验证码下发失败。
- 所以关键不是"为什么直接进验证"，而是"**这一步能不能过**"——它取决于 **环境可信度**（指纹自洽 + IP 稳定 + 设备稳定），**不是验证方式本身**。

**为什么「直接进验证」可能更容易踩雷：**
网页端愿意把你**直接推到验证环节**，往往说明它对当前会话的**可信度判断偏低**。此时若指纹还留着 Android 破绽（见 §2.4），风控权重会被进一步抬高，导致：
- 滑块更严、更容易判失败；
- 短信下发 / 校验被限；
- 就算当时过了，后续调 API 也更容易遇到软拒 / 频控。

也就是说：「直接进验证」是一个**信号**（提示你环境可能不够干净），**不是原因本身**。

**对策（按优先级）：**
1. **把 §2.4 的完全伪装做全、做自洽**（最重要）——UA / Client-Hints / navigator / 屏幕 / WebGL 必须全部指向同一个「Windows + Chrome/120」。
2. **固定 `x-device-id` + 复用 cookie / localStorage**，别每次登录都像"全新设备"。
3. **出口 IP 稳定**——避免频繁切换 / 机房 IP / 代理抖动（本机有 mihomo，注意别让登录流量走异常节点）。
4. **登录动作拟人**——别秒点、别高频重试、验证码试错有限。
5. **降低「新设备」权重**——能扫码就扫码（手机端是"已登录的可信设备"，通过率通常更高）。
6. **观察而非猜测**——harness 若能抓到登录请求 / 响应，优先看接口返回（`code` / `message`），比"猜是不是被风控"准得多。

**关于「扫码 vs 短信」：**
- 两者都只是**验证方式**，能否通过由**环境可信度**决定，不是选哪种更安全。
- **扫码**：借手机端（已登录、可信）完成验证，**通过率通常最高**，代价是要第二台设备（或同机分屏）。
- **短信**：本机可完成、省事，但**会叠加滑块**，且更吃当前网页环境——所以更应该在"§2.4 伪装做全"之后再走短信。

> 一句话：**别把"出现验证"当成"被风控"**。要过验证，把环境做干净（§2.4）+ 设备/IP 稳定，比纠结用扫码还是短信更有效。

---

## 5. 落地顺序（建议）

1. **改 `aliyun.py` 请求头**（`X-Canary` 升 `v6.8.12`、补 `Origin`/`Referer`/`x-device-id`）——3 处、风险最低；同时对照 **§2.4** 把 WebView 桌面伪装做全、做自洽；上线后观察取直链成功率与频控。
2. **改 `refresh_creds()` 为双端点兼容**。
3. **把 `AliyunLoginActivity` 接进 `load_creds()` 兜底**（新增 `read_webview_creds()`），让"没装官方 App"的机器也能登录。
4. （可选）WebView 登录时把同一个 `x-device-id` 也注入网页（cookie/localStorage），让**网页端与 API 端设备标识一致**，进一步降"新设备"风控。

---

## 6. 风险与待确认

- 上述头部（尤其 `X-Canary` 版本号）**沿用 Voyage 当前值**，是否长期有效取决于阿里侧策略；属"经验最优"，非保证。
- `x-device-id` 的**格式**未从 Voyage 完整还原（只知道存在并有稳定值）；用 UUID 占位，需真机验证是否被接受。
- `read_webview_creds()` 需确认 `SharedPreferences("panpush")` 的键名与 panpush 主程序一致（`AliyunLoginActivity.kt` 约定为 `cred_aliyun` / `cred_aliyun_at` / `cred_aliyun_drive`）。
- 全部改动**未做真机端到端验证**，需在目标机型上实测登录→列文件→取直链。
- 借鉴重点是"**登录链路与头部**"，**不涉及**破解/绕过阿里风控——网页登录仍需用户自行完成短信/滑块验证。

---

*本文档基于 panpush 现有源码（`aliyun.py` / `AliyunLoginActivity.kt`）与 Voyage 的静态逆向结论整理，供实现参考。*