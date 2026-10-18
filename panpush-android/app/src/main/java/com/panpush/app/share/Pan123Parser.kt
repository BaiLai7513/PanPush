package com.panpush.app.share

import android.util.Base64
import com.panpush.app.core.DownloadItem
import com.panpush.app.core.SiteId
import com.panpush.app.core.ShareFile
import com.panpush.app.net.Http
import com.panpush.app.net.Js
import org.json.JSONArray
import org.json.JSONObject

/**
 * 123 云盘解析器（实测契约见《开发文档》§5B）。
 *
 * 关键点：
 *  - 真 API 主机是 https://www.123pan.cn/b/api（.com 只托管前端，/api 会 404）
 *  - 列目录   GET  {API}/b/api/share/get?shareKey=..&SharePwd=..&parentFileId=..
 *  - 分享直链 POST {API}/api/share/download/info  {ShareKey,FileID,S3keyFlag,Size,Etag}
 *  - 大文件 5112 → 需登录 token → 转存到「我的网盘」再取 DownloadUrl
 *  - DownloadUrl 若指向 web-pro*.123952.com/download-v2/?params=xxx，需
 *    unquote → base64 → unquote 还原真实地址
 */
class Pan123Parser(
    private val url: String,
    private val passcode: String = "",
    cookie: String? = null,
    private val timeoutSec: Long = 20
) : ShareParser {

    companion object {
        const val API = "https://www.123pan.cn"

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        val ERR_HINT = mapOf(
            5103 to "分享不存在或已失效。",
            5112 to "大文件需登录 123 云盘（需要 token）→ 在「凭据」页导入或一键登录。",
            5113 to "该分享包含被平台封禁的违规文件。",
            101310 to "转存内容为空（请求缺 fileList）。"
        )
    }

    private val cookie: String? = cookie
    private val token: String? = extractToken(cookie)

    override val site: SiteId = SiteId.PAN123

    /**
     * 从分享链接里抠 shareKey。
     *
     * 实测支持两种形态：
     *  ① 标准分享：  `https://www.123pan.com/s/yS8UVv-tqged.html`   → `/s/<key>`
     *  ② **个人分享**：`https://1854398463.share.123pan.cn/123pan/rlinvd-sCJlv` → `/123pan/<key>`
     *     （这种域名/路径以前没覆盖，会直接判「无法识别分享 key」）
     */
    val shareKey: String? = run {
        val patterns = listOf(
            Regex("/s/([0-9a-zA-Z\\-]+)"),
            Regex("/123pan/([0-9a-zA-Z\\-]+)")
        )
        for (p in patterns) {
            val m = p.find(url) ?: continue
            return@run m.groupValues[1].replace(".html", "")
        }
        null
    }

    /** 解析模式：auto(默认) / share(只走分享直链) / netdisk(强制转存后取链) */
    var mode: String = "auto"

    private fun extractTokenText(c: String?): String? {
        if (c.isNullOrBlank()) return null
        for (part in c.split(";")) {
            val p = part.trim()
            val low = p.lowercase()
            if (low.startsWith("token=")) return p.substringAfter("=").trim()
            if (low.startsWith("authorization=")) return p.substringAfter("=").trim().removePrefix("Bearer ").trim()
        }
        if (!c.contains("=") && c.trim().length > 40) return c.trim()
        return null
    }

    private fun extractToken(c: String?): String? = extractTokenText(c)

    /** 页面同款头（web 平台） */
    private fun webHeaders(jsonBody: Boolean = false, ref: String = "https://www.123pan.com/"): MutableMap<String, String> {
        val h = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to ref,
            "Origin" to "https://www.123pan.com",
            "Accept" to "application/json, text/plain, */*",
            "platform" to "web",
            "app-version" to "3"
        )
        if (jsonBody) h["Content-Type"] = "application/json"
        token?.let { h["Authorization"] = "Bearer $it" }
        if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
        return h
    }

    /** 「我的网盘」类接口必须用 android 平台头（否则 DownloadUrl 不下发） */
    private fun appHeaders(jsonBody: Boolean = false): MutableMap<String, String> {
        val h = webHeaders(jsonBody)
        h["platform"] = "android"
        h["app-version"] = "3231"
        h["x-app-version"] = "3.2.16"
        return h
    }

    private fun errText(j: JSONObject, fallback: String): String =
        "[${j.opt("code")}] ${j.optString("message").ifBlank { fallback.take(160) }}"

    private fun q(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }

    // ---- 1. 列目录（递归 + 分页）----
    private fun listDir(
        parentFileId: String,
        visited: MutableSet<String>,
        out: MutableList<ShareFile>,
        progress: (String) -> Unit
    ) {
        if (!visited.add(parentFileId)) return
        var page = 0
        var next = "0"
        while (true) {
            page++
            val params = linkedMapOf(
                "limit" to "100",
                "next" to next,
                "orderBy" to "file_id",
                "orderDirection" to "desc",
                "parentFileId" to parentFileId,
                "Page" to page.toString(),
                "shareKey" to (shareKey ?: ""),
                "SharePwd" to passcode
            )
            val r = Http.get("$API/b/api/share/get?${q(params)}", webHeaders(), timeoutSec)
            val j = Js.obj(r.text) ?: throw ParseException("123 列目录失败：响应不是 JSON（HTTP ${r.status}）")
            if (j.optInt("code", -1) != 0) {
                val code = j.optInt("code", -1)
                throw ParseException("123 列目录失败：${errText(j, r.text)}", code, ERR_HINT[code])
            }
            val data = j.optJSONObject("data") ?: break
            val items = data.optJSONArray("InfoList") ?: JSONArray()
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optInt("Type", 0) == 1) {
                    progress("展开子目录：${it.optString("FileName")}")
                    listDir(it.optString("FileId"), visited, out, progress)
                } else {
                    out.add(
                        ShareFile(
                            fid = it.optString("FileId"),
                            name = it.optString("FileName"),
                            size = it.optLong("Size", 0L),
                            etag = it.optString("Etag"),
                            s3KeyFlag = it.optString("S3KeyFlag"),
                            path = it.optString("Path")
                        )
                    )
                }
            }
            val n = data.opt("Next")?.toString() ?: "-1"
            if (n == "-1" || items.length() == 0) break
            next = n
        }
    }

    // ---- 2. 分享直链 ----
    private fun shareDownloadUrl(f: ShareFile): String {
        val body = JSONObject().apply {
            put("ShareKey", shareKey ?: "")
            put("FileID", f.fid)
            put("S3keyFlag", f.s3KeyFlag)
            put("Size", f.size)
            put("Etag", f.etag)
        }
        val h = webHeaders(jsonBody = true, ref = url)
        h["platform"] = "android"
        val r = Http.postJson("$API/api/share/download/info", h, body.toString(), timeoutSec)
        val j = Js.obj(r.text) ?: throw ParseException("123 取直链失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("code", -1) != 0) {
            val code = j.optInt("code", -1)
            throw ParseException("123 取直链失败：${errText(j, r.text)}", code, ERR_HINT[code])
        }
        val d = j.optJSONObject("data") ?: JSONObject()
        var dl = d.optString("DownloadUrl").ifBlank { d.optString("DownloadURL") }
        if (dl.contains("params=")) {
            try {
                val params = android.net.Uri.parse(dl).getQueryParameter("params")
                if (!params.isNullOrBlank()) {
                    val real = decodeBase(params)
                    if (real.startsWith("http")) dl = real
                }
            } catch (_: Exception) {
            }
        }
        return dl
    }

    /** 复刻 LinkSwift 的 decodeBase：unquote → base64 解码 → unquote */
    private fun decodeBase(s: String): String {
        var t = s
        t = try {
            java.net.URLDecoder.decode(t, "UTF-8")
        } catch (e: Exception) {
            t
        }
        t = try {
            String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            t
        }
        t = try {
            java.net.URLDecoder.decode(t, "UTF-8")
        } catch (e: Exception) {
            t
        }
        return t
    }

    private fun dlHeaders(): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to "https://www.123pan.com/"
    )

    private fun shareDownloads(files: List<ShareFile>, progress: (String) -> Unit): List<DownloadItem> {
        val res = ArrayList<DownloadItem>()
        files.forEachIndexed { idx, f ->
            progress("分享直链 ${idx + 1}/${files.size}：${f.name}")
            val dl = shareDownloadUrl(f)
            if (dl.startsWith("http")) {
                res.add(DownloadItem(f.name, f.size, dl, dlHeaders(), "share"))
            }
        }
        return res
    }

    // ---- 3. 转存到「我的网盘」→ 取直链（大文件正解）----
    private fun saveToMyDisk(files: List<ShareFile>, parentFileId: String = "0"): String? {
        val tk = token ?: throw ParseException(
            "123 转存需要登录 token（在「凭据」页导入 token=<JWT> 或一键登录 123 APP）"
        )
        val fileList = JSONArray()
        files.forEach {
            fileList.put(
                JSONObject().apply {
                    put("fileId", it.fid)
                    put("size", it.size)
                    put("etag", it.etag)
                }
            )
        }
        val body = JSONObject().apply {
            put("shareKey", shareKey ?: "")
            put("sharePwd", passcode)
            put("fileIdList", JSONArray(files.map { it.fid }))
            put("targetFileId", parentFileId)
            put("parentFileId", parentFileId)
            put("duplicate", 1)
            put("event", "shareFile")
            put("fileList", fileList)
        }
        val h = appHeaders(jsonBody = true)
        h["Authorization"] = "Bearer $tk"
        val r = Http.postJson("$API/b/api/restful/goapi/v1/file/copy/save", h, body.toString(), timeoutSec)
        val j = Js.obj(r.text) ?: throw ParseException("123 转存失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("code", -1) != 0) {
            val code = j.optInt("code", -1)
            throw ParseException("123 转存失败：${errText(j, r.text)}", code, ERR_HINT[code])
        }
        return j.optJSONObject("data")?.optString("taskID")
    }

    private fun waitSaveTask(taskId: String, timeoutMs: Long = 180_000): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        val h = appHeaders()
        token?.let { h["Authorization"] = "Bearer $it" }
        while (true) {
            val r = Http.get(
                "$API/b/api/restful/goapi/v1/file/copy/save/get?taskID=${Http.urlEncode(taskId)}",
                h, 15
            )
            val j = Js.obj(r.text) ?: JSONObject()
            val d = j.optJSONObject("data") ?: JSONObject()
            if (d.optInt("status", -1) == 2) return d
            if (j.optInt("code", -1) != 0) {
                throw ParseException("123 转存任务查询失败：${errText(j, r.text)}")
            }
            if (System.currentTimeMillis() > deadline) {
                throw ParseException("123 转存任务超时（taskID=$taskId）")
            }
            Thread.sleep(2000)
        }
    }

    private fun myDiskList(parentFileId: String = "0"): List<JSONObject> {
        val params = linkedMapOf(
            "driveId" to "0",
            "parentFileId" to parentFileId,
            "limit" to "100",
            "next" to "0",
            "orderBy" to "file_id",
            "orderDirection" to "desc",
            "trashed" to "false",
            "SearchData" to "",
            "Page" to "1",
            "OnlyLookAbnormalFile" to "0",
            "event" to "homeList"
        )
        val r = Http.get("$API/b/api/file/list/new?${q(params)}", appHeaders(), timeoutSec)
        val j = Js.obj(r.text) ?: throw ParseException("123 我的网盘列表失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("code", -1) != 0) {
            throw ParseException("123 我的网盘列表失败：${errText(j, r.text)}")
        }
        val items = j.optJSONObject("data")?.optJSONArray("InfoList") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until items.length()) items.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun myDiskDownloads(names: List<String>, parentFileId: String = "0"): List<DownloadItem> {
        val byName = LinkedHashMap<String, JSONObject>()
        for (it in myDiskList(parentFileId)) {
            if (it.optInt("Type", -1) == 0) byName.putIfAbsent(it.optString("FileName"), it)
        }
        val res = ArrayList<DownloadItem>()
        for (n in names) {
            var hit = byName[n]
            if (hit == null) { // 兼容 "xxx(1).iso" 这类重名后缀
                val pref = n.substringBeforeLast('.', n)
                hit = byName.entries.firstOrNull { it.key.startsWith(pref) }?.value
            }
            val dl = hit?.optString("DownloadUrl") ?: ""
            if (hit != null && dl.startsWith("http")) {
                res.add(
                    DownloadItem(
                        name = hit.optString("FileName"),
                        size = hit.optLong("Size", 0L),
                        url = dl,
                        headers = dlHeaders(),
                        origin = "netdisk"
                    )
                )
            }
        }
        return res
    }

    private fun netdiskDownloads(files: List<ShareFile>, progress: (String) -> Unit): List<DownloadItem> {
        progress("转存到「我的网盘」（${files.size} 个文件）…")
        val taskId = saveToMyDisk(files)
        if (!taskId.isNullOrBlank()) {
            progress("等待转存任务完成…")
            waitSaveTask(taskId)
        }
        progress("从「我的网盘」取直链…")
        return myDiskDownloads(files.map { it.name })
    }

    // ---- 对外 ----
    override fun list(): List<ShareFile> {
        if (shareKey.isNullOrBlank()) {
            throw ParseException("无法从链接中识别 123 分享 key（形如 /s/xxxx-yyyy）")
        }
        val out = ArrayList<ShareFile>()
        listDir("0", HashSet(), out) {}
        return out
    }

    override fun resolve(selected: List<ShareFile>, progress: (String) -> Unit): List<DownloadItem> {
        if (shareKey.isNullOrBlank()) {
            throw ParseException("无法从链接中识别 123 分享 key（形如 /s/xxxx-yyyy）")
        }
        val files = selected.ifEmpty {
            val out = ArrayList<ShareFile>()
            listDir("0", HashSet(), out) { progress(it) }
            out
        }
        if (files.isEmpty()) throw ParseException("分享内没有可下载的文件（或需要提取码）")

        when (mode) {
            "netdisk" -> if (token != null) return netdiskDownloads(files, progress)
            "share" -> return shareDownloads(files, progress)
        }
        return try {
            shareDownloads(files, progress)
        } catch (e: ParseException) {
            if (token == null) throw e
            progress("分享直链不可用（${e.message}），改用「转存 → 我的网盘直链」…")
            val out = netdiskDownloads(files, progress)
            if (out.isNotEmpty()) out else throw e
        }
    }
}
