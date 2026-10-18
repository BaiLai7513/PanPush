package com.panpush.app.share

import com.panpush.app.core.DownloadItem
import com.panpush.app.core.SiteId
import com.panpush.app.core.ShareFile
import com.panpush.app.net.Http
import com.panpush.app.net.Js
import org.json.JSONArray
import org.json.JSONObject

/**
 * 夸克网盘解析器（UC 与夸克同源后端，复用本类，仅换域名/Referer）。
 *
 * 三步链路（实测契约见《开发文档》§5）:
 *   ① POST {api}/share/sharepage/token   {pwd_id, passcode}          → stoken
 *   ② GET  {api}/share/sharepage/detail  pwd_id, stoken, pdir_fid    → 文件列表
 *   ③ POST {api}/file/download           {fids, pwd_id, stoken}      → 签名直链（需 Cookie）
 * 直链下载必须带 Cookie + Referer，否则 412。
 */
class QuarkParser(
    private val url: String,
    private val passcode: String = "",
    private val cookie: String? = null,
    private val timeoutSec: Long = 20,
    override val site: SiteId = SiteId.QUARK
) : ShareParser {

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 " +
                "Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"

        val ERR_HINT = mapOf(
            23018 to "匿名（未登录）下载有体积上限，此文件超限。需要夸克登录 Cookie（__puus）。",
            31001 to "需要登录：该操作要求账号 Cookie（__puus）。",
            41006 to "分享不存在或已失效。",
            41010 to "提取码错误。"
        )
    }

    private val api: String = when (site) {
        SiteId.UC -> "https://drive.uc.cn/1/clouddrive"
        else -> "https://drive-pc.quark.cn/1/clouddrive"
    }

    private val referer: String = when (site) {
        SiteId.UC -> "https://drive.uc.cn/"
        else -> "https://pan.quark.cn/"
    }

    private val pwdId: String? =
        Regex("/s/([0-9a-zA-Z_\\-]+)").find(url)?.groupValues?.get(1)

    // stoken 会随会话变化，缓存一次复用，保证「列目录」与「取直链」同一会话
    private var cachedStoken: String? = null
    private var cachedPwd: String? = null

    private fun headers(ref: String = referer): MutableMap<String, String> {
        val h = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to ref,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )
        if (!cookie.isNullOrBlank()) h["Cookie"] = cookie
        return h
    }

    private fun baseQuery() = linkedMapOf(
        "pr" to "ucpro",
        "fr" to "pc",
        "uc_param_str" to ""
    )

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val qs = query.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
        return "$api/$path?$qs"
    }

    private fun errText(j: JSONObject, fallback: String): String =
        "[${j.opt("code")}] ${j.optString("message").ifBlank { fallback.take(160) }}"

    // ---- ① stoken ----
    private fun ensureStoken(): Pair<String, String> {
        cachedStoken?.let { return it to (cachedPwd ?: pwdId.orEmpty()) }
        val pid = pwdId ?: throw ParseException("无法从链接中识别 pwd_id（形如 /s/xxxxxx）")
        val body = JSONObject().apply {
            put("pwd_id", pid)
            put("passcode", passcode)
        }
        val r = Http.postJson(buildUrl("share/sharepage/token", baseQuery()), headers(), body.toString(), timeoutSec)
        val j = Js.obj(r.text) ?: throw ParseException("获取 stoken 失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("status", -1) != 200) {
            val code = j.optInt("code", -1)
            throw ParseException("获取 stoken 失败：${errText(j, r.text)}", code, ERR_HINT[code])
        }
        val d = j.optJSONObject("data") ?: JSONObject()
        val stoken = d.optString("stoken")
        if (stoken.isBlank()) throw ParseException("未取到 stoken（可能是提取码错误或分享已失效）")
        val pwd = d.optString("pwd_id").ifBlank { pid }
        cachedStoken = stoken
        cachedPwd = pwd
        return stoken to pwd
    }

    // ---- ② 列目录（递归展开子目录）----
    private fun listDir(
        stoken: String,
        pwd: String,
        pdirFid: String,
        visited: MutableSet<String>,
        out: MutableList<ShareFile>
    ) {
        if (!visited.add(pdirFid)) return
        val q = baseQuery().toMutableMap().apply {
            put("pwd_id", pwd)
            put("stoken", stoken)
            put("pdir_fid", pdirFid)
            put("force", "0")
            put("_page", "1")
            put("_size", "200")
            put("_fetch_banner", "0")
            put("_fetch_share", "1")
            put("_fetch_total", "1")
            put("_sort", "file_type:file_type,face:0,time:0,size:0,name:0")
            put("_is_hl", "0")
        }
        val r = Http.get(buildUrl("share/sharepage/detail", q), headers(), timeoutSec)
        val j = Js.obj(r.text) ?: throw ParseException("列目录失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("status", -1) != 200) {
            val code = j.optInt("code", -1)
            throw ParseException("列目录失败：${errText(j, r.text)}", code, ERR_HINT[code])
        }
        val list = j.optJSONObject("data")?.optJSONArray("list") ?: return
        for (i in 0 until list.length()) {
            val it = list.optJSONObject(i) ?: continue
            val fid = it.optString("fid")
            if (it.optBoolean("dir", false)) {
                listDir(stoken, pwd, fid, visited, out)
            } else {
                out.add(
                    ShareFile(
                        fid = fid,
                        name = it.optString("file_name"),
                        size = it.optLong("size", 0L)
                    )
                )
            }
        }
    }

    // ---- ③ 取直链 ----
    private fun getDownloads(stoken: String, pwd: String, fids: List<String>): List<DownloadItem> {
        val body = JSONObject().apply {
            put("fids", JSONArray(fids))
            put("pwd_id", pwd)
            put("stoken", stoken)
        }
        // 取直链时 Referer 用分享页更稳
        val r = Http.postJson(buildUrl("file/download", baseQuery()), headers(url), body.toString(), timeoutSec)
        val j = Js.obj(r.text) ?: throw ParseException("取下载直链失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("status", -1) != 200) {
            val code = j.optInt("code", -1)
            throw ParseException("取下载直链失败：${errText(j, r.text)}", code, ERR_HINT[code])
        }
        var data = j.opt("data")
        if (data is JSONObject) data = JSONArray().put(data)
        val arr = data as? JSONArray ?: JSONArray()

        // 直链（dl-pc-zb.drive.quark.cn）必须带 Cookie，否则 412
        val dlHeaders = mutableMapOf("User-Agent" to UA, "Referer" to url)
        if (!cookie.isNullOrBlank()) dlHeaders["Cookie"] = cookie

        val res = ArrayList<DownloadItem>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val dl = firstNonBlank(
                it.optString("download_url"),
                it.optString("url"),
                it.optString("downloadUrl"),
                it.optString("v_url")
            ) ?: continue
            res.add(
                DownloadItem(
                    name = firstNonBlank(it.optString("file_name"), it.optString("name")) ?: "download",
                    size = it.optLong("size", 0L),
                    url = dl,
                    headers = dlHeaders.toMap(),
                    origin = "share"
                )
            )
        }
        return res
    }

    private fun firstNonBlank(vararg s: String): String? = s.firstOrNull { !it.isNullOrBlank() }

    // ---- 对外 ----
    override fun list(): List<ShareFile> {
        val (stoken, pwd) = ensureStoken()
        val out = ArrayList<ShareFile>()
        listDir(stoken, pwd, "0", HashSet(), out)
        return out
    }

    override fun resolve(selected: List<ShareFile>, progress: (String) -> Unit): List<DownloadItem> {
        val (stoken, pwd) = ensureStoken()
        val files = selected.ifEmpty {
            val out = ArrayList<ShareFile>()
            listDir(stoken, pwd, "0", HashSet(), out)
            progress("列目录完成，共 ${out.size} 个文件")
            out
        }
        if (files.isEmpty()) throw ParseException("分享内没有可下载的文件")
        val fids = files.map { it.fid }
        progress("正在取签名直链（${fids.size} 个文件）…")
        return getDownloads(stoken, pwd, fids)
    }
}
