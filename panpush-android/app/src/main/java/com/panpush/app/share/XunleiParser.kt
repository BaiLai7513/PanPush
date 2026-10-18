package com.panpush.app.share

import com.panpush.app.core.DownloadItem
import com.panpush.app.core.SiteId
import com.panpush.app.core.ShareFile
import com.panpush.app.cred.XunleiCredStore
import com.panpush.app.net.Http
import com.panpush.app.net.Js
import org.json.JSONArray
import org.json.JSONObject

/**
 * 迅雷云盘解析器（对齐 xunlei.py，只读：列目录 + 取直链）。
 *
 * 关键结论（见《迅雷接入设计与实现》）：
 *  - 网页端 captcha 签名不可移植 → **不复现签名**，但**网页登录的 token 可以直接用**：
 *    网页 client（`Xqp0kJBXWhwaTpB6`）scope 含 `pan`，只发 `Authorization: Bearer` 即可
 *    （见 `xunlei-file-test/迅雷应用内登录取文件-测试与方案.md`）
 *  - 请求头两种模式：App 令牌 = `X-Client-Id` / `X-Device-Id` / `X-Captcha-Token`(+Authorization)；
 *    网页令牌 = 只要 `Authorization`
 *  - 根目录 parent_id 必须用**空串**（用 "0" 会被当真实 id → file_not_found）
 *  - 直链下载**只能**用 `User-Agent: okhttp/4.9.0`，别的 UA 一律 403
 *  - captcha 令牌寿命仅几分钟，过期时用**当前分享链接**触发一次 APP 刷新再重试
 *  - 分享直链被服务端限速（~6 Mbps），个人云盘直链 ~48 Mbps
 *
 * ⚠️ 迅雷**不是**「只能 root」：root 拿 App 三件套最省事，但**网页登录的 token 同样能取文件**
 *   （只发 Bearer，不需要 captcha）。两条通道的令牌不能混用，解析器按「有没有 captcha」自动判别。
 */
class XunleiParser(
    private val url: String,
    private val passcode: String = "",
    private val timeoutSec: Long = 20,
    /** 由界面注入：迅雷凭据不走通用 Cookie 串 */
    private val credStore: XunleiCredStore? = null,
    /** 是否允许过期时自动拉起 APP 刷新（对应 XUNLEI_NO_AUTO_REFRESH） */
    private val autoRefresh: Boolean = true
) : ShareParser {

    companion object {
        const val API_BASE = "https://api-pan.xunlei.com/drive/v1"

        /**
         * **网页端** client id（`pan.xunlei.com` 的前端 JS 里就是这个）。
         * 网页 scope = `profile offline pan sso user`（含 `pan`），所以网页登录**有网盘权限**。
         */
        const val CLIENT_ID = "Xqp0kJBXWhwaTpB6"
        const val CLIENT_VERSION = "1.93.8"
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 100

        /** App 令牌模式用的 UA（迅雷 App / 网页同一族，实测可用） */
        const val UA_APP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * **网页令牌模式**用的 UA。
         *
         * 依据 `xunlei-file-test` 文档：网页令牌只发 `Authorization: Bearer` 就能调
         * `api-pan.xunlei.com/drive/v1/files`（这正是网页版自己的做法），
         * 这里用网页 UA 与「网页客户端」身份保持一致，避免被服务端按 App 客户端校验。
         */
        const val UA_WEB =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /** ★ 直链下载首选 UA（迅雷分享链实测唯一可用） */
        const val UA_DL = "okhttp/4.9.0"

        /**
         * 不同来源的直链认的 UA 不同（例：某在线解析平台用 netdiskP2SP/3.0.20.138）。
         * 写死单一 UA 会让某类链接全部 403，所以做候选 + 自动探测。
         */
        val UA_DL_CANDIDATES = listOf(
            "okhttp/4.9.0",
            "netdiskP2SP/3.0.20.138",
            "Thunder/1.0",
            "Mozilla/5.0"
        )

        /**
         * 用 Range 探测该直链可用哪个 UA，返回第一个返回 200/206 的。
         * 全部失败时回落到 UA_DL（宁可试一次也不直接放弃）。
         */
        fun probeDownloadUa(url: String, timeoutSec: Long = 10, candidates: List<String>? = null): String {
            for (ua in (candidates ?: UA_DL_CANDIDATES)) {
                val r = Http.get(url, mapOf("User-Agent" to ua, "Range" to "bytes=0-0"), timeoutSec)
                if (r.status == 200 || r.status == 206) return ua
            }
            return UA_DL
        }

        const val COOKIE_GUIDE =
            "迅雷云盘需要凭据，三选一：\n" +
                "  A) root 直读（最快）：读 APP 的 App 令牌三件套" +
                "（Authorization / Captcha / DeviceId）。\n" +
                "     确保迅雷 APP 已登录即可；令牌过期时程序会拉起迅雷 APP 触发刷新。\n" +
                "  B) 应用内登录（无需 root）：在登录态页点「应用内登录」登 pan.xunlei.com，" +
                "程序会从 localStorage 的 credentials_* / captcha_* / deviceid 抠出\n" +
                "     access_token + refresh_token + captcha + device_id（**四样都必需**）。\n" +
                "  C) 说明：手动粘贴通道已移除，请用 A/B 两种方式。\n" +
                "     authorization=Bearer <jwt>\n" +
                "     captcha_token=ck0.<...>   （两种模式都要）\n" +
                "     device_id=<wdi10.… 或 APP 的 device_id_new>"

    /**
     * ★ **按凭据拼请求头**（解析器、凭据校验、调试探针**共用这一个函数**）。
     *
     * 为什么要共用：我曾为排查另写了一份探针头，结果**探针漏发 `X-Captcha-Token`**，
     * 差点误判「头都齐了还是不过」。教训：**探针必须与被测代码走同一条构造路径**。
     *
     * ## 两种令牌模式（令牌不能混用，但 API 是同一个）
     *
     * | 模式 | 令牌来源 | 必需头 |
     * |---|---|---|
     * | **网页令牌** | WebView 登录 → localStorage `credentials_Xqp0kJBXWhwaTpB6` | `Authorization` + `X-Device-Id` + `X-Captcha-Token` + `X-Client-Id/Version` |
     * | **App 令牌** | root 直读 `auth_pref.xml`（client=`Xp6vsxz_7IYVw2BB`） | 同上（值来自 App 那套） |
     *
     * ⚠️ **纠错**：早先以为「网页令牌只要 Bearer，不需要 captcha」——**实测是错的**。
     * 缺 `X-Device-Id` → `device_id is empty`；缺 `X-Captcha-Token` → `captcha_token is empty`。
     */
    fun authHeaders(c: XunleiCredStore.Creds): Map<String, String> {
        val h = mutableMapOf(
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "https://pan.xunlei.com/",
            "Authorization" to c.authorization,
            "X-Client-Id" to CLIENT_ID,
            "X-Client-Version" to CLIENT_VERSION
        )
        h["User-Agent"] = if (c.isWebToken) UA_WEB else UA_APP
        if (c.deviceId.isNotBlank()) h["X-Device-Id"] = c.deviceId
        if (c.captchaToken.isNotBlank()) h["X-Captcha-Token"] = c.captchaToken
        return h
    }
    }

    override val site: SiteId = SiteId.XUNLEI

    private val shareId: String? =
        Regex("pan\\.xunlei\\.com/s/([0-9A-Za-z_\\-.]+)").find(url)?.groupValues?.get(1)

    private val pwd: String = passcode.ifBlank { Router.extractPasscode(url) }

    private var creds: XunleiCredStore.Creds? = null
    private var passCodeToken: String = ""

    /** 解析过程中的提示（UA 探测结果等） */
    val notes = ArrayList<String>()

    /** 列目录时记录每个文件的原始条目与所属目录，取直链时复用（对齐 _raw / folder_id） */
    private val rawById = HashMap<String, JSONObject>()
    private val folderById = HashMap<String, String>()

    /** 每个下载 host 只探测一次可用 UA，结果缓存 */
    private val uaCache = HashMap<String, String>()

    private fun uaFor(url: String): String {
        val host = if (url.contains("//")) url.split("/").getOrNull(2) ?: url else url
        return uaCache.getOrPut(host) {
            val ua = probeDownloadUa(url, minOf(timeoutSec, 10))
            notes.add("下载 UA 自动探测：$host → $ua")
            ua
        }
    }

    // ---- 凭据 ----
    private fun store(): XunleiCredStore = credStore
        ?: throw ParseException(COOKIE_GUIDE)

    private fun credsNow(): XunleiCredStore.Creds {
        // loadReady()：读到凭据后若 access_token 已过期，会用 refresh_token 自动续期
        // （实测迅雷 APP 里的 access_token 只有 2 小时，经常已过期）
        var c = creds ?: store().loadReady()
        if (c == null) throw ParseException(COOKIE_GUIDE)
        // ⚠️ **网页令牌模式不要走 captcha 刷新**：那条路会去拉起迅雷 APP 读三件套，
        //    而网页令牌根本不需要 captcha（见 xunlei-file-test 文档）。
        //    硬刷会把好好的网页令牌换成 App 令牌，反而可能失效。
        if (autoRefresh && !c.isWebToken && !c.fresh()) {
            if (store().refreshCaptcha(url)) {
                c = store().readAppCreds() ?: c
                c = try {
                    store().ensureAccessFresh(c)
                } catch (e: Exception) {
                    c
                }
            }
        }
        creds = c
        return c
    }

    /** access_token 失效时的兜底：续期一次再重试 */
    /**
     * access_token 失效时：用 refresh_token 续期一次再重试。
     *
     * ★★ **按模式选通道**（网页令牌必须走网页 client，混用会被拒）——
     * 原来这里**只会走 App 通道**，所以网页令牌遇到 401 永远续不上。
     *
     * ★ 另一个重要意义：这条路径**不依赖本地时钟**。
     * 真机踩到「手机时钟慢了 4 天」→ `accessExpired()` 以为还有 11 小时，
     * 但服务端已经判过期（`oidc: token is expired`）。
     * 所以「**以服务端 401 为准去续期**」比「信任本地时间」可靠得多。
     */
    private fun retryAfterTokenRefresh(): Boolean {
        val c = creds ?: return false
        if (c.refreshToken.isBlank()) return false
        val n = try {
            if (c.isWebToken) {
                store().refreshWebToken(c.refreshToken)
            } else {
                store().refreshAccessToken(c.refreshToken)
            }
        } catch (e: Exception) {
            null
        } ?: return false
        val merged = n.copy(
            captchaToken = c.captchaToken,
            captchaExpiresAtSec = c.captchaExpiresAtSec,
            deviceId = c.deviceId.ifBlank { n.deviceId },
            isWeb = c.isWebToken
        )
        // 网页模式：把新令牌写回网页令牌槽（否则下次又读到旧的）
        if (c.isWebToken) {
            try {
                store().saveWebToken(
                    merged.authorization,
                    merged.refreshToken.ifBlank { c.refreshToken }
                )
            } catch (_: Exception) {
            }
        }
        creds = merged
        notes.add("access_token 已过期 → refresh_token 续期成功，重试中")
        return true
    }

    /** 解析器用：按当前凭据拼头（复用 companion 的 [authHeaders]，与「校验」同一条路径） */
    private fun headers(): Map<String, String> = authHeaders(credsNow())

    private fun api(path: String, params: Map<String, String>, retry: Boolean = true): JSONObject {
        val qs = params.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val r = Http.get("$API_BASE$path?$qs", headers(), timeoutSec)
        val j = Js.obj(r.text) ?: JSONObject()
        val err = j.optString("error")
        if (r.status != 200 || err.isNotBlank()) {
            val code = j.optInt("error_code", -1)
            val desc = j.optString("error_description").ifBlank { j.optString("message") }
                .ifBlank { r.text.take(160) }

            // ① captcha 失效 → 拉起 APP 刷新 captcha 后重试一次
            if (code == 9 || desc.contains("captcha", true)) {
                if (retry && autoRefresh && store().refreshCaptcha(url)) {
                    creds = null
                    return api(path, params, retry = false)
                }
                throw ParseException(
                    "迅雷验证码失效：$desc",
                    code,
                    "打开一次迅雷 APP 的分享页即可自动刷新令牌（或检查 APP 是否仍处于登录状态）。"
                )
            }

            // ② access_token 失效（401 / unauthorized / invalid token）→ 用 refresh_token 续期后重试一次
            val tokenDead = r.status == 401 || code == 401 || code == 2 ||
                desc.contains("unauthorized", true) || desc.contains("invalid token", true) ||
                desc.contains("access token", true)
            if (tokenDead && retry && retryAfterTokenRefresh()) {
                return api(path, params, retry = false)
            }

            val hint = when {
                tokenDead && !retry -> "access_token 续期后仍失败：请在迅雷 APP 里重新登录一次。"
                tokenDead -> "access_token 已过期且无法自动续期：请打开迅雷 APP 让它刷新登录态。"
                code == 9 -> "打开一次迅雷 APP 的分享页即可自动刷新。"
                else -> null
            }
            throw ParseException("迅雷接口失败 [$code]: $desc", code, hint)
        }
        return j
    }

    // ---- 结构解析辅助（字段命名兼容多种）----
    private fun unwrap(d: JSONObject?): JSONObject {
        if (d == null) return JSONObject()
        val hasList = d.has("files") || d.has("file_list")
        val data = d.optJSONObject("data")
        return if (!hasList && data != null) data else d
    }

    private fun items(d: JSONObject?): List<JSONObject> {
        val p = unwrap(d)
        val arr = p.optJSONArray("files") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun isFolder(it: JSONObject): Boolean {
        val kind = it.optString("kind")
        if (kind == "drive#folder" || kind == "folder") return true
        if (it.optBoolean("is_dir", false)) return true
        return it.optString("file_category") == "FOLDER"
    }

    private fun inlineUrl(it: JSONObject?): String {
        if (it == null) return ""
        val direct = it.optString("web_content_link").ifBlank { it.optString("download_url") }
            .ifBlank { it.optString("play_url") }
        if (direct.startsWith("http")) return direct
        val links = it.optJSONObject("links") ?: return ""
        for (k in listOf("download", "download_url", "web_content_link", "url", "play_url")) {
            val v = links.optString(k)
            if (v.startsWith("http")) return v
        }
        return ""
    }

    private fun idOf(it: JSONObject): String =
        it.optString("id").ifBlank { it.optString("file_id") }.ifBlank { it.optString("fileId") }

    private fun nameOf(it: JSONObject): String =
        it.optString("name").ifBlank { it.optString("file_name") }.ifBlank { it.optString("fileName") }
            .ifBlank { "未命名" }

    private fun sizeOf(it: JSONObject): Long =
        it.optLong("size", 0L).takeIf { it > 0 }
            ?: it.optLong("file_size", 0L).takeIf { it > 0 }
            ?: it.optLong("fileSize", 0L)

    // ---- 列目录 ----
    private fun listDir(
        parentId: String?,
        out: MutableList<ShareFile>,
        seen: MutableSet<String>,
        depth: Int,
        progress: (String) -> Unit
    ) {
        if (depth > 8) return
        var pageToken = ""
        var page = 0
        while (page < MAX_PAGES) {
            page++
            val params = linkedMapOf(
                "share_id" to (shareId ?: ""),
                "limit" to PAGE_SIZE.toString(),
                "space" to "",
                "thumbnail_size" to "SIZE_MEDIUM",
                "scene" to "NORMAL"
            )
            val path: String
            if (!parentId.isNullOrEmpty()) {
                params["parent_id"] = parentId
                params["pass_code_token"] = passCodeToken
                path = "/share/detail"
            } else {
                params["pass_code"] = pwd
                path = "/share"
            }
            if (passCodeToken.isNotEmpty()) params["pass_code_token"] = passCodeToken
            if (pageToken.isNotEmpty()) params["page_token"] = pageToken

            val j = api(path, params)
            if (passCodeToken.isEmpty()) {
                passCodeToken = j.optString("pass_code_token")
                    .ifBlank { j.optJSONObject("share_info")?.optString("pass_code_token") ?: "" }
            }

            val items = items(j)
            for (it in items) {
                val fid = idOf(it)
                if (fid.isBlank()) continue
                if (isFolder(it)) {
                    if (seen.add(fid)) {
                        progress("展开子目录：${nameOf(it)}")
                        listDir(fid, out, seen, depth + 1, progress)
                    }
                } else {
                    rawById[fid] = it
                    folderById[fid] = parentId ?: ""
                    out.add(
                        ShareFile(
                            fid = fid,
                            name = nameOf(it),
                            size = sizeOf(it)
                        )
                    )
                }
            }
            val next = j.optString("next_page_token").ifBlank { j.optString("page_token_next") }
            if (next.isBlank() || next == pageToken || items.isEmpty()) break
            pageToken = next
        }
    }

    // ---- 取直链 ----
    private fun resolveUrl(f: ShareFile): String {
        // 列表里可能已内联给出直链
        var urlStr = inlineUrl(rawById[f.fid])
        if (urlStr.isNotEmpty()) return urlStr
        val params = linkedMapOf(
            "share_id" to (shareId ?: ""),
            "file_id" to f.fid,
            "pass_code" to pwd,
            "pass_code_token" to passCodeToken,
            "folder_id" to (folderById[f.fid] ?: ""),
            "space" to "",
            "scene" to "NORMAL"
        )
        val j = api("/share/file_info", params)
        // 接口可能回填新的 pass_code_token
        j.optString("pass_code_token").takeIf { it.isNotBlank() }?.let { passCodeToken = it }
        val payload = unwrap(j)
        val info = payload.optJSONObject("file_info") ?: payload.optJSONObject("info") ?: payload
        urlStr = inlineUrl(info)
        return urlStr
    }

    // ---- 对外 ----
    override fun list(): List<ShareFile> {
        if (shareId.isNullOrBlank()) {
            throw ParseException("无法从链接中识别迅雷分享 ID（形如 pan.xunlei.com/s/<ID>）")
        }
        rawById.clear()
        folderById.clear()
        val out = ArrayList<ShareFile>()
        listDir(null, out, HashSet(), 0) {}
        return out
    }

    override fun resolve(selected: List<ShareFile>, progress: (String) -> Unit): List<DownloadItem> {
        if (shareId.isNullOrBlank()) {
            throw ParseException("无法从链接中识别迅雷分享 ID（形如 pan.xunlei.com/s/<ID>）")
        }
        val files = selected.ifEmpty { list() }
        if (files.isEmpty()) {
            throw ParseException("分享内没有可下载的文件（或分享已失效 / 提取码错误）")
        }
        // ★ UA 自动探测：不同来源直链认的 UA 不同（分享链=okhttp，平台链可能其它）
        val dlHeaders = mapOf("User-Agent" to UA_DL)
        val res = ArrayList<DownloadItem>()
        var firstErr: String? = null
        files.forEachIndexed { idx, f ->
            progress("取直链 ${idx + 1}/${files.size}：${f.name}")
            val u = try {
                resolveUrl(f)
            } catch (e: Exception) {
                if (firstErr == null) firstErr = e.message
                ""
            }
            if (u.isNotEmpty()) {
                res.add(DownloadItem(f.name, f.size, u, mapOf("User-Agent" to uaFor(u)), "share"))
            }
        }
        if (res.isEmpty()) {
            throw ParseException(
                "未能取到迅雷直链（分享可能已失效）",
                hint = firstErr
            )
        }
        return res
    }
}
