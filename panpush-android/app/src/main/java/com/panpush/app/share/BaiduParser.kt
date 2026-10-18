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
 * 百度网盘解析器（实测契约见《开发文档》§5C）。
 *
 * 关键踩坑：`surl` 前缀「一个字符」的规律
 *   /share/verify          → surl **去掉**开头 1（带 1 会 404）
 *   /share/tplconfig       → surl **带**开头 1
 *   /api/sharedownload     → surl **带**开头 1
 *
 * 匿名可列目录 + 取 dlink，但**实际下载必须登录 Cookie（BDUSS）**；
 * 大文件分享直链会被服务端拒绝（list 为字符串 = 需先转存），
 * 故 parse 流程：先在「我的网盘」按文件名找 → /api/filemetas?dlink=1 取链，
 * 没转存的再回退分享直链。
 */
class BaiduParser(
    private val url: String,
    private val passcode: String = "",
    private val cookie: String? = null,
    private val timeoutSec: Long = 20
) : ShareParser {

    companion object {
        const val API = "https://pan.baidu.com"

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 netdisk;"

        val ERR_HINT = mapOf(
            -6 to "需要登录 Cookie（BDUSS）：在「凭据」页配置百度登录态。",
            105 to "提取码错误。",
            -9 to "提取码错误。",
            -20 to "缺/错 sekey（randsk）——通常是提取码校验未成功。",
            31023 to "sign 无效，请重试。",
            31045 to "直链下载必须带登录 Cookie（user not exists）。"
        )
    }

    override val site: SiteId = SiteId.BAIDU

    val surl: String = Regex("/s/([0-9a-zA-Z_\\-]+)").find(url)
        ?.groupValues?.get(1)?.substringBefore("#")?.substringBefore("?") ?: ""

    private var shareUk: String = ""
    private var shareId: String = ""
    private var bdstoken: String = ""
    private var randsk: String = ""
    private var bdclnd: String = ""
    private var jsToken: String = ""

    private fun cookieHeader(): String {
        var ck = cookie.orEmpty()
        if (bdclnd.isNotEmpty() && !ck.contains("BDCLND=")) {
            ck = if (ck.isEmpty()) "BDCLND=$bdclnd" else "$ck; BDCLND=$bdclnd"
        }
        return ck
    }

    private fun headers(ref: String = url, extra: Map<String, String> = emptyMap()): MutableMap<String, String> {
        val h = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to ref,
            "Accept" to "application/json, text/plain, */*"
        )
        val ck = cookieHeader()
        if (ck.isNotEmpty()) h["Cookie"] = ck
        h.putAll(extra)
        return h
    }

    private fun diskHeaders(): MutableMap<String, String> {
        val h = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to "https://pan.baidu.com/disk/home",
            "Accept" to "application/json, text/plain, */*"
        )
        if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
        return h
    }

    private fun captureCookies(setCookies: List<String>) {
        for (c in setCookies) {
            Regex("BDCLND=([^;]+)").find(c)?.let { bdclnd = it.groupValues[1] }
        }
    }

    private fun baiduId(): String =
        Regex("BAIDUID=([^;]+)").find(cookie.orEmpty())?.groupValues?.get(1) ?: ""

    private fun logid(): String {
        val bid = baiduId()
        if (bid.isEmpty()) return ""
        return Base64.encodeToString(bid.toByteArray(), Base64.NO_WRAP)
    }

    /** verify 接口要求 surl 去掉开头的 '1' */
    fun surlNo1(): String = if (surl.startsWith("1")) surl.substring(1) else surl

    private fun shareRef(): String = "https://pan.baidu.com/s/$surl"

    // ---- 0. 分享页元数据 ----
    /**
     * 保证分享页元数据（share_uk/shareid/BDCLND）与提取码校验已完成。
     *
     * 必须幂等：界面「解析」与「取直链」是两次独立调用，
     * 第二次会重新构造解析器，如果这里不补做，分享直链会因 uk/primaryid 为空而失败。
     */
    private fun ensureMeta() {
        if (shareUk.isNotBlank()) return
        fetchShareMeta()
        verifyPassword()
    }

    private fun fetchShareMeta(): String {
        val r = Http.get(url, headers("https://pan.baidu.com/"), timeoutSec)
        captureCookies(r.headerSetCookies)
        val html = r.text
        if (r.status != 200 || html.isEmpty()) {
            throw ParseException("无法打开百度分享页（HTTP ${r.status}）")
        }
        // 新版分享页：真值在页面 JSON 里；旧版在 window.yunData。
        // 直接全页搜「带数字值的 share_uk/shareid」，模板占位（非数字）自然被跳过。
        val uk = Regex("[\"']?share_uk[\"']?\\s*:\\s*[\"']?(\\d{5,})").find(html)?.groupValues?.get(1)
        val sid = Regex("[\"']?shareid[\"']?\\s*:\\s*[\"']?(\\d{5,})").find(html)?.groupValues?.get(1)
        if (uk == null || sid == null) {
            throw ParseException("未能从分享页解析 share_uk / shareid（链接可能失效或需要登录）")
        }
        shareUk = uk
        shareId = sid
        Regex("jsToken\"?\\s*[:=]\\s*\"?([A-Za-z0-9_\\-]+)").find(html)?.let { jsToken = it.groupValues[1] }
        return html
    }

    // ---- 1. 提取码校验 ----
    private fun verifyPassword() {
        if (passcode.isBlank()) return
        val qs = linkedMapOf(
            "channel" to "chunlei", "clienttype" to "0", "web" to "1",
            "app_id" to "250528", "surl" to surlNo1(),
            "t" to System.currentTimeMillis().toString(),
            "bdstoken" to bdstoken, "logid" to logid()
        )
        val query = qs.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val r = Http.postForm(
            "$API/share/verify?$query", headers(shareRef()),
            "pwd=${Http.urlEncode(passcode)}", timeoutSec
        )
        captureCookies(r.headerSetCookies)
        val j = Js.obj(r.text) ?: throw ParseException("百度提取码验证失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("errno", -1) != 0) {
            val errno = j.optInt("errno", -999)
            throw ParseException(
                "百度提取码验证失败：[${j.opt("errno")}] ${j.optString("err_msg").ifBlank { r.text.take(140) }}",
                errno, ERR_HINT[errno]
            )
        }
        randsk = j.optString("randsk")
    }

    // ---- 2. 取 sign / timestamp ----
    private fun getSign(): Pair<String, String> {
        val qs = linkedMapOf(
            "fields" to "sign,timestamp", "channel" to "chunlei", "web" to "1",
            "app_id" to "250528", "clienttype" to "0", "view_mode" to "1",
            "surl" to surl, "bdstoken" to bdstoken, "logid" to logid()
        )
        val query = qs.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val r = Http.get("$API/share/tplconfig?$query", headers(shareRef()), timeoutSec)
        val j = Js.obj(r.text) ?: return "" to ""
        val d = j.optJSONObject("data") ?: return "" to ""
        return d.optString("sign") to d.optString("timestamp")
    }

    // ---- 3. 列目录：解锁后分享页内嵌 file_list（老接口 /share/list 已损坏 errno2）----
    private fun extractFileList(html: String): JSONArray? {
        // 线上结构： "file_list":[...]  或  "file_list": [...],  （数组内可能有嵌套对象）
        val marker = "\"file_list\""
        var idx = html.indexOf(marker)
        while (idx >= 0) {
            val colon = html.indexOf(':', idx + marker.length)
            if (colon > 0) {
                val start = html.indexOf('[', colon)
                if (start > 0 && start - colon < 8) {
                    val arr = balancedArray(html, start)
                    if (arr != null) return Js.arr(arr)
                }
            }
            idx = html.indexOf(marker, idx + 1)
        }
        return null
    }

    /** 从 start（'['）开始按括号配对截取完整数组文本（忽略字符串内的括号） */
    private fun balancedArray(s: String, start: Int): String? {
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return s.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun listDir(
        html: String,
        dir: String,
        relPrefix: String,
        visited: MutableSet<String>,
        out: MutableList<ShareFile>,
        progress: (String) -> Unit
    ) {
        if (!visited.add(dir)) return
        val arr = extractFileList(html)
            ?: throw ParseException("未能从分享页解析 file_list（可能需要提取码、或分享已失效）")
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val path = it.optString("path")
            val isDir = it.optInt("isdir", 0) == 1
            val name = if (isDir) it.optString("server_filename") else it.optString("server_filename")
            if (isDir) {
                // 子目录：用 sharedownload 接口的 dir 参数展开
                val child = if (path.isNotBlank()) path else "$dir$name"
                progress("展开子目录：$name")
                try {
                    val childHtml = fetchDirHtml(child)
                    listDir(childHtml, child, "$relPrefix$name/", visited, out, progress)
                } catch (e: Exception) {
                    progress("子目录 $name 展开失败：${e.message}")
                }
            } else {
                out.add(
                    ShareFile(
                        fid = it.optString("fs_id"),
                        name = name,
                        size = it.optLong("size", 0L),
                        dir = false,
                        path = relPrefix + name
                    )
                )
            }
        }
    }

    /** 借 /api/sharedownload 的 dir 参数换取某个子目录的页面数据（其中含该目录的 file_list） */
    private fun fetchDirHtml(dir: String): String {
        val qs = linkedMapOf(
            "channel" to "chunlei", "clienttype" to "0", "web" to "1",
            "app_id" to "250528", "surl" to surl, "dir" to dir,
            "bdstoken" to bdstoken, "logid" to logid()
        )
        val query = qs.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val body = buildMap {
            put("encrypt", "0")
            put("product", "share")
            put("uk", shareUk)
            put("primaryid", shareId)
            put("fid_list", "[]")
            put("dir", dir)
        }.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val r = Http.postForm("$API/api/sharedownload?$query", headers(shareRef()), body, timeoutSec)
        return r.text
    }

    // ---- 4. 分享直链 ----
    private fun shareDownload(f: ShareFile, sign: String, timestamp: String): DownloadItem {
        val qs = linkedMapOf(
            "channel" to "chunlei", "clienttype" to "0", "web" to "1",
            "app_id" to "250528", "surl" to surl,
            "sign" to sign, "timestamp" to timestamp,
            "bdstoken" to bdstoken, "logid" to logid()
        )
        val query = qs.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val bodyMap = linkedMapOf(
            "encrypt" to "0",
            "product" to "share",
            "uk" to shareUk,
            "primaryid" to shareId,
            "fid_list" to JSONArray(listOf(f.fid)).toString()
        )
        if (randsk.isNotEmpty()) {
            bodyMap["extra"] = JSONObject().put("sekey", java.net.URLDecoder.decode(randsk, "UTF-8")).toString()
        }
        val body = bodyMap.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val r = Http.postForm("$API/api/sharedownload?$query", headers(shareRef()), body, timeoutSec)
        val j = Js.obj(r.text) ?: throw ParseException("百度取直链失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("errno", -1) != 0) {
            val errno = j.optInt("errno", -999)
            throw ParseException(
                "百度取直链失败：[${j.opt("errno")}] ${j.optString("err_msg").ifBlank { r.text.take(160) }}",
                errno, ERR_HINT[errno]
            )
        }
        val listVal = j.opt("list")
        if (listVal is String) {
            throw ParseException(
                "该文件过大，百度不允许直接获取分享直链。",
                hint = "请先在百度网盘里「保存到我的网盘」，再重新解析（本程序会自动改走网盘直链）。"
            )
        }
        val lst = listVal as? JSONArray
        if (lst == null || lst.length() == 0) {
            throw ParseException("百度未返回下载信息：${j.optString("err_msg").ifBlank { r.text.take(160) }}")
        }
        val it = lst.optJSONObject(0) ?: throw ParseException("百度未返回下载信息")
        val dl = it.optString("dlink")
        if (dl.isBlank()) throw ParseException("百度未返回 dlink")
        val hdrs = mutableMapOf("User-Agent" to "netdisk;", "Referer" to "https://pan.baidu.com/")
        if (!cookie.isNullOrBlank()) hdrs["Cookie"] = cookie
        return DownloadItem(f.name, f.size, dl, hdrs, "share")
    }

    // ---- 5. 转存后（我的网盘）直链：绕开分享的「过大」限制 ----
    private fun netdiskBdstoken(): String {
        if (bdstoken.isNotBlank()) return bdstoken
        val qs = linkedMapOf(
            "fields" to JSONArray(listOf("bdstoken")).toString(),
            "channel" to "chunlei", "web" to "1", "app_id" to "250528", "clienttype" to "0"
        )
        val query = qs.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val r = Http.get("$API/api/gettemplatevariable?$query", diskHeaders(), timeoutSec)
        val j = Js.obj(r.text) ?: return ""
        bdstoken = j.optJSONObject("result")?.optString("bdstoken") ?: ""
        return bdstoken
    }

    private fun netdiskFind(name: String): JSONObject? {
        val bt = netdiskBdstoken()
        if (bt.isBlank() || name.isBlank()) return null
        val qs = linkedMapOf(
            "key" to name, "recursion" to "1", "web" to "1", "app_id" to "250528",
            "channel" to "chunlei", "clienttype" to "0", "bdstoken" to bt
        )
        val query = qs.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val r = Http.get("$API/api/search?$query", diskHeaders(), timeoutSec)
        val j = Js.obj(r.text) ?: return null
        val lst = j.optJSONArray("list") ?: return null
        var loose: JSONObject? = null
        for (i in 0 until lst.length()) {
            val it = lst.optJSONObject(i) ?: continue
            if (it.optInt("isdir", 0) != 0) continue
            if (it.optString("server_filename") == name) return it
            if (loose == null && it.optString("server_filename").contains(name)) loose = it
        }
        return loose
    }

    private fun netdiskDlink(path: String): String? {
        val bt = netdiskBdstoken()
        if (bt.isBlank() || path.isBlank()) return null
        val qs = linkedMapOf(
            "target" to JSONArray(listOf(path)).toString(), "dlink" to "1",
            "channel" to "chunlei", "web" to "1", "app_id" to "250528",
            "clienttype" to "0", "bdstoken" to bt
        )
        val query = qs.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        val r = Http.get("$API/api/filemetas?$query", diskHeaders(), timeoutSec)
        val j = Js.obj(r.text) ?: return null
        if (j.optInt("errno", -1) == 0) {
            val info = j.optJSONArray("info") ?: return null
            val first = info.optJSONObject(0) ?: return null
            return first.optString("dlink").ifBlank { null }
        }
        return null
    }

    private fun netdiskDownloads(
        files: List<ShareFile>,
        progress: (String) -> Unit
    ): Pair<List<DownloadItem>, List<ShareFile>> {
        val downloads = ArrayList<DownloadItem>()
        val pending = ArrayList<ShareFile>()
        val hdrs = mutableMapOf("User-Agent" to "netdisk;", "Referer" to "https://pan.baidu.com/")
        if (!cookie.isNullOrBlank()) hdrs["Cookie"] = cookie
        files.forEachIndexed { idx, f ->
            progress("网盘定位 ${idx + 1}/${files.size}：${f.name}")
            val hit = try {
                netdiskFind(f.name)
            } catch (e: Exception) {
                null
            }
            val dl = if (hit != null) try {
                netdiskDlink(hit.optString("path"))
            } catch (e: Exception) {
                null
            } else null
            if (dl != null) {
                downloads.add(DownloadItem(f.name, f.size, dl, hdrs.toMap(), "netdisk"))
            } else {
                pending.add(f)
            }
        }
        return downloads to pending
    }

    // ---- 对外 ----
    override fun list(): List<ShareFile> {
        if (surl.isBlank()) throw ParseException("无法从链接中识别百度分享 surl（形如 /s/1xxxxxxxx）")
        val html = fetchShareMeta()
        verifyPassword()
        val unlocked = try {
            val r = Http.get(url, headers("https://pan.baidu.com/"), timeoutSec)
            captureCookies(r.headerSetCookies)
            if (extractFileList(r.text) != null) r.text else html
        } catch (e: Exception) {
            html
        }
        val out = ArrayList<ShareFile>()
        listDir(unlocked, "/", "", HashSet(), out) {}
        return out
    }

    override fun resolve(selected: List<ShareFile>, progress: (String) -> Unit): List<DownloadItem> {
        ensureMeta()
        val files = selected.ifEmpty { list() }
        if (files.isEmpty()) throw ParseException("分享内没有可下载的文件（或需要提取码）")

        val downloads = ArrayList<DownloadItem>()
        var pending = files
        // 优先走「转存后（我的网盘）」直链：可绕开分享大文件限制
        if (!cookie.isNullOrBlank()) {
            val (got, left) = netdiskDownloads(pending, progress)
            downloads.addAll(got)
            pending = left
        }
        // 仍未被网盘命中的，回退分享直链（小文件可用）
        if (pending.isNotEmpty()) {
            val (sign, ts) = getSign()
            val errors = ArrayList<String>()
            pending.forEachIndexed { idx, f ->
                progress("分享直链 ${idx + 1}/${pending.size}：${f.name}")
                try {
                    downloads.add(shareDownload(f, sign, ts))
                } catch (e: Exception) {
                    errors.add("${f.name}: ${e.message}")
                }
            }
            if (downloads.isEmpty() && errors.isNotEmpty()) {
                throw ParseException(errors.joinToString("\n"))
            }
        }
        if (downloads.isEmpty()) {
            throw ParseException(
                "未能获取直链。百度大文件需先「转存到我的网盘」，并在「凭据」页配置百度登录 Cookie（BDUSS）。"
            )
        }
        return downloads
    }
}
