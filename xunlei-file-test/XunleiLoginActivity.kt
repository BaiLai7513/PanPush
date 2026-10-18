package com.panpush.login

/*
 * 迅雷云盘「应用内 WebView 登录」→ 提取 access_token → 调 api-pan 列文件
 * ------------------------------------------------------------------
 * 依赖：androidx.appcompat；无需 OkHttp（用 HttpURLConnection）。
 * Manifest:
 *   <activity android:name=".login.XunleiLoginActivity"
 *       android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize"
 *       android:windowSoftInputMode="adjustResize" />
 * 需要网络权限：
 *   <uses-permission android:name="android.permission.INTERNET" />
 *
 * 用法：startActivityForResult(new Intent(this, XunleiLoginActivity.class), REQ_XUNLEI);
 * 成功后 SharedPreferences("panpush") 里会写入：
 *   cred_xunlei       = <access_token>
 *   cred_time_cred_xunlei = <毫秒时间戳>
 */

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class XunleiLoginActivity : AppCompatActivity() {

    companion object {
        // ★ 桌面 UA：迅雷按端分流，桌面 UA 给完整网页版
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private const val HOME = "https://pan.xunlei.com/"

        // 读 cookie 时覆盖的域
        private val COOKIE_URLS = listOf(
            "https://pan.xunlei.com",
            "https://xunlei.com",
            "https://i.xunlei.com",
            "https://xluser-ssl.xunlei.com"
        )

        private const val API_FILES =
            "https://api-pan.xunlei.com/drive/v1/files?limit=50"

        // 把整个 localStorage dump 成 JSON
        private const val DUMP_LS_JS =
            "(function(){var o={};try{for(var i=0;i<localStorage.length;i++){" +
            "var k=localStorage.key(i);o[k]=localStorage.getItem(k);}}catch(e){}" +
            "return JSON.stringify(o);})()"
    }

    private lateinit var web: WebView
    private lateinit var btnDone: Button
    private var saved = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------- 容器：WebView 撑满 + 悬浮"完成登录"按钮 ----------
        val root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))

        btnDone = Button(this).apply { text = "我已完成登录" }
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.END
        lp.setMargins(0, 0, 32, 32)
        root.addView(btnDone, lp)
        setContentView(root)

        // ---------- WebSettings（含布局适配） ----------
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true            // localStorage
            databaseEnabled = true
            userAgentString = DESKTOP_UA        // ★ 桌面 UA
            useWideViewPort = true              // ★ 桌面布局
            loadWithOverviewMode = true         // ★ 整页缩到屏宽，防错乱
            setInitialScale(0)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)    // 弹窗并入当前 WebView
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // ---------- WebChromeClient（弹窗/alert） ----------
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView, isDialog: Boolean,
                isUserGesture: Boolean, resultMsg: android.os.Message?
            ): Boolean = false
        }

        // ---------- WebViewClient ----------
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                v: WebView, req: WebResourceRequest
            ): Boolean = !req.url.toString().startsWith("http") // 拦 App 深链

            override fun onPageFinished(view: WebView, url: String) {
                forceDesktopViewport()
                probe(saveAlways = false)
            }
        }

        btnDone.setOnClickListener { probe(saveAlways = true) }

        web.loadUrl(HOME)
        startPolling()
    }

    // ================= 抓取 =================

    /** 轮询兜底：每 2s 探一次，最多 3 分钟。 */
    private fun startPolling() {
        val h = Handler(Looper.getMainLooper())
        val start = System.currentTimeMillis()
        lateinit var r: Runnable
        r = Runnable {
            if (!saved) probe(saveAlways = false)
            if (!saved && System.currentTimeMillis() - start < 180_000) {
                h.postDelayed(r, 2000)
            }
        }
        h.post(r)
    }

    /** 同时读 cookie + localStorage，抽出 access_token。 */
    private fun probe(saveAlways: Boolean) {
        val cookie = COOKIE_URLS.joinToString("; ") {
            CookieManager.getInstance().getCookie(it).orEmpty()
        }
        web.evaluateJavascript(DUMP_LS_JS) { ls ->
            val blob = cookie + " " + (ls ?: "")
            val token = parseAccessToken(blob)
            if (token != null) {
                if (!saved) saveToken(token)
            } else if (saveAlways) {
                Toast.makeText(this, "未找到 access_token，请确认已登录完成", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 从任意串里抠 access_token（支持 URL 编码 JSON / 裸 JWT）。 */
    private fun parseAccessToken(blob: String): String? {
        val decoded = try {
            URLDecoder.decode(blob, "UTF-8")
        } catch (e: Exception) {
            blob
        }
        Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(decoded)
            ?.let { return it.groupValues[1] }
        Regex("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").find(decoded)
            ?.let { return it.groupValues[1] }
        // 兜底：直接找 JWT
        Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+").find(decoded)
            ?.let { return it.value }
        return null
    }

    private fun saveToken(token: String) {
        saved = true
        getSharedPreferences("panpush", MODE_PRIVATE).edit()
            .putString("cred_xunlei", token)
            .putLong("cred_time_cred_xunlei", System.currentTimeMillis())
            .apply()

        // 尽力验证：调一次列文件（失败也已保存令牌）
        XunleiApi.listFiles(token) { ok, msg ->
            runOnUiThread {
                val tip = if (ok) "迅雷登录成功，已能列文件" else "令牌已保存（列表校验：$msg）"
                Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    /** 强制桌面逻辑宽度，防站点按窄屏渲染后错乱。 */
    private fun forceDesktopViewport() {
        web.evaluateJavascript(
            "(function(){var W=1280;" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "document.getElementsByTagName('head')[0].appendChild(m);}" +
            "m.setAttribute('content','width='+W+', user-scalable=yes');})()", null)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

/**
 * 迅雷云盘最小 API 客户端（仅列文件，用于校验令牌）。
 * 用 HttpURLConnection，免 OkHttp 依赖。
 */
object XunleiApi {

    fun listFiles(token: String, cb: (Boolean, String) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://api-pan.xunlei.com/drive/v1/files?limit=50")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                cb(code in 200..299, "HTTP $code ${body.take(200)}")
            } catch (e: Exception) {
                cb(false, e.message ?: "error")
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}