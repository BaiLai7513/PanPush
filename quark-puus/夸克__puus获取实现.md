# 夸克网盘 `__puus` 获取实现（应用内 WebView 登录）

> 目标：在 Android App 内，通过自建 WebView 让用户登录夸克网页版，从**自己进程的 CookieManager** 里取出 `__puus`（网盘登录态），持久化备用。
> 适用：panpush 等需要夸克网盘凭据的 App。设备基线：Android 15 / targetSdk 35。

---

## 0. 一句话原理

夸克网页登录成功后，服务器通过 `Set-Cookie` 下发 `__puus` → 落到**本 App 自己的 WebView cookie 库**（`/data/data/<你的包名>/app_webview/Default/Cookies`）→ 用 `CookieManager.getCookie()` 读回即可。

**不需要 root。** `__puus` 是 httpOnly，但 `CookieManager.getCookie()` 是 Native 接口，**不受 httpOnly 限制**，能读到。

---

## 1. 关键前提：必须用桌面 UA

夸克按 UA 分流，实测（curl，同一 URL `https://pan.quark.cn/`）：

| UA | 响应 | 结果 |
|---|---|---|
| **桌面 UA**（Windows Chrome） | `HTTP/2 200`，页面含 `Login` / `LoginWay` / `qrcode-logo` / `扫码` / `登录` | ✅ 完整网页版 + 登录入口 |
| **移动 UA**（Android Chrome） | `HTTP/2 302` → `h5.sm.cn/blm/mobile-entry`（下载落地页） | ❌ 被踢去"下载 App" |

→ 移动 UA 根本到不了登录页，**必须设桌面 UA**。

> 另注：桌面 UA 访问首页时服务器会直接下发 `set-cookie: ctoken=...`（匿名令牌，**≠ 登录态**）。真正的登录态是 `__puus`。

---

## 2. 整体流程

```
点「登录夸克」
   │
   ▼
打开 QuarkLoginActivity
   · WebView 设桌面 UA
   · 加载 https://pan.quark.cn/
   │
   ▼
用户扫码 / 账号密码登录
   │
   ▼
登录成功（页面出现文件列表；/list 不再 302 回 /）
   │
   ▼
轮询 CookieManager.getCookie(...) 直到含 __puus
   │
   ▼
存入 SharedPreferences（cred_quark）→ 返回 RESULT_OK
```

---

## 3. 完整实现（Kotlin）

### 3.1 Activity 全代码

```kotlin
package com.panpush.login

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class QuarkLoginActivity : AppCompatActivity() {

    companion object {
        // ★ 桌面 UA：夸克按 UA 分流，移动 UA 会被 302 到下载页
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val HOME = "https://pan.quark.cn/"

        // 读取 cookie 时覆盖的域名（__puus 可能设在 domain=.quark.cn）
        private val COOKIE_URLS = listOf(
            "https://pan.quark.cn",
            "https://quark.cn",
            "https://passport.quark.cn"
        )
    }

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        setContentView(FrameLayout(this).apply { addView(web) })

        // ---------- WebSettings ----------
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage，登录态依赖
            databaseEnabled = true
            userAgentString = DESKTOP_UA      // ★ 关键：桌面 UA
            useWideViewPort = true            // ★ 桌面布局
            loadWithOverviewMode = true       // ★ 桌面布局
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // ---------- Cookie ----------
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // ---------- WebViewClient：拦 App 深链，留在 WebView ----------
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, req: WebResourceRequest
            ): Boolean {
                val u = req.url.toString()
                return when {
                    u.startsWith("http") -> false                 // 正常网页留在 WebView
                    u.startsWith("quark://") ||
                    u.startsWith("alipays://") ||
                    u.startsWith("intent://") -> true             // 一律拦掉
                    else -> true
                }
            }
        }

        web.loadUrl(HOME)

        // 开始轮询抓 __puus
        pollForPuus()
    }

    /** 登录后轮询 cookie，直到出现 __puus。 */
    private fun pollForPuus(timeoutMs: Long = 90_000) {
        val start = System.currentTimeMillis()
        val h = Handler(Looper.getMainLooper())

        val task = object : Runnable {
            override fun run() {
                val ck = COOKIE_URLS.joinToString("; ") {
                    CookieManager.getInstance().getCookie(it).orEmpty()
                }
                if (ck.contains("__puus")) {
                    saveCred(ck)
                } else if (System.currentTimeMillis() - start < timeoutMs) {
                    h.postDelayed(this, 1000)
                }
                // 超时不强制关闭，用户可继续操作
            }
        }
        h.post(task)
    }

    private fun saveCred(cookie: String) {
        getSharedPreferences("panpush", MODE_PRIVATE).edit()
            .putString("cred_quark", cookie)
            .putLong("cred_time_quark", System.currentTimeMillis())
            .apply()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
```

### 3.2 进阶：按域名精确取 `__puus` 的值

如果只想取 `__puus` 单个值，自己解析 cookie 串：

```kotlin
fun extractPuus(cookie: String): String? =
    cookie.split(";").map { it.trim() }
        .firstOrNull { it.startsWith("__puus=") }
        ?.substringAfter("=")
```

### 3.3 抽取时机：也可以监听页面跳转

除了定时轮询，还可在 `onPageFinished` 里判断"已登录"后立刻抓一次：

```kotlin
override fun onPageFinished(view: WebView, url: String) {
    val ck = COOKIE_URLS.joinToString("; ") {
        CookieManager.getInstance().getCookie(it).orEmpty()
    }
    if (ck.contains("__puus")) saveCred(ck)
}
```

---

## 4. 登录入口 / 判定

| 用途 | 值 |
|---|---|
| 入口 URL | `https://pan.quark.cn/` |
| 登录方式 | 首页 → "登录" → **扫码** 或账号密码 |
| 登录成功标志 | `/list` 不再 302 回 `/`；`getCookie` 含 `__puus` |

---

## 5. 常见坑

| 现象 | 原因 | 解法 |
|---|---|---|
| 只有"下载 App" | 移动 UA 被 302 到 `h5.sm.cn` | 强制桌面 UA |
| 桌面版排版错乱 | 没开 wide viewport | `useWideViewPort` + `loadWithOverviewMode` |
| 点登录跳去夸克 App | `quark://` 深链 | `shouldOverrideUrlLoading` 拦非 http |
| `getCookie` 拿不到 `__puus` | 未登录 / 跳转未完成 | 轮询直到出现 |
| JS 读不到 `__puus` | httpOnly | 用 `CookieManager.getCookie()` |
| 把 `ctoken` 当登录态 | `ctoken` 是匿名令牌，不登录也有 | 认准 `__puus` |

---

## 6. 验收 checklist

- [ ] 点"登录夸克" → WebView 显示**桌面版页面**（不是下载页）
- [ ] 用户登录后 `getCookie("https://pan.quark.cn")` 含 `__puus`
- [ ] `panpush.xml` 的 `cred_quark` 被更新为含 `__puus` 的串
- [ ] 用该 cookie 调夸克网盘 API 能列出文件

---

## 7. 备注

- 本文只讲夸克；UC（`com.UCMobile`）同源但网盘已收拢到夸克，无需单独支持。
- `__puus` 有效期有限，需在失效后引导重新登录。
- 夸克网页登录入口（200 / 含扫码）与移动 UA 302 对照为 **2026-10-12 curl 实测**；"`__puus` 登录后下发" 需真机联调最终确认。