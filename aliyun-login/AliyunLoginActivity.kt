package com.panpush.login

/*
 * 阿里云盘「应用内 WebView 登录」→ 提取 refresh_token → 刷新 access_token → 列文件
 * ------------------------------------------------------------------
 * 依赖：androidx.appcompat；无需 OkHttp（用 HttpURLConnection）。
 * Manifest:
 *   <activity android:name=".login.AliyunLoginActivity"
 *       android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize"
 *       android:windowSoftInputMode="adjustResize" />
 * 需要网络权限：
 *   <uses-permission android:name="android.permission.INTERNET" />
 *
 * ★ 反爬适配（手机 UA 登不了，桌面 UA 又易触发人机校验）：
 *   1) 桌面 UA + 主框架 Sec-CH-UA-* 头伪装（DESKTOP_HEADERS）；
 *   2) JS 指纹伪装（FINGERPRINT_JS：platform/vendor/触摸/plugins/userAgentData/WebGL）；
 *   3) 布局适配：useWideViewPort + loadWithOverviewMode + 注入 viewport(W=900)，
 *      允许双指放大看清滑块验证码。
 *
 * 用法：startActivityForResult(new Intent(this, AliyunLoginActivity.class), REQ_ALIYUN);
 * 成功后 SharedPreferences("panpush") 里会写入：
 *   cred_aliyun       = <refresh_token>   (32hex，长期有效，用于后续刷新)
 *   cred_aliyun_at    = <access_token>
 *   cred_aliyun_drive = <default_drive_id>
 *   cred_time_cred_aliyun = <毫秒时间戳>
 */

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class AliyunLoginActivity : AppCompatActivity() {

    companion object {
        // ★ 桌面 UA：阿里网页版按端分流，桌面 UA 给完整站点
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // 桌面站点（登录主体）
        private const val HOME = "https://www.alipan.com/"

        // 网页端 client_id（auth.aliyundrive.com）
        private const val CLIENT_ID = "25dzX3vbYqktVxyX"

        // 读 cookie 时覆盖的域（部分辅助 cookie 在 aliyundrive 上）
        private val COOKIE_URLS = listOf(
            "https://www.alipan.com",
            "https://alipan.com",
            "https://www.aliyundrive.com",
            "https://auth.aliyundrive.com"
        )

        // 把整个 localStorage dump 成 JSON
        private const val DUMP_LS_JS =
            "(function(){var o={};try{for(var i=0;i<localStorage.length;i++){" +
            "var k=localStorage.key(i);o[k]=localStorage.getItem(k);}}catch(e){}" +
            "return JSON.stringify(o);})()"

        // ★ 主框架请求头伪装：UA 说 Windows，Client Hints 也必须一致，否则露馅触发人机校验
        private val DESKTOP_HEADERS = mapOf(
            "User-Agent"                  to DESKTOP_UA,
            "Sec-CH-UA"                   to "\"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\", \"Not?A_Brand\";v=\"24\"",
            "Sec-CH-UA-Mobile"            to "?0",
            "Sec-CH-UA-Platform"          to "\"Windows\"",
            "Sec-CH-UA-Platform-Version"  to "\"10.0.0\"",
            "Sec-CH-UA-Arch"              to "\"x86\"",
            "Sec-CH-UA-Bitness"           to "\"64\"",
            "Sec-CH-UA-Full-Version-List" to "\"Chromium\";v=\"120.0.0.0\", \"Google Chrome\";v=\"120.0.0.0\"",
            "Accept-Language"             to "zh-CN,zh;q=0.9,en;q=0.8"
        )

        /**
         * ★ JS 指纹伪装：把"手机内核处处是 Android"的证据改成桌面 Chrome 的样子。
         * 只改 navigator / WebGL 读取值，不拦截真实触摸（不影响滑块拖拽）。
         */
        private const val FINGERPRINT_JS = """
(function(){
  var D = function(o,k,v){ try{ Object.defineProperty(o,k,{get:function(){return v;},configurable:true}); }catch(e){} };
  // 1) navigator 基础项
  D(navigator,'platform','Win32');
  D(navigator,'vendor','Google Inc.');
  D(navigator,'maxTouchPoints',0);
  D(navigator,'webdriver',false);
  D(navigator,'hardwareConcurrency',8);
  D(navigator,'deviceMemory',8);
  D(navigator,'language','zh-CN');
  D(navigator,'languages',['zh-CN','zh','en']);
  D(navigator,'pdfViewerEnabled',true);
  // 2) plugins 造出桌面非空
  var names=['PDF Viewer','Chrome PDF Viewer','Chromium PDF Viewer','Microsoft Edge PDF Viewer','WebKit built-in PDF'];
  var fk=names.map(function(n){return {name:n,filename:'internal-pdf-viewer',description:'Portable Document Format'};});
  fk.item=function(i){return this[i];};
  fk.namedItem=function(n){for(var i=0;i<this.length;i++){if(this[i].name===n)return this[i];}return null;};
  D(navigator,'plugins',fk);
  // 3) userAgentData（Client Hints 的 JS 侧）
  var hev={architecture:'x86',bitness:'64',model:'',platform:'Windows',platformVersion:'10.0.0',
           uaFullVersion:'120.0.0.0',
           fullVersionList:[{brand:'Chromium',version:'120.0.0.0'},{brand:'Google Chrome',version:'120.0.0.0'}],
           wow64:false};
  D(navigator,'userAgentData',{
    brands:[{brand:'Chromium',version:'120'},{brand:'Google Chrome',version:'120'},{brand:'Not?A_Brand',version:'24'}],
    mobile:false, platform:'Windows',
    getHighEntropyValues:function(){return Promise.resolve(hev);},
    toJSON:function(){return {brands:this.brands,mobile:this.mobile,platform:this.platform};}
  });
  // 4) window.chrome
  if(!window.chrome) window.chrome={};
  window.chrome.runtime=window.chrome.runtime||{};
  window.chrome.app=window.chrome.app||{isInstalled:false};
  // 5) 抹掉触摸特征
  try{ Object.defineProperty(window,'ontouchstart',{get:function(){return undefined;},configurable:true}); }catch(e){}
  try{ delete window.ontouchstart; }catch(e){}
  // 6) WebGL 指纹（Android GPU → Intel/ANGLE）
  function patchGL(proto){
    if(!proto) return;
    var orig=proto.getParameter;
    proto.getParameter=function(p){
      if(p===37445) return 'Google Inc. (Intel)';
      if(p===37446) return 'ANGLE (Intel, Intel(R) UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)';
      return orig.call(this,p);
    };
  }
  patchGL(window.WebGLRenderingContext && WebGLRenderingContext.prototype);
  patchGL(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);
})();
"""
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
            domStorageEnabled = true            // ★ localStorage 依赖它
            databaseEnabled = true
            userAgentString = DESKTOP_UA        // ★ 桌面 UA
            useWideViewPort = true              // ★ 桌面布局
            loadWithOverviewMode = true         // ★ 整页缩到屏宽，防错乱
            setInitialScale(0)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)    // 扫码弹窗并入当前 WebView
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        // ---------- WebChromeClient ----------
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

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // ★ 尽早注入指纹伪装（Android WebView 无 document_start 钩子）
                view.evaluateJavascript(FINGERPRINT_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript(FINGERPRINT_JS, null) // 补一次，防被页面脚本覆盖
                forceDesktopViewport()
                probe(saveAlways = false)
            }
        }

        btnDone.setOnClickListener { probe(saveAlways = true) }

        // ★ 带头发起加载（主框架覆盖 Client Hints，与桌面 UA 保持一致）
        web.loadUrl(HOME, DESKTOP_HEADERS)
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

    /** 读 localStorage，抽出 refresh_token。 */
    private fun probe(saveAlways: Boolean) {
        web.evaluateJavascript(DUMP_LS_JS) { ls ->
            val raw = unquote(ls?.takeIf { it != "null" } ?: "")
            val parsed = parseAliyunToken(raw)
            if (parsed != null) {
                if (!saved) saveAndVerify(parsed)
            } else if (saveAlways) {
                Toast.makeText(this, "未找到 token，请确认已登录完成", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 从 dump 出的 localStorage JSON 里解析：
     *   refresh_token / access_token / default_drive_id
     * 优先读键 `token`（阿里把整包 JSON 存这里），失败则全库正则搜。
     */
    private fun parseAliyunToken(rawLs: String): JSONObject? {
        if (rawLs.isBlank()) return null
        val decoded = try { URLDecoder.decode(rawLs, "UTF-8") } catch (e: Exception) { rawLs }

        // 1) 优先解析 localStorage 顶层 "token" 字段
        try {
            val root = JSONObject(decoded)
            val tv = root.optString("token", "")
            if (tv.isNotBlank()) {
                val t = JSONObject(tv)
                if (t.optString("refresh_token").isNotBlank()) return t
            }
        } catch (e: Exception) { /* ignore */ }

        // 2) 兜底：正则从整库抠 refresh_token / access_token / default_drive_id
        val rt = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
        if (rt.isNullOrBlank()) return null
        val at = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1) ?: ""
        val did = Regex("\"default_drive_id\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1) ?: ""
        return JSONObject().apply {
            put("refresh_token", rt)
            put("access_token", at)
            put("default_drive_id", did)
        }
    }

    /** 保存 refresh_token，并用它刷新 access_token + 列文件做校验。 */
    private fun saveAndVerify(t: JSONObject) {
        saved = true
        val rt = t.optString("refresh_token")
        val atInitial = t.optString("access_token")
        val driveInitial = t.optString("default_drive_id")

        // 先落盘（即使校验失败，refresh_token 是核心凭证）
        getSharedPreferences("panpush", MODE_PRIVATE).edit()
            .putString("cred_aliyun", rt)
            .putString("cred_aliyun_at", atInitial)
            .putString("cred_aliyun_drive", driveInitial)
            .putLong("cred_time_cred_aliyun", System.currentTimeMillis())
            .apply()

        // 用 refresh_token 换新 access_token（网页 client），再列文件
        AliyunApi.refresh(rt) { ok, body ->
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(this, "令牌已保存（刷新失败：${body.take(120)}）",
                        Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK); finish()
                }
                return@refresh
            }
            // 解析新 access_token / default_drive_id
            var at = atInitial
            var drive = driveInitial
            try {
                val j = JSONObject(body)
                at = j.optString("access_token", at)
                drive = j.optString("default_drive_id", drive)
                getSharedPreferences("panpush", MODE_PRIVATE).edit()
                    .putString("cred_aliyun_at", at)
                    .putString("cred_aliyun_drive", drive)
                    .apply()
            } catch (e: Exception) { /* ignore */ }

            AliyunApi.listFiles(at, drive) { okList, msg ->
                runOnUiThread {
                    val tip = if (okList) "阿里云盘登录成功，已能列文件"
                              else "令牌已保存（列表校验：$msg）"
                    Toast.makeText(this, tip, Toast.LENGTH_LONG).show()
                    setResult(RESULT_OK); finish()
                }
            }
        }
    }

    /** 强制桌面逻辑宽度（W=900 折中：字够大且不裁切），允许双指放大看清验证码。 */
    private fun forceDesktopViewport() {
        web.evaluateJavascript(
            "(function(){var W=900;" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
            "document.getElementsByTagName('head')[0].appendChild(m);}" +
            "m.setAttribute('content','width='+W+', initial-scale=1, maximum-scale=4, user-scalable=yes');" +
            "})()", null)
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\""))
            s.substring(1, s.length - 1) else s

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}

/**
 * 阿里云盘最小 API 客户端（刷新令牌 + 列文件，用于校验令牌）。
 * 用 HttpURLConnection，免 OkHttp 依赖。无请求签名，纯 Bearer。
 */
object AliyunApi {

    private const val CLIENT_ID = "25dzX3vbYqktVxyX"

    /** 用 refresh_token 换 access_token（网页 client）。 */
    fun refresh(refreshToken: String, cb: (Boolean, String) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("https://api.aliyundrive.com/token/refresh")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-requested-with", "XMLHttpRequest")
                }
                val payload = "{\"client_id\":\"$CLIENT_ID\",\"refresh_token\":\"$refreshToken\"}"
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                cb(code in 200..299, body)
            } catch (e: Exception) {
                cb(false, e.message ?: "error")
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    /** 用 access_token 列根目录文件。 */
    fun listFiles(accessToken: String, driveId: String, cb: (Boolean, String) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("https://api.aliyundrive.com/adrive/v3/file/list")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = "{\"drive_id\":\"$driveId\",\"parent_file_id\":\"root\"," +
                              "\"limit\":100,\"order_by\":\"name\",\"order_direction\":\"ASC\"}"
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                cb(code in 200..299, "HTTP $code ${body.take(200)}")
            } catch (e: Exception) {
                cb(false, e.message ?: "error")
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}