# panpush（com.panpush.app）诊断与改进建议

> 主题：v1.2.2 出现「感觉修得更差了」的实测定位、根因分析、各站点登录设计，以及给 harness 的改进清单
> 面向：`com.panpush.app` 开发者（harness）
> 设备：OnePlus PKR110，Android 15，APatch Root，targetSdk=35
> 被测版本：`com.panpush.app` v1.2.2（versionCode 5，2026-10-11 22:26 安装）
> 日期：2026-10-11

---

## 0. 一句话结论

**本次「更差」不是 root 问题、也不是 App 冻结问题。**

真正原因是：

> panpush 用**普通 `PackageManager`** 去检测「迅雷 / 阿里云盘 App 是否安装」，
> 但本机 `targetSdk=35`（Android 15）强制**包可见性**，且 Manifest 的 `<queries>` **未声明这两个包名**，
> 导致 panpush **看不见任何第三方 App** → 直接判定「未检测到 APP」→
> **整条 root 直读流程在第 1 步就被短路**，迅雷 / 阿里永远停在「未就绪 / ✗ 无效」。

同时，WebView 登录通道对**夸克 / 123 / 迅雷 / 阿里**这些「令牌只存在 App 内、且多为 httpOnly / App 私有」的站点，**本质上拿不到令牌**，只有百度例外。这才是「登录了也没用」的根本原因。

---

## 1. 现象（用户侧报告）

1. 迅雷云盘、阿里云盘显示 **「未就绪 / ✗ 无效」**：
   - 迅雷：`✗ 无效  未找到三件套凭据（需 root 直读迅雷 APP，或手动粘贴）`
   - 阿里：`✗ 无效  未找到阿里云盘凭据（需 root 直读 APP 明文 DB，或手动粘贴 token）`
   - 提示语：`未检测到迅雷 APP（com.xunlei.downloadprovider）或 su 未授权`
2. 用「一键登录（应用内 WebView）」登录后，夸克 / 123 仍不可用；且登录页
   「**一个是电脑版、一个是手机版，只能下载**」，没有正常登录表单。
3. 「其他的用 root 也没啥用」。
4. 怀疑是「冻结了 App」导致。

---

## 2. 实测证据（全部真机复现）

### 2.1 root 是好的，su 是通的

- App 界面显示：`root：已获得`
- APatch 授权表 `/data/adb/ap/package_config`：
  ```
  com.panpush.app,0,1,10623,0,u:r:magisk:s0
  ```
  即 `exclude=0, allow=1` → **panpush 已获得 root 授权**。

> 结论：**「su 未授权」的说法不成立**，报错文案有误导性。

### 2.2 目标 App 都装着（且曾处于冻结状态）

- `pm list packages` 可见：
  - `com.xunlei.downloadprovider`（迅雷）
  - `com.alicloud.databox`（阿里云盘）
- `pm list packages -d`（已禁用）里**两个都在**；`pm list packages -e`（已启用）里**两个都不在**；
- `dumpsys package` 显示 `enabled=2`（`COMPONENT_ENABLED_STATE_DISABLED`）→ 当时确实被冻结（推测用 Hail）。

> 关键：用户后续**解冻后依然无效** → 说明**冻结不是主因**。

### 2.3 【根因铁证】包可见性把 panpush 屏蔽了

同一时刻、同一台机，用不同身份列包：

| 身份 | `pm list packages | wc -l` | 能否看到迅雷/阿里 |
|---|---|---|
| root | **688** | ✅ 能 |
| panpush（uid 10623） | **1** | ❌ 看不见 |

```bash
# panpush 身份几乎看不见任何包
su 10623 -c 'pm list packages | wc -l'            # → 1
su 10623 -c 'pm list packages | grep -i xunlei'   # → 空
su 10623 -c 'pm list packages | grep -i databox'  # → 空
# root 身份完全正常
pm list packages | grep -iE 'xunlei|databox'      # → 两个都在
```

### 2.4 Manifest 的 `<queries>` 缺目标包

`dumpsys package com.panpush.app` 的 `Queries:` 段显示可查询的包只有
`bin.mt.plus.canary`、输入法、`com.aliyun.tongyi` 等，
**没有 `com.xunlei.downloadprovider`，没有 `com.alicloud.databox`**。

于是按包名查询这两个 App → 抛 `NameNotFoundException` → 界面报「未检测到 APP」。

### 2.5 root 直读本身是可行的（证明问题在「检测」而非「读取」）

root 可直接读取两个目标的私有数据目录（内容正常列出）：

```
/data/data/com.xunlei.downloadprovider   → app_database / app_plugins_v3 / ...
/data/data/com.alicloud.databox          → app_database / app_h5container / app_sslv3 / ...
```

> 也就是说：**如果跳过「App 检测」直接 root 读，是读得到东西的**。问题出在检测环节把流程提前掐断了。

### 2.6 凭据缓存现状（`shared_prefs/panpush.xml`）

```xml
<string name="cred_quark">ctoken=-A4by2liy-rsIpvONhxN-U65</string>
<string name="cred_baidu">PANWEB=1; BAIDUID=...; BAIDUID_BFESS=...; __bid_n=...; fuid=...</string>
<string name="cred_123">token=b49683453c69dc783dbc727f95cf8f264eabefd457579f95e1216dd2c82866f5</string>
<long name="cred_time_quark" .../> <long name="cred_time_baidu" .../> <long name="cred_time_123" .../>
```

**只缓存了 夸克 / 百度 / 123 三家，完全没有 `cred_xunlei` / `cred_aliyun`** → 与这两个站点「未就绪」严格对应。

### 2.7 Cookie 其实一直在（WebView 登录是成功的）

实测 `app_webview/Default/Cookies`（root 直读）共 **27 条**，含：

```
pan.quark.cn   ctoken
.123pan.cn/_c_WBKFRo ...；user.123pan.cn ...；yun.123pan.cn ...
.baidu.com     BAIDUID / BAIDUID_BFESS / __bid_n / fuid
.pan.baidu.com PANWEB；.pcs.baidu.com pcsett；wappass.baidu.com XFI/XFT ...
```

> 即：**WebView 登录确实把 Cookie 存下来了**，登录态没有丢。
> 但这些 Cookie **不等于**解析器需要的令牌 → 见第 4 节。

---

## 3. 根因分析

### 3.1 主因：App 检测走了非 root 的 `PackageManager`

- Android 11（API 30）起引入**包可见性**；`targetSdk=35` 会严格执行。
- 普通 App 只能看到：自己、同签名、`<queries>` 声明的、被 `forceQueryable` 的包。
- panpush 未声明目标网盘包名 → **按包名查 → 查不到 → 判「未安装」**。
- 而它偏偏**把整条 root 直读流程 gate 在这个检测结果上** → 一票否决。

### 3.2 次因：错误文案把两件事混在一起

`未检测到 XX APP **或** su 未授权` —— 实际 su 完全正常，文案误导排查方向（用户第一反应就是「是不是 root / 是不是我冻结了」）。

### 3.3 与「冻结」的关系（澄清）

- 冻结（`enabled=DISABLED`）会让**普通 App 的包查询**更查不到；
- 但即使解冻，只要没解决 `<queries>` / 非 root 检测，**照样查不到**；
- 对 root 直读：冻结状态下**数据目录仍可读**，但「拉起 App 刷新令牌」会失败。

> 所以冻结是「雪上加霜」，不是「因」。

---

## 4. 各站点登录需求 × 正确获取方式

| 站点 | 需要登录？ | 关键令牌 | 令牌位置 / 属性 | 网页(WebView)登录能否拿到 | 正确获取路径 |
|---|---|---|---|---|---|
| 蓝奏云 | ❌ 不需要 | — | — | — | 匿名直接解析 |
| 百度网盘 | ✅ | `BDUSS`（+ `STOKEN`/`BAIDUID`） | **非 httpOnly** | ✅ **能** | **通道 A**：应用内 WebView 登录 → 读回自己 Cookie 库 |
| 夸克网盘 | ✅ | `__puus`（**httpOnly**）+ `ctoken` | 网页 JS 读不到 | ❌ 不能 | **通道 B**：root 直读夸克 App（或手动粘贴） |
| 123 云盘 | ✅ | `token=<JWT>` | 与网页端**另一套鉴权**，Cookie 里没有 | ❌ 不能 | **通道 B**：root 直读 123 App（或手动粘贴） |
| 迅雷云盘 | ✅ | 三件套 `Authorization` / `captcha` / `device_id` | `captcha` **只能由 App 生成** | ❌ 不能 | **通道 B**：root 直读迅雷 App |
| 阿里云盘 | ✅ | `access_token` / `refresh_token` / `drive_id` | 存在于 App 明文 DB | ❌ 不能 | **通道 B**：root 直读阿里 App |

### 4.1 登录方案的正确设计 = 两条通道

- **通道 A（应用内 WebView 登录）** —— 只对「非 httpOnly、网页拿得到」的站点有效。
  - **实际上只有百度**。
  - 做法：`WebView` 登录 → `CookieManager.getCookie()` / 读 `app_webview/Default/Cookies` → 落盘。
- **通道 B（root 直读目标 App）** —— 夸克 / 123 / 迅雷 / 阿里 全部走这条。
  - 做法：`su -c` 读取目标 App 的 `shared_prefs` / `databases` / `files`，抽取令牌。
  - **不需要 WebView 登录**；WebView 登录对它们**天生无用**。

### 4.2 关键判断

> **「几个都需要登录吗？」** —— 除蓝奏外都要；但**需要登录 ≠ 需要 WebView 登录**。
> **「直接检测本地登录状态，还是其他？」** —— 正解正是「**能 root 直读的就直读本地 App 的登录态**」，
> 只有百度才走 WebView。把「WebView 一键登录」当主路径，方向本身就错了。

---

## 5. 登录页「电脑版 / 手机版 / 只能下载」问题

反编译 `classes*.dex` 可见该 App 内置了多套 UA 并允许切换：

```
桌面 UA:  Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/131 ... Safari/537.36
移动 UA:  Mozilla/5.0 (Linux; Android 13; Pixel 6) ... Mobile Safari/537.36
夸克专用: quark-cloud-drive/2.5.20 ... Electron/18.3.5.4 ... Channel/pckk_other_ch
百度 UA:  ... netdisk; ...
方法:     setUserAgentString / toggleUserAgent / applyUserAgent
```

由此产生：
- **按站点切 UA** → 有的开出来是**电脑版**、有的是**手机版**（用户直观感受「一个电脑一个手机」）；
- 很多网盘对「桌面版 / 非官方 UA」的默认响应就是**「请下载 App / 扫码登录」**，**不给登录表单** → 用户感受「**只能下载**」。

> 这不是 UI bug，而是 **WebView 登录方案的固有局限**：网盘登录页普遍对 WebView 壳「防呆」。
> 结论：**不要指望用 WebView 登录解决夸克/123/迅雷/阿里**，即便 UA 调对了也大概率拿不到 httpOnly / App 私有令牌。

---

## 6. 登录 UI 设计与实现（含 WebView 正确写法）

> 本章回答「登录 UI 到底该怎么写」——包含各站点该不该做网页登录、WebView 渲染错乱（夸克只能下载 / 123 文字重叠）的成因与修法，以及可直接使用的实现代码。

### 6.0 核心原则：按站点能力分流，别做一个「万能 WebView 登录页」

| 站点 | 该不该做网页登录 UI | 正确的交互入口 |
|---|---|---|
| 蓝奏云 | ❌ 不需要 | 无需凭据，直接解析 |
| **百度网盘** | ✅ **要**（唯一值得） | 「应用内网页登录」→ 读回自己 Cookie 库（`BDUSS` 非 httpOnly） |
| **夸克网盘** | ❌ 不要 | 「root 直读夸克 App」+「手动粘贴」 |
| **123 云盘** | ❌ 不要 | 「root 直读 123 App」+「手动粘贴」 |
| **迅雷云盘** | ❌ 不要 | 「root 直读迅雷 App」+「手动粘贴」 |
| **阿里云盘** | ❌ 不要 | 「root 直读阿里 App」+「手动粘贴」 |

> 判断依据：令牌是否 `httpOnly` / 是否只存在于官方 App。**只有百度例外**。

用「登录模式」枚举驱动 UI，而不是所有站点共用一个 WebView 按钮：

```kotlin
enum class LoginMode {
    NONE,        // 蓝奏：无需登录
    WEBVIEW,     // 百度：应用内网页登录
    ROOT_READ,   // 夸克/123/迅雷/阿里：root 直读本机 App 数据
}

data class SiteProfile(
    val id: String,
    val name: String,
    val mode: LoginMode,
    val loginUrl: String? = null,   // 仅 WEBVIEW 用
    val ua: String? = null,         // 仅 WEBVIEW 用
    val wideViewport: Boolean = false,
    val rootPkg: String? = null,    // 仅 ROOT_READ 用：目标 App 包名
)
```

### 6.1 为什么夸克 / 123 的 WebView 登录「必然烂」

三个叠加原因，全是机制性的，调参治不好：

1. **UA 决定站点给哪个版本**
   - 移动 UA → 站点判「手机浏览器」→ 夸克/123 直接返回**「请下载 App / 打开 App」引导页**（不给登录表单）→ 即用户看到的「只能下载」。
   - 桌面 UA → 站点给**桌面版**，但桌面版按 ~1280px 宽设计 → 在 ~360–400dp 的手机 WebView 里被压扁 → 文字重叠、显示不全。
2. **WebView 视口缺失导致布局错乱**
   手机 WebView 默认视口很窄，桌面页又没有移动适配 → 布局引擎按窄宽硬排桌面 CSS → **元素重叠**（123 的典型症状）。
3. **就算渲染对了，令牌还是拿不到**
   - 夸克要 `__puus`（**httpOnly**）→ JS / WebView 都读不到；
   - 123 网页端是**另一套鉴权**，解析要的 `token=<JWT>` 不在 Cookie 里；
   - 夸克/123 桌面版登录方式本身就是**用官方 App 扫码** → 绕一圈还是要装 App、要 root。

> **结论**：对夸克/123/迅雷/阿里，网页登录投入产出比 ≈ 0。不做，直接 root 直读。

### 6.2 如果仍要保留 WebView 兜底（正确写法）

以「百度」这类真正可用 WebView 的站点为准；夸克/123 即便保留也只当「看得到但登不了」的兜底。

**关键设置（修「显示不全 / 重叠」）：**

```kotlin
@SuppressLint("SetJavaScriptEnabled")
private fun setupWebView(web: WebView, p: SiteProfile) {
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true          // localStorage（123 的 token 也在这）
        databaseEnabled = true
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 允许第三方 Cookie（登录跳转常跨域）
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // —— 桌面站点：关键两行 ——
        useWideViewPort = true            // 允许页面用自身 viewport 宽度
        loadWithOverviewMode = true       // 按内容宽度缩放以适配屏幕
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false

        // —— 按站点切 UA ——
        p.ua?.let { userAgentString = it }
    }
    web.setInitialScale(0)                // 0 = 自动缩放
}
```

> `useWideViewPort = true` + `loadWithOverviewMode = true` 这两行，是修 **123 文字重叠 / 桌面页显示不全** 的核心。

**UA 常量：**

```kotlin
object UA {
    // 桌面（Windows Chrome）——想让站点出桌面版时用
    const val DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // 手机（Android Chrome）——想让站点出移动版时用
    const val MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // 百度网盘专用（带 netdisk; 标识，站点会认）
    const val BAIDU =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 netdisk;"
}
```

**Cookie 持久化 + 两条红线：**

```kotlin
web.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String) {
        // 关键：每次页面加载完 flush 一次，落盘到 app_webview/Default/Cookies
        CookieManager.getInstance().flush()
    }
}
```

- 🚫 **绝不在「启动 / 校验 / 切换站点」路径调用**：`CookieManager.removeAllCookies(...)`、`removeSessionCookies(...)`、`WebView.clearCache(true)`、`clearHistory()`、`clearFormData()` —— 这些会**亲手清掉登录态**，是「登录存不下」的头号杀手。
- ✅ 只在用户**主动点「退出登录」**时才清。

**最小登录 Activity：**

```kotlin
class WebViewLoginActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var profile: SiteProfile

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        profile = intent.getStringExtra("site")?.let(SiteRegistry::get) ?: finishDefault()

        web = WebView(this)
        setContentView(web)
        setupWebView(web, profile)   // 见上

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
            }
        }
        profile.loginUrl?.let { web.loadUrl(it) }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()   // 退出前再 flush 一次
        super.onDestroy()
    }

    private fun finishDefault(): Nothing {
        finish(); throw IllegalStateException("site missing")
    }
}
```

Manifest 注意：`<application android:usesCleartextTraffic="true" ...>`（若跳转含 http；纯 https 可不要）。

### 6.3 推荐的实际 UI 形态

每站一张卡片（状态 + 动作），动作按钮由 `SiteProfile.mode` 决定：

```
┌──────────────────────────────────────────┐
│ 迅雷云盘                        ● 未就绪   │
│ 需要：Authorization / captcha / device_id │
│ [ root 直读本机数据 ]  [ 手动粘贴 ]        │   ← ROOT_READ 模式
└──────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│ 百度网盘                        ● 未就绪   │
│ 需要：BDUSS（网页可获取）                  │
│ [ 应用内登录 ]  [ root 直读 ]  [ 手动粘贴 ] │   ← WEBVIEW 模式
└──────────────────────────────────────────┘

┌──────────────────────────────────────────┐
│ 蓝奏云                          ● 免登录   │
│ 全匿名解析，无需任何凭据                    │   ← NONE 模式
└──────────────────────────────────────────┘
```

| mode | 显示的动作 |
|---|---|
| `NONE` | （无） |
| `WEBVIEW` | 应用内登录 / root 直读 / 手动粘贴（三者都给，互为兜底） |
| `ROOT_READ` | **只给** root 直读 / 手动粘贴（**不给**网页登录入口） |

- **夸克/123 不要给「网页登录」入口**：用户点了只会看到「只能下载 / 文字重叠 / 扫码」的坑页 → 体验更差、浪费时间。宁可不出这个按钮。
- 顶部放「全部校验」：并发跑各站点校验，状态显示 `已就绪 / 未就绪 / 免登录`，未就绪文案要**可操作**（"点『root 直读』或『手动粘贴』"）。

### 6.4 各站点问题 × 修法速查

| 站点 | 现象 | 成因 | 建议动作 |
|---|---|---|---|
| 夸克 | 手机端只能下载 | 站点对手机 WebView 防呆 | **不做网页登录**，改 root 直读 |
| 夸克 | 电脑端显示不全 | 桌面页 + 窄视口 | 若保留：桌面 UA + 宽视口（但仍需扫 App） |
| 123 | 文字重叠、无法交互 | 桌面页 + 无宽视口 | **不做网页登录**，改 root 直读；若保留：`useWideViewPort + loadWithOverviewMode` |
| 百度 | 正常 | `BDUSS` 非 httpOnly | 保留 WebView 登录，登录后 flush 落盘 |
| 迅雷 | 未就绪 | 需 App 三件套 | 不做网页登录，root 直读迅雷 App |
| 阿里 | 未就绪 | 需 App 明文 DB token | 不做网页登录，root 直读阿里 App |
| 蓝奏 | — | 全匿名 | 无需任何登录 UI |

### 6.5 反面清单（不要做）

1. ❌ 用同一个 WebView 页面承载所有站点的「登录」。
2. ❌ 给夸克/123/迅雷/阿里显示「网页登录」按钮。
3. ❌ 在启动/校验/切站点时 `removeAllCookies` / `clearCache`。
4. ❌ 只用移动 UA 去开桌面页（必然重叠），或只用桌面 UA 去开移动页。
5. ❌ 把整个可用性 gate 在「检测到目标 App」上（见 §7 P1）。
6. ❌ 忘记 `CookieManager.flush()` → 进程被杀前 Cookie 不落盘。

### 6.6 本章小结

> **登录 UI 的正确形态 = 按站点能力分流：**
> **百度走应用内 WebView；夸克/123/迅雷/阿里一律不做网页登录，直接 root 直读本机 App 数据；蓝奏免登录。**
> 网页登录对必须 App 令牌的站点是死路，修渲染也救不回来。

---

## 7. 给 harness 的改进清单（按优先级）

### P0 —— 必须改，直接决定「修不修得好」

1. **检测改用 root，不要用普通 `PackageManager`**：
   ```kotlin
   // ✅ 推荐：root 判存在
   val exists = RootShell.exec("ls -d /data/data/com.xunlei.downloadprovider").ok
   // ✅ 或：
   RootShell.exec("pm list packages | grep -q com.xunlei.downloadprovider")
   // ❌ 避免：
   packageManager.getPackageInfo("com.xunlei.downloadprovider", 0) // 包可见性会失败
   ```
2. **或在 Manifest 补 `<queries>`**（若坚持用 PackageManager）：
   ```xml
   <queries>
     <package android:name="com.xunlei.downloadprovider" />
     <package android:name="com.alicloud.databox" />
     <package android:name="com.quark.browser" />
     <!-- 123 / 其它目标一并列出 -->
   </queries>
   ```
   > 注意：`QUERY_ALL_PACKAGES` 权限上架会被审核卡，慎用。

### P1 —— 必须改，决定「好不好用」

3. **不要把整个流程 gate 在「App 已安装」上**：
   检测失败时，仍应放开「**手动粘贴令牌**」这条兜底路径，让用户至少能用。
4. **弱化「一键 WebView 登录」主导航**：
   - 夸克 / 123 / 迅雷 / 阿里：主推 **root 直读 + 手动粘贴**；
   - 只对百度保留「应用内 WebView 登录」通道。
5. **修正错误文案**：
   - 把「找不到 App」与「su 被拒」**拆成两类提示**；
   - 明确告诉用户「root 已获得，但未能定位目标 App 数据目录」之类，别让用户误以为是权限问题。

### P2 —— 体验优化

6. **冻结场景**：目标 App 被 `disabled` 时，`root 直读数据目录仍可读`；应：
   - 优先**直接读缓存令牌**；
   - 「拉起 App 刷新令牌」失败时给明确提示（或建议解冻）。
7. **UA 策略**：为仅剩的 WebView 通道（百度）固定合适 UA，去掉无意义的桌面/移动盲切。
8. **凭据有效期**：`cred_time_*` 已有，建议增加「过期前提醒 / 一键重新抓取」入口。

---

## 8. 验收标准（改完后如何判断「修好了」）

1. **迅雷**：不解锁 App 的情况下能进入「已就绪」，或至少能从迅雷 App 明文库读出三件套；
2. **阿里**：能从阿里 App 明文 DB 读出 token 组并显示「已就绪」；
3. 在**目标 App 未安装**的机器上，界面提供「手动粘贴」且可用；
4. 在**目标 App 被冻结**的机器上，给出明确提示而非误报「su 未授权」；
5. `root：已获得` 与各站点状态**语义一致**，不再自相矛盾。

---

## 9. 附录：复现命令（可在任意 root 设备上重跑）

```bash
# 1. 看版本 / 状态
dumpsys package com.panpush.app | grep -E 'versionCode|versionName|targetSdk'

# 2. 目标 App 是否安装 / 是否冻结
pm list packages | grep -iE 'xunlei|databox'
pm list packages -d | grep -iE 'xunlei|databox'   # -d = 已禁用(冻结)
dumpsys package com.xunlei.downloadprovider | grep -E 'enabled='

# 3. 【根因】包可见性对照：panpush 身份 vs root
su 10623 -c 'pm list packages | wc -l'            # 预期 ≈1
pm list packages | wc -l                          # 预期 数百

# 4. Manifest 可查询的包
dumpsys package com.panpush.app | sed -n '/Queries:/,/^$/p'

# 5. root 直读目标数据目录
ls /data/data/com.xunlei.downloadprovider
ls /data/data/com.alicloud.databox

# 6. 凭据缓存
cat /data/data/com.panpush.app/shared_prefs/panpush.xml

# 7. 当前 Cookie（root 直读，需复制 DB+journal 避开活库锁）
cat /data/data/com.panpush.app/app_webview/Default/Cookies  # 头 16 字节应为 "SQLite format 3"
# 本项目已有现成工具：webview_ck.py --pkg com.panpush.app --cookies
```

---

## 10. 各站点凭据读取路径（真机实测）

> 本节回答「root 直读到底该读哪些文件」。所有路径均在本机核实：**是否存在、里面有没有解析要的令牌**。
> **完整逐键版见配套文档 `扫描路径文档.md`。**

> ⚠️ 本节下方为初版结论；随后补充了更完整的实测，**修正见本节文末「10.6 补充修正」**。

### 10.1 panpush 实际读取路径 vs 真机现实

panpush 的 dex 里写死了如下路径（本次从 `classes*.dex` 字符串提取）：

| 站点 | App 包名 | panpush 读取路径 | 真机是否存在 | 是否含所需令牌 |
|---|---|---|---|---|
| 迅雷 | `com.xunlei.downloadprovider` | `shared_prefs/auth_pref.xml` | ✅ | ✅ `access_token`(JWT) + `refresh_token` |
| 迅雷 | 同上 | `shared_prefs/xl-acc-captcha-token.xml` | ✅ | ✅ `captchatoken`（`ck0.…`） |
| 迅雷 | 同上 | `shared_prefs/__device_info_new__.xml` | ✅ | ✅ `device_id_new` |
| 迅雷 | 同上 | `shared_prefs/login_user.xml` | ❌ **不存在** | — |
| 阿里 | `com.alicloud.databox` | `databases/0.db` → `tb_user_info` | ✅ | ✅ `drive_id` / `access_token` / `refresh_token` |
| 百度 | `com.baidu.netdisk` | `app_webview_com.baidu.netdisk/Default/Cookies` | ✅ | 百度 **App 自己的** WebView 库 |
| 夸克 | `com.quark.browser` | `app_u46_webview/Default/Cookies` | ✅ 存在 | ⚠️ 只有 `b./m.quark.cn` 跟踪 cookie，**无 `pan.quark.cn` 的 `__puus`** |
| 夸克 | 同上 | `app_uc_webview/Default/Cookies` | ❌ 实际目录名是 `app_u4_webview` | 名字对不上 |

### 10.2 实测细节

**迅雷**（`/data/data/com.xunlei.downloadprovider/shared_prefs/`）——三件套齐全、root 可读：

- `auth_pref.xml`：`credentials_Xp6vsxz_7IYVw2BB` = JSON，含
  `access_token`(RSA-JWT) / `refresh_token` / `user_id` / `expires_at` / `expires_in`
- `xl-acc-captcha-token.xml`：`<hash>captchatoken` = `ck0.…`（多个）+ `<hash>expiresat`
- `__device_info_new__.xml`：`device_id_new` / `unique_id` / `hubble_id`

> ⚠️ 注意 `access_token` 的 `expires_at` 已过期——**读到的是"能读但可能过期"的令牌**，需要配合 `refresh_token` 续期，或让迅雷 App 刷新一次。

**阿里**（`/data/data/com.alicloud.databox/databases/0.db`）——表 `tb_user_info`（1 行），字段：

```
user_id, nick_name, drive_id, access_token, refresh_token,
expires_in, expire_time, token_type, ...
```

token 组齐全，路径正确。

**夸克**（`/data/data/com.quark.browser/`）——实测目录与内容：

- 真实存在的 WebView 库：`app_u4_webview/Default/Cookies`、`app_u46_webview/Default/Cookies`（**没有 `app_uc_webview`**）
- 两个库里关于夸克的 cookie 只有：
  - `b.quark.cn`：`__itrace_wid` / `b-user-id`（跟踪类）
  - `m.quark.cn`：`XSRF-TOKEN` / `_ch` / `_ss` / `phid` / `sm_*`（会话类）
  - **没有 `pan.quark.cn`，也没有 `__puus` / `ctoken`**

> 结论：夸克网盘的登录令牌**不在夸克浏览器的 WebView cookie 库里**，大概率在夸克账号体系的**加密存储**（那堆哈希命名的 `.sp`）中，root 直读难度大 → **夸克优先"手动粘贴"**。

**123 云盘**：本机**未安装 123 App**（只找到网盘网页），因此 root 直读无从谈起 → **只能手动粘贴**。
（若目标设备装了，123 App 包名通常为 `com.p123.*` 之类，需另测。）

### 10.3 结论：panpush 的「读取」到底有没有问题

1. **【不是读取的问题，是检测的问题】**
   迅雷/阿里/百度的路径**基本都对、数据都在**。它读不到，是因为在 `PackageManager` 检测 APP 那一步就 return 了，**根本没执行到 root 读取**。
   → 所以报错是「未检测到 APP」，而不是「文件读不到」。**先修 §7 P0 的检测，读取才轮得到。**
2. **迅雷**：多找了一个不存在的 `login_user.xml`（无害，但说明逻辑按旧版写的）。真正要的三个文件都在。
3. **阿里**：路径正确，`0.db` 表字段完全对得上。
4. **百度**：读的是百度 App 的 WebView 库（`app_webview_com.baidu.netdisk`），路径正确。
5. **夸克（有问题）**：① 目录名 `app_uc_webview` 与实际 `app_u4_webview` 不符；② 即便读到 `app_u46_webview`，里面也**没有**网盘登录令牌 → 这条路走不通，**应放弃夸克 root 直读，改手动粘贴**。

### 10.4 建议：读取应按「文件存在性」判断，而非「包名存在性」

```kotlin
// ❌ 现在：包名检测失败就整条不跑
if (!pm.getPackageInfo(pkg, 0)) return NotReady("未检测到APP")

// ✅ 建议：直接探文件，包检测仅作提示
fun readXunlei(): Cred? {
    val base = "/data/data/com.xunlei.downloadprovider/shared_prefs"
    val auth = root("cat $base/auth_pref.xml")
    val cap  = root("cat $base/xl-acc-captcha-token.xml")
    val dev  = root("cat $base/__device_info_new__.xml")
    if (auth == null && cap == null) return null   // 真的没有，再走手动粘贴
    return Cred(auth, cap, dev)
}
```

要点：
- **能读就读**，别被"App 是否安装"卡死；
- 读出后**校验有效期**（迅雷 token 会过期），过期提示用户"打开 App 刷新一次"或走手动粘贴；
- 失败时**降级到手动粘贴**，而不是直接判"无效"。

### 10.5 各站点「该读哪里」速查（交给 harness）

| 站点 | 读取目标（root 直读） | 拿到什么 |
|---|---|---|
| 迅雷 | `com.xunlei.downloadprovider/shared_prefs/{auth_pref,xl-acc-captcha-token,__device_info_new__}.xml` | access_token + refresh_token + captcha + device_id |
| 阿里 | `com.alicloud.databox/databases/0.db` → `tb_user_info` | drive_id + access_token + refresh_token |
| 百度 | `com.baidu.netdisk/app_webview_com.baidu.netdisk/Default/Cookies` | BDUSS 等（或走应用内 WebView） |
| 夸克 | ⚠️ 浏览器 cookie 库无网盘令牌 → **改手动粘贴** | — |
| 123 | 本机无 App → **手动粘贴**（装了 App 另测） | — |
| 蓝奏 | 无需 | — |

---

### 10.6 补充修正（后续深挖 dex + 扫描实测）

> 下列内容**修正/补充**了 §10.1–10.5 的初版结论。完整逐键版见 `扫描路径文档.md`。

1. **123 云盘 App 其实装了**：包名是 `com.mfcloudcalculate.networkdisk`（**不是**城通、也不是 `com.p123.*`）。
   token 路径：`shared_prefs/login_user.xml` → `userToken`（`Bearer eyJ…`）。
   → 因此 §10.1/§10.3 里「123 本机无 App」的初判**作废**。
2. **`login_user.xml` 属于 123，不是迅雷**：panpush dex 里同时出现 `com.mfcloudcalculate.networkdisk` + `shared_prefs/login_user.xml` + 正则 `name="userToken">\s*(?:Bearer\s+)?([A-Za-z0-9._\-]{20,})`，说明它**把 `login_user.xml` 正确对应到 123**。
   → 所以 §10.3 说的「迅雷多找一个不存在的 login_user.xml（bug）」**应予撤销**——那是字符串池排列造成的误判，实际归属 123。
3. **panpush 的读取路径基本全对**（迅雷 / 阿里 / 123 / 百度都一致），**含 123**。
4. **百度**：除 App WebView 库外，也可走应用内登录；两条路都有效。
5. **夸克**：panpush 还引用了另一个浏览器包 `com.UCMobile`（夸克/UC 同内核）；但**两个浏览器的 cookie 库里都没有夸克网盘令牌**（无 `pan.quark.cn` / `__puus`）→ 夸克仍只能手动粘贴。
6. **唯一确定的读取类问题**：夸克目录名 `app_uc_webview` 与实际 `app_u4_webview` 不符；且夸克/UC 数据源无网盘令牌。

> **最终结论不变**：核心症结仍是「**用非 root 的 `PackageManager` 检测 App → 被包可见性挡死 → 提前 return，读都没读**」。修掉 §7 P0 后，迅雷 / 阿里 / 123 / 百度 应能直接读到凭据。

---

## 11. 事实来源与状态

- 结论基于 **2026-10-11 真机实测**，非经验推断；
- 被测版本 **v1.2.2（versionCode=5）**；
- 已知 **PANPUSH 尚未修复包可见性 / 检测方式问题**；
- 本文档可随版本迭代持续更新。

> 备注：本文档描述「诊断、改进建议与登录 UI 设计」，不含对 App 的修改。后续按第 7 节清单修改后，请用第 8 节验收标准回归。