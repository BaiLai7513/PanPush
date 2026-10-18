package com.panpush.app.share

import com.panpush.app.core.DownloadItem
import com.panpush.app.core.SiteId
import com.panpush.app.core.ShareFile
import com.panpush.app.cred.AliyunCredStore
import com.panpush.app.net.Http
import com.panpush.app.net.Js
import org.json.JSONArray
import org.json.JSONObject

/**
 * 阿里云盘解析器（对齐 aliyun.py）。
 *
 * 一句话：分享链接 → 匿名列目录 → **转存到我的云盘** → 取 OSS 直链（可多线程）→ 回收副本。
 *
 * 为什么必须这样（真机实测）：
 *  - 匿名分享令牌能列目录，但**第三方直接下载分享文件被禁**（Forbidden / ForbiddenNoPermission.File）
 *  - 取直链接口 `POST /v2/file/get_download_url` **必须登录态**（Authorization），
 *    匿名 `x-share-token` 不被接受
 *  - 直链是阿里 OSS（STS 临时凭证，~15 分钟），支持 Range，但**按连接限速**
 *  - 存在**体积门控**：≥约 100MB 取不到直链（HTTP 200 软拒，url 为空）
 *
 * 两个硬门槛的处理：
 *  1. `size >= SIZE_GATE` 直接跳过并写入 notes
 *  2. 取直链频控：空 url → 15s 退避重试 3 次
 */
class AliyunParser(
    private val url: String,
    private val passcode: String = "",
    private val timeoutSec: Long = 20,
    /** 由界面注入：阿里凭据走 APP 明文 DB / 手动落盘，不吃通用 Cookie 串 */
    private val credStore: AliyunCredStore? = null,
    /** 界面注入的 Context，用于取稳定的 x-device-id（见 [deviceId]） */
    private val appContext: android.content.Context? = null,
    /** 取到直链后是否回收转存副本（等价 ALIYUN_NO_CLEAN） */
    private val autoClean: Boolean = true
) : ShareParser {

    companion object {
        const val API = "https://api.aliyundrive.com"
        const val AUTH = "https://auth.aliyundrive.com"

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * ★ 对齐 `ali-lushuo-对比/panpush借鉴Voyage阿里登录-方案.md` §2.1：
         * 让它「越接近正常网页客户端」，降低被风控软拒 / 频控的概率。
         *
         * 该文档对照 Voyage 的结论（原文表格）：
         *  · `X-Canary` 从 **`v2.3.1` 升到 `v6.8.12`**（原来版本明显偏旧）
         *  · 补 **`Origin`**（原来没有）
         *  · `Referer` 从 `https://www.alipan.com/` 改为 **`https://www.aliyundrive.com`**
         *  · 补 **`x-device-id`**（稳定设备标识，见 [_deviceId]）
         *
         * ⚠️ 文档明确说：这是**降风控**，不是解锁功能 —— 滑块只出现在**登录**环节，
         * 改头不会让滑块消失；它影响的是登录后调接口被软拒/频控的概率。
         */
        const val X_CANARY = "client=web,app=adrive,version=v6.8.12"
        const val REFERER = "https://www.alipan.com"
        const val ORIGIN = "https://www.alipan.com"

        /**
         * 稳定的设备标识（UUID，首次生成后持久化，全局复用）。
         *
         * 文档建议：「固定 `x-device-id`，别每次登录都像全新设备」——
         * 设备标识飘忽会抬高风控里的「新设备」权重。
         */
        fun deviceId(ctx: android.content.Context): String {
            val sp = ctx.getSharedPreferences("panpush_device", android.content.Context.MODE_PRIVATE)
            sp.getString("device_id", null)?.takeIf { it.isNotBlank() }?.let { return it }
            val v = java.util.UUID.randomUUID().toString().replace("-", "")
            sp.edit().putString("device_id", v).apply()
            return v
        }

        val COOKIE_GUIDE = AliyunCredStore.COOKIE_GUIDE
        val SIZE_GATE = AliyunCredStore.SIZE_GATE
    }

    override val site: SiteId = SiteId.ALI

    /**
     * 阿里分享 ID。
     *
     * ⚠️ 阿里有**两种链接**，接口完全不通：
     *  · **分享** `https://www.alipan.com/s/<ID>`  → 可匿名解析（本解析器支持）
     *  · **快传** `https://www.alipan.com/t/<ID>`  → **私有接口，第三方拿不到**
     *
     * 真机实测（2026-10-12，用真实快传链接）：
     *  · 把 `/t/` 的 token 当 `share_id` 调 `/v2/share_link/get_share_token`
     *    → `404 {"code":"NotFound.ShareLink"}`；
     *  · 试过 `pdsapi.aliyundrive.com` 主机、空 `Authorization`、
     *    `/v2/transfer/get_transfer_token`、`/v2/share_link/get_share_by_anonymous` 等
     *    候选路径，**全部 404**；
     *  · 快传页面（标题「阿里云盘快传」）走的是其前端 bundle 内的私有流程。
     *
     * 所以这里**明确区分**并给出可操作建议，而不是笼统报「无法解析」。
     */
    private val quickTransferId: String? =
        Regex("/t/([0-9A-Za-z_\\-]+)").find(url)?.groupValues?.get(1)

    private val shareId: String? = Regex("/s/([0-9A-Za-z_\\-]+)").find(url)?.groupValues?.get(1)
    private val pwd: String = passcode.ifBlank { Router.extractPasscode(url) }

    private var creds: AliyunCredStore.Creds? = null
    private var driveId: String = ""
    private var shareToken: String? = null

    /** 解析过程中的提示（超门控跳过、频控重试等） */
    val notes = ArrayList<String>()

    // ---- 凭据 ----
    private fun store(): AliyunCredStore =
        credStore ?: throw ParseException(COOKIE_GUIDE)

    private fun credsNow(): AliyunCredStore.Creds {
        // loadReady()：阿里 DB 里的 access_token 常常已过期（APP 只在内存持有新 token），
        // 这里会自动用 refresh_token 续期
        var c = creds ?: store().loadReady()
        if (c == null) throw ParseException(COOKIE_GUIDE)
        val fresh = try {
            store().ensureFresh(c)
        } catch (e: Exception) {
            c
        }
        creds = fresh
        if (driveId.isBlank()) driveId = fresh.driveId
        return fresh
    }

    /** token 失效类错误 → 续期一次 */
    private fun touchAuthError(j: JSONObject): Boolean {
        val code = j.optString("code")
        if (code.contains("AccessToken") || (code.contains("Token") && code.contains("Invalid"))) {
            val c = creds ?: return false
            // ⚠️ 必须带上 c.clientId：网页登录来的 refresh_token **只能**用网页 client 刷新，
            //    用 App 通道会被拒（InvalidParameter.ClientId）——见 aliyun-login 文档 §1.2。
            val n = store().refresh(c.refreshToken, c.driveId, c.clientId) ?: return false
            val merged = if (n.driveId.isBlank()) n.copy(driveId = c.driveId) else n
            creds = merged
            // ★ 被动续期也要**落盘**，否则下次启动又用回旧令牌
            //   （非 root 场景全靠这条 + ensureFresh 的主动续期把有效期续下去）
            try {
                store().saveManualCreds(
                    accessToken = merged.accessToken,
                    refreshToken = merged.refreshToken.ifBlank { c.refreshToken },
                    driveId = merged.driveId,
                    clientId = c.clientId,
                    source = if (c.clientId == AliyunCredStore.WEB_CLIENT_ID) "网页 token 续期" else "refresh",
                    expireTime = merged.expireTime
                )
            } catch (_: Exception) {
            }
            return true
        }
        return false
    }

    private fun authHeader(share: Boolean = false): Map<String, String> {
        val c = credsNow()
        val h = mutableMapOf(
            "Authorization" to "Bearer ${c.accessToken}",
            "X-Canary" to X_CANARY,
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "Referer" to REFERER,
            // ★ 对齐官方网页客户端身份（见 X_CANARY 的说明）
            "Origin" to ORIGIN,
            "x-device-id" to deviceIdOf()
        )
        if (share && !shareToken.isNullOrBlank()) h["x-share-token"] = shareToken!!
        return h
    }

    /** 稳定的 x-device-id（由界面注入 Context；取不到时退化为主机名无关的常量） */
    private fun deviceIdOf(): String =
        appContext?.let { deviceId(it) } ?: "00000000000000000000000000000000"

    private fun post(path: String, body: JSONObject, headers: Map<String, String>, timeout: Long = timeoutSec): com.panpush.app.net.HttpResult {
        val r = Http.postJson("$API$path", headers, body.toString(), timeout)
        // 诊断：把「哪一步、什么状态码、返回什么」写进 logcat（tag=PanPushAli）
        try {
            android.util.Log.i(
                "PanPushAli",
                "POST $path -> HTTP ${r.status} body=${r.text.take(300)}"
            )
        } catch (_: Exception) {
        }
        return r
    }

    private fun logAli(msg: String) {
        try {
            android.util.Log.i("PanPushAli", msg)
        } catch (_: Exception) {
        }
    }

    // ---- 分享令牌 ----
    private fun getShareToken(): String {
        shareToken?.let { return it }
        val body = JSONObject().apply {
            put("share_id", shareId ?: "")
            put("share_pwd", pwd)
        }
        val r = post(
            "/v2/share_link/get_share_token", body,
            mapOf(
                "X-Canary" to X_CANARY,
                "User-Agent" to UA,
                "Accept" to "application/json, text/plain, */*",
                "Referer" to REFERER,
                "Origin" to ORIGIN,
                "x-device-id" to deviceIdOf()
            )
        )
        val j = Js.obj(r.text) ?: throw ParseException("获取阿里分享令牌失败：响应不是 JSON（HTTP ${r.status}）")
        val st = j.optString("share_token")
        if (st.isBlank()) {
            val code = j.optString("code")
            val msg = j.optString("message").ifBlank { r.text.take(160) }
            throw when {
                code.contains("Cancelled") -> ParseException("分享已被作者取消")
                code.contains("ShareLink") -> ParseException("分享链接不存在或已失效：$msg")
                msg.contains("密码") || msg.contains("password", true) ->
                    ParseException("需要提取码（当前：\"$pwd\"）", hint = "把提取码填到「提取码」栏。")
                else -> ParseException("获取阿里分享令牌失败：$msg")
            }
        }
        shareToken = st
        return st
    }

    // ---- 列目录（匿名递归）----
    private fun walk(
        parent: String,
        out: MutableList<ShareFile>,
        seen: MutableSet<String>,
        depth: Int,
        progress: (String) -> Unit
    ) {
        if (depth > 12) return
        var marker = ""
        var page = 0
        while (page < 100) {
            page++
            val body = JSONObject().apply {
                put("share_id", shareId ?: "")
                put("parent_file_id", parent)
                put("limit", 100)
                put("order_by", "name")
                put("order_direction", "ASC")
                put("fields", "*")
                if (marker.isNotEmpty()) put("marker", marker)
            }
            val r = post(
                "/adrive/v3/file/list", body,
                mapOf(
                    "x-share-token" to getShareToken(),
                    "X-Canary" to X_CANARY,
                    "User-Agent" to UA,
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to REFERER,
                    "Origin" to ORIGIN,
                    "x-device-id" to deviceIdOf()
                )
            )
            val j = Js.obj(r.text) ?: break
            val items: JSONArray = j.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val fid = it.optString("file_id")
                if (fid.isBlank()) continue
                val name = it.optString("name").ifBlank { "未命名" }
                if (it.optString("type") == "folder") {
                    if (seen.add(fid)) {
                        progress("展开子目录：$name")
                        walk(fid, out, seen, depth + 1, progress)
                    }
                } else {
                    out.add(ShareFile(fid = fid, name = name, size = it.optLong("size", 0L)))
                }
            }
            marker = j.optString("next_marker")
            if (marker.isBlank() || items.length() == 0) break
        }
    }

    // ---- 转存 / 取直链 / 回收 ----
    private fun copyFile(fileId: String): String {
        val body = JSONObject().apply {
            put("file_id", fileId)
            put("share_id", shareId ?: "")
            put("to_drive_id", driveId)
            put("to_parent_file_id", "root")
            put("overwrite", true)
        }
        val r = post("/v2/file/copy", body, authHeader(share = true))
        val j = Js.obj(r.text) ?: throw ParseException("阿里转存失败：响应不是 JSON（HTTP ${r.status}）")
        val nf = j.optString("file_id")
        if (nf.isBlank()) {
            if (touchAuthError(j)) {
                // 续期后重试一次
                val r2 = post("/v2/file/copy", body, authHeader(share = true))
                val j2 = Js.obj(r2.text) ?: JSONObject()
                val nf2 = j2.optString("file_id")
                if (nf2.isNotBlank()) return nf2
                throw ParseException("阿里转存失败：${j2.optString("message").ifBlank { r2.text.take(160) }}")
            }
            throw ParseException("阿里转存失败：${j.optString("message").ifBlank { r.text.take(160) }}")
        }
        return nf
    }

    /** 取直链（空 url = 频控/体积门控 → 退避重试） */
    private fun downloadUrl(fileId: String, retries: Int = 3, gapMs: Long = 15_000): String {
        val body = JSONObject().apply {
            put("drive_id", driveId)
            put("file_id", fileId)
            put("expire_sec", 900)
        }
        var last = ""
        for (i in 0 until retries) {
            val r = post("/v2/file/get_download_url", body, authHeader())
            val j = Js.obj(r.text) ?: JSONObject()
            val u = j.optString("url").ifBlank { j.optString("cdn_url") }
            if (u.isNotBlank()) return u
            last = j.optString("message").ifBlank { r.text.take(160) }
            if (j.optString("code").isNotEmpty() && touchAuthError(j)) continue
            if (j.optString("code").isNotEmpty()) {
                throw ParseException("阿里取直链失败：$last")
            }
            if (i < retries - 1) Thread.sleep(gapMs) // 空 url → 频控，退避
        }
        throw ParseException("阿里取直链失败（频控 / 体积门控）：$last")
    }

    private fun trash(fileId: String) {
        try {
            val body = JSONObject().apply {
                put("drive_id", driveId)
                put("file_id", fileId)
            }
            post("/v2/recyclebin/trash", body, authHeader(), 15)
        } catch (_: Exception) {
        }
    }

    // ---- 对外 ----
    /**
     * 快传链接的专用提示（比「无法识别分享 ID」有用得多）。
     *
     * 见 [quickTransferId] 的注释：快传 `/t/` 与分享 `/s/` **接口不通用**，
     * 真机实测所有候选路径都 404，所以这里直接给出可操作的替代方案。
     */
    private fun quickTransferError(): ParseException = ParseException(
        "这是阿里云盘「快传」链接（…/t/…），不是「分享」链接（…/s/…），两者接口不通用。",
        hint = "快传走的是阿里**私有接口**，第三方取不到文件列表与直链。请用下面任一方式：\n" +
            "  ① 让分享者改用「分享」生成链接（形如 …/s/xxxxx）——本 App 可直接解析；\n" +
            "  ② 自己在「阿里云盘」App 里打开这条快传链接 → 保存到我的云盘 → " +
            "再从自己的云盘「分享」一条 …/s/xxxxx 链接给本 App。"
    )

    override fun list(): List<ShareFile> {
        if (!quickTransferId.isNullOrBlank() && shareId.isNullOrBlank()) {
            logAli("识别为快传链接(/t/)，阿里未开放该接口")
            throw quickTransferError()
        }
        logAli("list() 开始：url=${url.take(80)} shareId=${shareId} pwd=${if (pwd.isBlank()) "(空)" else "(有)"}")
        if (shareId.isNullOrBlank()) {
            throw ParseException(
                "无法从链接识别阿里分享 ID。",
                hint = "期望形如 https://www.alipan.com/s/<ID> 或 https://www.aliyundrive.com/s/<ID>；" +
                    "当前链接里没有 /s/<ID> 这一段。"
            )
        }
        val out = ArrayList<ShareFile>()
        walk("root", out, HashSet(), 0) {}
        logAli("list() 结束：${out.size} 个文件")
        // 列目录阶段就把超门控的文件标出来（对应 CLI 的 list_only notes）
        for (f in out) {
            if (f.size >= SIZE_GATE) {
                notes.add("${f.name}（${"%.1f".format(f.size / 1048576.0)} MB）超过第三方体积门控，可能取不到直链")
            }
        }
        return out
    }

    override fun resolve(selected: List<ShareFile>, progress: (String) -> Unit): List<DownloadItem> {
        if (!quickTransferId.isNullOrBlank() && shareId.isNullOrBlank()) throw quickTransferError()
        if (shareId.isNullOrBlank()) {
            throw ParseException("无法从链接识别阿里分享 ID（形如 alipan.com/s/<ID>）")
        }
        val c = credsNow()
        if (driveId.isBlank()) driveId = c.driveId
        val files = selected.ifEmpty { list() }
        if (files.isEmpty()) throw ParseException("分享内没有可下载的文件（或需要提取码）")
        if (driveId.isBlank()) throw ParseException("缺少 drive_id（凭据不完整）")

        val downloads = ArrayList<DownloadItem>()
        // ★ 只发 UA，**不发 Referer**：阿里的签名 CDN 链接对「错误/多余 Referer」会 403。
        //   用户实测「浏览器 → 1DM+」能下，说明链接本身没问题，差别就在请求头。
        val dlHeaders = mapOf("User-Agent" to UA)

        files.forEachIndexed { idx, f ->
            if (f.size >= SIZE_GATE) {
                notes.add(
                    "[跳过] ${f.name}（${"%.1f".format(f.size / 1048576.0)} MB）超体积门控" +
                        "（第三方直链被阿里限制，会员是否解除待验证）"
                )
                return@forEachIndexed
            }
            progress("转存 ${idx + 1}/${files.size}：${f.name}")
            val nfid = try {
                copyFile(f.fid)
            } catch (e: Exception) {
                notes.add("[跳过] ${f.name}：${e.message}")
                return@forEachIndexed
            }
            var u: String? = null
            try {
                progress("取直链 ${idx + 1}/${files.size}：${f.name}")
                u = downloadUrl(nfid)
            } catch (e: Exception) {
                notes.add("[跳过] ${f.name}：${e.message}")
            } finally {
                // 回收后直链仍可用（STS 签名独立），故默认「取到直链→回收副本」
                if (autoClean) trash(nfid)
            }
            if (!u.isNullOrBlank()) {
                downloads.add(DownloadItem(f.name, f.size, u, dlHeaders, "netdisk"))
            }
        }
        if (downloads.isEmpty()) {
            val reason = notes.lastOrNull() ?: "无可取链文件"
            throw ParseException(
                "未能取到阿里云盘直链。",
                hint = "$reason\n" +
                    "常见原因：① 文件 ≥100MB 触发体积门控；② 取直链频控（等 20s 重试）；" +
                    "③ token 失效（重新 root 读取）。"
            )
        }
        return downloads
    }
}
