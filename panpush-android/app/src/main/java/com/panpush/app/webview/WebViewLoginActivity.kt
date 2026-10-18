package com.panpush.app.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.panpush.app.core.SiteId
import com.panpush.app.cred.SiteProfile
import org.json.JSONObject

/**
 * WebViewLoginActivity —— **应用内** WebView 登录。
 *
 * ## 为什么必须「应用内 WebView」
 *
 * 外部浏览器 / CustomTabs 的 Cookie 存在**浏览器沙箱**里，本 App 永远读不到，
 * 所以「用系统浏览器登录」这条路**不可能**把登录态存下来 —— 那是设计错误，不是 bug。
 * 因此本 Activity 是唯一的登录入口，界面里也不再提供「系统浏览器登录」。
 *
 * ## 已修的 7 个持久化坑
 *
 *  ① `CookieManager.flush()`（onPageFinished / onPause / onDestroy）
 *     → 否则 Cookie 只在内存，进程被杀即丢
 *  ② `setAcceptThirdPartyCookies(webView, true)` → 跨域 SSO 必需
 *     （百度 wappass → pan.baidu.com、夸克 passport）
 *  ③ 不 incognito / 不清 Cookie → 不清掉自己刚登录的状态
 *  ④ `domStorageEnabled`（+ `databaseEnabled`）→ 123pan 等把 token 放 localStorage
 *  ⑤ 不伪造 UA 到「系统默认」以外的奇怪值；允许混合内容兼容模式
 *  ⑥ 全进程统一 `CookieManager.getInstance()`
 *  ⑦ 固定默认 `app_webview` profile（不 setDataDirectorySuffix、不清数据）
 *
 * 登录成功判据来自 [SiteProfile]：`checkHost` + `checkCookie`
 * （例外：阿里走 `localStorage.token`，见 [cookieIsToken]）。
 */
class WebViewLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SITE = "site"
        const val EXTRA_LOGIN_URL = "login_url"
        const val EXTRA_CHECK_HOST = "check_host"
        const val EXTRA_CHECK_COOKIE = "check_cookie"

        /** 回传：Cookie 头（形如 `a=1; b=2`） */
        const val EXTRA_RESULT_COOKIE = "result_cookie"

        /** 回传：localStorage 的 JSON（`key -> value`），123 这类 token 可能在这里 */
        const val EXTRA_RESULT_LOCAL_STORAGE = "result_local_storage"

        /** 迅雷网页凭据在 localStorage 里的键（真机实测位置） */
        const val XUNLEI_CRED_KEY = "credentials_${SiteProfile.XUNLEI_WEB_CLIENT_ID}"

        /** 手机浏览器 UA（百度用） */
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * 桌面 UA —— Windows（**夸克/123/迅雷/阿里 都用它**）。
         *
         * 实测（`quark-puus/夸克__puus获取实现.md` §1）：夸克按 UA 分流，移动 UA 会被
         * `302 → h5.sm.cn/blm/mobile-entry`（「下载 App」落地页），**到不了登录页**；
         * 桌面 UA 返回 `200` 完整网页版 + 登录入口。
         *
         * 阿里同理：`aliyun-login` 文档 §0 实测「手机 UA 根本不给登录」。
         */
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * 桌面 UA —— **Linux**（`X11; Linux x86_64`）。
         *
         * 比 Windows 版更「不矛盾」：安卓的 `navigator.platform` 本就是 Linux 家族，
         * 伪装成 Linux 桌面时 UA 与 platform 一致（伪装 Windows 则是 Windows vs Linux）。
         * 登录页上可一键切换，哪个能过人机校验就用哪个。
         */
        const val DESKTOP_LINUX_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * **安卓 Chrome UA（不含 `wv`）**。
         *
         * Android WebView 的默认 UA 会带 `; wv` 标记 —— 那是最明显的「我不是真浏览器」特征。
         * 这里显式设一个真机同款的 Android Chrome UA（无 `wv`），让 UA 与设备指纹一致。
         */
        const val ANDROID_CHROME_UA =
            "Mozilla/5.0 (Linux; Android 14; PKR110) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 沿用原来的桌面逻辑宽度（非阿里站点用，保持原行为不变） */
        private const val LEGACY_DESKTOP_VIEWPORT_W = 1280

        /**
         * **反风控（stealth）脚本 —— 完整指纹伪装**。
         *
         * ## 为什么「只改 UA」不够
         *
         * 现代风控（阿里 baxia 等）**交叉校验十几个 JS 指纹**，
         * 只要有一个与 UA 声明的平台矛盾，就判高风险 → 表现为「人机校验不通过」。
         * Android WebView 最致命的几处矛盾：
         *
         * | 指纹 | 安卓 WebView 实际 | 声明桌面 UA 后应是什么 |
         * |---|---|---|
         * | **`navigator.userAgentData.platform`** | **`"Android"`** ← 最致命 | `"Windows"` / `"Linux"` |
         * | `navigator.userAgentData.mobile` | `true` | `false` |
         * | **`navigator.connection`** | **存在**（NetworkInformation） | 桌面 Chrome **不存在** |
         * | `navigator.plugins.length` | **0** | 桌面 Chrome 有 PDF 插件（≥5） |
         * | `navigator.pdfViewerEnabled` | `false` | `true` |
         * | `'ontouchstart' in window` | `true` | `false` |
         * | `navigator.vendor` | `""` | `Google Inc.` |
         * | `window.chrome` | 无 / 残缺 | 完整 `runtime`/`app`/`csi`/`loadTimes` |
         * | **WebGL `UNMASKED_RENDERER`** | **Adreno/Mali** | `ANGLE (…Intel…)` |
         * | `navigator.webdriver` | 视版本 | `false` |
         *
         * ## 实现要点
         *
         * * 全部用 `Object.defineProperty` 盖在 `Navigator.prototype` 上，
         *   并保持 `configurable:true`，避免被反查时露馅。
         * * `userAgentData.getHighEntropyValues()` 也要盖 —— 风控常走高熵接口拿完整平台信息。
         * * `navigator.connection` 是**删除**（不是伪造）：桌面 Chrome 本来就没有。
         * * WebGL patch 的是**原型**（WebGL1 + WebGL2），覆盖所有 context。
         * * 注入时机：`onPageStarted`（尽早）+ `onPageFinished`（兜底），本脚本**幂等**。
         *
         * @param platform 期望的 `navigator.platform`
         * @param uaDataPlatform 期望的 `userAgentData.platform`
         * @param touchPoints `maxTouchPoints`（桌面 0 / 安卓 5）
         * @param desktop 是否伪装桌面（决定 plugins / connection / 触摸特征等）
         * @param spoofScreen 是否伪装 `screen.*` / `devicePixelRatio` / `outerWidth`。
         *   **默认关闭** —— 踩过的坑：把 `screen` 说成 1920x1080、`dpr` 说成 1，
         *   而真实视口只有 ~360~900px。滑块/风控脚本常拿 `screen.width` 或 `outerWidth`
         *   换算拖拽距离 → **算出来的距离与实际不一致 → 滑了也判定不过**。
         *   所以这一项反而会**弄坏滑块**，默认不做。
         */
        private fun stealthJs(
            platform: String,
            uaDataPlatform: String,
            touchPoints: Int,
            desktop: Boolean,
            spoofScreen: Boolean = false
        ): String {
            val touch = if (desktop) 0 else touchPoints
            val mobile = if (desktop) "false" else "true"
            return "(function(){try{" +
                "var P=Navigator.prototype,S=Screen.prototype;" +
                "function def(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}}" +
                // ---- 1) navigator 基础项 ----
                "def(P,'webdriver',false);" +
                "def(P,'platform','$platform');" +
                "def(P,'maxTouchPoints',$touch);" +
                "def(P,'vendor','Google Inc.');" +
                "def(P,'hardwareConcurrency',8);" +
                "def(P,'deviceMemory',8);" +
                "def(P,'language','zh-CN');" +
                "def(P,'languages',['zh-CN','zh','en']);" +
                "def(P,'pdfViewerEnabled',true);" +
                // ---- 2) userAgentData（Client Hints 的 JS 侧）----
                // 安卓 WebView 默认 {platform:'Android', mobile:true}，与桌面 UA 直接矛盾。
                // getHighEntropyValues 也要盖 —— 风控常走高熵接口。
                "var hev={architecture:'x86',bitness:'64',model:'',platform:'$uaDataPlatform'," +
                "platformVersion:'10.0.0',uaFullVersion:'120.0.0.0'," +
                "fullVersionList:[{brand:'Chromium',version:'120.0.0.0'},{brand:'Google Chrome',version:'120.0.0.0'}]," +
                "wow64:false};" +
                "var uad={brands:[{brand:'Chromium',version:'120'},{brand:'Google Chrome',version:'120'}," +
                "{brand:'Not?A_Brand',version:'24'}],mobile:$mobile,platform:'$uaDataPlatform'," +
                "getHighEntropyValues:function(){return Promise.resolve(hev);}," +
                "toJSON:function(){return {brands:this.brands,mobile:this.mobile,platform:this.platform};}};" +
                "def(P,'userAgentData',uad);" +
                // ---- 3) window.chrome（桌面 Chrome 必有 runtime / app）----
                "if(!window.chrome){window.chrome={};}" +
                "window.chrome.runtime=window.chrome.runtime||{};" +
                "window.chrome.app=window.chrome.app||{isInstalled:false};" +
                "if(!window.chrome.csi){window.chrome.csi=function(){return {onloadT:Date.now(),startE:Date.now(),pageT:1,tran:15};};}" +
                "if(!window.chrome.loadTimes){window.chrome.loadTimes=function(){return {commitLoadTime:Date.now()/1000,connectionInfo:'h2',finishDocumentLoadTime:Date.now()/1000,finishLoadTime:Date.now()/1000,firstPaintTime:Date.now()/1000,firstPaintAfterLoadTime:0,navigationType:'Other',npnNegotiatedProtocol:'h2',requestTime:Date.now()/1000,startLoadTime:Date.now()/1000,wasAlternateProtocolAvailable:false,wasFetchedViaSpdy:true,wasNpnNegotiated:true};};}" +
                // ---- 4) WebGL：patch **原型**（覆盖所有 context，含 WebGL2）----
                // 安卓 GPU 是 Adreno/Mali，最明显的「移动端证据」之一。
                // 37445/37446 = UNMASKED_VENDOR_WEBGL / UNMASKED_RENDERER_WEBGL。
                // 必须两个一起改，只改一个会被交叉比对出来。
                "function patchGL(proto){if(!proto)return;var orig=proto.getParameter;" +
                "proto.getParameter=function(p){" +
                "if(p===37445)return 'Google Inc. (Intel)';" +
                "if(p===37446)return 'ANGLE (Intel, Intel(R) UHD Graphics 620 Direct3D11 vs_5_0 ps_5_0, D3D11)';" +
                "return orig.call(this,p);};}" +
                "patchGL(window.WebGLRenderingContext && WebGLRenderingContext.prototype);" +
                "patchGL(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);" +
                (if (desktop) (
                    // ---- 5) 桌面专属：plugins / mimeTypes / connection / 触摸特征 ----
                    "var names=['PDF Viewer','Chrome PDF Viewer','Chromium PDF Viewer'," +
                    "'Microsoft Edge PDF Viewer','WebKit built-in PDF'];" +
                    "var fk=names.map(function(n){return {name:n,filename:'internal-pdf-viewer'," +
                    "description:'Portable Document Format'};});" +
                    "fk.item=function(i){return this[i];};" +
                    "fk.namedItem=function(n){for(var i=0;i<this.length;i++){if(this[i].name===n)return this[i];}return null;};" +
                    "def(P,'plugins',fk);" +
                    "def(P,'mimeTypes',[{type:'application/pdf',suffixes:'pdf',description:'Portable Document Format'}," +
                    "{type:'text/pdf',suffixes:'pdf',description:'Portable Document Format'}]);" +
                    // 抹掉触摸特征：桌面 UA 却 'ontouchstart' in window 为 true 是最典型的矛盾
                    "try{Object.defineProperty(window,'ontouchstart',{get:function(){return undefined;},configurable:true});}catch(e){}" +
                    "try{delete window.ontouchstart;}catch(e){}" +
                    "try{delete window.ontouchend;}catch(e){}" +
                    // 桌面 Chrome 没有 NetworkInformation → 删掉
                    "try{Object.defineProperty(P,'connection',{get:function(){return undefined;},configurable:true});}catch(e){}" +
                    // 屏幕尺寸/PixelRatio **默认不伪装**（会弄坏滑块，见 spoofScreen 说明）
                    (if (spoofScreen) (
                        "def(S,'width',1920);def(S,'height',1080);" +
                        "def(S,'availWidth',1920);def(S,'availHeight',1040);" +
                        "def(S,'colorDepth',24);def(S,'pixelDepth',24);" +
                        "try{Object.defineProperty(window,'devicePixelRatio',{get:function(){return 1;},configurable:true});}catch(e){}" +
                        "try{Object.defineProperty(window,'outerWidth',{get:function(){return 1920;},configurable:true});}catch(e){}" +
                        "try{Object.defineProperty(window,'outerHeight',{get:function(){return 1040;},configurable:true});}catch(e){}"
                    ) else "")
                ) else "") +
                "}catch(e){}})()"
        }

        /**
         * **主框架请求头伪装（Client Hints）—— 「只改 UA 不够」的另一半**。
         *
         * ## 为什么 JS 伪装救不了这一层
         *
         * `settings.userAgentString` 只改 `User-Agent`。**`Sec-CH-UA-*` 是内核按真实设备发的
         * HTTP 头**，JS 里改 `navigator.userAgentData` 也拦不住它们。
         *
         * ⚠️ **实测到的硬限制**（用 `shouldInterceptRequest` 回读真实头验证）：
         * `loadUrl(url, headers)` 只能覆盖 **4 个**高熵头：
         * `sec-ch-ua-arch` / `-bitness` / `-platform-version` / `-full-version-list` 成功；
         * 而**最关键的三个压不过内核**：
         * ```
         * sec-ch-ua="Chromium";v="154", "Android WebView";v="154"   ← 仍是内核真实值
         * sec-ch-ua-mobile=?1
         * sec-ch-ua-platform="Android"
         * ```
         * 它们由 WebView 的 `UserAgentMetadata` 决定，**`userAgentString` 改不动、
         * 附加头也压不过**。要全量覆盖得用 `shouldInterceptRequest` 重建请求，
         * 但那会**破坏登录的 POST 提交**（拿不到请求体）→ 不可行。
         *
         * 所以这里按「能覆盖多少算多少」处理，**不夸大效果**。
         */
        private fun desktopHeaders(mode: SiteProfile.UaMode): Map<String, String> = when (mode) {
            SiteProfile.UaMode.DESKTOP -> mapOf(
                "User-Agent" to DESKTOP_UA,
                "Sec-CH-UA" to
                    "\"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\", \"Not?A_Brand\";v=\"24\"",
                "Sec-CH-UA-Mobile" to "?0",
                "Sec-CH-UA-Platform" to "\"Windows\"",
                "Sec-CH-UA-Platform-Version" to "\"10.0.0\"",
                "Sec-CH-UA-Arch" to "\"x86\"",
                "Sec-CH-UA-Bitness" to "\"64\"",
                "Sec-CH-UA-Full-Version-List" to
                    "\"Chromium\";v=\"120.0.0.0\", \"Google Chrome\";v=\"120.0.0.0\"",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
            )
            SiteProfile.UaMode.DESKTOP_LINUX -> mapOf(
                "User-Agent" to DESKTOP_LINUX_UA,
                "Sec-CH-UA" to
                    "\"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\", \"Not?A_Brand\";v=\"24\"",
                "Sec-CH-UA-Mobile" to "?0",
                "Sec-CH-UA-Platform" to "\"Linux\"",
                "Sec-CH-UA-Platform-Version" to "\"\"",
                "Sec-CH-UA-Arch" to "\"x86\"",
                "Sec-CH-UA-Bitness" to "\"64\"",
                "Sec-CH-UA-Full-Version-List" to
                    "\"Chromium\";v=\"120.0.0.0\", \"Google Chrome\";v=\"120.0.0.0\"",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8"
            )
            // 安卓 / 手机模式不伪装：真实指纹本来就是自洽的
            SiteProfile.UaMode.ANDROID_CHROME, SiteProfile.UaMode.MOBILE -> emptyMap()
        }

        /**
         * ★★★ **把阿里登录 iframe 切到「验证码（短信）登录」**。
         *
         * ## 从 `h5-auth/0.1.6/js/app.js` 挖出来的真相
         *
         * 阿里登录页结构 = **顶层 `h5-auth` 页面** + **内嵌 passport `mini_login.htm` iframe**
         * （iframe 的 id 是 `alibaba-login-box`，由 `mini-login-embedder` 创建）。
         *
         * 登录方式由 **iframe URL 上的 `appEntrance`** 决定：
         * ```
         * appEntrance = web_default  → 默认（二维码/扫码）
         * appEntrance = web_sms      → 验证码（短信）登录   ← 要的
         * ```
         * 官方页面上那个「验证码登录」文字链接，点击时执行的就是
         * `initLogin({appEntrance:"web_sms"})`；
         * ⚠️ 但它**只在 `currentTab === "password"` 时才渲染**，
         * 而 `currentTab` 默认是 **`"qrcode"`** —— 所以默认页面上**根本看不到「验证码登录」**。
         *
         * ## 做法
         *
         * 直接**改写 iframe 的 src**，把 `appEntrance` 换成 `web_sms`
         * （iframe 元素不变 → 父页与它的 postMessage 握手仍然有效 → 登录完成后
         * OAuth code 换 token 的流程照常走）。**幂等**，可重复注入。
         *
         * @return 一段 JS，执行后返回状态字符串（便于日志验证）
         */
        private fun aliSmsJs(): String =
            "(function(){try{" +
                "var f=document.getElementById('alibaba-login-box')||" +
                "document.querySelector('#frame-box iframe')||" +
                "document.querySelector('iframe[src*=mini_login]');" +
                "if(!f) return 'no-iframe-yet';" +
                "var s=f.src||''; if(!s) return 'iframe-no-src';" +
                "if(s.indexOf('appEntrance=web_sms')>=0) return 'already-sms';" +
                "var u; if(/[?&]appEntrance=/.test(s)){u=s.replace(/([?&])appEntrance=[^&]*/,'$1appEntrance=web_sms');}" +
                "else {u=s+(s.indexOf('?')<0?'?':'&')+'appEntrance=web_sms';}" +
                "f.src=u; return 'switched-to-sms';" +
                "}catch(e){return 'err '+e;}})()"

        /**
         * 手机/自适应页面用：**只在页面自己没声明 viewport 时**补一个自适应进去。
         *
         * 「页面挤成一坨」的根因：很多网盘登录页没有 viewport meta（桌面布局），
         * WebView 会按 980px 虚拟宽度渲染 → 整页塞进屏幕、字小到没法点。
         */
        private const val VIEWPORT_FIX_JS =
            "(function(){try{if(document.querySelector('meta[name=viewport]'))return 'has';" +
                "var m=document.createElement('meta');m.name='viewport';" +
                "m.content='width=device-width,initial-scale=1.0,maximum-scale=5.0,user-scalable=yes';" +
                "(document.head||document.documentElement).appendChild(m);return 'fixed';}catch(e){return 'err';}})()"

        /**
         * 把桌面页的逻辑宽度钉成 `w`（配合 `useWideViewPort + loadWithOverviewMode`）。
         *
         * ⚠️ **只给非伪装站点用**（沿用原来的 1280 行为）。
         * 阿里**不能**用：passport 登录页自带 `width=device-width`，
         * 强改宽度会把页面缩小 → **滑块坐标被缩放 → 拖了也判定不过**。
         */
        private fun forceViewportJs(w: Int): String =
            "(function(){try{var W=$w;" +
                "var m=document.querySelector('meta[name=\"viewport\"]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "(document.head||document.getElementsByTagName('head')[0]).appendChild(m);}" +
                "m.setAttribute('content','width='+W+', initial-scale=1, maximum-scale=4, user-scalable=yes');" +
                "}catch(e){}})()"

        /** 按站点构造登录 Intent（直接用 [SiteProfile] 的判定值） */
        fun buildIntent(ctx: Context, site: SiteId): Intent {
            val p = SiteProfile.of(site)
            return Intent(ctx, WebViewLoginActivity::class.java).apply {
                putExtra(EXTRA_SITE, site.key)
                putExtra(EXTRA_LOGIN_URL, p.webLoginUrl ?: p.externalUrl)
                putExtra(EXTRA_CHECK_HOST, p.checkHost)
                putExtra(EXTRA_CHECK_COOKIE, p.checkCookie)
            }
        }
    }

    /**
     * 登录页的显示参数（同一进程内跨多次登录记住，省得每次都调）。
     * 只影响**登录页显示**，不影响任何凭据内容。
     */
    private class DisplaySettings {
        /** 仅缩放；UA 由站点档案决定 */
        var textZoom: Float = 1.0f
    }

    private val settings = DisplaySettings()

    private lateinit var webView: WebView
    private lateinit var statusView: TextView
    private lateinit var progress: ProgressBar

    /** UA 切换按钮（人机校验不过时用来换桌面指纹重试） */
    private var uaBtn: Button? = null

    /** 「验证码登录」按钮引用 */
    private var smsBtnRef: Button? = null

    private var site: SiteId = SiteId.UNKNOWN
    private var checkHost: String = ""
    private var checkCookie: String = ""
    private var currentUrl: String = ""

    /** 该站点用哪种 UA 模式（见 [SiteProfile.UaMode]） */
    private var uaMode: SiteProfile.UaMode = SiteProfile.UaMode.MOBILE

    /** 该站点是否要用桌面布局 */
    private var desktopUa: Boolean = false

    /** 是否已经抓到目标令牌（避免重复 finish） */
    private var gotToken = false

    /** 最近一次主框架请求实际带出的 Client Hints（用于验收 C3 / 诊断） */
    private var lastMainFrameHeaders: String = ""

    /** 最近一次注入过 viewport 的 URL（每 URL 只注一次，避免打断滑块拖拽） */
    private var lastViewportUrl: String = ""

    /**
     * 是否启用**全套反爬伪装**（只有阿里开，见 [SiteProfile.Profile.spoofFingerprint]）。
     *
     * 关闭时（夸克/百度/123/迅雷）完全不注入任何指纹脚本、也不加伪装头 ——
     * 保持这些站点原来能正常登录的行为不变。
     */
    private var spoofFingerprint: Boolean = false

    /**
     * 该站点的令牌是否**就是某个 Cookie**（见 [SiteProfile.Profile.cookieIsToken]）。
     * false 时不做 Cookie 自动收尾（否则一进页面就 finish，像「闪退」）。
     */
    private var cookieIsToken: Boolean = true

    /** 阿里：是否希望用「验证码（短信）登录」 */
    private var aliWantSms: Boolean = true

    /**
     * 阿里：**指纹伪装是否开启**。
     *
     * ⚠️ 现在改成**自动跟随 UA 模式**，不再是用户手动开关：
     *  · UA 是**桌面**（Win/Linux）→ 安卓内核的指纹与 UA **矛盾** → 必须伪装；
     *  · UA 是**手机/安卓** → 环境本来就是真安卓浏览器，**指纹天然自洽** → 伪装反而多余，
     *    而且会把 `screen` 之类的值改成与真实视口冲突（可能弄坏滑块）。
     *
     * 这就是 `ali-lushuo-对比` 文档路线 A 的核心：**别伪装，直接用手机 UA**。
     */
    private val autoSpoof: Boolean
        get() = spoofFingerprint &&
            (uaMode == SiteProfile.UaMode.DESKTOP || uaMode == SiteProfile.UaMode.DESKTOP_LINUX)

    /** 阿里：是否已记下「进页面时」的 token 基线（见 [hasNewLsToken]） */
    private var lsBaselineCaptured: Boolean = false

    /** 阿里/迅雷：进页面时的 token 基线 —— 只有**与它不同**才算本次登录成功 */
    private var lsBaselineToken: String = ""

    /** 拿到的 localStorage 快照（登录成功或点「完成」时抓一次） */
    private val localStorageSnapshot = JSONObject()
    private var lastCookieHeader: String = ""

    /** 迅雷 localStorage 键名是否已打过日志（只打一次，避免刷屏） */
    private var xunleiLsLogged = false

    /** 上次静默回填迅雷凭据的指纹（避免每次轮询都写盘） */
    private var lastXunleiSavedSig = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        site = SiteId.fromKey(intent.getStringExtra(EXTRA_SITE))
        val profile = SiteProfile.of(site)
        val loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL)
            ?: profile.let { it.webLoginUrl ?: it.externalUrl }
        checkHost = intent.getStringExtra(EXTRA_CHECK_HOST) ?: profile.checkHost
        checkCookie = intent.getStringExtra(EXTRA_CHECK_COOKIE) ?: profile.checkCookie
        uaMode = profile.uaMode
        desktopUa = profile.desktopUa
        spoofFingerprint = profile.spoofFingerprint
        cookieIsToken = profile.cookieIsToken

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0E14.toInt())
        }

        // ---- 顶部：状态 + 缩放 + 完成 ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(6))
            setBackgroundColor(0xFF121620.toInt())
        }
        statusView = TextView(this).apply {
            text = "正在打开 ${site.label} 登录页…"
            setTextColor(0xFFB6BFD0.toInt())
            textSize = 12f
        }
        val barRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        // 缩放按钮：个别步骤页元素偏小，需要能手动放大
        val zoomOut = Button(this).apply {
            text = "－"
            setOnClickListener { applyZoom(settings.textZoom - 0.25f) }
        }
        val zoomIn = Button(this).apply {
            text = "＋"
            setOnClickListener { applyZoom(settings.textZoom + 0.25f) }
        }
        val doneBtn = Button(this).apply {
            text = "完成并保存"
            setOnClickListener { finishWithResult() }
        }
        // ⚠️ 状态文字 + 缩放 + 完成 挤一行会把状态文字压扁，所以辅助功能单独放第 3 行。
        barRow2.addView(
            statusView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        barRow2.addView(zoomOut)
        barRow2.addView(zoomIn)
        barRow2.addView(doneBtn)
        bar.addView(barRow2)

        // ★ 第 3 行：**只有阿里才显示**（UA 切换 + 验证码登录）——
        //   其余站点沿用原界面（避免多一行控件造成拥挤）。
        //   注意这里**不受 spoofFingerprint 控制**了：阿里的伪装是跟着 UA 自动开关的，
        //   默认（安卓 UA）不伪装，但仍需要这两个按钮来换档 / 切验证码登录。
        if (site == SiteId.ALI || site == SiteId.PAN123 || spoofFingerprint) {
            val barRow3 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            // UA 切换：换的是**整组**伪装（UA + Sec-CH-UA-* + JS 指纹）
            val uaToggle = Button(this).apply {
                text = "UA:${uaLabel()}"
                setOnClickListener { cycleUa() }
            }
            uaBtn = uaToggle
            // 「验证码登录」：官方默认 appEntrance=web_default → 只出扫码，
            // 且「验证码登录」链接只在 currentTab==="password" 时才渲染
            // → 默认页面上看不到。这里直接把登录 iframe 的 appEntrance 改成 web_sms。
            val smsBtn = Button(this).apply {
                text = "验证码登录"
                setOnClickListener {
                    aliWantSms = true
                    applyAliSmsTab("manual")
                    statusView.text = "正在切到「验证码（短信）登录」…没反应就再点一次"
                }
            }
            smsBtnRef = smsBtn
            val hint = TextView(this).apply {
                text = "滑块拖不过就换「UA」；没验证码就点「验证码登录」"
                setTextColor(0xFF8A93A6.toInt())
                textSize = 11f
                setPadding(dp(8), 0, 0, 0)
            }
            barRow3.addView(uaToggle)
            barRow3.addView(smsBtn)
            barRow3.addView(
                hint,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            bar.addView(barRow3)
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = ProgressBar.GONE
        }
        val progressBar = progress

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            // 供 JS 回传 localStorage 用（仅站内页面会调用）
            addJavascriptInterface(LocalStorageBridge(), "PanPushBridge")
            webViewClient = object : WebViewClient() {
                /**
                 * 验证 Client Hints 伪装是否真的发出去了（文档 T1 / 用例 C3）。
                 *
                 * `Sec-CH-UA-*` 是**内核发的头**，JS 里改不掉 —— 只能这样实测。
                 * 期望：`sec-ch-ua-mobile = ?0`、`sec-ch-ua-platform = "Windows"`。
                 * 实测结论见 [desktopHeaders] 的注释（核心三条压不过内核）。
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    if (request.isForMainFrame) {
                        val h = request.requestHeaders
                        val ch = h.entries
                            .filter {
                                it.key.lowercase().startsWith("sec-ch-ua") ||
                                    it.key.equals("User-Agent", true)
                            }
                            .joinToString(" | ") { "${it.key}=${it.value}" }
                        android.util.Log.i("PanPushHdr", "[主框架] ${request.url}\n    $ch")
                        lastMainFrameHeaders = ch
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                /**
                 * 只拦**非 http(s) 深链**，其余一律放行。
                 *
                 * ⚠️ 夸克会在点「登录」时弹 `quark://` 深链去唤起夸克 App
                 * （见 `quark-puus/夸克__puus获取实现.md` §5）—— 若放行，
                 * 就被踢到 App 里、WebView 永远登不上。这里直接拦掉留在本页。
                 * http(s) 必须放行：登录有多级跨域跳转（百度 wappass → pan.baidu.com）。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val u = request.url.toString()
                    currentUrl = u
                    if (u.startsWith("http://") || u.startsWith("https://")) return false
                    statusView.text = "已拦下外部 App 唤起（$u）—— 请在本页内继续登录"
                    return true
                }

                /**
                 * **尽早注入指纹伪装**。
                 *
                 * `onPageFinished` 时站点的 JS 早就跑完了 —— 风控往往在**页面首帧**
                 * 就把 `navigator.userAgentData` / `platform` / plugins 读走并上报。
                 * 所以 `onPageStarted` 也要注一次（本脚本幂等，重复注入无害）。
                 */
                override fun onPageStarted(
                    view: WebView,
                    url: String,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    currentUrl = url
                    // 只有阿里做指纹伪装；其余站点不注入任何东西（保持原行为）
                    if (autoSpoof) injectStealth(view, "start")
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    currentUrl = url
                    // 坑①：每次页面加载完成都 flush，确保 Cookie 落盘
                    CookieStore.flush()
                    progressBar.visibility = ProgressBar.GONE
                    refreshStatus()
                    grabLocalStorage(view)
                    // 夸克/百度：页面加载完成就先抓一次（__puus / BDUSS 可能这时已下发）
                    tryGrabTokenOnce()
                    // 阿里：看 localStorage 里的 token
                    tryGrabAliTokenOnce()
                    // 阿里：把登录 iframe 切到验证码登录
                    // （iframe 由 h5-auth 异步创建 → 延迟两次再试，幂等）
                    if (site == SiteId.ALI && aliWantSms) {
                        view.postDelayed({ applyAliSmsTab("finish+900ms") }, 900)
                        view.postDelayed({ applyAliSmsTab("finish+2600ms") }, 2600)
                    }
                    // 反风控：再注一次（有的页面在 DOMContentLoaded 后才读指纹）
                    if (autoSpoof) injectStealth(view, "finish")
                    // 视口处理：
                    //  · **阿里**（spoofFingerprint）→ **不强改视口**！
                    //    passport 登录页自带 `width=device-width,initial-scale=1`（本就是移动友好页），
                    //    强改成固定宽度会把页面缩小 → 滑块坐标被缩放 → 拖了也判定不过。
                    //    这里只做「页面没声明 viewport 时补一个」的兜底（对 passport 是空操作）。
                    //  · 其余站点 → 沿用原来的行为（桌面 1280 / 手机自适应，每次注入）
                    if (spoofFingerprint) {
                        if (url != lastViewportUrl) {
                            lastViewportUrl = url
                            view.evaluateJavascript(VIEWPORT_FIX_JS, null)
                        }
                    } else {
                        if (desktopUa) {
                            view.evaluateJavascript(
                                forceViewportJs(LEGACY_DESKTOP_VIEWPORT_W), null
                            )
                        } else {
                            view.evaluateJavascript(VIEWPORT_FIX_JS, null)
                        }
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility =
                        if (newProgress in 1..99) ProgressBar.VISIBLE else ProgressBar.GONE
                }
            }
        }

        configureWebView()

        root.addView(bar)
        root.addView(progress)
        root.addView(webView)
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        // 带伪装头发起首次加载（主框架 Client Hints 需与 UA 一致）
        loadWithSpoof(loginUrl)
        currentUrl = loginUrl
        // 应用上次的缩放（同一进程内记住），默认 100%
        webView.settings.textZoom = (settings.textZoom * 100).toInt()
        statusView.text = "请在下方页面登录 ${site.label}（登录后会自动识别）"

        // 返回键：能回退就回退，否则按「完成并保存」语义收尾
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finishWithResult()
            }
        })

        // SPA 登录完成后不一定再触发 onPageFinished → 加轮询兜底
        startTokenPolling()
    }

    /**
     * 带伪装头加载（**仅 `spoofFingerprint` 站点**才加头，且伪装开关打开时）。
     *
     * 其余站点走普通 `loadUrl` —— 它们实测登录正常，不引入额外变量。
     */
    private fun loadWithSpoof(url: String) {
        currentUrl = url
        val h = if (autoSpoof) desktopHeaders(uaMode) else emptyMap()
        if (h.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, h)
    }

    /**
     * 配置 WebView —— 逐条对应 7 个坑。
     * 注意这里**不调用** removeAllCookies / clearCache / clearHistory（那是坑③⑦）。
     *
     * ## UA / 指纹策略（按站点分流）
     *
     * `quark-puus/夸克__puus获取实现.md` §1 的 curl 实测：
     *  · **桌面 UA** → `200` 完整网页版（含「登录」/扫码）
     *  · **移动 UA** → `302 → h5.sm.cn/blm/mobile-entry`（「下载 App」落地页）
     *
     * `aliyun-login/阿里云盘登录-指纹伪装与移动端布局适配.md` §0 实测：
     *  · **手机 UA → 阿里根本不给登录**（推 App）
     *  · **桌面 UA → 出登录界面，但手机内核会卡人机校验**
     *    （因为内核发的 `Sec-CH-UA-*` 与 JS 指纹仍是 Android，与桌面 UA 矛盾）
     *
     * → 所以阿里用 [SiteProfile.UaMode.DESKTOP]，并开 [spoofFingerprint] 做
     *   请求头 + JS 指纹两层伪装（**仅阿里**，其余站点保持原行为）。
     */
    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        // 坑④：localStorage 必须开（登录态依赖）
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        // 缩放：允许双指放大看清滑块验证码；隐藏 +/- 浮层（自绘按钮更好用）
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false

        when (uaMode) {
            SiteProfile.UaMode.DESKTOP -> {
                s.userAgentString = DESKTOP_UA
                s.useWideViewPort = true
                s.loadWithOverviewMode = true
            }
            SiteProfile.UaMode.DESKTOP_LINUX -> {
                s.userAgentString = DESKTOP_LINUX_UA
                s.useWideViewPort = true
                s.loadWithOverviewMode = true
            }
            SiteProfile.UaMode.ANDROID_CHROME -> {
                // 真机同款安卓 Chrome UA（**不含 wv**）：与设备指纹一致
                s.userAgentString = ANDROID_CHROME_UA
                s.useWideViewPort = true
                s.loadWithOverviewMode = false
            }
            SiteProfile.UaMode.MOBILE -> {
                s.userAgentString = MOBILE_UA
                s.useWideViewPort = true
                s.loadWithOverviewMode = false
            }
        }
        webView.setInitialScale(0)

        // 坑②/⑥：统一 CookieManager，允许第三方 Cookie
        CookieStore.enablePersistentCookies(webView)
    }

    /** 字号/缩放调整（仅用于个别偏小的步骤页） */
    private fun applyZoom(value: Float) {
        val v = value.coerceIn(0.75f, 2.5f)
        settings.textZoom = v
        webView.settings.textZoom = (v * 100).toInt()
        statusView.text = "缩放 ${(v * 100).toInt()}%"
    }

    /** 按当前 UA 模式生成 stealth 脚本（见 [stealthJs]） */
    private fun stealthNow(): String = when (uaMode) {
        SiteProfile.UaMode.DESKTOP ->
            stealthJs("Win32", "Windows", 0, desktop = true, spoofScreen = false)
        SiteProfile.UaMode.DESKTOP_LINUX ->
            stealthJs("Linux x86_64", "Linux", 0, desktop = true, spoofScreen = false)
        SiteProfile.UaMode.ANDROID_CHROME, SiteProfile.UaMode.MOBILE ->
            stealthJs("Linux armv8l", "Android", 5, desktop = false)
    }

    /**
     * 循环切换 UA 并重载当前页。
     *
     * **默认 Windows 桌面**；一种过不了人机校验就换下一种。
     * 顺序：桌面Win → 桌面Linux → 安卓Chrome → 手机版。
     */
    private fun cycleUa() {
        val order = SiteProfile.uaModeOrder
        val idx = order.indexOf(uaMode).let { if (it < 0) 0 else it }
        uaMode = order[(idx + 1) % order.size]
        desktopUa = uaMode == SiteProfile.UaMode.DESKTOP ||
            uaMode == SiteProfile.UaMode.DESKTOP_LINUX
        configureWebView()
        uaBtn?.text = "UA:${uaLabel()}"
        lastViewportUrl = ""
        currentUrl.takeIf { it.isNotBlank() }?.let { loadWithSpoof(it) }
        statusView.text = "已切换 UA：${uaLabel()}（若人机校验不过，再点一次换一种）"
    }

    private fun uaLabel(): String = when (uaMode) {
        SiteProfile.UaMode.DESKTOP -> "桌面Win"
        SiteProfile.UaMode.DESKTOP_LINUX -> "桌面Linux"
        SiteProfile.UaMode.ANDROID_CHROME -> "安卓Chrome"
        SiteProfile.UaMode.MOBILE -> "手机版"
    }

    /**
     * 注入伪装脚本，并**回读实际生效值**打日志（`PanPushStealth`）。
     *
     * 为什么要回读：JS 里的 `defineProperty` 可能因为版本差异静默失败，
     * 只看「我注入了」等于没验证。这里把风控真正会读的那几个字段读回来，
     * 一眼就能看出伪装到底有没有生效、有没有自相矛盾。
     */
    private fun injectStealth(view: WebView, tag: String) {
        view.evaluateJavascript(stealthNow(), null)
        val probe = "(function(){try{var u=navigator.userAgentData||{};" +
            "return JSON.stringify({ua:navigator.userAgent.slice(0,60)," +
            "plat:navigator.platform,touch:navigator.maxTouchPoints," +
            "wd:navigator.webdriver,uadPlat:u.platform,uadMob:u.mobile," +
            "plugins:(navigator.plugins||[]).length," +
            "conn:(navigator.connection?'yes':'no')," +
            "chrome:(window.chrome&&window.chrome.runtime?'yes':'no')," +
            "sw:screen.width+'x'+screen.height,dpr:window.devicePixelRatio," +
            "outer:window.outerWidth+'x'+window.outerHeight});}catch(e){return 'ERR '+e;}})()"
        view.evaluateJavascript(probe) { r ->
            android.util.Log.i("PanPushStealth", "[$tag/${uaLabel()}] $r")
        }
    }

    /**
     * 抓目标令牌（夸克 `__puus` / 百度 `BDUSS` / 迅雷 `credentials_`）。
     *
     * 关键（`夸克__puus获取实现.md` §0）：`__puus` 是 httpOnly，
     * **网页 JS 读不到，但 `CookieManager.getCookie()` 是 Native 接口、不受 httpOnly 限制**，
     * 所以能在本 App 自己的 WebView 里读回。
     *
     * ⚠️ **仅对「令牌就是 Cookie」的站点生效**（[SiteProfile.Profile.cookieIsToken]）。
     * 阿里关掉了它：`ADRIVE_UID` 这类 Cookie **登录前就可能已存在**，
     * 一进页面就判「已抓到」→ 立即 finish，表现为**「打开就闪退」**。
     */
    private fun tryGrabTokenOnce() {
        if (gotToken) return
        if (!cookieIsToken) return
        val header = currentCookieHeader()
        if (header.isBlank()) return
        if (!header.contains(checkCookie, ignoreCase = true)) return
        // ★ 按用户要求：**不自动保存/不自动退出**。
        //   只记下抓到的值并提示，必须由用户点「完成并保存」才提交并关闭。
        lastCookieHeader = header
        statusView.text = "✅ 已检测到 $checkCookie → 点右上角「完成并保存」"
    }

    /**
     * ★ **localStorage 里令牌的判定**（阿里 + 迅雷）。
     *
     * 为什么要基线（[lsBaselineCaptured] / [lsBaselineToken]）：
     * 这两个站点的登录态都存在 localStorage，而**上次登录留下的令牌也在** ——
     * 直接判「有令牌」会**一进页面就 finish**（看起来像「打开就闪退」）。
     * 所以只有令牌**从无到有**（或值变了）才算「本次真的登录成功了」。
     *
     * 若本来就已登录（令牌早就在），则不自动收尾，由用户点「完成并保存」——
     * 既避免页面一闪而过，也避免拿旧令牌当新结果。
     */
    private fun lsTokenValue(): String = when (site) {
        SiteId.ALI -> aliTokenValue()
        SiteId.XUNLEI -> xunleiTokenValue()
        else -> ""
    }

    private fun hasNewLsToken(): Boolean {
        val cur = lsTokenValue()
        if (cur.isBlank()) return false
        if (!lsBaselineCaptured) {
            lsBaselineCaptured = true
            lsBaselineToken = cur
            return false
        }
        return cur != lsBaselineToken
    }

    /**
     * 抓到 localStorage 里的令牌 → **只提示，不收尾**。
     *
     * 用户明确要求：应用内登录必须**手动点「完成并保存」**才提交并退出，
     * 不能自动保存/自动关闭（否则用户还没看清页面就闪退，也容易误存旧令牌）。
     */
    private fun tryGrabLsTokenOnce() {
        if (hasNewLsToken()) {
            statusView.text = "✅ 已检测到 ${site.label} token → 点右上角「完成并保存」"
        }
    }

    /** 兼容旧名（阿里） */
    private fun hasAliToken(): Boolean = hasNewLsToken()

    private fun tryGrabAliTokenOnce() = tryGrabLsTokenOnce()

    /**
     * 迅雷：从 localStorage 的 **`credentials_<clientId>`** 取令牌字符串。
     *
     * 真机实测：迅雷网页登录的凭据**不在 cookie**（cookie 只有 sessionid/userid 等 6 个），
     * 而在 localStorage 的 `credentials_Xqp0kJBXWhwaTpB6`，值是
     * `{"token_type":"Bearer","access_token":"…","refresh_token":"…"}` ——
     * **含 refresh_token**，这是「非 root 下能续期」的前提。
     */
    private fun xunleiTokenValue(): String {
        val o = localStorageSnapshot
        if (o.length() == 0) return ""
        val key = if (o.has(XUNLEI_CRED_KEY)) {
            XUNLEI_CRED_KEY
        } else {
            o.keys().asSequence().firstOrNull { it.startsWith("credentials_") } ?: return ""
        }
        val v = o.optString(key)
        if (v.isBlank()) return ""
        return v.trim().removeSurrounding("\"").replace("\\\"", "\"")
    }

    /** 从快照里取 `token` 的实际值（兼容 JSON 串 / 转义 / 兜底正则） */
    private fun aliTokenValue(): String {
        val o = localStorageSnapshot
        if (o.length() == 0) return ""
        val tv = o.optString("token", "")
        if (tv.isNotBlank()) return tv.removeSurrounding("\"").replace("\\\"", "\"")
        // 兜底：任意键值里含 refresh_token
        val it = o.keys()
        while (it.hasNext()) {
            val v = o.optString(it.next())
            if (v.contains("refresh_token")) return v
        }
        return ""
    }

    /** 阿里：把登录 iframe 切到「验证码（短信）登录」并打日志 */
    private fun applyAliSmsTab(tag: String) {
        if (site != SiteId.ALI || !aliWantSms) return
        webView.evaluateJavascript(aliSmsJs()) { r ->
            android.util.Log.i("PanPushAliSms", "[$tag] $r")
        }
    }

    /** 从 CookieManager 直接读（覆盖多个域名，`__puus` 可能设在 domain=.quark.cn） */
    private fun currentCookieHeader(): String {
        val urls = when (site) {
            SiteId.QUARK, SiteId.UC -> listOf(
                "https://pan.quark.cn", "https://quark.cn", "https://passport.quark.cn"
            )
            SiteId.BAIDU -> listOf(
                "https://pan.baidu.com", "https://baidu.com", "https://passport.baidu.com"
            )
            else -> listOf(
                "https://${checkHost.ifBlank { "pan.baidu.com" }}"
            )
        }
        val parts = LinkedHashSet<String>()
        for (u in urls) {
            CookieManager.getInstance().getCookie(u)
                ?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?.forEach { parts.add(it) }
        }
        return parts.joinToString("; ")
    }

    /** 抓 localStorage 快照（JSON 字符串），用于 123/阿里 这类 token 存 localStorage 的站点 */
    private fun grabLocalStorage(view: WebView) {
        view.evaluateJavascript(
            "(function(){try{var o={};for(var i=0;i<localStorage.length;i++){" +
                "var k=localStorage.key(i);o[k]=localStorage.getItem(k);}" +
                "PanPushBridge.onLocalStorage(JSON.stringify(o));}catch(e){}})()",
            null
        )
    }

    private inner class LocalStorageBridge {
        @JavascriptInterface
        fun onLocalStorage(json: String) {
            try {
                val o = JSONObject(json)
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    localStorageSnapshot.put(k, o.optString(k))
                }
                // 迅雷：**只打一次** localStorage 的**键名**（值截断），
                // 用来查清「网页登录到底把 token / refresh_token 存在哪」。
                if (site == SiteId.XUNLEI && !xunleiLsLogged && o.length() > 0) {
                    xunleiLsLogged = true
                    val sb = StringBuilder("[迅雷 localStorage] keys=${o.length()}")
                    val kk = o.keys()
                    while (kk.hasNext()) {
                        val k = kk.next()
                        val v = o.optString(k)
                        sb.append("\n  ").append(k).append(" len=").append(v.length)
                        if (v.contains("refresh", true)) sb.append("  ★含refresh")
                        sb.append("  =").append(if (v.length > 40) v.take(40) + "…" else v)
                    }
                    android.util.Log.i("PanPushXlLs", sb.toString())
                    // ★ debug 自测：用 localStorage 里的 refresh_token **实测网页续期通道**，
                    //   验证「非 root 下续有效期」这条路真的通（release 不做这个额外请求）。
                    if (com.panpush.app.BuildConfig.DEBUG) {
                        try {
                            val cred = xunleiTokenValue()
                            val j = JSONObject(cred)
                            val rt = j.optString("refresh_token")
                            android.util.Log.i(
                                "PanPushXlLs",
                                "[自测] localStorage refresh_token 长度=${rt.length}"
                            )
                            if (rt.isNotBlank()) {
                                val store = com.panpush.app.cred.XunleiCredStore(
                                    applicationContext
                                )
                                val n = store.refreshWebToken(rt)
                                android.util.Log.i(
                                    "PanPushXlLs",
                                    if (n == null) {
                                        "[自测] 网页续期失败：${store.lastRefreshError}"
                                    } else {
                                        "[自测] ✓ 网页续期成功！新 access=${n.authorization.length} " +
                                            "新 refresh=${n.refreshToken.length}"
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.i("PanPushXlLs", "[自测] 异常 ${e.message}")
                        }
                    }
                }
                // 迅雷：**静默回填**网页凭据（access / refresh / device_id / **captcha**）。
                //
                // 为什么要在这里做：
                //  · 迅雷网页模式**四样都要**（缺 device_id → "device_id is empty"，
                //    缺 captcha → "captcha_token is empty"）；
                //  · 而 `captcha_<clientId>` **寿命只有几分钟**，会不断失效 ——
                //    但**只要打开一次登录页，页面就会把它刷新**。
                //  → 所以「打开登录页」顺便把最新的一组凭据落盘，
                //    用户解析前点一下登录页就等于「刷新 captcha」。
                if (site == SiteId.XUNLEI && o.length() > 0) {
                    try {
                        val rr = com.panpush.app.cred.WebCookieReader.read(
                            SiteId.XUNLEI, extraBlob = json
                        )
                        val ck = rr.cookie
                        if (!ck.isNullOrBlank()) {
                            fun field(k: String): String =
                                Regex("$k=([^;]+)").find(ck)?.groupValues?.get(1)?.trim().orEmpty()
                            val a = field("authorization")
                            if (a.isNotBlank()) {
                                val store = com.panpush.app.cred.XunleiCredStore(applicationContext)
                                val sig = a.takeLast(24) + "|" + field("captcha_token").takeLast(12)
                                if (sig != lastXunleiSavedSig) {
                                    lastXunleiSavedSig = sig
                                    store.saveWebToken(
                                        a, field("refresh_token"),
                                        field("device_id"), field("captcha_token")
                                    )
                                    android.util.Log.i(
                                        "PanPushXlSave",
                                        "已静默回填迅雷网页凭据：refresh=" +
                                            (if (field("refresh_token").isBlank()) "无" else "有") +
                                            " device=" +
                                            (if (field("device_id").isBlank()) "无" else "有") +
                                            " captcha=" +
                                            (if (field("captcha_token").isBlank()) "无" else "有")
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.i("PanPushXlSave", "回填异常 ${e.message}")
                    }
                }
                // 阿里：localStorage 里一出现 token 就收尾
                // （SPA 登录完不一定再触发 onPageFinished，所以在这里判更及时）
                if (site == SiteId.ALI && !gotToken && hasAliToken()) {
                    runOnUiThread { tryGrabAliTokenOnce() }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 轮询兜底：每 2.5s 检查一次（最多 5 分钟）。
     *
     * 为什么需要：阿里/迅雷的登录页是 **SPA**，登录完成后可能**不再触发 `onPageFinished`**，
     * 只在页面加载时抓一次会漏掉。轮询比依赖页面事件可靠。
     */
    private fun startTokenPolling() {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val start = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                if (gotToken || isFinishing) return
                if (System.currentTimeMillis() - start > 5 * 60_000L) return
                try {
                    grabLocalStorage(webView)
                    tryGrabTokenOnce()
                    tryGrabAliTokenOnce()
                    // 阿里：前 30 秒持续确保 iframe 停在「验证码登录」
                    // （iframe 可能在登录过程中被重建回默认的扫码）
                    if (site == SiteId.ALI && aliWantSms &&
                        System.currentTimeMillis() - start < 30_000L
                    ) {
                        applyAliSmsTab("poll")
                    }
                } catch (_: Exception) {
                }
                h.postDelayed(this, 2500)
            }
        }
        h.postDelayed(r, 3000)
    }

    /** 刷新顶部状态：是否已检测到「登录成功」判据 */
    private fun refreshStatus() {
        val cookie = CookieStore.cookieHeader(checkHost)
        lastCookieHeader = cookie ?: ""
        // ★ 阿里 / 迅雷的令牌在 localStorage，不用 Cookie 判据（见 [cookieIsToken]）
        if (site == SiteId.ALI || site == SiteId.XUNLEI) {
            val raw = lsTokenValue()
            val has = raw.isNotBlank()
            statusView.text = if (has) {
                "✅ 已检测到 ${site.label} 凭据（localStorage）→ 点「完成并保存」即保存" +
                    "（会一并保存 refresh_token，之后可自动续期）"
            } else {
                "请在下方完成登录（验证码 / 密码 / 扫码均可）；" +
                    "登录后会自动识别，也可点「完成并保存」"
            }
            statusView.setTextColor(if (has) 0xFF8FE3B0.toInt() else 0xFFB6BFD0.toInt())
            return
        }
        val loggedIn = checkCookie.isNotBlank() && cookie != null && cookie.contains(checkCookie)
        statusView.text = when {
            loggedIn -> "✅ 已检测到登录（$checkHost 出现 $checkCookie）→ 点「完成并保存」"
            cookie.isNullOrBlank() ->
                "尚未检测到 $checkHost 的 Cookie，请继续完成登录（含跨域跳转）"
            else ->
                "已收到 ${cookie.split(";").size} 个 Cookie，但还没有 $checkCookie；" +
                    "若已登录可直接点「完成并保存」"
        }
        statusView.setTextColor(if (loggedIn) 0xFF8FE3B0.toInt() else 0xFFB6BFD0.toInt())
    }

    private fun finishWithResult() {
        CookieStore.flush()
        // 再抓一次 localStorage，尽量拿到最新的
        try {
            webView.evaluateJavascript(
                "(function(){try{var o={};for(var i=0;i<localStorage.length;i++){" +
                    "var k=localStorage.key(i);o[k]=localStorage.getItem(k);}" +
                    "PanPushBridge.onLocalStorage(JSON.stringify(o));}catch(e){}})()",
                null
            )
        } catch (_: Exception) {
        }
        val cookie = CookieStore.cookieHeader(checkHost) ?: lastCookieHeader
        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(EXTRA_RESULT_COOKIE, cookie ?: "")
                putExtra(EXTRA_RESULT_LOCAL_STORAGE, localStorageSnapshot.toString())
            }
        )
        finish()
    }

    override fun onPause() {
        super.onPause()
        // 坑①：退到后台即 flush，防止进程被杀导致内存 Cookie 丢失
        CookieStore.flush()
    }

    override fun onDestroy() {
        CookieStore.flush()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
