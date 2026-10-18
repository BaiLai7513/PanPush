package com.panpush.app.cred

import com.panpush.app.core.SiteId

/**
 * 站点凭据方案（决定「登录态」页给用户看什么、点什么，以及登录后怎么读回）。
 *
 * ## 为什么必须用「应用内 WebView」登录
 *
 * 外部浏览器 / CustomTabs 的 Cookie 存在**浏览器沙箱**里，本 App 在**另一个沙箱**，
 * 永远读不到 → 「用浏览器登录」这条路**不可能**把登录态存下来，那是设计错误。
 * 所以 App 内只保留**一个登录入口**：`WebViewLoginActivity`（应用内 WebView），
 * 界面里也不再提供「系统浏览器登录」按钮。
 *
 * ## 登录成功判据与令牌来源（真机实测）
 *
 * | 站点 | checkHost | checkCookie | 最终要的令牌 | 内置 WebView 能读回最终令牌吗 |
 * |---|---|---|---|---|
 * | 夸克 / UC | `pan.quark.cn` | `ctoken` | `__puus`（**httpOnly**） | ❌ → root 读夸克浏览器库 / 电脑 F12 |
 * | 百度 | `pan.baidu.com` | `PANWEB` | `BDUSS`（非 httpOnly） | ✅ 能 |
 * | 123 | `www.123pan.com` | `_c_WBKFRo` | APP 的 JWT `token=<JWT>` | ❌ 网页没有这个字段 → root 读 `login_user.xml` |
 * | 阿里 | `www.alipan.com` | `__uid` | `access_token/refresh_token/drive_id` | ❌ → root 读 APP 明文 DB |
 * | 迅雷 | `pan.xunlei.com` | `login` | 三件套（captcha 只能 APP 生成） | ❌ → root 读 shared_prefs |
 * | 蓝奏 | — | — | 无（全匿名） | — |
 *
 * 即：**所有站点都能在内置 WebView 里登录**（Cookie 才落在自己的沙箱里、才可能被读到），
 * 但**只有百度/夸克**能从网页直接拿到最终令牌；其余登录后仍需 root 直读 ——
 * 界面会把这点写清楚，不再让人白点。
 */
object SiteProfile {

    /** 最终令牌从哪里读得到 */
    enum class TokenSource {
        /** 内置 WebView 能直接读回最终令牌 */
        WEB_VIEW,

        /** 内置 WebView 能登录，但最终令牌只能 root 直读本机 App */
        ROOT_APP,

        /** 内置 WebView 能登录，但最终令牌仍需 root 直读 */
        MANUAL,

        /** 该站点不需要凭据 */
        NONE
    }

    /**
     * 取凭据的**通道**（按优先级组合）。
     *
     * 依据 `login-vs-root/应用内登录 vs Root直读 差异与选型.md`（§3 + §6）：
     *
     * > **Root 直读拿的是「目标 App 客户端」签发的 token；WebView 拿的是「网页客户端」
     * > 签发的 token。两者**值不同**，各自只对各自的 API 有效。**
     *
     * 所以通道不能随便挑：
     *
     * | 站点 | 解析器调的 API | 可用通道（优先级） |
     * |---|---|---|
     * | 夸克 | Web API（要 `__puus`） | WEBVIEW → MANUAL（root 读不到，已移除） |
     * | 百度 | 同域 cookie，两者通用 | WEBVIEW → ROOT |
     * | 阿里 | **Web API**（`api.aliyundrive.com`，网页端也用这套） | ROOT → WEBVIEW |
     * | 123 | **App API**（token 里 `platform=3`） | ROOT → WEBVIEW |
     * | 迅雷 | **App API**（captcha 只能由 APP 生成）**但网页 token 也能调** | ROOT → WEBVIEW |
     * |      | 网页 client（`Xqp0kJBXWhwaTpB6`）只发 Bearer + device + captcha | |
     * | 蓝奏 | 免登录（分享页匿名可解析） | （无） |
     *
     * ⚠️ **已去掉手动粘贴通道**（`MANUAL`）：用户明确要求 —— root + 应用内登录
     * 已能覆盖全部需求，多一条手动通道只增加界面复杂度。
     */
    enum class Channel { ROOT, WEBVIEW }

    fun channelLabel(c: Channel): String = when (c) {
        Channel.ROOT -> "root 直读"
        Channel.WEBVIEW -> "应用内登录"
    }

    /**
     * 登录页用哪种 UA。
     *
     * ## 为什么不能无脑用桌面 UA（阿里「人机校验不通过」的根因）
     *
     * 阿里网页版**不按 UA 分流**（实测桌面/安卓 UA 都 200，不像夸克会 302），
     * 所以「必须桌面 UA」对它并不成立。反过来，在**安卓机**上伪装 Windows 桌面
     * 会制造一堆**自相矛盾**的指纹，正好是阿里风控（baxia）要抓的：
     *
     * | 指纹 | 伪装 Windows 桌面时 | 真机实际 |
     * |---|---|---|
     * | `navigator.platform` | 声明 Win32 | `Linux armv8l` |
     * | `navigator.maxTouchPoints` | 桌面应为 0 | > 0（触摸屏） |
     * | `ontouchstart` | 桌面不该有 | 存在 |
     * | WebGL renderer | 桌面 GPU | 移动 GPU |
     *
     * → 于是选了 [ANDROID_CHROME]：UA 与真机一致（且**不含 `wv` 标记**，
     *   那是 Android WebView 的默认特征，同样会被风控识别）。
     *
     * 只有**确实按 UA 分流**的站点才用 [DESKTOP]（夸克：移动 UA 会被 302 到下载页）。
     */
    enum class UaMode {
        /** 手机浏览器 UA（百度用，实测可用） */
        MOBILE,

        /** 桌面 Chrome UA —— Windows（**夸克必须**；阿里网页端也只让桌面登录） */
        DESKTOP,

        /**
         * 桌面 Chrome UA —— Linux（`X11; Linux x86_64`）。
         *
         * 为什么还要一个 Linux 版本：安卓的 `navigator.platform` 是 `Linux armv8l`，
         * 伪装成 Windows 时「UA 说 Windows、platform 说 Linux」是个矛盾点；
         * 伪装成 **Linux 桌面**时 UA 与 platform 都落在 Linux 家族，**矛盾更小**。
         * 两个都留着，登录页上可一键切换 —— 哪个能过人机校验就用哪个。
         */
        DESKTOP_LINUX,

        /** 安卓 Chrome UA（**不含 `wv`**）：与真机一致，适合不拦安卓的站点 */
        ANDROID_CHROME
    }

    data class Profile(
        val site: SiteId,
        /** 令牌存在哪（给用户看的一句话） */
        val tokenWhere: String,
        /** 内置 WebView 登录页地址（null = 该站不给登录入口） */
        val webLoginUrl: String?,
        /** 用系统浏览器打开的地址（仅作「查看站点」用，不用于取 Cookie） */
        val externalUrl: String,
        /** 登录成功判据：域名 */
        val checkHost: String,
        /** 登录成功判据：该域名下出现此 cookie 即视为已登录 */
        val checkCookie: String,
        /**
         * 内置 WebView 能否读回**最终需要的令牌**。
         * false = 能登录，但令牌得靠 root 直读。
         */
        val webViewUseful: Boolean,
        /** 最终令牌来源 */
        val tokenSource: TokenSource,
        /** root 直读来源说明（null = 该站没有本机 App 凭据可读） */
        val localSource: String?,
        /**
         * 登录页是否必须用**桌面 UA**。
         *
         * ⚠️ 真机 + curl 实测（见 `quark-puus/夸克__puus获取实现.md` §1）：
         * 夸克按 UA 分流 —— 桌面 UA 返回 `200` 完整网页版（含「登录」/扫码）；
         * 移动 UA 直接 `302 → h5.sm.cn/blm/mobile-entry`（「下载 App」落地页），
         * **根本到不了登录页**。所以夸克必须桌面 UA，否则永远登不上。
         */
        /**
         * 登录页 UA 模式。
         *
         * ## 默认 [MOBILE]（沿用原来的行为）
         *
         * 只有**确实需要桌面版**的站点才显式改成 [DESKTOP]（夸克/123/迅雷/阿里）：
         *  · 夸克：移动 UA → `302` 到 `h5.sm.cn`「下载 App」页，到不了登录页；
         *  · 阿里：**手机 UA 根本不给登录**（推 App）→ 而且还要开 [Profile.spoofFingerprint]；
         *  · 123 / 迅雷：网盘 Web 的桌面版才是完整站点。
         *
         * [ANDROID_CHROME]（真机同款、不含 `wv`）仅作为「换一种伪装重试」的备选保留。
         */
        val uaMode: UaMode = UaMode.MOBILE,
        /** 桌面 UA 时是否按桌面布局渲染（宽视口，避免挤成一坨） */
        val wideViewport: Boolean = false,

        /**
         * ★ **是否做全套反爬伪装（仅阿里开启）**。
         *
         * 用户明确要求：**只有阿里**需要「三层伪装」——
         * 请求头 Client Hints + JS 指纹 + 布局微调，
         * 因为阿里**手机 UA 根本不给登录、桌面 UA 又卡人机校验**。
         *
         * 其余站点（夸克/百度/123/迅雷）**沿用原来的行为**：
         * 它们实测能正常登录，**不要**给它们注入指纹伪装 ——
         * 那些注入（改 `screen`、`plugins`、`WebGL`、删 `ontouchstart`）
         * 可能干扰站点自身逻辑，属于「没坏就别动」。
         *
         * 开关打开后 [WebViewLoginActivity] 会：
         *  · 给主框架请求带 `Sec-CH-UA-*` 伪装头（[desktopHeaders]）
         *  · 每次页面加载注入 JS 指纹伪装（[stealthJs]）
         *  · 用 W=900 的桌面视口（字号更适合手机，且只在换 URL 时注一次，避免打断滑块）
         */
        val spoofFingerprint: Boolean = false,

        /**
         * ★ **该站点的「令牌」是否就是某个 Cookie**（决定登录页能否「一进页面就自动收尾」）。
         *
         *  · `true`（默认）：夸克 `__puus` / 百度 `BDUSS` / 迅雷 `credentials_*` ——
         *    这些 Cookie **就是令牌本身**，出现即可判定登录完成。
         *  · `false`：**阿里** —— 它的令牌在 `localStorage.token`（不是 Cookie），
         *    而 `ADRIVE_UID` 之类的 Cookie **登录前就可能已存在**（上次登录留下的）。
         *
         * ⚠️ 踩过的坑：曾把阿里的 `checkCookie` 设成 `ADRIVE_UID`，结果
         * **一进登录页就自动 `finish()`**（看起来像「打开就闪退」）——
         * 因为那个 Cookie 早就在了 → 误判「已抓到令牌」。
         * 所以阿里必须 [cookieIsToken] = false，改由 localStorage 判定（见
         * `WebViewLoginActivity.hasAliToken()`）。
         */
        val cookieIsToken: Boolean = true
    ) {
        /** 兼容旧调用：是否用桌面 UA */
        val desktopUa: Boolean
            get() = uaMode == UaMode.DESKTOP || uaMode == UaMode.DESKTOP_LINUX
    }

    /**
     * 夸克 / UC 的共用凭据方案。
     *
     * ## 为什么现在又给夸克「应用内登录」了（推翻早先的结论）
     *
     * 早先按《诊断与改进建议》§6.4 判「夸克不做网页登录」，理由是「手机端只能下载」。
     * 但 `quark-puus/夸克__puus获取实现.md` 给出了真因与解法：
     *  · 「只能下载」是**移动 UA 被 302 到 `h5.sm.cn`** 造成的，**换桌面 UA 就正常**；
     *  · `__puus` 虽是 httpOnly，但 `CookieManager.getCookie()` 是 **Native 接口、
     *    不受 httpOnly 限制**，所以**能读回**。
     *
     * 而 root 直读那三个浏览器 Cookie 库（真机实测 32+9+0 行）里
     * **只有 `b./m.quark.cn`、没有 `pan.quark.cn`、没有 `__puus`** → **root 读是无效的**，已去掉。
     */
    private fun quarkProfile(site: SiteId): Profile = Profile(
        site = site,
        tokenWhere = "需要 __puus —— httpOnly，网页 JS 读不到，但 WebView 的 CookieManager 能读回",
        webLoginUrl = "https://pan.quark.cn/",
        externalUrl = "https://pan.quark.cn/",
        checkHost = "pan.quark.cn",
        // 注意：ctoken 是匿名令牌（不登录也有），判据要认 __puus
        checkCookie = "__puus",
        webViewUseful = true,
        tokenSource = TokenSource.WEB_VIEW,
        // root 读无效（库里没有 pan.quark.cn / __puus），所以不给 localSource
        localSource = null,
        // ★ 夸克**必须**桌面 UA：移动 UA 会被 302 到 h5.sm.cn「下载 App」页（已实测）
        uaMode = UaMode.DESKTOP,
        wideViewport = true
    )

    fun of(site: SiteId): Profile = when (site) {
        // UC 与夸克同源、共用同一套 __puus（且 UC 网盘已收拢到夸克），所以走同一份方案
        SiteId.QUARK, SiteId.UC -> quarkProfile(site)

        SiteId.BAIDU -> Profile(
            site = site,
            tokenWhere = "需要 BDUSS（配合 STOKEN / BAIDUID 更稳）—— 非 httpOnly，网页能拿到",
            // ✅ 唯一保留应用内 WebView 登录的站点：BDUSS 非 httpOnly，登录后能直接读回
            webLoginUrl = "https://pan.baidu.com/",
            externalUrl = "https://pan.baidu.com/",
            checkHost = "pan.baidu.com",
            checkCookie = "PANWEB",
            webViewUseful = true,
            tokenSource = TokenSource.WEB_VIEW,
            localSource = "百度网盘 APP 的 WebView Cookie 库（明文 SQLite，含 BDUSS/STOKEN）",
            // 百度**沿用原来的手机 UA**（实测可用，不动它）→ uaMode 用默认的 MOBILE
        )

        SiteId.PAN123 -> Profile(
            site = site,
            tokenWhere = "App 令牌 `token=<JWT>`（网页端是另一套鉴权；App token 里 platform=3）",
            // ✅ 按 `webview-login-3` 文档开 WebView 登录：**桌面 UA**（手机 UA 会推 App）。
            //    但**首选仍是 root**：123 解析器调的是 App API，而 WebView 拿到的是网页 token
            //    （platform 不同），未必通用 —— 见 `login-vs-root` §3/§5。
            // ⚠️ 必须用 **/login**：首页 `www.123pan.com/` 是营销页，
            //    登录入口藏在右上角，WebView 里很容易找不到 → 表现为「登录有问题」。
            webLoginUrl = "https://www.123pan.com/login",
            externalUrl = "https://www.123pan.com/",
            checkHost = "www.123pan.com",
            checkCookie = "_c_WBKFRo",
            webViewUseful = true,
            tokenSource = TokenSource.ROOT_APP,
            localSource = "123 云盘 APP 的 shared_prefs/login_user.xml → userToken（明文，兜底读 app_flutter/logs）",
            // ★ 123 用 **ANDROID_CHROME**（不是 Windows 桌面，也不是裸 WebView）：
            //   真机现象「登录显示验证码失效」= UA 与 Client Hints 自相矛盾的典型症状
            //   （声明 Windows，但 navigator.platform / sec-ch-ua-platform 都是 Android）。
            //   ANDROID_CHROME 是「真机同款 Chrome，但去掉 `wv` 标记」——
            //   既与 CH 一致，又不像裸 WebView 那样容易被推去「下载 App」。
            //   界面也给了 UA 切换按钮，可现场对比桌面 UA 的效果。
            uaMode = UaMode.ANDROID_CHROME,
            wideViewport = false
        )

        SiteId.ALI -> Profile(
            site = site,
            tokenWhere = "access_token / refresh_token / drive_id —— App 与网页各一套会话",
            // ✅ 按 `aliyun-login`：阿里网页版把 token 放在 **localStorage.token**（JSON），
            //    必须用 evaluateJavascript 读。解析器调的是 **Web API**（`api.aliyundrive.com`），
            //    网页 token 可用，所以给 WebView 入口；但首选仍是 root（App 那套更稳）。
            // ★ 直接用 passport 的 OAuth 登录页并**打开短信登录**
            //   （官网默认 hidePhoneCode=true 只给扫码 —— 见 aliyunOauthUrl 的说明）
            webLoginUrl = aliyunOauthUrl(hidePhoneCode = false),
            externalUrl = "https://www.alipan.com/",
            checkHost = "www.alipan.com",
            // 登录后 www.alipan.com 会下发 ADRIVE_UID（仅供参考/展示用）。
            // ⚠️ 但**不能用它自动收尾**：它登录前就可能存在（上次登录留下的）→
            //    一进页面就误判「已抓到」而 finish（表现为「打开就闪退」）。
            //    所以下面 cookieIsToken = false，收尾改看 localStorage.token。
            checkCookie = "ADRIVE_UID",
            webViewUseful = true,
            tokenSource = TokenSource.ROOT_APP,
            localSource = "阿里云盘 APP 的 databases/0.db → 表 tb_user_info（token 明文）",
            // ★★ 按 `ali-lushuo-对比/阿里云盘-短信登录过滑块-方案.md` **路线 A**：
            //    **走手机版登录页 + 触摸滑块**，而不是桌面 UA。
            //
            //    该文档实测结论：
            //     · 「阿里**手机版登录页**的滑块是**触摸滑块**，手指拖动轨迹自然；
            //        **桌面版**滑块在手机指纹里反而容易被判异常」
            //     · 「实测 alipan 的**手机 UA 均 200**（阿里手机 UA 未被 302 拦走，与夸克不同）」
            //
            //    ★ 更关键的一点（我自己上轮实测出来的硬限制正好被这条绕过）：
            //    桌面 UA 时「UA 说 Windows、`Sec-CH-UA-Platform` 说 Android」这个矛盾
            //    **在应用层改不掉**（内核 UserAgentMetadata 决定的，见 WebViewLoginActivity）；
            //    而**用手机 UA 时这个矛盾根本不存在** —— 环境本来就是真安卓浏览器，
            //    指纹天然自洽，**不需要任何伪装**。这才是「过风控」的正道。
            //
            //    ANDROID_CHROME = 真机同款安卓 Chrome UA（**不含 `wv`**，那不是 WebView 特征）。
            uaMode = UaMode.ANDROID_CHROME,
            // 默认**不做**桌面伪装；只有切成桌面 UA 时才自动开（见 WebViewLoginActivity.autoSpoof）
            wideViewport = false,
            spoofFingerprint = true,
            // 阿里的令牌在 localStorage，不在 Cookie；Cookie 判据会误收尾 → 关掉
            cookieIsToken = false
        )

        SiteId.XUNLEI -> Profile(
            site = site,
            tokenWhere = "App 三件套（Authorization + captcha + device_id）**或**网页 token（只要 Bearer）",
            // ✅ 按 `xunlei-file-test/迅雷应用内登录取文件-测试与方案.md` 开 WebView 登录。
            //    我之前判「网页 token 喂不通」只对了一半：令牌确实不能**混用**，
            //    但**同一个 API 用纯 Bearer（不带 captcha）就能跑** ——
            //    网页 client（Xqp0kJBXWhwaTpB6）的 scope 含 `pan`，网页版自己就这么调。
            //    所以网页登录是**无 root 时的正解**，不是死路。
            webLoginUrl = "https://pan.xunlei.com/",
            externalUrl = "https://pan.xunlei.com/",
            checkHost = "pan.xunlei.com",
            // 网页登录后凭证以 `credentials_<clientId>` 形式存在 cookie 里（值是 URL 编码的 JSON）
            checkCookie = "credentials_",
            webViewUseful = true,
            tokenSource = TokenSource.ROOT_APP,
            localSource = "迅雷 APP 的 shared_prefs（auth_pref.xml / xl-acc-captcha-token.xml / __device_info_new__.xml）",
            // 与所有站点一致：**默认 Windows 桌面**（安卓 UA 实测没用）
            uaMode = UaMode.DESKTOP,
            wideViewport = true
        )

        SiteId.LANZOU -> Profile(
            site = site,
            // ★ 按用户要求**回退为纯免登录**：蓝奏分享链接匿名即可解析，
            //   不需要登录、不需要凭据。登录/包名那套已移除。
            //   （真机实测第三方客户端的凭据也都是加密的，root 读不出来。）
            tokenWhere = "无需凭据（蓝奏全匿名：免登录分享）",
            webLoginUrl = null,
            externalUrl = "https://www.lanzou.com/",
            checkHost = "",
            checkCookie = "",
            webViewUseful = false,
            tokenSource = TokenSource.NONE,
            localSource = null,
        )

        SiteId.UNKNOWN -> Profile(
            site = site,
            tokenWhere = "未知站点",
            webLoginUrl = null,
            externalUrl = "https://www.baidu.com/",
            checkHost = "",
            checkCookie = "",
            webViewUseful = false,
            tokenSource = TokenSource.NONE,
            localSource = null,
        )
    }

    /**
     * 「凭据」页展示顺序。
     *
     * **UC 已去除**（见 `quark-puus/夸克__puus获取实现.md` §7：
     * 「UC 同源但网盘已收拢到夸克，无需单独支持」）——
     * UC 链接仍可解析，但凭据与夸克共用 `cred_quark`，不再单独占一项。
     */
    val orderedSites: List<SiteId> = listOf(
        SiteId.BAIDU, SiteId.PAN123, SiteId.QUARK, SiteId.XUNLEI, SiteId.ALI
    )

    /** 该站点是否给「一键登录（内置浏览器）」入口 */
    fun canLoginInWebView(site: SiteId): Boolean = of(site).webLoginUrl != null

    /** 登录后能不能直接拿到最终令牌 */
    fun webViewYieldsToken(site: SiteId): Boolean = of(site).tokenSource == TokenSource.WEB_VIEW

    /**
     * 该站点**可用凭据通道**，按优先级排列（界面按钮顺序 / 自动尝试顺序都用它）。
     *
     * 依据 `login-vs-root/应用内登录 vs Root直读 差异与选型.md` §6：
     * **优先级 = Root（有 root 且 App 已登录） > WebView 登录**
     * 但每个站点能用的通道不同（见 [Channel] 的表），所以这里按站点算：
     *
     * ⚠️ 手动粘贴通道已移除（用户要求：root + 应用内登录已够用）。
     *
     * @param rootAvailable 本机是否拿到了 root
     */
    fun channelsFor(site: SiteId, rootAvailable: Boolean): List<Channel> {
        val p = of(site)
        if (p.tokenSource == TokenSource.NONE) return emptyList()
        val out = ArrayList<Channel>()
        // ① Root：只有「从本机 App 真能读到该令牌」时才给（夸克不能 —— 库里没有 __puus）
        if (rootAvailable && p.tokenSource == TokenSource.ROOT_APP && p.localSource != null) {
            out.add(Channel.ROOT)
        }
        // ② WebView：只要「网页令牌对本站 API 可用」就给 —— 现在是**五个站点全给**
        //    （夸克网页 token 就是对的那套；迅雷/阿里/123 网页 token 也各自能用，
        //     只是与 App 令牌不是同一个 client，界面会提示「未必通用」）
        if (p.webLoginUrl != null && p.webViewUseful) out.add(Channel.WEBVIEW)
        // 若该站首选 WebView（百度/夸克），把 WebView 提到最前
        if (p.tokenSource == TokenSource.WEB_VIEW) {
            out.sortBy { if (it == Channel.WEBVIEW) 0 else 1 }
        }
        return out
    }

    /**
     * ★★ 阿里登录入口（从 `www.alipan.com` 的前端代码里挖出来的）。
     *
     * ## 为什么不能直接用 `https://www.alipan.com/`
     *
     * `www.alipan.com` 的 `/sign/in` 页面把登录表单放在 **iframe** 里，
     * 而它生成 iframe 地址的代码是（`h5-sign/1.1.4/js/app.js` 的 `genOAuthUrl`）：
     *
     * ```js
     * var n = auth_endpoint + "/v2/oauth/authorize";
     * var i = { client_id, redirect_uri, response_type:"code",
     *           login_type:"custom", state };
     * return n + "?" + qs(i)
     *      + "#/nestedlogin?keepLogin=false&hidePhoneCode=true"   // ← ★ 就是这里
     *      + ad__pass__q__*;
     * ```
     *
     * **`hidePhoneCode=true` 把「手机验证码（短信）登录」藏掉了** —— 这就是
     * 我们在 WebView 里**只看到扫码**的原因（页面自身也只实现了扫码）。
     * 而阿里网页版**密码登录最容易触发人机校验（滑块）**，扫码又需要另一台设备。
     *
     * ## 做法
     *
     * 绕开 SPA，**直接进 passport 的 OAuth 登录页**，并把 `hidePhoneCode` 改成 `false`
     * → 短信登录/密码登录的选项卡就会出现。参数取自 `/sign/in` 页面里的真实 `Global`：
     * ```
     * auth_endpoint = https://auth.aliyundrive.com   （alipan 域替换成 auth.alipan.com）
     * client_id     = 25dzX3vbYqktVxyX               （网页端 client，与 /token/refresh 同一套）
     * redirect_uri  = <origin>/sign/callback         （登录成功后回调，SPA 在此写入 localStorage.token）
     * ```
     *
     * 登录成功后跳 `https://www.alipan.com/sign/callback?code=…`，
     * SPA 换 code 并把 token 写进 `localStorage.token` → 由
     * `MainViewModel.importAliyunFromLocalStorage()` 读回。
     *
     * @param hidePhoneCode true = 只要扫码（官网默认）；false = **显示短信/密码登录**
     */
    fun aliyunOauthUrl(hidePhoneCode: Boolean): String {
        val q = "client_id=$ALI_WEB_CLIENT_ID" +
            "&redirect_uri=" + java.net.URLEncoder.encode("https://www.alipan.com/sign/callback", "UTF-8") +
            "&response_type=code" +
            "&login_type=custom" +
            "&state=" + java.net.URLEncoder.encode(
                "{\"origin\":\"https://www.alipan.com\"}", "UTF-8"
            )
        return "https://auth.alipan.com/v2/oauth/authorize?$q" +
            "#/nestedlogin?keepLogin=false&hidePhoneCode=$hidePhoneCode"
    }

    /** 阿里**网页端** client_id（与 `AliyunCredStore.WEB_CLIENT_ID` 同一套） */
    const val ALI_WEB_CLIENT_ID = "25dzX3vbYqktVxyX"

    /** 迅雷**网页端** client_id（网页登录的 `credentials_<id>` 与续期都用它） */
    const val XUNLEI_WEB_CLIENT_ID = "Xqp0kJBXWhwaTpB6"

    /** 候选项：4 种 UA 模式（界面上的 UA 切换用） */
    val uaModeOrder = listOf(
        UaMode.DESKTOP, UaMode.DESKTOP_LINUX, UaMode.ANDROID_CHROME, UaMode.MOBILE
    )

    /** 该站点首选通道（界面主按钮用） */
    fun primaryChannel(site: SiteId, rootAvailable: Boolean): Channel? =
        channelsFor(site, rootAvailable).firstOrNull()

    /**
     * 该站点「拿凭据有多快」的一句话（界面总览用）。
     *
     * 这是**实测后的排序**（不是拍脑袋）：
     *  · 蓝奏：免登录，0 步
     *  · 夸克：**只能**应用内登录（root 读不到 `__puus`），扫码即得 —— 但因为要扫码，算中等
     *  · 百度：应用内登录 / root 直读都行，两者通用
     *  · 阿里：root 直读最快（App token，免登录）；没 root 走应用内登录
     *  · 123：root 直读最快；网页 token 与 App token `platform` 不同、未必通用
     *  · 迅雷：root 直读最快；网页 token 也能用但 access_token **只有 2 小时**，
     *           captcha 还只有几分钟 → 最容易需要续期
     */
    fun quickNote(site: SiteId, rootAvailable: Boolean): String = when (site) {
        SiteId.LANZOU -> "免登录 · 0 步"
        SiteId.QUARK -> "只能应用内登录（桌面 UA 扫码）· root 读不到"
        SiteId.BAIDU -> if (rootAvailable) "应用内登录 / root 直读 都行（同域，通用）"
        else "应用内登录即可"
        SiteId.ALI -> if (rootAvailable) "最快 root 直读（免登录）· 备选应用内登录"
        else "应用内登录（读 localStorage）"
        SiteId.PAN123 -> if (rootAvailable) "最快 root 直读 · 网页 token 未必通用"
        else "应用内登录（网页 token，需实测）"
        SiteId.XUNLEI -> if (rootAvailable) "最快 root 直读 · token 仅 2 小时，常需续期"
        else "应用内登录（网页 token，只需 Bearer）"
        else -> ""
    }

    // ------------------------------------------------------------------
    // 站点就绪状态（**唯一判据**）
    // ------------------------------------------------------------------

    /** 就绪三态（对齐《诊断与改进建议》§6.3） */
    enum class Readiness { READY, NOT_READY, NO_LOGIN }

    /**
     * 计算某站点的就绪状态。
     *
     * 界面（状态总览）、「一键诊断」的结论行、以及 adb 验收探测**都调用这一个函数** ——
     * 这样不会出现「root 已获得」但站点状态自相矛盾的情况（验收标准 5）。
     *
     * 各站点的「真相来源」不同，这点必须写清楚：
     *  · 免登录（蓝奏）→ [Readiness.NO_LOGIN]
     *  · 百度 / 123 / 夸克 → 看**已保存凭据**（[CredentialValidator.ready] 会校验关键字段是否齐全：
     *    百度要 `BDUSS`、123 要 JWT、夸克要 `__puus`）
     *  · 迅雷 → 每次解析都 root 直读三件套，**不落 SharedPreferences**；
     *          读到且（access 未过期 或 有 refresh_token 可续期）即算就绪
     *  · 阿里 → 同理用 root 直读出来的 token 组，读到 access_token 即就绪
     */
    @Suppress("LongParameterList")
    fun readiness(
        site: SiteId,
        savedCredential: String,
        savedReady: Boolean,
        xunleiReadable: Boolean = false,
        xunleiAccessExpired: Boolean = true,
        xunleiRefreshable: Boolean = false,
        aliyunAccessTokenLength: Int = 0
    ): Readiness {
        if (of(site).tokenSource == TokenSource.NONE) return Readiness.NO_LOGIN
        return when (site) {
            SiteId.XUNLEI -> when {
                !xunleiReadable -> Readiness.NOT_READY
                // access 过期不要紧：只要有 refresh_token 就能续期 → 仍算就绪
                xunleiAccessExpired && !xunleiRefreshable -> Readiness.NOT_READY
                else -> Readiness.READY
            }
            SiteId.ALI -> when {
                aliyunAccessTokenLength > 0 -> Readiness.READY
                savedCredential.isNotBlank() && savedReady -> Readiness.READY
                else -> Readiness.NOT_READY
            }
            else ->
                if (savedCredential.isNotBlank() && savedReady) Readiness.READY
                else Readiness.NOT_READY
        }
    }

    /** 便于界面直接显示的中文标签 */
    fun readinessLabel(r: Readiness): String = when (r) {
        Readiness.READY -> "已就绪"
        Readiness.NOT_READY -> "未就绪"
        Readiness.NO_LOGIN -> "免登录"
    }
}
