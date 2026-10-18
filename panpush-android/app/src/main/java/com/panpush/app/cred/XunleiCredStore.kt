package com.panpush.app.cred

import android.content.Context
import org.json.JSONObject

/**
 * 迅雷云盘凭据（三件套）——对齐 xunlei.py 的凭据层。
 *
 * 为什么迅雷必须 root：官方网页端的 captcha 签名已改为**白盒混淆公钥签名**，
 * 算法不可移植；因此本实现不复现签名，而是直接从迅雷 APP 私有目录 root 读取
 * APP 已经签好的现成凭据。
 *
 * 三件套来源（APP 私有 shared_prefs/）：
 *   Authorization    ← auth_pref.xml，键含 "credential" 的 JSON 里 access_token（Bearer JWT）
 *   X-Captcha-Token  ← xl-acc-captcha-token.xml，键名以 "captchatoken" 结尾（值 ck0. 前缀）
 *   X-Device-Id      ← __device_info_new__.xml 的 device_id_new
 *
 * ⚠️ 易错点（文档明确标注）：
 *   1) captcha 键名后缀 "captchatoken" 长度是 **12**，前缀 = key.dropLast(12)；
 *      用错长度会导致 expiresat 取不到 → 排序失效 → 一直用旧令牌。
 *   2) DeviceId 必须用 APP 的 device_id_new（令牌里绑定了它，换别的会 device not match）。
 *   3) captcha 令牌寿命只有几分钟，且只有 APP 处理「分享深链」时才会刷新。
 */
class XunleiCredStore(private val context: Context) {

    companion object {
        const val PKG = "com.xunlei.downloadprovider"
        const val PREFS = "/data/data/$PKG/shared_prefs"

        const val API_BASE = "https://api-pan.xunlei.com/drive/v1"

        /**
         * access_token 续期端点。真机实测：`api-pan.xunlei.com/drive/v1/auth/token` 是 **404**，
         * 正确的是 xluser-ssl 这个（403/400 说明路径存在、只是参数或 UA 不对）。
         */
        const val AUTH_TOKEN_URL = "https://xluser-ssl.xunlei.com/v1/auth/token"

        /** WEB 端 client id（APP 的 `Xp6vsxz_7IYVw2BB` 亦可，实测两者都过） */
        /** APP 端 client id */
        const val CLIENT_ID = "Xp6vsxz_7IYVw2BB"

        /**
         * **网页端** client id（`pan.xunlei.com` 前端 JS 里的值）。
         *
         * 网页登录拿到的 `refresh_token` 属于这个 client，
         * 续期时要用它（见 [refreshWebToken]）。
         */
        const val WEB_CLIENT_ID = "Xqp0kJBXWhwaTpB6"
        const val CLIENT_VERSION = "1.93.8"
        const val UA_DL = "okhttp/4.9.0"

        /**
         * 续期用的 client_secret。真机实测：这个值配合 `okhttp/4.9.0` UA
         * 能成功换到新 access_token（HTTP 200，expires_in=7200）。
         */
        const val CLIENT_SECRET = "cXfZ8FGVvEQP5Xv1"

        private const val CAPTCHA_SUFFIX = "captchatoken" // 长度 12

        /** ISO8601（'2026-10-11T13:40:31Z'）→ epoch 秒；失败 0 */
        fun parseIso(s: String?): Long {
            if (s.isNullOrBlank() || s.length < 19) return 0
            return try {
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                (fmt.parse(s.substring(0, 19))?.time ?: 0L) / 1000
            } catch (e: Exception) {
                0L
            }
        }
    }

    data class Creds(
        val authorization: String,
        val captchaToken: String,
        /** 秒级时间戳；0 表示未知 */
        val captchaExpiresAtSec: Long,
        val deviceId: String,
        val source: String,
        /** refresh_token（access_token 过期后续期用） */
        val refreshToken: String = "",
        /** access_token 的 expires_at（ISO8601，来自 APP 明文；未知为空） */
        val accessExpiresAt: String = "",
        /**
         * ★ **是否为「网页令牌」模式**（WebView 登录来的）。
         *
         * ⚠️ 以前是用 `captchaToken.isBlank()` **推断**的 —— 但真机实测发现
         * **网页模式同样需要 `X-Captcha-Token`**（localStorage 的
         * `captcha_<clientId>` → `{"token":"ck0.…"}`，缺它 API 报
         * `captcha_invalid / "captcha_token is empty"`）。
         * 一旦网页令牌也带上 captcha，这个推断就失效了 →
         * 必须用**显式标志**区分，否则续期会走错通道。
         */
        val isWeb: Boolean = false
    ) {
        /**
         * 是否网页令牌。**用显式标志**，不再靠「captcha 为空」推断。
         * （两种模式都需要 captcha，所以不能用它区分。）
         */
        val isWebToken: Boolean get() = isWeb

        /**
         * captcha 是否新鲜。
         *
         * 两种模式**都**需要 captcha，所以判据统一：有且未过期才算新鲜。
         * （真机实测：网页模式的 captcha 来自 localStorage 的
         * `captcha_<clientId>`，同样会过期，过期后 API 报 `captcha_invalid`。）
         */
        fun fresh(marginSec: Long = 5): Boolean =
            captchaExpiresAtSec > 0 &&
                captchaExpiresAtSec > (System.currentTimeMillis() / 1000) + marginSec

        fun remainingSec(): Long =
            if (captchaExpiresAtSec <= 0) 0 else captchaExpiresAtSec - System.currentTimeMillis() / 1000

        /**
         * ⚠️ 真机实测：迅雷 APP 里的 `access_token` 只有 **2 小时**有效，
         * 经常**已经过期**（`expires_at` 早于当前时间）。此时必须用 `refresh_token` 续期，
         * 否则接口一律 401 / captcha_invalid —— 这就是「读到了 token 却还是不能用」的原因。
         */
        fun accessExpired(marginSec: Long = 120): Boolean {
            val exp = accessExpiresAtSec()
            if (exp <= 0) return false // 无过期信息 → 当作可用，接口报错再续期
            return exp - System.currentTimeMillis() / 1000 <= marginSec
        }

        /** access_token 剩余秒数；未知返回 -1 */
        fun accessRemainingSec(): Long {
            val exp = accessExpiresAtSec()
            return if (exp <= 0) -1 else exp - System.currentTimeMillis() / 1000
        }

        private fun accessExpiresAtSec(): Long = parseIso(accessExpiresAt)

        /** 从 Bearer JWT 的 payload 里解出 exp（秒），拿不到返回 0 */
        fun accessExpiryFromJwt(): Long {
            val jwt = authorization.removePrefix("Bearer ").trim()
            val parts = jwt.split(".")
            if (parts.size < 2) return 0
            return try {
                val pad = parts[1].padEnd((parts[1].length + 3) / 4 * 4, '=')
                val payload = String(
                    android.util.Base64.decode(pad, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),
                    Charsets.UTF_8
                )
                org.json.JSONObject(payload).optLong("exp", 0L)
            } catch (e: Exception) {
                0L
            }
        }
    }

    class CredsError(message: String) : Exception(message)
    class CaptchaError(message: String) : Exception(message)

    /** 最近一次续期失败原因（诊断/界面显示；空 = 没失败） */
    @Volatile
    var lastRefreshError: String = ""
        private set

    /** 落盘的三件套（root / 应用内登录之外的持久化） */
    private val manualPrefs = context.getSharedPreferences("xunlei_manual", Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Android shared_prefs XML → Map（对齐 _parse_pref，含 html 反转义）
    // ------------------------------------------------------------------
    private fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun parsePref(xml: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("<string name=\"([^\"]+)\">([^<]*)</string>").findAll(xml).forEach {
            out[it.groupValues[1]] = unescape(it.groupValues[2])
        }
        Regex("<(?:long|int|boolean) name=\"([^\"]+)\" value=\"([^\"]*)\"").findAll(xml).forEach {
            out[it.groupValues[1]] = it.groupValues[2]
        }
        return out
    }

    /**
     * 取有效期最长的 captcha 令牌。
     * 键名后缀 'captchatoken' 长度是 12，前缀 = key.dropLast(12)；
     * 用错长度会取不到 expiresat、排序失效、一直用旧令牌。
     */
    private fun latestCaptcha(pref: Map<String, String>): Pair<String?, Long> {
        var bestExp = 0L
        var bestToken: String? = null
        for ((k, v) in pref) {
            if (!k.endsWith(CAPTCHA_SUFFIX) || !v.startsWith("ck0.")) continue
            val prefix = k.dropLast(CAPTCHA_SUFFIX.length)
            val exp = (pref[prefix + "expiresat"] ?: "0").toLongOrNull() ?: 0L
            // expiresat 是毫秒时间戳，这里统一换算成秒
            val expSec = exp / 1000
            if (expSec > bestExp) {
                bestExp = expSec
                bestToken = v
            }
        }
        return bestToken to bestExp
    }

    // ------------------------------------------------------------------
    // root 直读
    // ------------------------------------------------------------------
    /**
     * 经 root 读 APP 三件套。
     *
     * ⚠️ 设计要点（按《诊断与改进建议》§10.4）：**不要先判断「App 是否安装」**，
     * 直接按**文件是否存在**取值；能读到就用，读不到才交给上层降级提示。
     * 另外 `auth_pref.xml` 里的 `access_token` 常常已过期 → 一起把 `refresh_token`
     * 和 `expires_at` 带出去，由 [ensureAccessFresh] 续期。
     */
    fun readAppCreds(): Creds? {
        val capXml = RootShell.readFile("$PREFS/xl-acc-captcha-token.xml") ?: return null
        val devXml = RootShell.readFile("$PREFS/__device_info_new__.xml") ?: return null
        val authXml = RootShell.readFile("$PREFS/auth_pref.xml") ?: return null

        val capPref = parsePref(capXml)
        val devPref = parsePref(devXml)
        val authPref = parsePref(authXml)

        val (captcha, capExp) = latestCaptcha(capPref)
        val deviceId = devPref["device_id_new"]

        // credentials_* 的值是「HTML 转义后的 JSON」，parsePref 已反转义
        var token: String? = null
        var refresh: String = ""
        var expiresAt: String = ""
        for ((k, v) in authPref) {
            if (k.lowercase().contains("credential") && v.trim().startsWith("{")) {
                try {
                    val o = JSONObject(v)
                    token = o.optString("access_token").ifBlank { null }
                    refresh = o.optString("refresh_token")
                    expiresAt = o.optString("expires_at")
                } catch (e: Exception) {
                    // 兜底：直接用正则从原始串里抠（防止转义异常）
                }
                if (token != null) break
            }
        }

        // ✨ 「refresh_token 失效」判定只在**凭据文件真的换了**时才作废。
        //    （不能每次读都复位：读取是高频操作，一复位就会反复打续期接口，
        //      而 refresh_token 失 效后每次都是 400/4126，既慢又容易被风控。）
        //    用户去打开一次迅雷 APP 后，auth_pref.xml 内容会变 → 这里自动放行重试。
        val sig = "${token.orEmpty().length}:${expiresAt}:${refresh.length}"
        if (sig != lastCredSignature) {
            lastCredSignature = sig
            if (refreshTokenDead) logR("凭据文件已更新 → 允许重新尝试续期")
            refreshTokenDead = false
        }

        if (token.isNullOrBlank()) {
            token = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(authXml)?.groupValues?.get(1)
            refresh = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"").find(authXml)?.groupValues?.get(1) ?: refresh
            expiresAt = Regex("\"expires_at\"\\s*:\\s*\"([^\"]+)\"").find(authXml)?.groupValues?.get(1) ?: expiresAt
        }

        if (captcha.isNullOrBlank() || deviceId.isNullOrBlank() || token.isNullOrBlank()) return null
        val bearer = if (token.startsWith("Bearer ")) token else "Bearer $token"
        return Creds(
            authorization = bearer,
            captchaToken = captcha,
            captchaExpiresAtSec = capExp,
            deviceId = deviceId,
            source = "APP 直读（$PREFS）",
            refreshToken = refresh,
            accessExpiresAt = expiresAt
        )
    }

    /**
     * 用 `refresh_token` 换新的 `access_token`（迅雷 access_token 只有 2 小时）。
     *
     * 失败时把**真实原因**记到 [lastRefreshError] 并写 logcat —— 不再出现「点了没反应」。
     */
    fun refreshAccessToken(refreshToken: String): Creds? =
        refreshWith(refreshToken, CLIENT_ID, CLIENT_SECRET)

    /**
     * ★ **网页 client 通道的续期**（非 root 场景靠它把有效期续下去）。
     *
     * 背景（真机实测）：迅雷**网页登录**的凭据不在 cookie，而在
     * `localStorage` 的 **`credentials_<clientId>`**（clientId = `Xqp0kJBXWhwaTpB6`），
     * 其值是 JSON：`{"token_type":"Bearer","access_token":"…","refresh_token":"…"}` ——
     * **含 refresh_token**，所以网页令牌**是可以续期的**。
     *
     * 续期端点与 App 令牌相同（`xluser-ssl.xunlei.com/v1/auth/token`），
     * 但要用**网页 client** 的 id。实测 `CLIENT_ID` 的注释已说明「两者都过」，
     * 所以这里用网页 client id + 同一个 secret 试；失败会原样报错。
     */
    fun refreshWebToken(refreshToken: String): Creds? =
        refreshWith(refreshToken, WEB_CLIENT_ID, CLIENT_SECRET)

    private fun refreshWith(
        refreshToken: String,
        clientId: String,
        clientSecret: String
    ): Creds? {
        lastRefreshError = ""
        if (refreshToken.isBlank()) {
            lastRefreshError = "凭据里没有 refresh_token"
            logR("续期失败：$lastRefreshError")
            return null
        }
        val form = listOf(
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken
        ).joinToString("&") { "${it.first}=${java.net.URLEncoder.encode(it.second, "UTF-8")}" }
        val r = try {
            com.panpush.app.net.Http.postForm(
                AUTH_TOKEN_URL,
                mapOf(
                    // ★★ 绝不能用浏览器 UA：迅雷对浏览器 UA + client_secret 会直接 403
                    //    （"[Danger], Please Do Not Save Your client_secret in browser"）。
                    //    实测用 okhttp/4.9.0 立刻 200 并返回新 access_token（expires_in=7200）。
                    "User-Agent" to UA_DL,
                    "Accept" to "application/json, text/plain, */*"
                ),
                form, 20
            )
        } catch (e: Exception) {
            lastRefreshError = "网络异常：${e.javaClass.simpleName}: ${e.message}"
            logR("续期失败：$lastRefreshError")
            return null
        }
        if (r.status == 0) {
            lastRefreshError = "请求失败：${r.text.take(200)}"
            logR("续期失败：$lastRefreshError")
            return null
        }
        val j = com.panpush.app.net.Js.obj(r.text)
        if (j == null) {
            lastRefreshError = "HTTP ${r.status} 响应不是 JSON：${r.text.take(200)}"
            logR("续期失败：$lastRefreshError")
            return null
        }
        val at = j.optString("access_token")
        if (at.isBlank()) {
            // 真机实测最常见的两种失败原文：
            //   HTTP 400 {"error":"invalid_grant","error_code":4126,
            //             "error_description":"invalid refresh token",
            //             "message":"The refresh token is incorrect or expired"}
            //   HTTP 403 （浏览器 UA + client_secret 会触发迅雷的保护）
            val errCode = j.optInt("error_code", 0)
            val desc = j.optString("error_description").ifBlank { j.optString("error") }
            lastRefreshError = when {
                errCode == 4126 || desc.contains("invalid refresh token", true) -> {
                    refreshTokenDead = true
                    // 分模式给解法：网页令牌**不能**靠「root 读取三件套」修，只能重新登录
                    if (clientId == WEB_CLIENT_ID) {
                        "refresh_token 已失效（error_code 4126）：太久没登录，网页登录这套已作废。\n" +
                            "**解法：点「应用内登录」重新登录一次 pan.xunlei.com**（不需要 root）"
                    } else {
                        "refresh_token 已失效（error_code 4126）。" +
                            "原因：太久没打开迅雷 APP。**解法：打开一次迅雷 APP（它自己会续期），" +
                            "再回来点「root 读取三件套」重新读取**"
                    }
                }
                r.status == 403 ->
                    "HTTP 403 被迅雷拦截（UA/风控）。若反复出现请反馈，接口原文：" + desc
                else ->
                    "HTTP ${r.status} 未返回 access_token：" + desc.ifBlank { r.text.take(200) }
            }
            logR("续期失败：$lastRefreshError")
            return null
        }
        // 响应只给 expires_in（秒）→ 本地换算 expires_at，便于落盘后仍能判断新鲜度
        val inSec = j.optLong("expires_in", 0L)
        val expiresAt = if (inSec > 0) {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            fmt.format(java.util.Date(System.currentTimeMillis() + inSec * 1000))
        } else ""
        val out = Creds(
            authorization = "Bearer $at",
            captchaToken = "",              // 续期只换 access_token；captcha 由调用方补回
            captchaExpiresAtSec = 0L,
            deviceId = "",
            source = "refresh_token 续期成功",
            refreshToken = j.optString("refresh_token").ifBlank { refreshToken },
            accessExpiresAt = expiresAt
        )
        saveRefreshed(out)
        logR("续期成功：新 access_token 剩余 ${out.accessRemainingSec()}s")
        return out
    }

    private fun logR(msg: String) {
        try {
            android.util.Log.i("PanPushXunlei", msg)
        } catch (_: Exception) {
        }
    }

    /** 把续期后的 access_token 落盘（下次冷启动直接可用，不必再续期） */
    private fun saveRefreshed(c: Creds) {
        manualPrefs.edit()
            .putString("refreshed_authorization", c.authorization)
            .putString("refreshed_expires_at", c.accessExpiresAt)
            .putString("refreshed_refresh_token", c.refreshToken)
            .apply()
    }

    /** 读取上次续期落盘的 access_token（若仍新鲜） */
    private fun readRefreshed(): Creds? {
        val auth = manualPrefs.getString("refreshed_authorization", "") ?: ""
        if (auth.isBlank()) return null
        val c = Creds(
            authorization = auth,
            captchaToken = "",
            captchaExpiresAtSec = 0L,
            deviceId = "",
            source = "本地续期缓存",
            refreshToken = manualPrefs.getString("refreshed_refresh_token", "") ?: "",
            accessExpiresAt = manualPrefs.getString("refreshed_expires_at", "") ?: ""
        )
        // 续期缓存只有 ≤2 小时有效，过期就当没有
        return if (c.accessExpired(marginSec = 300)) null else c
    }

    /**
     * 保证 `access_token` 新鲜。
     *
     * 关键：迅雷 APP 里的 access_token 只有 **2 小时**，实测经常已经过期。
     * 这里用 `refresh_token` 续期（续期成功后 [refreshAccessToken] 会落盘，
     * 所以下一次冷启动能直接用「本地续期缓存」，不必重复续期）。
     * 续期失败时原样返回（让上层用旧 token 试一次，报错更真实）。
     */
    /**
     * 续期失败时原样返回（让上层用旧 token 试一次，报错更真实）。
     *
     * ⚠️ 一旦确认 `refresh_token` 已失效（error_code 4126），就**不再反复打接口** ——
     * 真机实测：refresh_token 失效后每次续期都是 400/4126，反复请求既慢又容易被风控。
     * 这时唯一解法是**打开一次迅雷 APP 让它自己续期**，所以直接短路并给出提示。
     */
    fun ensureAccessFresh(c: Creds): Creds {
        if (!c.accessExpired()) return c
        // ★ **网页令牌走网页 client 通道续期**（非 root 场景的关键）。
        //   网页令牌属于 client `Xqp0kJBXWhwaTpB6`，**不能**用 App 的 client 去刷
        //   （混用会失败，还可能把 refresh_token 判死 —— 见 `login-vs-root` §3）。
        //   网页 localStorage 里的 `credentials_<clientId>` **含 refresh_token**，所以能续。
        if (c.isWebToken) {
            if (c.refreshToken.isBlank()) return c   // 没有 refresh_token → 只能重新登录
            val w = try {
                refreshWebToken(c.refreshToken)
            } catch (e: Exception) {
                null
            } ?: return c
            // 续期成功后把新令牌写回网页令牌槽（保留 captcha=空 即仍是网页模式）
            saveWebToken(w.authorization, w.refreshToken.ifBlank { c.refreshToken })
            return w.copy(source = "网页 token 续期")
        }
        // refresh_token 已经确认死了 → 不再重试，等用户去开 APP
        if (refreshTokenDead) return c
        // 先看有没有仍新鲜的续期缓存（避免每次冷启动都打一次续期）
        readRefreshed()?.let { cached ->
            return cached.copy(
                captchaToken = c.captchaToken,
                captchaExpiresAtSec = c.captchaExpiresAtSec,
                deviceId = c.deviceId
            )
        }
        val n = refreshAccessToken(c.refreshToken) ?: return c
        return n.copy(
            captchaToken = c.captchaToken,
            captchaExpiresAtSec = c.captchaExpiresAtSec,
            deviceId = c.deviceId
        )
    }

    /** `refresh_token` 是否已被判定失效（4126）；凭据文件变化时自动复位 */
    @Volatile
    var refreshTokenDead: Boolean = false
        private set

    /** 最近一次读到凭据的指纹（用于判断「用户是否重新登录过」） */
    @Volatile
    private var lastCredSignature: String = ""

    /** 续期是否可能成功（有 refresh_token 就行） */
    fun canRefresh(c: Creds): Boolean = c.refreshToken.isNotBlank()

    /** 迅雷 APP 是否装了（**仅用于文案**，不再用作流程开关） */
    fun appInstalled(): Boolean =
        RootShell.exists("/data/data/$PKG") || RootShell.exists(PREFS)

    /**
     * 诊断：精确区分「su 不可用」「App 没装 / 没数据」「读到了但过期」三类情况。
     * 对应《诊断与改进建议》§7 P1-5：别把「找不到 App」和「su 被拒」混成一句话。
     */
    fun diagnose(): String {
        if (!RootShell.hasSu()) return "su 不可用：设备未 root，或 APatch/Magisk 对本应用隐藏了 su"
        val files = listOf(
            "auth_pref.xml", "xl-acc-captcha-token.xml", "__device_info_new__.xml"
        )
        val got = files.filter { RootShell.readFile("$PREFS/$it") != null }
        if (got.isEmpty()) {
            return "su 可用，但 $PREFS 下读不到迅雷凭据文件" +
                "（迅雷未安装，或装了但从未登录过）"
        }
        return "su 可用，已读到 ${got.size}/${files.size} 个凭据文件：${got.joinToString(", ")}"
    }

    // ------------------------------------------------------------------
    // 手动落盘（无 root 兜底）
    // ------------------------------------------------------------------
    fun readManualCreds(): Creds? {
        val auth = manualPrefs.getString("authorization", "") ?: ""
        val cap = manualPrefs.getString("captcha_token", "") ?: ""
        val dev = manualPrefs.getString("device_id", "") ?: ""
        if (auth.isBlank() || cap.isBlank() || dev.isBlank()) return null
        val c = Creds(
            authorization = if (auth.startsWith("Bearer ")) auth else "Bearer $auth",
            captchaToken = cap,
            captchaExpiresAtSec = (manualPrefs.getString("captcha_expiresat", "0") ?: "0").toLongOrNull() ?: 0L,
            deviceId = dev,
            source = "已保存"
        )
        // 没有 expires_at 时，从 JWT 的 exp 里解一个出来（便于判断是否需要续期）
        val exp = c.accessExpiryFromJwt()
        return if (exp > 0) {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            c.copy(accessExpiresAt = fmt.format(java.util.Date(exp * 1000)))
        } else c
    }

    fun saveManualCreds(authorization: String, captchaToken: String, deviceId: String, expiresAtSec: Long) {
        manualPrefs.edit()
            .putString("authorization", authorization.trim())
            .putString("captcha_token", captchaToken.trim())
            .putString("device_id", deviceId.trim())
            .putString("captcha_expiresat", expiresAtSec.toString())
            .apply()
    }

    fun clearManualCreds() {
        manualPrefs.edit().clear().apply()
    }

    // ------------------------------------------------------------------
    // 网页令牌（WebView 登录，见 xunlei-file-test 文档）
    // ------------------------------------------------------------------
    /**
     * 落盘**网页令牌**（WebView 登录 `pan.xunlei.com` 后从 **localStorage 的
     * `credentials_<clientId>`**（真机实测位置）抠出的 `access_token` / `refresh_token`）。
     *
     * ## ⚠️ 纠错：网页令牌**也需要 `X-Device-Id`**
     *
     * 之前这里写「网页 client 自己处理验证码，调 API **只要 Bearer**」—— **实测是错的**：
     * 缺 `device_id` 时 `api-pan.xunlei.com` 直接
     * `400 invalid_argument / detail: "device_id is empty"`。
     * 网页登录会在 localStorage/cookie 里放 `deviceid`（`wdi10.…`），
     * 所以这里也要把它存下来，由 `XunleiParser.headers()` 在网页模式下发出。
     *
     * 存在独立的 prefs 键里，**不覆盖** App 三件套；`load()` 里 App 优先。
     */
    fun saveWebToken(
        authorization: String,
        refreshToken: String,
        deviceId: String = "",
        captchaToken: String = ""
    ) {
        // ★★ **防止「旧令牌覆盖新令牌」**（真机踩到）：
        //   静默回填读的是**页面的 localStorage**，而刷新后页面里可能还留着**旧令牌**
        //   （迅雷刷新会吊销旧的）。如果不加判断直接写，就会把刚续期拿到的新令牌
        //   覆盖回已吊销的旧令牌 → 接口 401 `oidc: token is expired`。
        //   规则：**谁的 access_token 过期时间更晚就保留谁**；captcha/device 始终更新。
        val curAuth = manualPrefs.getString("web_authorization", "") ?: ""
        val curRt = manualPrefs.getString("web_refresh_token", "") ?: ""
        var keepAuth = authorization.trim()
        var keepRt = refreshToken.trim()
        if (curAuth.isNotBlank()) {
            fun expOf(a: String): Long = try {
                Creds(
                    authorization = a,
                    captchaToken = "",
                    captchaExpiresAtSec = 0L,
                    deviceId = "",
                    source = "",
                    refreshToken = ""
                ).accessExpiryFromJwt()
            } catch (e: Exception) {
                0L
            }
            val curExp = expOf(curAuth)
            val newExp = expOf(keepAuth)
            if (curExp > newExp) {
                // 旧（已存）的那个更晚过期 → 保留它；refresh 也保留旧的（与新 access 配套）
                keepAuth = curAuth
                keepRt = curRt
                logR("回填：新令牌比已存的更旧（cur=$curExp new=$newExp）→ 保留已存的，只更新 captcha/device")
            }
        }
        val e = manualPrefs.edit()
            .putString("web_authorization", keepAuth)
            .putString("web_refresh_token", keepRt)
            .putBoolean("web_mode", true)
            .putLong("web_saved_at", System.currentTimeMillis())
        if (deviceId.isNotBlank()) e.putString("web_device_id", deviceId.trim())
        // ★ 网页模式**也要 captcha**（缺它 → captcha_invalid / "captcha_token is empty"）
        if (captchaToken.isNotBlank()) e.putString("web_captcha_token", captchaToken.trim())
        e.apply()
    }

    fun readWebToken(): Creds? {
        val auth = manualPrefs.getString("web_authorization", "") ?: ""
        if (auth.isBlank()) return null
        // ★ device_id 兜底回填：老记录（本修复之前保存的）没有 device_id，
        //   但网页登录早已把 `deviceid` 写进了 cookie（`wdi10.…`）——
        //   这里直接从我们自己的 CookieManager 取回来并落盘，
        //   **不需要用户重新登录**。
        var dev = manualPrefs.getString("web_device_id", "") ?: ""
        if (dev.isBlank()) {
            dev = readDeviceIdFromCookie()
            if (dev.isNotBlank()) {
                manualPrefs.edit().putString("web_device_id", dev).apply()
            }
        }
        val c = Creds(
            authorization = if (auth.startsWith("Bearer ")) auth else "Bearer $auth",
            // ★ 网页模式**也要 captcha**（来自 localStorage 的 `captcha_<clientId>`）
            captchaToken = manualPrefs.getString("web_captcha_token", "") ?: "",
            captchaExpiresAtSec = 0L,
            deviceId = dev,
            source = "应用内网页登录",
            refreshToken = manualPrefs.getString("web_refresh_token", "") ?: "",
            // ★ 显式标志（不再靠 captcha 是否为空推断）
            isWeb = true
        )
        val exp = c.accessExpiryFromJwt()
        return if (exp > 0) {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            c.copy(accessExpiresAt = fmt.format(java.util.Date(exp * 1000)))
        } else c
    }

    /**
     * 从**我们自己 WebView 的 cookie** 里取 `deviceid`（`wdi10.…`）。
     *
     * 迅雷网页登录会把它写在 `pan.xunlei.com` / `.xunlei.com` 上；
     * 调 `api-pan.xunlei.com` 时必须作为 `X-Device-Id` 发出（否则 400 `device_id is empty`）。
     * `CookieManager.getCookie` 是本地读，**不需要 root、也不需要重新登录**。
     */
    private fun readDeviceIdFromCookie(): String = try {
        val cm = android.webkit.CookieManager.getInstance()
        val urls = listOf(
            "https://pan.xunlei.com", "https://xunlei.com", "https://i.xunlei.com"
        )
        var found = ""
        for (u in urls) {
            val c = cm.getCookie(u) ?: continue
            val m = Regex("(?:^|;\\s*)deviceid=([^;]+)").find(c)
            if (m != null) {
                found = m.groupValues[1].trim()
                if (found.isNotBlank()) break
            }
        }
        found
    } catch (e: Exception) {
        ""
    }

    fun clearWebToken() {        manualPrefs.edit()
            .remove("web_authorization")
            .remove("web_refresh_token")
            .remove("web_saved_at")
            .apply()
    }

    fun hasWebToken(): Boolean = readWebToken() != null

    /**
     * 取凭据：**按「可用性」挑，而不是死按来源优先级**。
     *
     * ## 为什么要改（真机踩到）
     *
     * 原来写死 `readAppCreds() ?: readWebToken() ?: readManualCreds()` ——
     * 只要 root 能读到 App 三件套就**永远用它**，哪怕那个 access_token 已经过期
     * （App 的 token 只有 2 小时，`readAppCreds()` 本身不续期），
     * 结果把**明明可用的网页令牌挡住了** →
     * 表现就是「应用内登录拿到了，但解析用的还是那套过期的」（用户反馈的现象）。
     *
     * ## 现在的顺序
     *
     * 1. **未过期**的凭据（App / 网页 / 手动，谁的 access 还新鲜就用谁）；
     * 2. 都过期 → 优先**带 refresh_token** 的（能自动续）；
     * 3. 再不行才退回第一个。
     */
    fun load(): Creds? {
        val candidates = listOfNotNull(readAppCreds(), readWebToken(), readManualCreds())
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { !it.accessExpired() }?.let { return it }
        candidates.firstOrNull { it.refreshToken.isNotBlank() }?.let { return it }
        return candidates.first()
    }

    /**
     * 给解析器用的完整取用：读到就把过期的 access_token 续期。
     * 这样「读到了 token 却还是不能用」的情况（access_token 过期）能自动解决。
     */
    fun loadReady(): Creds? {
        val c = load() ?: return null
        return try {
            ensureAccessFresh(c)
        } catch (e: Exception) {
            c
        }
    }

    // ------------------------------------------------------------------
    // captcha 刷新：用分享深链把 APP 拉到前台，触发它申请新令牌
    // ------------------------------------------------------------------
    /**
     * 触发 APP 刷新 captcha。
     * 只启动 APP 首页**不会**刷新，必须让它处理一次分享深链。
     * 约 6~10s 后 xl-acc-captcha-token.xml 出现新条目。
     */
    fun refreshCaptcha(shareUrl: String?, waitMs: Long = 6000, tries: Int = 3): Boolean {
        if (shareUrl.isNullOrBlank()) return false
        val old = readAppCreds()?.captchaToken
        repeat(tries) {
            // 深链会路由到迅雷；加 -p 更精确，但个别 ROM 会因此启动失败，故失败后去掉 -p 重试一次
            val withPkg = buildString {
                append("am start --user 0 -a android.intent.action.VIEW ")
                append("-d ${RootShell.shQuote(shareUrl)} ")
                append("-p $PKG")
            }
            val noPkg = buildString {
                append("am start --user 0 -a android.intent.action.VIEW ")
                append("-d ${RootShell.shQuote(shareUrl)}")
            }
            // 走全局命名空间（app 的受限视图里 am 仍可用，但统一走 execGlobal 更稳）
            var r = RootShell.execGlobal(withPkg, 20)
            if (r.combined.contains("Error", true) || r.combined.contains("Exception", true)) {
                r = RootShell.execGlobal(noPkg, 20)
            }
            // am 本身不可用（没 root / 无 am）就别白等
            if (r.combined.contains("not found", true) || r.combined.contains("inaccessible", true)) {
                return false
            }
            Thread.sleep(waitMs)
            val now = readAppCreds()?.captchaToken
            if (!now.isNullOrBlank() && now != old) return true
        }
        return false
    }
}
