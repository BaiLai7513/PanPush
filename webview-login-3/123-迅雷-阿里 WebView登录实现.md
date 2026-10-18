# 123 / 迅雷 / 阿里 云盘 —— 应用内 WebView 登录实现

> 目标：在 App 内用自建 WebView 让用户登录 **123 云盘 / 迅雷云盘 / 阿里云盘**，登录后从**自己进程**里抓取所需令牌（cookie / localStorage），持久化备用。
> 适用：panpush 等网盘工具；无 root 场景。设备基线：Android 15 / targetSdk 35。

---

## 0. 结论速览

| 站点 | 网页版 | 手机 UA 情况 | **采用 UA** |
|---|---|---|---|
| **123 云盘** | `www.123pan.com` | 200，但页面偏营销/会推 App | **桌面 UA** |
| **迅雷云盘** | `pan.xunlei.com` | 200，含 `app-tip` / "打开 App" 弹窗 | **桌面 UA** |
| **阿里云盘** | `www.alipan.com` | 200，SPA 渲染 | **桌面 UA** |

> **统一用桌面 UA**：三家手机 UA 都会出现"打开/下载 App"的干扰，桌面 UA 直接给完整网页版登录。
> （对比：夸克移动 UA 会被 **302** 踢去下载页，必须桌面 UA；这三家只是"推送"不是"强制跳转"，但桌面 UA 仍最省心。）

**抓取方式**：
- **Cookie** → `CookieManager.getCookie(url)`（无视 httpOnly）
- **localStorage** → `webView.evaluateJavascript("...")`（**阿里云盘的 token 就在这里**）

---

## 1. 整体架构

一套**通用** `WebViewLoginActivity`，用 `SiteConfig` 参数化三个站点：

```
SiteConfig {
    name          站点名
    homeUrl       起始 URL
    cookieUrls    抓 cookie 要覆盖的域
    lsKeys        关注的 localStorage 键（可能为空 = 直接 dump 全部）
}

123   → homeUrl=https://www.123pan.com/     lsKeys=[token, ...]
迅雷  → homeUrl=https://pan.xunlei.com/     lsKeys=[token, ...]
阿里  → homeUrl=https://www.alipan.com/     lsKeys=[token]   // ★ token 在 localStorage
```

---

## 2. 完整实现（Kotlin）

### 2.1 SiteConfig

```kotlin
enum class Site(
    val display: String,
    val homeUrl: String,
    val cookieUrls: List<String>,
    val lsKeys: List<String>   // 关注/优先查找的 localStorage 键
) {
    PAN123("123云盘", "https://www.123pan.com/",
        listOf("https://www.123pan.com", "https://123pan.com"),
        listOf("token", "accessToken", "refreshToken")),

    XUNLEI("迅雷云盘", "https://pan.xunlei.com/",
        listOf("https://pan.xunlei.com", "https://xunlei.com"),
        listOf("token", "accessToken", "refreshToken")),

    ALIYUN("阿里云盘", "https://www.alipan.com/",
        listOf("https://www.alipan.com", "https://alipan.com", "https://aliyundrive.com"),
        listOf("token"));       // 阿里云盘网页把 token(JSON) 放在 localStorage.token
}
```

### 2.2 通用登录 Activity

```kotlin
package com.panpush.login

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class WebLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SITE = "site"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        // 各站"登录成功"后 localStorage 里应出现的键
        private val LS_TOKEN_HINTS = mapOf(
            "PAN123" to listOf("token", "accessToken"),
            "XUNLEI" to listOf("token", "accessToken"),
            "ALIYUN" to listOf("token")
        )
    }

    private lateinit var web: WebView
    private lateinit var site: Site

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        site = Site.valueOf(intent.getStringExtra(EXTRA_SITE) ?: Site.PAN123.name)

        web = WebView(this)
        setContentView(FrameLayout(this).apply { addView(web) })

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // ★ 必须：localStorage
            databaseEnabled = true
            userAgentString = DESKTOP_UA      // ★ 桌面 UA
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                v: WebView, req: WebResourceRequest
            ): Boolean {
                val u = req.url.toString()
                return when {
                    u.startsWith("http") -> false
                    // 拦掉会跳出 WebView 的深链（迅雷 xunlei://、阿里 alipays:// 等）
                    else -> true
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                // 每次页面完成都探一次，登录成功即抓
                probeAndMaybeSave()
            }
        }

        web.loadUrl(site.homeUrl)
        pollLogin()
    }

    /** 定时探一次（兜底），直到抓到令牌。 */
    private fun pollLogin(timeoutMs: Long = 120_000) {
        val start = System.currentTimeMillis()
        val h = Handler(Looper.getMainLooper())
        val t = object : Runnable {
            override fun run() {
                probeAndMaybeSave()
                if (System.currentTimeMillis() - start < timeoutMs) h.postDelayed(this, 1500)
            }
        }
        h.post(t)
    }

    /** 同时读 cookie + localStorage，命中即保存。 */
    private fun probeAndMaybeSave() {
        // 1) cookies
        val ck = site.cookieUrls.joinToString("; ") {
            CookieManager.getInstance().getCookie(it).orEmpty()
        }
        // 2) localStorage（异步）
        web.evaluateJavascript(DUMP_LOCALSTORAGE_JS) { js ->
            val ls = js ?: "null"          // 形如 "{\"token\":\"...\"}"
            val hit = site.lsKeys.any { ls.contains("\"$it\"") } ||
                      ls.contains("refresh_token") || ls.contains("access_token")
            val cookieHit = ck.isNotEmpty() && (
                when (site) {
                    Site.PAN123 -> ck.contains("token", true) || ck.contains("PANWEB", true)
                    Site.XUNLEI -> ck.contains("loginToken", true) || ck.contains("deviceid", true)
                    Site.ALIYUN -> true     // 阿里主要看 localStorage
                }
            )
            if (hit || cookieHit) save(ck, ls)
        }
    }

    private fun save(cookie: String, localStorage: String) {
        val key = when (site) {
            Site.PAN123 -> "cred_123"
            Site.XUNLEI -> "cred_xunlei"
            Site.ALIYUN -> "cred_aliyun"
        }
        getSharedPreferences("panpush", MODE_PRIVATE).edit()
            .putString(key, cookie)
            .putString("${key}_ls", localStorage)   // 存 localStorage（阿里 token 在这里）
            .putLong("cred_time_$key", System.currentTimeMillis())
            .apply()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }

    companion object Js {
        // 把整个 localStorage dump 成 JSON 字符串
        const val DUMP_LOCALSTORAGE_JS =
            "(function(){var o={};try{for(var i=0;i<localStorage.length;i++)" +
            "{var k=localStorage.key(i);o[k]=localStorage.getItem(k);}}catch(e){}" +
            "return JSON.stringify(o);})()"
    }
}
```

### 2.3 抓取相关方法（核心，单独列出）

```kotlin
// ========== A. 读 Cookie（夸克 __puus / 百度 BDUSS / 迅雷 loginToken …）==========
fun readCookies(urls: List<String>): String =
    urls.joinToString("; ") { CookieManager.getInstance().getCookie(it).orEmpty() }

// ========== B. 读 localStorage（阿里云盘 token / 123 token …）==========
// evaluateJavascript 是异步的，结果在回调里
fun readLocalStorage(web: WebView, onResult: (String) -> Unit) {
    web.evaluateJavascript(
        "(function(){var o={};try{for(var i=0;i<localStorage.length;i++)" +
        "{var k=localStorage.key(i);o[k]=localStorage.getItem(k);}}catch(e){}" +
        "return JSON.stringify(o);})()"
    ) { onResult(it ?: "null") }
}

// ========== C. 读单个 key ==========
fun readLsKey(web: WebView, key: String, onResult: (String?) -> Unit) {
    web.evaluateJavascript("(function(){try{return localStorage.getItem('$key');}catch(e){return null;}})()") {
        onResult(it?.takeIf { s -> s != "null" }?.let { s -> unquote(s) })
    }
}
private fun unquote(s: String): String =
    if (s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s
```

> **`getCookie` 能拿到 httpOnly cookie**（Native 接口，不受 httpOnly 限制）；**localStorage 必须用 `evaluateJavascript`**，因为 JS 之外拿不到。

---

### 2.4 布局适配（★ 防止桌面页错乱）

强制桌面 UA 后，页面按**桌面宽**（通常 ≥1200px）渲染，手机上极易"挤成一团/横向溢出/错位"。按下面 6 条做就不会乱。

#### ① 容器必须撑满（最常见的"乱"源）

WebView 宽高都要 `match_parent`，放在 `FrameLayout`/`ConstraintLayout` 里，**绝不要给固定 dp 尺寸**：

```xml
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <!-- WebView 撑满全屏 -->
    <WebView
        android:id="@+id/web"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- 悬浮"完成登录"按钮：页面再乱也能结束流程 -->
    <Button
        android:id="@+id/btnDone"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp"
        android:text="我已完成登录" />
</FrameLayout>
```

#### ② 两个关键开关：`useWideViewPort` + `loadWithOverviewMode`

```kotlin
web.settings.apply {
    useWideViewPort = true        // 允许页面按自身 viewport 布局
    loadWithOverviewMode = true   // ★ 自动缩小到"整页适配屏宽"，桌面页不再横向溢出
    setInitialScale(0)            // 用默认缩放（交给上面两个开关算）
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false
}
```

> `loadWithOverviewMode = true` 是**防乱的核心**：它把整页缩放到屏宽，桌面布局完整保留（只是变小），而不是被挤断。

#### ③ 兜底：注入 viewport，把逻辑宽度钉成桌面宽

若某站仍按手机窄屏渲染或溢出，在页面加载后注入 `width=1280`，逼它用桌面布局：

```kotlin
/** 强制桌面逻辑宽度，防止站点按窄屏渲染后错乱。每次页面加载完成调用一次。 */
private fun forceDesktopViewport() {
    web.evaluateJavascript(
        "(function(){" +
        "  var W=1280;" +
        "  var m=document.querySelector('meta[name=\"viewport\"]');" +
        "  if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
        "         document.getElementsByTagName('head')[0].appendChild(m);}" +
        "  m.setAttribute('content','width='+W+', user-scalable=yes');" +
        "})()", null)
}
```

配合 `useWideViewPort=true`，页面就以 1280 CSS px 排版、再整体缩到屏宽 → **桌面布局 + 不乱**。

#### ④ 旋转 / 键盘 不重建、不遮挡

Manifest：

```xml
<activity
    android:name=".login.WebLoginActivity"
    android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize"
    android:windowSoftInputMode="adjustResize" />
```

- `configChanges`：旋转时**不重建** Activity（重建会清空 WebView 登录进度）；
- `adjustResize`：输入账号/验证码时键盘不遮住输入框。

#### ⑤ 完整"适配版" WebView 初始化（可直接替换 §2.2 的对应段）

```kotlin
@SuppressLint("SetJavaScriptEnabled")
private fun setupWeb(container: FrameLayout) {
    web = WebView(this)
    container.addView(web, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT))

    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        userAgentString = DESKTOP_UA
        // ★ 布局适配三件套
        useWideViewPort = true
        loadWithOverviewMode = true
        setInitialScale(0)
        // 允许用户手动缩放看清小字
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(false)     // 登录弹窗走当前 WebView，避免白屏
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(web, true)
    }

    // 处理 window.open / alert（有些站点登录走弹窗）
    web.webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
        ): Boolean = false   // 配合 setSupportMultipleWindows(false)，弹窗并入当前页
    }

    web.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            return !u.startsWith("http")   // 拦掉 App 深链，http(s) 留在本 WebView
        }
        override fun onPageFinished(view: WebView, url: String) {
            forceDesktopViewport()         // ★ 每次加载完修一次 viewport
            probeAndMaybeSave()
        }
    }
}
```

#### ⑥ 如果不想注入、想更省事

只靠 ② 的 `useWideViewPort + loadWithOverviewMode` 通常就够。**只有当某站明显溢出/错位时**，才追加 ③ 的 `forceDesktopViewport()`。两者可叠加，不冲突。

---

## 3. 各站点要点

### 3.1 123 云盘 — `www.123pan.com`
- 起始：`https://www.123pan.com/`，点"登录"（支持手机号/扫码）。
- 抓取：**cookie + localStorage 都读**；优先看 localStorage 里 `token`。
- 保存键：`cred_123`。

### 3.2 迅雷云盘 — `pan.xunlei.com`
- 起始：`https://pan.xunlei.com/`，登录弹层 `login-web-pop`。
- 抓取：**cookie 为主**（登录后 cookie 里含 `loginToken` / `deviceid`），localStorage 兜底。
- 注意：迅雷登录可能带**图形验证码**，WebView 里能正常显示即可。
- 保存键：`cred_xunlei`。

### 3.3 阿里云盘 — `www.alipan.com`
- 起始：`https://www.alipan.com/`，扫码（阿里/淘宝账号）登录。
- 抓取：**令牌在 `localStorage.token`**（JSON：`access_token` / `refresh_token` / `expires_in`）→ 必须走 `evaluateJavascript`。
- 保存键：`cred_aliyun`（token JSON 存到 `cred_aliyun_ls`）。

---

## 4. 常见坑

| 现象 | 原因 | 解法 |
|---|---|---|
| 页面推"下载 App" | 手机 UA | **统一桌面 UA** |
| **桌面页挤成一团 / 横向溢出 / 错位** | 没开 overview、容器没撑满 | `useWideViewPort + loadWithOverviewMode`；WebView `match_parent`；必要时 `forceDesktopViewport()`（§2.4） |
| **旋转后登录进度没了** | Activity 重建 | Manifest 加 `configChanges`（§2.4④） |
| **键盘挡住输入框** | 软键盘模式 | `windowSoftInputMode="adjustResize"`（§2.4④） |
| 登录弹窗白屏 | 站点用 `window.open` | `setSupportMultipleWindows(false)` + `WebChromeClient`（§2.4⑤） |
| 读不到 token | 令牌在 localStorage，却只读了 cookie | 用 `evaluateJavascript` 读 localStorage |
| `evaluateJavascript` 返回 null | 页面还没加载完 | 在 `onPageFinished` 后再调 |
| 阿里 token 是 JSON 串被转义 | JS 返回带引号 | 用 `unquote()` 去引号 |
| 点登录跳去 App | 深链 `xunlei://` 等 | `shouldOverrideUrlLoading` 拦非 http |
| 判断"是否登录"不准 | 只看 cookie 空 | 结合 localStorage 键一起判断 |

---

## 5. 关于 root 直读 vs WebView（建议）

| 站点 | 首选 | 说明 |
|---|---|---|
| **迅雷 / 阿里 / 123** | ✅ **root 直读** | 路径已实测可用（迅雷 `auth_pref.xml`、阿里 `0.db`、123 `login_user.xml`） |
| 同上（无 root） | ✅ WebView 登录 | 本文方案，作为兜底 |
| 百度 / 夸克 | ✅ WebView 登录 | App 令牌拿不到，只能走网页 |

> **WebView 登录拿到的是网页端令牌**，务必确认调用的是对应的**网页 API**；若调 App 专有 API，令牌可能不通用。

---

## 6. 验收 checklist

- [ ] 三个站点点"登录" → WebView 显示**桌面版登录页**（无"下载 App"干扰）
- [ ] 登录后：迅雷/123 能读到 cookie；阿里能读到 `localStorage.token`（含 `refresh_token`）
- [ ] `panpush.xml` 出现 `cred_123` / `cred_xunlei` / `cred_aliyun`
- [ ] 用抓到令牌调对应 API 能列出文件

---

## 7. 事实来源

- 三家网页版可达性、桌面/手机 UA 页面差异：**2026-10-12 curl 实测**；
- 阿里云盘 token 在 localStorage：通用已知行为，**需真机联调最终确认具体键名**；
- 抓取方法（`CookieManager.getCookie` 无视 httpOnly、`evaluateJavascript` 读 localStorage）：Android WebView 标准行为。