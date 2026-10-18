# 阿里云盘 —— 对比 `com.lx.lushuo.gjx.xjg`（Voyage）的登录/解析做法

> 背景：panpush 用桌面 UA 做阿里云盘**密码登录会卡人机校验（滑块）**，只有**扫码**能过；
> 而第三方 App `com.lx.lushuo.gjx.xjg` 却能正常登录。本文**只针对阿里云盘**，把它拆开看它到底怎么做的，
> 再给出 panpush 的可行改进方向。
> 设备基线：OnePlus PKR110 / Android 15 / APatch Root。日期：2026-10-13。

---

## 0. 结论先行

> **不是"某个 UA 有魔力"。** 该 App 能避开滑块，靠的是**两件 panpush 没做的事**：
>
> 1. **设备信任注册**：调 `/users/v1/users/device/create_session`（代码注释「设备校验」），带 `x-device-id` + `x-signature` —— 先把设备"登记"为可信，风控就不升级到滑块；
> 2. **冒充官方 adrive 网页客户端**：所有 API 请求带 `X-Canary: client=web,app=adrive,version=v6.8.12` + `Origin/Referer: https://www.aliyundrive.com`，即以"官方网页版"身份出现，而不是"一台手机上跑着的桌面浏览器"。
>
> 而 panpush 的做法是：**在手机 WebView 里塞一个通用桌面 Chrome UA，去网页 passport 做密码登录** —— 指纹自相矛盾、又没有设备信任，风控自然升级到滑块。**扫码能过**，是因为扫码是另一条更受信任的流程。

---

## 1. 该 App 是怎么登录阿里的（实测证据）

### 1.1 它就是 WebView 登录 —— 有 cookie 为证

`/data/data/com.lx.lushuo.gjx.xjg/app_webview/Default/IndexedDB/` 下存在：
```
https_www.alipan.com_0.indexeddb.leveldb
https_user.123pan.cn_0.indexeddb.leveldb
https_yun.123pan.cn_0.indexeddb.leveldb
```
→ 说明它**真的用 WebView 打开过 `www.alipan.com`** 做登录。

`app_webview/Default/Cookies` 里与阿里相关的 cookie（实测导出）：
| host | cookie（关键） | 含义 |
|---|---|---|
| `.alipan.com` | `SESSIONID` `cookie2` `_tb_token_` `cna` `csg` `isg` `t` `tfstk` `_samesite_flag_` | **淘宝/阿里 passport 体系**登录态 |
| `www.alipan.com` | `ADRIVE_UID`(56) `__itrace_wid` `_bl_uid` `arms_uid` | 网盘用户标识 |
| `passport.alipan.com` | `XSRF-TOKEN` `_uab_collina` `last_cc` `last_u_aliyun_drive_web_sms` `arms_uid` | **短信登录痕迹**（`..._web_sms`） |
| `auth.alipan.com` | `arms_uid` | 鉴权域 |
| `.ynuf.aliapp.org` | `cbc`(68) | **阿里风控（ynuf）指纹 cookie** |

> 关键点：`passport.alipan.com` 上出现 `last_u_aliyun_drive_web_sms` → 至少用过**短信验证码登录**；同时 App 有专门的**扫码登录**页（见 §1.4）。

### 1.2 登录后走**原生 API**（不是网页）

在 `classes4.dex` 反汇编中定位到阿里 API 请求的构造（偏移 `0x3168ec` 一带）：
```
base      https://api.aliyundrive.com
headers   Authorization: "Bearer " + <token>
          Origin:  https://www.aliyundrive.com
          Referer: ...
          X-Canary: client=web,app=adrive,version=v6.8.12     ← ★ 冒充官方网页客户端
          x-device-id: <device id>
          x-signature: <签名>
```
token 续期（偏移 `0x3227da` 一带）：
```
POST https://api.aliyundrive.com/v2/account/token
body: refresh_token=<rt>&grant_type=...      → 返回 access_token
```
→ 说明它**登录一次拿到 refresh_token 后，后续全部走官方 API 自行续期**，和我们 panpush 的 `aliyun.py` 思路一致；差别在于它**额外带了官方客户端标识和签名**。

### 1.3 ★ 设备信任注册（避开滑块的关键）

同一处反汇编里出现：
```
POST /users/v1/users/device/create_session     // 字符串旁注："设备校验"
headers: x-device-id, x-signature
```
→ 这是阿里新版的**设备会话注册**流程。它把本机登记为一个"可信设备"，风控对可信设备的滑块要求会明显放宽。

> 也就是说，**它"能登录"不是 UA 的功劳，而是它补做了设备注册 + 客户端身份伪装。**

### 1.4 它其实也主推扫码登录

- 独立活动类：`Lcom/chuanyi/voyage/ui/account/QrLoginLinkActivity;`
- 深链：`voyage://account/qr-login`

→ 扫码是该 App 支持的**一等公民**（和 panpush 现状一致：扫码能过）。

### 1.5 UA 是**动态/服务端下发**的，不是写死

反汇编里有一个 OkHttp 拦截器（偏移 `0xb29d1a` 一带）：
```
读取响应头 "X-Voyage-Retro-UA" → 用它替换请求的 "User-Agent"
```
→ 该 App 的 UA 由**服务端按站点动态下发**（"Retro UA"机制）。所以"它用哪个 UA"并不固定，核心不在 UA 本身。

### 1.6 凭证存储：加密，且非明文

- `files/datastore/pan_credentials.preferences_pb` 里的 `cred_aliyun` 是一段**高熵密文**（不是明文 token）。
- 另有 `pan_entitlement.preferences_pb`（`watermark`/`verdict`，同样密文）。

→ 它的 `cred_aliyun` 对应 panpush 的 `cred_aliyun`，但**做了加密**。

### 1.7 它还顺带做的事
- 桌面 UA 常量：Chrome/124、130、140、141、Mac Chrome137、Edg143 等（多套）。
- WebView UA 实测在 `classes4` 的一处设置为 **Windows Chrome/130 桌面 UA**（该 WebView 名为 `VoyageCreatorBridge`，是小程序宿主，非 pan 登录页）。

---

## 2. 为什么 panpush 会卡滑块，而这个 App 不会

| 维度 | panpush（现状） | Voyage（该 App） | 差异影响 |
|---|---|---|---|
| 登录入口 | 手机 WebView + 桌面 UA，**网页密码登录** | WebView 登录（**短信/扫码**）+ 原生 API | 密码网页登录最易触发滑块 |
| Client 身份 | 通用桌面 Chrome | `X-Canary: client=web,app=adrive,v6.8.12` | 官方客户端身份 → 风控更宽 |
| 设备信任 | ❌ 无 | ✅ `device/create_session` + `x-device-id/x-signature` | 有设备注册 → 不升级滑块 |
| 指纹一致性 | 桌面 UA 但 `navigator`/Client Hints 是 Android | 服务端按需下发 UA + 原生 API | 指纹矛盾 → 判可疑 |
| token 续期 | 自研 `aliyun.py`（Bearer） | `/v2/account/token` + 官方头 | 机制相近，差在客户端身份 |
| 滑块兜底 | 只能扫码 | 扫码是一等公民 | 一致 |

> **一句话**：panpush 的表现像"一台手机伪装的桌面浏览器在登录"，被判高风险；Voyage 表现为"官方网页客户端 + 已登记的可信设备"，风控放开。

---

## 3. panpush 可落地的改进方向（阿里，按性价比排序）

### 方案 A（最省事，推荐先做）：把扫码登录做扎实
- 现状扫码已能过 → 把它作为阿里登录的**主路径**，密码登录作为可选。
- 好处：零对抗成本；与 Voyage 的现状一致。
- 代价：用户需手机 App 扫码。

### 方案 B（对齐"官方客户端身份"）：给阿里 API 请求补官方头
在 `aliyun.py` 的请求上增加（实测该 App 就这么带）：
```
Origin:  https://www.aliyundrive.com
Referer: https://www.aliyundrive.com
X-Canary: client=web,app=adrive,version=v6.8.12
```
- 成本低、无风险，先试。
- 是否能显著降低滑块**待联调验证**（本次无法用真实账号跑端到端）。

### 方案 C（治本，工作量最大）：实现设备会话注册
复刻 `/users/v1/users/device/create_session` + `x-device-id` + `x-signature`。
- 这是"避开滑块"的真正关键，但**`x-signature` 的算法在 APK 里被 R8 混淆，本次未能还原**（见 §5）。
- 阿里 `x-signature` 属业界已知可复现算法，可另行专项攻克。

### 方案 D（登录层）：优先短信/扫码，弱化密码
Voyage 的 cookie 显示用过短信登录；同时有扫码页。建议 panpush 阿里登录也**默认短信验证码或扫码**，密码作为兜底。

---

## 4. 与 panpush 现有文档的衔接
- 指纹一致性问题与移动端布局：见《阿里云盘登录-指纹伪装与移动端布局适配》。
- 网页 client_id / `/token/refresh` 对照实验：见《阿里云盘应用内登录-测试与实现》。
- App↔Web 令牌不通用：见《应用内登录 vs Root直读 差异与选型》。

---

## 5. 未确认 / 待办（诚实标注）

1. **`x-signature` / `create_session` 的确切算法**：APK 已 R8 混淆（pan 相关类描述符被压成短名如 `Laqs;`），本次只定位到调用点与所需头，**算法未还原**。需专项逆向。
2. **方案 B 是否真能降滑块**：机制推断（"官方客户端身份更受信任"），**未经真实账号端到端验证**。
3. 该 App 的**密码登录路径是否也会偶发滑块**：未测（可能它自己也默认短信/扫码）。
4. `cred_aliyun` 的**加解密算法**：未还原（仅确认是密文）。
5. 所有结论基于 APK 静态分析 + 本机数据实测；**未含真实登录抓包**。

---

## 6. 事实来源（可复现）

- 包信息：`pm path com.lx.lushuo.gjx.xjg`；`dumpsys package`（v1.0.11，targetSdk 37）。
- APK 静态分析：`/sdcard/Make/lushuo/base.apk`，`dexdump -d dex/classes4.dex`。
 - 阿里 API 头：偏移 `0x3168ec` 一带；`create_session`/「设备校验」：`0x316fa8` 一带；
 - token 续期：`0x3227da` 一带；`X-Voyage-Retro-UA` 拦截器：`0xb29d1a` 一带；
 - WebView UA（CreatorBridge）：`0x7d441c` 一带。
- 运行时数据：`/data/data/com.lx.lushuo.gjx.xjg/app_webview/Default/Cookies`、
 `files/datastore/pan_credentials.preferences_pb`（**已脱敏，本文不留明文**）。