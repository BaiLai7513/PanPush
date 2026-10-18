package com.panpush.app.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

/**
 * WebViewLoginActivity —— 应用内 WebView 登录（已修 7 个持久化坑）。
 *
 * 为什么必须"应用内 WebView"：
 *   外部浏览器 / CustomTabs 的 Cookie 存在【浏览器沙箱】，本 App 永远读不到，
 *   所以"浏览器登录存不状态"是设计错误，不是 bug。必须用本 Activity。
 *
 * 使用：
 *   Intent(this, WebViewLoginActivity::class.java).apply {
 *       putExtra(EXTRA_LOGIN_URL, "https://passport.quark.cn/...")     // 登录页
 *       putExtra(EXTRA_CHECK_HOST, "pan.quark.cn")                     // 判定登录成功的域名
 *       putExtra(EXTRA_CHECK_COOKIE, "ctoken")                         // 该域名下出现此 cookie 即视为已登录
 *   }
 *   startActivityForResult(intent, REQ_LOGIN) // 或 ActivityResultLauncher
 *   回调里 result.data?.getStringExtra(EXTRA_RESULT_COOKIE) 即可拿到登录 Cookie
 *
 * AndroidManifest（关键项）：
 *   <activity android:name=".webview.WebViewLoginActivity"
 *             android:configChanges="orientation|screenSize|keyboardHidden"
 *             android:exported="false"/>
 *   且 Application 不能设置 android:usesCleartextTraffic="true" 之外的放行；登录务必走 HTTPS。
 */
class WebViewLoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOGIN_URL = "login_url"
        const val EXTRA_CHECK_HOST = "check_host"
        const val EXTRA_CHECK_COOKIE = "check_cookie"
        const val EXTRA_RESULT_COOKIE = "result_cookie"

        const val REQ_LOGIN = 0x1001

        /** 便捷构造登录 Intent。 */
        fun buildIntent(
            ctx: android.content.Context,
            loginUrl: String,
            checkHost: String,
            checkCookie: String
        ): Intent = Intent(ctx, WebViewLoginActivity::class.java).apply {
            putExtra(EXTRA_LOGIN_URL, loginUrl)
            putExtra(EXTRA_CHECK_HOST, checkHost)
            putExtra(EXTRA_CHECK_COOKIE, checkCookie)
        }
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL) ?: run { finish(); return }
        val checkHost = intent.getStringExtra(EXTRA_CHECK_HOST) ?: ""
        val checkCookie = intent.getStringExtra(EXTRA_CHECK_COOKIE) ?: ""

        webView = WebView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        })

        configureWebView()

        webView.webViewClient = object : WebViewClient() {
            // 不拦截任何跳转：登录常有多级跨域跳转（如百度 wappass → pan.baidu.com）。
            override fun shouldOverrideUrlLoading(v: WebView?, url: String?): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // 【坑1】每次页面加载完成都 flush，确保 Cookie 落盘。
                CookieManager.getInstance().flush()

                if (checkHost.isNotEmpty() && checkCookie.isNotEmpty()) {
                    val c = CookieManager.getInstance().getCookie("https://$checkHost")
                    if (c != null && c.contains(checkCookie)) {
                        deliverResultAndFinish(c)
                    }
                }
            }
        }

        webView.loadUrl(loginUrl)
    }

    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        // 【坑4】localStorage 必须开（123pan 等的 token 存在 localStorage）。
        s.domStorageEnabled = true
        s.databaseEnabled = true
        // 允许 Cookie 持久化；不要设 LOAD_NO_CACHE，别用 incognito。
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.userAgentString = s.userAgentString // 保持默认 UA，别伪装
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)

        // 【坑2】允许第三方 Cookie —— 跨域 SSO 必需（百度 wappass→pan.baidu.com、夸克 passport）。
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // 注意：本 App 是持久 WebView profile，这里【不要】调用
        // CookieManager.removeAllCookies / clearCache / clearHistory，
        // 否则等于每次登录后自己把登录态清掉（坑3/坑7）。
    }

    private fun deliverResultAndFinish(cookie: String) {
        CookieManager.getInstance().flush()
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_COOKIE, cookie))
        finish()
    }

    override fun onPause() {
        super.onPause()
        // 【坑1】退到后台即 flush，防止进程被杀导致内存 Cookie 丢失。
        CookieManager.getInstance().flush()
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        if (this::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}