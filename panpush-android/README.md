# PanPush（安卓 App · arm64）

> 把**网盘分享链接**在本机解析成**带签名的临时直链**，并**直接推送进 Gopeed**。
> 不需要第三方解析站、不需要卡密，全程只经过你自己的手机。

本工程是 `panpush-export`（Termux CLI 版 Python 脚本 + 开发文档）的 **Android 原生 App 移植**：
把 CLI 的三步解析链路、凭据获取（三条通道）、Gopeed 推送契约，全部用 Kotlin 重写，
并做成可安装的 **arm64-v8a APK**。

---

## 一、产物

| 文件 | 说明 |
|---|---|
| `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` | 调试版（自签名，可直接安装） |
| `app/build/outputs/apk/release/app-arm64-v8a-release.apk` | 发布版（同样自签名，`minifyEnabled=false`） |

> 本工程 `abiFilters` 与 `splits.abi` 都只保留 **arm64-v8a**，因此只产出 arm64 的包。
> 当前版本 **v1.2.3**（versionCode 6）：v1.0.0 起支持夸克/UC/123/百度，
> v1.1.0 加迅雷，v1.2.0 再加**蓝奏云**与**阿里云盘**；
> v1.2.2 按交付包把登录统一为**应用内 WebView**（修 7 个持久化坑）+ 新增**存储自检**；
> **v1.2.3**：① **有 root 就优先用 root 直读**（按钮置顶，快且省事）；
> ② 修掉**登录页布局挤成一坨**的问题（viewport 修正 + 缩放 + 手机/桌面 UA 切换）；
> ③ 澄清 **UA 用在哪一步**（登录页不需要 PC UA，是取直链那步需要）。

---

## 二、App 功能（对应 CLI 的哪一块）

| App 功能 | 对应 CLI | 说明 |
|---|---|---|
| 粘链接 → 解析 → 列文件（可选勾选） | `panpush.py <url> [pwd] --list-only` | 站点自动路由 |
| 取直链并推送 Gopeed（支持批量） | `panpush.py <url> [pwd]` | 单文件 `POST /api/v1/tasks`；多文件 `/tasks/batch` |
| 提取码自动识别 | `extract_passcode()` | 链接带 `?pwd=xxxx` 时自动填 |
| Gopeed 设置（地址/Token/并发/目录） | `--gopeed` `--gopeed-token` `--connections` `--dir` | 设置页可测试连通性 |
| 登录态管理（3 条通道） | `getcookie.py` 的 A/B/C | 见下节 |
| 123 取链模式 | `PANPUSH_123_MODE` | auto / share / netdisk |
| 任务列表 | — | 新增：直接读 Gopeed `/api/v1/tasks` |

### 支持站点

| 站点 | 状态 | 说明 |
|---|---|---|
| 123 云盘 | ✅ 端到端 | 匿名可列目录+取直链；大文件走「转存 → 我的网盘直链」（需 token） |
| 夸克网盘 | ✅ 解析+推送 | 取直链与下载都需登录 Cookie（`__puus`），直链必须带 Cookie 否则 412 |
| UC 网盘 | ✅ 同夸克 | 同源后端，仅换域名/Referer |
| 百度网盘 | ✅ 解析+推送 | 列目录匿名可；下载需 `BDUSS`；大文件先「转存」再取网盘直链 |
| 迅雷云盘 | ✅ 解析+推送 | **需 root**：captcha 令牌只能由 APP 生成 → root 直读三件套（或手动粘贴） |
| **蓝奏云** | ✅ 端到端 | **全匿名**，无需任何凭据；内含 acw_sc__v2 WAF 求解器；支持文件夹页 |
| **阿里云盘** | ✅ 解析+推送 | **需 root**（或手动 token）：分享文件不允许第三方直下 → 转存 → 取 OSS 直链 → 回收副本。**≥100MB 取不到直链** |
| 城通网盘 | ⏳ 未做 | 评估结论：能解析但限速严重、直链几分钟失效，投入产出比低 |

---

## 三、登录态（凭据）

### 3.1 登录只有一条路：**应用内 WebView**

> ⚠️ **外部浏览器 / CustomTabs 的 Cookie 存在浏览器沙箱里，本 App 在另一个沙箱，
> 永远读不到** —— 所以「用系统浏览器登录」**不可能**把登录态存下来，那是设计错误，不是缺个读取逻辑。
> v1.2.2 起界面里**去掉了所有「系统浏览器登录」入口**，只保留应用内 WebView。

登录页 `webview/WebViewLoginActivity` 已按真机实测修掉 7 个持久化坑：

| # | 修复点 | 不做会怎样 |
|---|---|---|
| ① | `CookieManager.flush()`（onPageFinished / onPause / onDestroy） | Cookie 只在内存，进程被杀即丢 |
| ② | `setAcceptThirdPartyCookies(webView, true)` | 跨域 SSO（百度 `wappass→pan.baidu.com`、夸克 passport）丢 Cookie |
| ③ | 不 incognito / **不调用** `removeAllCookies` | 每次登录后自己把状态清掉 |
| ④ | `domStorageEnabled = true`（+ `databaseEnabled`） | 123pan 等存在 localStorage 的 token 存不下 |
| ⑤ | 保持 HTTPS、**不伪装 UA**、混合内容兼容模式 | `SameSite=None; Secure` 被丢 |
| ⑥ | 全进程统一 `CookieManager.getInstance()` | 多 WebView 不共享 |
| ⑦ | 固定默认 `app_webview` profile | 换 profile / 清数据 = 丢登录态 |

**登录成功判据**（实测，来自交付包）：夸克 `pan.quark.cn/ctoken` · 百度 `pan.baidu.com/PANWEB` ·
123 `www.123pan.com/_c_WBKFRo`。登录页顶部会实时显示「✅ 已检测到登录」。

### 3.1.1 登录页操作与「挤成一坨」的修法

登录页顶部工具条（v1.2.3）：**缩放 `－ ＋`** · **手机版/桌面版切换** · **完成并保存**。

页面挤成一坨的主因是**很多网盘登录页没有 viewport meta**（按桌面 980px 虚拟宽度渲染，
整页塞进手机屏幕、字小到点不到）。修法：

* 页面**自己没声明** viewport 时，注入一个 `width=device-width, initial-scale=1.0, user-scalable=yes`
  （页面声明了就尊重它，不覆盖）；
* `useWideViewPort = true` + `loadWithOverviewMode = false`：让页面按可用宽度渲染、可横向滚动，
  而不是被强行缩放压扁；
* 保留双指缩放 + `textZoom` 缩放按钮（0.75×~3×），**元素太小点不到时先按「＋」**；
* 实在不行按 **「桌面版」** 切 PC UA 重载 —— 部分网盘的桌面版反而更完整、更好点。

### 3.1.2 UA 到底用在哪一步（常被搞混）

| 步骤 | 需要什么 UA | 说明 |
|---|---|---|
| **登录页** | **不需要 PC UA** | 登录只是网页，拿到的 Cookie 与 UA 无关。手机版 UA 即可（百度/夸克/123 都验过） |
| **解析取直链（夸克）** | **必须伪装 PC 客户端 UA** | 夸克 API 要 `quark-cloud-drive/2.5.20 … Electron/…`，程序已内置（`QuarkParser.UA`） |
| **直链下载（夸克）** | 同 PC UA + `Referer` + `Cookie` | 缺 Cookie 会 412 |
| **直链下载（迅雷）** | **自动探测** | 按 host 依次试 `okhttp/4.9.0` → `netdiskP2SP/…` → `Thunder/1.0` → `Mozilla/5.0` |
| **直链下载（蓝奏）** | 浏览器 UA + WAF Cookie | `acw_sc__v2` 求解后随直链下发 |

> 所以「内置夸克登录要不要用 PC UA」的答案是：**登录页不用**；**取直链那步必须用**，而那是程序内部的事，
> 跟你在登录页看到什么 UA 无关。登录页给 UA 开关只是为了**页面能正常显示和操作**。

### 3.1.3 有 root 就用 root（v1.2.3）

`RootShell.hasSu()` 只检查 su 文件是否存在（**不触发授权弹窗**）。检测到 su 时：

* 「凭据」页顶部直接提示「已检测到 su → 各站点优先走 root 直读」；
* 每张站点卡片把 **「root 直读」做成主按钮并放在第一位**，一键登录退居次要位置；
* 卡片推荐标签变成「★ 本机有 root → 优先用 root 直读（最快最省事）」。

> root 通道确实更快更省事：一次性 `su` 复制明文库/DB → 直接拿到 `__puus`/`token`/`BDUSS`/三件套，
> 不用在登录页里绕。检测不到 su（或 APatch/Magisk 对应用隐藏了 su）时，
> 界面会回退到「一键登录（应用内 WebView）」+「手动粘贴」。

### 3.2 「登录了」不等于「拿到最终令牌」

| 站点 | 登录判据 | 最终要的令牌 | 登录后能从内置浏览器读回吗 | 界面给的入口 |
|---|---|---|---|---|
| 夸克 / UC | `ctoken` | `__puus`（**httpOnly**） | ❌ | 一键登录 + root 直读 / 手动粘贴 |
| 123 云盘 | `_c_WBKFRo` | **APP 的 JWT**（网页端是另一套鉴权） | ❌ | 一键登录 + root 直读 / 手动粘贴 |
| 百度网盘 | `PANWEB` | `BDUSS`（非 httpOnly） | ✅ **能** | **一键登录**（直接拿令牌）+ root 直读 |
| 迅雷 | — | 三件套（captcha 只能 APP 生成） | ❌ | root 直读 / 手动粘贴 |
| 阿里云盘 | — | token 组（APP 明文 SQLite） | ❌ | root 直读 / 手动粘贴 |
| 蓝奏云 | — | 无（全匿名） | — | 免登录，不需要 |

三种通道：

| 通道 | App 里的入口 | 需要 root | 说明 |
|---|---|---|---|
| **① 应用内 WebView 登录** | 卡片上的「一键登录」 | ❌ | Cookie 落在自己的沙箱；百度可直接读回令牌 |
| **② root 直读本机 App** | 卡片上的「root 直读」 | ✅ | 读**别的** App 私有目录里的明文凭据（见 3.4） |
| **③ 手动粘贴** | 卡片上的「手动粘贴」 | ❌ | 电脑 F12 / 外部获取后粘进来；123 可直接粘裸 JWT（自动补 `token=`） |

### 3.3 存储自检：验证「登录到底存没存下来」

每张卡片上都有 **「存储自检」**，读的是**自己 App** 的 `app_webview/Default/`
（**读自己不需要 root**），会打印：

* profile 路径 + `Cookies` / `Cookies-journal` / `Cookies-wal` / `Local Storage/leveldb` / `IndexedDB` 是否存在；
* Cookie 明细（host / name / value，标注 `[httpOnly]`、`（加密）`）；
* Local Storage leveldb 里抽出的可打印串。

> **判据**：登录后杀进程 → 重开 → 再点「存储自检」，**仍能读到非空 Cookie** = 登录态确实持久化了。
> 这是交付包里唯一能真正证明修复有效的验收项。

> ⚠️ 实测纠正：本 App **没有** `databases/`，`shared_prefs/` 里也**不含 token**，登录态全在 `app_webview/` 下。
> 另外 `run-as` 对**非 debuggable** 包必失败（那是调试权限，不是 root），必须用 `su`。

### 3.4 root 直读的各站点来源（读**别的** App 才需要 root）

| 站点 | App 包名 | 明文凭据位置 |
|---|---|---|
| 夸克 / UC | `com.quark.browser` | `app_u46_webview/Default/Cookies`（明文 SQLite，需含 `__puus`） |
| 百度 | `com.baidu.netdisk` | `app_webview_com.baidu.netdisk/Default/Cookies`（含 `BDUSS`/`STOKEN`） |
| 123 | `com.mfcloudcalculate.networkdisk` | `shared_prefs/login_user.xml` → `userToken`（兜底 `app_flutter/logs/*`） |
| 迅雷 | `com.xunlei.downloadprovider` | `auth_pref.xml` / `xl-acc-captcha-token.xml` / `__device_info_new__.xml` |
| 阿里 | `com.alicloud.databox` | `databases/0.db` → 表 `tb_user_info` |

> 凭据都在 **App 私有目录**（`-rw-------`），非 root（含 shell / Shizuku）**读不到**。
> 设备对普通应用隐藏 `su`（APatch/Magisk denylist）时，root 直读会给出明确失败原因、不会崩溃；
> 此时走「手动粘贴」。

> ⚠️ **不要为读自己 App 的登录态去申请 root** —— 没必要（用「存储自检」即可），还会引入授权失败风险。

### 迅雷是特例：三件套 + 必须 root

迅雷网页端的 captcha 签名已经改成**白盒混淆的公钥签名**（算法不可移植，参考实现的盐链机制已失效），
所以本 App **不复现签名**，而是直接"借"迅雷 APP 已经签好的令牌：

| 用途 | 来源（`/data/data/com.xunlei.downloadprovider/shared_prefs/`） |
|---|---|
| `Authorization` | `auth_pref.xml` 里 `credentials_*` 的 `access_token`（Bearer JWT） |
| `X-Captcha-Token` | `xl-acc-captcha-token.xml`（`ck0.` 前缀） |
| `X-Device-Id` | `__device_info_new__.xml` 的 `device_id_new`（令牌绑定了它） |

* App 里入口：「登录态」页的**迅雷云盘**卡片 →「root 读取三件套」（或「手动粘贴三件套」兜底）。
* **captcha 令牌寿命只有几分钟**，且**只启动 APP 首页不会刷新**——必须让它处理一次分享深链。
  所以解析时若发现令牌过期，App 会用**你当前的分享链接**把迅雷 APP 拉起一次（约 6~10 秒，会短暂切到前台）
  来触发刷新，然后自动重试。可在卡片上关掉这个自动刷新。
* 下载 UA 由程序对每个下载 host **自动探测**（分享链实测认 `okhttp/4.9.0`，见下）。
* 分享直链被服务端限速（实测 ~6 Mbps），并发给 8~16；个人云盘直链 ~48 Mbps。
* ⚠️ 转存（`/share/restore`）是**写操作**，本 App 未实现（与 CLI 版一致）。

> **v1.2.0 改进（对齐 docs/平台对比与改进.md §3.1）**：UA 不再写死。
> 因为「哪个 UA 能用」其实取决于直链的来源（分享链 vs 平台自己账号的链），
> 所以现在对每个下载 host 用 `Range: bytes=0-0` 依次试
> `okhttp/4.9.0 → netdiskP2SP/3.0.20.138 → Thunder/1.0 → Mozilla/5.0`，
> 取第一个 200/206 的并缓存；探测结果会显示在解析页的「解析提示」里。

### 蓝奏云：全匿名

* **不需要任何凭据**，直接粘 `https://xxx.lanzoui.com/xxxx` 即可；带密码的分享把密码填「提取码」。
* 域名会轮换（lanzoui / lanzoux / lanzouw / lanzouq / lanzouf / lanzn…），按 host 正则识别，页面内相对地址相对分享页 host 解析。
* 兼容两种页面形态：经典页内联 `isngis`/`wp_sign`；新样式页的 sign 在 `<iframe src="/fn?...">` 里。
* 文件夹页（`/b…`）走 `/filemoreajax.php` 分页取表。
* 下载 CDN 可能带**阿里云 WAF 挑战**（`acw_sc__v2`）：内置求解器（位置表重排 + 固定 KEY 异或），
  解出的 Cookie 随直链一起交给 Gopeed。因 CDN 有频控，**只探测一次**，真正下载交给 Gopeed。

### 阿里云盘：需要 root，且大文件取不到直链

* 阿里的特殊性：**分享文件不允许第三方直接下载**（`Forbidden`），
  取直链接口 `/v2/file/get_download_url` **必须登录态**。所以流程是：

  ```
  分享链接 → 匿名 get_share_token → adrive/v3/file/list 列目录
          → POST /v2/file/copy 转存到「我的云盘」
          → POST /v2/file/get_download_url 取 OSS 直链（~15 分钟有效，支持 Range）
          → POST /v2/recyclebin/trash 回收副本（回收后直链仍可用，STS 签名独立）
  ```

* 凭据：阿里云盘 APP 把 token **明文**存在
  `/data/data/com.alicloud.databox/databases/0.db` → 表 `tb_user_info`
  （`access_token` / `refresh_token` / `expire_time` / `drive_id`）。
  App 里入口：「登录态」页的**阿里云盘**卡片 →「root 读取 token」，无 root 可「手动粘贴 token」。
* `access_token` 快过期（或报 AccessToken 类错误）时自动用 `refresh_token` 调
  `POST /v2/account/token` 续期。
* **体积门控（硬门槛）**：≥约 100MB 的文件取直链会返回 HTTP 200 但 `url` 为空（软拒，免费账号）。
  App 对 ≥100MB 的文件**直接跳过**并写进「解析提示」。
* **取直链频控**：连续请求会返回空 url，约 20s 恢复 → 内置 15s 退避重试 3 次。
* 直链**按连接限速**（实测 1→128 连接：0.12→4.45 MB/s）→ 并发默认 64。

保存后点「校验」做**在线自检**：
* 百度 → `GET /rest/2.0/xpan/nas?method=uinfo`（`errno -6 Invalid Bduss` = 失效）
* 123 → `GET /b/api/user/info`（`code 0` = 有效；`401` = 掉登录）
* 夸克 → 无稳定公开接口，只做字段级弱校验（是否含 `__puus`）
* 迅雷 → 无在线接口：检查三件套是否读得到 + captcha 令牌新鲜度
* 阿里 → 无在线接口：检查 token 是否读得到 + `drive_id` 是否齐全 + 剩余有效期

凭据只保存在本机 `SharedPreferences`，不上传任何服务器、不参与 `allowBackup`。

---

## 四、快速上手

1. 安装 `app-arm64-v8a-debug.apk`（arm64 安卓机，Android 8.0+）。
2. 打开 **Gopeed** → 设置 → **API** 打开（默认 `127.0.0.1:9999`）。
3. 打开 PanPush → 「设置」→ 确认 API 地址 → 「保存并测试连接」，右上角出现绿点即可。
4. 「设置」→「管理登录态 / 凭据」→ 对要用的网盘点「**一键登录**」（应用内 WebView），
   登录后点「完成并保存」；页面顶部出现「✅ 已检测到登录」即成功。
5. 回「解析」页 → 粘贴分享链接（带 `?pwd=` 会自动识别提取码）→「解析并列出文件」。
6. 勾选要下的文件 →「取直链并推送 Gopeed」。想在 Gopeed 里看进度，切到「任务」页刷新。

**冒烟测试建议（照开发文档 §六）**：先用 **123 云盘**免登录链接跑通「列目录 → 取直链 → 推送」，
再用小文件验证，最后才上 4GB+ 大文件。

---

## 五、工程结构

```
panpush-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties / local.properties
├── gradlew / gradlew.bat                    # Gradle wrapper（8.13）
└── app/
    ├── build.gradle.kts                     # arm64-v8a only、Compose、OkHttp
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/panpush/app/
        │   ├── PanPushApp.kt                # Application + 依赖容器
        │   ├── MainActivity.kt              # 入口（含「分享 → PanPush」接收）
        │   ├── core/                        # 模型 / 配置 / 容器
        │   │   ├── Model.kt                 # SiteId / ShareFile / DownloadItem / Outcome
        │   │   ├── Settings.kt              # Gopeed 设置 + 各站点凭据
        │   │   └── AppContainer.kt
        │   ├── net/Http.kt                  # OkHttp 封装（对齐 _http/_get）
        │   ├── share/                        # 解析器（对齐 panpush.py / xunlei.py / lanzou.py / aliyun.py）
        │   │   ├── ShareParser.kt           # 接口 + 站点路由 Router + ParseException
        │   │   ├── QuarkParser.kt           # 夸克 + UC
        │   │   ├── Pan123Parser.kt          # 123（含转存后取链）
        │   │   ├── BaiduParser.kt           # 百度（含网盘直链）
        │   │   ├── XunleiParser.kt          # 迅雷分享（三件套 + 令牌自动刷新 + UA 自动探测）
        │   │   ├── LanZouParser.kt          # 蓝奏云（全匿名 + acw_sc__v2 WAF 求解）
        │   │   └── AliyunParser.kt          # 阿里云盘（转存 → OSS 直链 → 回收）
        │   ├── push/GopeedClient.kt         # Gopeed API 契约（严格按已验证结构）
        │   ├── cred/                        # 凭据（对齐 getcookie.py / xunlei.py / aliyun.py）
        │   │   ├── SiteProfile.kt           # 站点凭据方案：登录判据 + 令牌来源 + 各通道文案
        │   │   ├── RootShell.kt             # su 授权执行
        │   │   ├── CredentialExtractor.kt   # root 提取（别的 App：明文 Cookie 库 / shared_prefs）
        │   │   ├── XunleiCredStore.kt       # 迅雷三件套：root 直读 + 刷新 + 手动落盘
        │   │   ├── AliyunCredStore.kt       # 阿里 token：root 直读 SQLite + 续期 + 手动落盘
        │   │   ├── WebCookieReader.kt       # 运行期读本应用 WebView CookieStore
        │   │   └── CredentialValidator.kt   # 在线自检
        │   ├── webview/                     # 应用内 WebView 登录套件（对齐交付包）
        │   │   ├── WebViewLoginActivity.kt  # 登录页（已修 7 个持久化坑）+ localStorage 桥
        │   │   ├── CookieStore.kt           # flush / 取 Cookie / 复制 DB+journal 读自己
        │   │   └── WebViewInspector.kt      # 存储自检：profile / Cookie / localStorage（不需要 root）
        │   └── ui/                          # Compose 界面
        │       ├── PanPushRoot.kt / ParseScreen.kt / TasksScreen.kt
        │       ├── SettingsScreen.kt / CredentialsScreen.kt
        │       └── theme/Theme.kt
        └── res/                             # 图标 / 主题 / 文案
```

---

## 六、构建

```bash
# 需要 JDK 17+、Android SDK（platform 35 + build-tools 35）
# local.properties 里的 sdk.dir 指向你的 Android SDK
./gradlew :app:assembleDebug      # 调试包
./gradlew :app:assembleRelease    # 发布包（用 debug 签名，便于直接安装）
```

产物：`app/build/outputs/apk/<变体>/app-arm64-v8a-<变体>.apk`

---

## 七、能力边界（照抄实测结论）

1. 取到的是**带签名、有时效**的临时直链，不是永久链接；过期重新解析即可。
2. 夸克/百度/123 对**大文件下载**都有登录墙，必须带用户自己的凭据——这不是工具能绕的。
3. **百度非 SVIP 限速**（实测 ~80–120 KB/s）；**123 转存后直链**实测 ~9 MB/s。
4. 百度大文件分享直链会被服务端拒绝（`list` 为字符串 = 需转存）；App 会先尝试网盘定位，
   找不到就自动回退分享直链，并提示「需先在百度网盘里转存一次」。
5. 123 大文件 `5112` 时需要 token；App 支持 `auto / share / netdisk` 三种模式。
6. **畸形 Gopeed 请求会打挂进程**：只发开发文档里验证过的结构（`req.extra.header` 为对象、
   `connections` 放 `opts.extra`），不做结构试错。
7. root 提取依赖设备有可用 `su`；APatch/Magisk 对应用隐藏 `su` 时会明确报错并引导走通道 ①/③。

---

## 八、来源

移植自两个设备实测导出包：

**`panpush-export/`**（主程序 + getcookie）

* `panpush.py`（主程序，纯标准库）→ `share/` + `push/` + `ui/`
* `getcookie.py`（登录态工具）→ `cred/`
* `开发文档.md` §4/§5/§5B/§5C/§6/§7/§8 → 端点、请求头、错误码、Gopeed 契约、凭据模型
* `导出说明.md` §五（需要改造的设备相关点）→ 本工程的改造依据：
  绝对路径改为 App 私有目录 + 可配置；`getcookie` 的扫描/`su` 逻辑改为「三条通道」；
  Android 包装器（`.sh`）在 App 里不再需要。

**`panpush-xunlei-export/`**（迅雷 + 蓝奏 + 阿里接入）

* `xunlei.py` → `share/XunleiParser.kt` + `cred/XunleiCredStore.kt`
* `lanzou.py` → `share/LanZouParser.kt`
* `aliyun.py` → `share/AliyunParser.kt` + `cred/AliyunCredStore.kt`
* `docs/迅雷接入设计与实现.md` §2（协议/请求头）、§3（三件套与令牌生命周期）、
  §4（限速与 UA）、§7（集成点）→ 全部落地
* `docs/协议分析.md` §七（root 直读方案）、§八（限速对比）→ 解释了为什么迅雷走 root、不改签名
* `docs/阿里云盘接入设计与实现.md` §2（端点矩阵/直链特性/体积门控/频控/凭据）、
  §3（内部函数与流程）、§4（集成改动）→ 全部落地
* `docs/网盘站点评估与集成优先级.md` §二（蓝奏协议全貌与两个踩坑）、
  §五（可借鉴点：按站点并发、UA 自动探测）→ 全部落地
* `docs/平台对比与改进.md` §3.1（UA 自动探测）、§3.4（按站点默认并发）→ v1.2.0 已补上
* 集成点里「`_load_saved_cookie` 对 xunlei / aliyun / lanzou 早退」
  → App 里表现为：这三站的凭据**不参与通用 Cookie 串**，
  迅雷/阿里由界面注入各自的 `*CredStore`，蓝奏干脆不需要凭据
