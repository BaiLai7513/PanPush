package com.panpush.app.cred

import com.panpush.app.core.SiteId
import com.panpush.app.webview.CookieStore

/**
 * 从**本应用自己的** WebView CookieStore 读取凭据。
 *
 * ## 关键：`CookieManager.getCookie()` 是 **Native 接口，不受 httpOnly 限制**
 *
 * 这一点我一开始判断错了（曾以为「httpOnly 读不到」）。正确的是：
 *  · 网页 JS（`document.cookie`）读不到 httpOnly；
 *  · 但 `CookieManager.getCookie(url)` **读得到**。
 *
 * 所以「网页读不到」的真正原因**从来不是 httpOnly**，而是**令牌根本不在网页这一侧**：
 *  · 夸克 `__puus` → 在网页 cookie 里，**能读到**（已真机验证：cred_quark 含 __puus）
 *  · 百度 `BDUSS` → 非 httpOnly，能读到 ✅
 *  · **迅雷** → 网页登录后凭证以 `credentials_<clientId>` 存在 cookie（值是 URL 编码的 JSON）
 *    → 解析出 `access_token` 即可（见 [extractXunlei]）
 *  · 123 → 网页 token 与 App token 的 `platform` 不同，未必通用
 *
 * 运行期走 `CookieManager`（[CookieStore]）；离线/兜底可走 `WebViewInspector` 直读自己的
 * `app_webview/Default/Cookies`（**读自己不需要 root**）。
 */
object WebCookieReader {

    /** 迅雷网页端 client id（`pan.xunlei.com` 前端 JS 里的值） */
    private const val XUNLEI_WEB_CLIENT = "Xqp0kJBXWhwaTpB6"

    /** 站点 → 该站点可能存放登录态的 URL（`getCookie` 按 URL 聚合子域） */
    private fun urls(site: SiteId): List<String> = when (site) {
        SiteId.QUARK, SiteId.UC -> listOf(
            "https://pan.quark.cn/",
            "https://drive-pc.quark.cn/",
            "https://drive.uc.cn/",
            "https://uc.cn/"
        )
        SiteId.BAIDU -> listOf(
            "https://pan.baidu.com/",
            "https://passport.baidu.com/",
            "https://baidu.com/"
        )
        SiteId.PAN123 -> listOf(
            "https://www.123pan.com/",
            "https://www.123pan.cn/"
        )
        SiteId.ALI -> listOf(
            "https://www.alipan.com/",
            "https://www.aliyundrive.com/",
            "https://passport.aliyundrive.com/"
        )
        SiteId.XUNLEI -> listOf(
            "https://pan.xunlei.com/",
            "https://xunlei.com/",
            "https://i.xunlei.com/",
            "https://xluser-ssl.xunlei.com/"
        )
        SiteId.LANZOU -> emptyList()
        SiteId.UNKNOWN -> emptyList()
    }

    private fun keyOf(site: SiteId): String = when (site) {
        SiteId.QUARK, SiteId.UC -> "__puus"
        SiteId.BAIDU -> "BDUSS"
        else -> ""
    }

    private val orderOf = mapOf(
        SiteId.QUARK to listOf("__puus", "__pus", "__kp", "__uid", "b-user-id"),
        SiteId.UC to listOf("__puus", "__pus", "__kp", "__uid", "b-user-id"),
        SiteId.BAIDU to listOf("BDUSS", "STOKEN", "BAIDUID", "BDUSS_BFESS", "PTOKEN", "SAPTOKEN")
    )

    /** 一次性读取结果 + 可读诊断（失败时给准确原因，而不是干巴巴「没找到」） */
    data class ReadResult(
        val cookie: String?,
        val site: SiteId,
        /** 本应用 CookieStore 里该站点的字段名（已排序去重） */
        val seenNames: List<String>,
        /** 缺少的关键令牌名（无则为空） */
        val missingKey: String?,
        /** 该站点的关键令牌是否属于「网页读不到」的类型（httpOnly / 根本不是同一套鉴权） */
        val webUnreachable: Boolean
    ) {
        fun message(): String = when {
            cookie != null -> "已从内置浏览器读取 ${site.label} 登录态（${seenNames.size} 个字段）"
            webUnreachable && site == SiteId.QUARK ->
                "内置浏览器读不到 __puus：它是 **httpOnly** Cookie，网页侧拿不到。" +
                    "请改用「应用内登录」（桌面 UA 可拿到 __puus）或「root 直读」（读夸克浏览器明文 Cookie 库）。"
            webUnreachable && site == SiteId.PAN123 ->
                "123 云盘要的是 **APP 的登录 token**，网页端 Cookie 里没有这个字段。" +
                    "请改用「应用内登录」或「root 直读」（读 APP 的 login_user.xml）。"
            webUnreachable ->
                "${site.label} 的登录态无法从网页获取，请改用「root 直读」或「应用内登录」。"
            missingKey != null && seenNames.isEmpty() ->
                "内置浏览器里没有任何 ${site.label} 的 Cookie → 还没在内置页面登录成功（登录后请点「完成并保存」）。"
            missingKey != null ->
                "内置浏览器里有 ${seenNames.size} 个字段（${seenNames.take(6).joinToString(", ")}），" +
                    "但缺少关键令牌 $missingKey → 登录可能没成功，或该令牌是 httpOnly。"
            else -> "没读到可用登录态。"
        }
    }

    /** 读取并拼装站点 Cookie；一并返回诊断信息 */
    fun read(site: SiteId, extraBlob: String? = null): ReadResult {
        val map = LinkedHashMap<String, String>()
        for (u in urls(site)) {
            val c = CookieStore.cookieHeader(u) ?: continue
            for (part in c.split(";")) {
                val p = part.trim()
                val i = p.indexOf('=')
                if (i <= 0) continue
                val name = p.substring(0, i).trim()
                val value = p.substring(i + 1).trim()
                if (value.isNotEmpty()) map.putIfAbsent(name, value)
            }
        }
        val key = keyOf(site)
        val webUnreachable = !SiteProfile.webViewYieldsToken(site)

        if (site == SiteId.PAN123) {
            return ReadResult(null, site, map.keys.sorted(), "token", webUnreachable = true)
        }
        // ★ 迅雷：凭证不在固定键里，而是 `credentials_<clientId>`（值是 URL 编码的 JSON）；
        //    不同版本也可能放在 localStorage，所以把 extraBlob 一起并进搜索串
        if (site == SiteId.XUNLEI) {
            val cred = extractXunlei(map, extraBlob)
            if (cred == null) {
                return ReadResult(
                    null, site, map.keys.sorted(), "credentials_/access_token",
                    webUnreachable = false
                )
            }
            return ReadResult(cred, site, map.keys.sorted(), null, webUnreachable = false)
        }
        if (key.isEmpty()) {
            return ReadResult(null, site, map.keys.sorted(), null, webUnreachable)
        }
        if (!map.containsKey(key)) {
            return ReadResult(null, site, map.keys.sorted(), key, webUnreachable)
        }
        val parts = ArrayList<String>()
        for (n in orderOf[site].orEmpty()) map[n]?.let { parts.add("$n=$it") }
        // 补上其它同域字段（避免漏掉风控字段）
        for ((n, v) in map) if (parts.none { it.startsWith("$n=") }) parts.add("$n=$v")
        return ReadResult(parts.joinToString("; "), site, map.keys.sorted(), null, webUnreachable)
    }

    /** 读取并拼装站点 Cookie；没有关键令牌时返回 null */
    fun readCookie(site: SiteId): String? = read(site).cookie

    /** 该站点当前是否已有登录判据（用 SiteProfile 的 checkHost/checkCookie） */
    fun hasLogin(site: SiteId): Boolean {
        val p = SiteProfile.of(site)
        if (p.checkHost.isBlank() || p.checkCookie.isBlank()) return false
        return CookieStore.hasCookie(p.checkHost, p.checkCookie)
    }

    /** 本应用 CookieStore 里是否存在该站点的**任何** Cookie */
    fun hasAnyCookie(site: SiteId): Boolean {
        for (u in urls(site)) {
            val c = CookieStore.cookieHeader(u)
            if (!c.isNullOrBlank()) return true
        }
        return false
    }

    fun flush() = CookieStore.flush()

    /**
     * 从迅雷网页的 cookie（+ localStorage）里抠出 `access_token`（网页令牌模式）。
     *
     * 依据 `xunlei-file-test/迅雷应用内登录取文件-测试与方案.md` §1.3 / §3.2
     * 与其参考实现 `XunleiLoginActivity.kt` 的 `parseAccessToken()`：
     *
     *  1. 凭证以 **`credentials_<clientId>`**（clientId = `Xqp0kJBXWhwaTpB6`）
     *     存在 cookie 里，**值是 URL 编码的 JSON**；
     *     但**不同版本也可能放 localStorage** → 所以要把 localStorage dump 一起搜
     *     （参考实现就是 `cookie + " " + localStorage` 拼起来再正则）。
     *  2. 顺序：`"access_token"` → `"accessToken"`（**驼峰变体**）→ 裸 JWT。
     *  3. 全程先做 **URL 解码**（`%7B` → `{`）。
     *
     * @param map 各域名的 cookie 键值
     * @param extraBlob localStorage 的 JSON dump（可为 null）
     * @return `"authorization=Bearer <at>[; refresh_token=<rt>]"`；抠不到返回 null
     */
    private fun extractXunlei(map: Map<String, String>, extraBlob: String? = null): String? {
        // ① 优先网页 client，其次任意 credentials_*
        val preferredKey = "credentials_$XUNLEI_WEB_CLIENT"
        val rawKey = if (map.containsKey(preferredKey)) {
            preferredKey
        } else {
            map.keys.firstOrNull { it.startsWith("credentials_") }
        }
        val raw = rawKey?.let { map[it] }.orEmpty()

        // ★★ **真机实测**：迅雷网页登录的凭据其实存在 **localStorage**，
        //    键就是 `credentials_Xqp0kJBXWhwaTpB6`（值是一包 JSON：
        //    `{"token_type":"Bearer","access_token":"…","refresh_token":"…"}`），
        //    而**不在 cookie 里**（cookie 只有 sessionid/userid 等 6 个）。
        //    所以这里**先**把 localStorage 快照按 JSON 解析，能拿到最完整的两个令牌。
        val fromLs = parseCredentialsJson(extraBlob, preferredKey)
        if (fromLs != null) return fromLs

        // ② 兜底：把 cookie 全部值 + localStorage 拼成一个整串再正则搜
        //    （有些版本凭证落在别的键或 cookie 里）
        val blob = buildString {
            if (raw.isNotBlank()) append(raw)
            map.forEach { (k, v) -> if (k != rawKey) append(' ').append(k).append('=').append(v) }
            if (!extraBlob.isNullOrBlank()) append(' ').append(extraBlob)
        }
        if (blob.isBlank()) return null
        val decoded = try {
            java.net.URLDecoder.decode(blob, "UTF-8")
        } catch (e: Exception) {
            blob
        }
        // ③ `access_token` → `accessToken`（驼峰）→ 裸 JWT（三级兜底）
        val at = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
            ?: Regex("\"accessToken\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
            ?: Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                .find(decoded)?.value
            ?: return null
        val rt = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
            ?: Regex("\"refreshToken\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
            ?: ""
        val token = if (at.startsWith("Bearer ", true)) at else "Bearer $at"
        // device_id 兜底：从 `deviceid` 字段/键里抠（`wdi10.…`）
        val dev = Regex("\"device_?[Ii]d\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1)
            ?: Regex("deviceid\\s*[=:\"]+\\s*\"?(wdi10\\.[A-Za-z0-9]+)").find(decoded)?.groupValues?.get(1)
            ?: ""
        return buildString {
            append("authorization=").append(token)
            if (rt.isNotBlank()) append("; refresh_token=").append(rt)
            if (dev.isNotBlank()) append("; device_id=").append(dev)
        }
    }

    /**
     * 从 localStorage 快照里解析 **`credentials_<clientId>`**（值是一包 JSON）。
     *
     * 这是迅雷网页登录凭据的**真实位置**（真机实测），
     * 解析出来能同时拿到 `access_token` **和 `refresh_token`** ——
     * 后者是「非 root 下能续期」的前提。
     *
     * @return `"authorization=Bearer …; refresh_token=…"`；解析不到返回 null
     */
    private fun parseCredentialsJson(extraBlob: String?, preferredKey: String): String? {
        if (extraBlob.isNullOrBlank()) return null
        val root = try {
            org.json.JSONObject(extraBlob)
        } catch (e: Exception) {
            return null
        }
        // 优先网页 client 的键；否则任意 credentials_*
        val key = if (root.has(preferredKey)) {
            preferredKey
        } else {
            root.keys().asSequence().firstOrNull { it.startsWith("credentials_") }
        } ?: return null
        var raw = root.optString(key)
        if (raw.isBlank()) return null
        // 值可能是被转义/带引号的 JSON 串
        raw = raw.trim().removeSurrounding("\"").replace("\\\"", "\"")
        val j = try {
            org.json.JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        val at = j.optString("access_token").ifBlank { j.optString("accessToken") }
        if (at.isBlank()) return null
        val rt = j.optString("refresh_token").ifBlank { j.optString("refreshToken") }
        val token = if (at.startsWith("Bearer ", true)) at else "Bearer $at"
        // ★★ **device_id 必须一起带**：真机实测网页令牌调 `api-pan.xunlei.com` 时
        //    缺它会直接 `400 invalid_argument / "device_id is empty"` ——
        //    所以「网页令牌只要 Bearer」的假设是**错的**。
        //    ⚠️ device_id **不在 `credentials_*` 里**，而是 localStorage 的**独立键**
        //    `deviceid`（`wdi10.…`，约 70 字符）→ 所以要从**根对象**取。
        val dev = j.optString("device_id").ifBlank { j.optString("deviceId") }
            .ifBlank { root.optString("deviceid") }
            .ifBlank { root.optString("device_id") }
            .ifBlank {
                // 再兜底：根 JSON 里任何以 wdi10. 开头的值
                root.keys().asSequence()
                    .map { root.optString(it) }
                    .firstOrNull { it.startsWith("wdi10.") }
                    .orEmpty()
            }
        // ★★ **captcha_token 也必须带**（同样实测）：
        //    缺它 → `400 captcha_invalid / "captcha_token is empty"`。
        //    网页登录把它放在 localStorage 的 `captcha_<clientId>` → `{"token":"ck0.…"}`。
        val capKey = "captcha_$XUNLEI_WEB_CLIENT"
        val cap = if (root.has(capKey)) {
            val cv = root.optString(capKey).trim().removeSurrounding("\"").replace("\\\"", "\"")
            try {
                org.json.JSONObject(cv).optString("token")
            } catch (e: Exception) {
                Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(cv)?.groupValues?.get(1).orEmpty()
            }
        } else {
            ""
        }
        return buildString {
            append("authorization=").append(token)
            if (rt.isNotBlank()) append("; refresh_token=").append(rt)
            if (dev.isNotBlank()) append("; device_id=").append(dev)
            if (cap.isNotBlank()) append("; captcha_token=").append(cap)
        }
    }

    /** ⚠️ 只有用户**显式**要求清空时才调用；启动/校验时**不要**调用（会亲手清掉登录态） */
    fun clearAll() {
        try {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
    }
}
