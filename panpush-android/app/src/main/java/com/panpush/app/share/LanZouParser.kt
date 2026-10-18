package com.panpush.app.share

import com.panpush.app.core.DownloadItem
import com.panpush.app.core.SiteId
import com.panpush.app.core.ShareFile
import com.panpush.app.net.Http
import com.panpush.app.net.Js
import org.json.JSONArray

/**
 * 蓝奏云解析器（对齐 lanzou.py）。
 *
 * 设计要点：
 *  - **全匿名**：无需任何账号 / Cookie（官网承诺「不限速、免登录分享」）
 *  - **域名无关**：蓝奏域名会轮换（lanzoui/lanzoux/lanzouw/lanzouq/lanzouf/lanzn…），
 *    统一按 host 正则识别，页面内相对地址一律相对分享页 host 解析
 *  - 两种页面形态：
 *      经典文件页：页面内联 `isngis` / `wp_sign` + 提交端点 ajax*.php
 *      新样式文件页：主页含 `<iframe src="/fn?...">`，真正的 sign 在 /fn 页里
 *  - 文件夹页（`/b…`）：走 `/filemoreajax.php` 拿 JSON 文件表，再逐个解析
 *  - 下载 CDN 可能带 `acw_sc__v2`（阿里云 WAF）JS 挑战：内置求解器，
 *    算出 Cookie 后随直链一起下发给 Gopeed
 *
 * ⚠️ 下载 CDN 有频控：短时间反复访问同一 `?<token>` 会返回「网络异常需验证」落地页，
 *    因此这里**只发 1 次**探测请求解挑战，真正的下载交给 Gopeed。
 */
class LanZouParser(
    private val rawUrl: String,
    private val passcode: String = "",
    private val timeoutSec: Long = 20
) : ShareParser {

    companion object {
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** 蓝奏域名族（会轮换，用宽松匹配） */
        val DOMAIN_RE = Regex(
            "https?://(?:[a-z0-9-]+\\.)?(?:lanzo[a-z0-9-]*|lanzou[a-z0-9-]*|lanzn[a-z0-9-]*)\\.(?:com|cn)/",
            RegexOption.IGNORE_CASE
        )

        val COOKIE_GUIDE =
            "蓝奏云无需登录即可解析（免 Cookie）。\n" +
                "  直接粘贴形如 https://xxx.lanzoui.com/xxxx 的分享链接即可；\n" +
                "  带密码的分享，把密码填到「提取码」栏（或链接里的 ?pwd=）。"

        /** acw_sc__v2 求解位置表（阿里云 WAF 固定算法） */
        private val ACW_POS = intArrayOf(
            0xf, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x1, 0x26, 0xa, 0x9, 0x13, 0x1f,
            0x28, 0x1b, 0x16, 0x17, 0x19, 0xd, 0x6, 0xb, 0x27, 0x12, 0x14, 0x8,
            0xe, 0x15, 0x20, 0x1a, 0x2, 0x1e, 0x7, 0x4, 0x11, 0x5, 0x3, 0x1c,
            0x22, 0x25, 0xc, 0x24
        )
        private const val ACW_KEY = "3000176000856006061501533003690027800375"

        /**
         * 解阿里云 WAF 的 acw_sc__v2 挑战，返回 Cookie 值。
         * 算法：按位置表把 arg1 重排，再与固定 KEY 逐字节 XOR（对齐 lanzou.py 的 solve_acw_sc__v2）。
         */
        fun solveAcwScV2(arg1: String): String {
            val q = arrayOfNulls<Char>(arg1.length)
            for (x in arg1.indices) {
                for (z in ACW_POS.indices) {
                    if (ACW_POS[z] == x + 1) q[z] = arg1[x]
                }
            }
            val u = q.filterNotNull().joinToString("")
            val sb = StringBuilder()
            var x = 0
            // 两两取十六进制字节做 XOR；位数不足时原样保留（不因奇数长度崩掉）
            while (x < u.length) {
                val a = u.substring(x, minOf(x + 2, u.length)).toIntOrNull(16)
                val b = ACW_KEY.substring(x, minOf(x + 2, ACW_KEY.length)).toIntOrNull(16)
                if (a == null || b == null) {
                    sb.append(u.substring(x, minOf(x + 2, u.length)))
                } else {
                    sb.append((a xor b).toString(16).padStart(2, '0'))
                }
                x += 2
            }
            return sb.toString()
        }

        /** 把蓝奏的 '10.3 M' / '1.2 G' / '500 K' 转成字节数；失败返回 0 */
        fun parseSize(s: String?): Long {
            if (s.isNullOrBlank()) return 0L
            s.trim().toLongOrNull()?.let { return it }
            val m = Regex("^\\s*([0-9.]+)\\s*([KMGTP]?)B?\\s*$", RegexOption.IGNORE_CASE).find(s) ?: return 0L
            val v = m.groupValues[1].toDoubleOrNull() ?: return 0L
            val mult = when (m.groupValues[2].uppercase()) {
                "K" -> 1024L
                "M" -> 1024L * 1024
                "G" -> 1024L * 1024 * 1024
                "T" -> 1024L * 1024 * 1024 * 1024
                "P" -> 1024L * 1024 * 1024 * 1024 * 1024
                else -> 1L
            }
            return (v * mult).toLong()
        }

        fun isLanZouUrl(url: String?): Boolean = !url.isNullOrBlank() && DOMAIN_RE.containsMatchIn(url)
    }

    override val site: SiteId = SiteId.LANZOU

    private val url: String = (if (rawUrl.startsWith("http", true)) rawUrl else "https://$rawUrl").trim()
    private val base: String = Regex("(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: url
    private val pwd: String = passcode.ifBlank { Router.extractPasscode(url) }


    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to UA,
            "Accept" to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )
        h.putAll(extra)
        return h
    }


    /**
     * 取标题（蓝奏页面标题即文件名）。
     *
     * ⚠️ 真机实测：标题会带**站点后缀**，例如
     * `MT管理器_2.14.5-…-final.apk网盘`（注意是把「网盘」直接粘在扩展名后面）。
     * 不清理的话，推送的文件名会变成 `…apk网盘` —— **扩展名错、Gopeed 可能认不出文件类型**。
     *
     * 所以这里依次剥掉常见后缀：`- 蓝奏云` / `蓝奏云网盘` / `蓝奏云` / `网盘`。
     */
    private fun titleOf(html: String): String {
        val m = Regex("<title>(.*?)</title>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html) ?: run {
            android.util.Log.i("PanPushLanzou", "标题抓不到（页面长度=${html.length}）")
            return ""
        }
        val raw = m.groupValues[1].trim()
        var t = raw
        t = t.replace(Regex("\\s*[-–|]\\s*蓝奏云.*$"), "")
        // 反复剥「蓝奏云网盘 / 蓝奏云 / 网盘」这类站点后缀（可能叠加）
        repeat(2) {
            val before = t
            t = t.replace(Regex("(蓝奏云网盘|蓝奏云|网盘)\\s*$"), "").trim()
            if (t == before) return@repeat
        }
        android.util.Log.i("PanPushLanzou", "标题 raw=[$raw] → 采用=[$t] 页面长度=${html.length}")
        return t
    }

    // ------------------------------------------------------------------
    // 列目录
    // ------------------------------------------------------------------
    override fun list(): List<ShareFile> {
        val r = Http.get(url, headers(), timeoutSec)
        val html = r.text
        if (r.status == 0 || html.isBlank()) {
            throw ParseException("蓝奏页面打不开（HTTP ${r.status}）：${html.take(120)}")
        }
        val path = android.net.Uri.parse(url).path ?: ""
        val isFolder = Regex("/b0?[A-Za-z0-9]+").containsMatchIn(path) ||
            html.contains("filemoreajax.php")
        return if (isFolder) listFolder(html) else listSingle(html, path)
    }

    private fun listSingle(html: String, path: String): List<ShareFile> {
        val name = titleOf(html)
        // 诊断：标题不可靠时，把整页（才几 KB）分块打出来，便于定位文件名容器
        if (name.length <= 4) {
            var k = 0
            while (k < html.length) {
                val e = (k + 1200).coerceAtMost(html.length)
                android.util.Log.i(
                    "PanPushLanzou",
                    "页面[$k]=" + html.substring(k, e).replace(Regex("\\s+"), " ")
                )
                k = e
            }
        }
        return listOf(
            ShareFile(
                fid = path.trimStart('/'),
                name = name.ifBlank { "蓝奏文件" },
                // ★ 修 bug：原来这里**写死 0L** → 单文件分享永远显示「0KB」。
                //   蓝奏的单文件页其实带大小，只是写法不固定，所以多模式抓 + 记日志。
                size = sizeFromPage(html)
            )
        )
    }

    /**
     * 从蓝奏**单文件分享页**里抠文件大小。
     *
     * 为什么需要多模式：蓝奏模板有多套（且会改版），大小的写法不统一，实测见过：
     *  · `文件大小：1.5 M`
     *  · `<span class="p7">1.55 M</span>`
     *  · `var filesize = "1.5M"` / `"size":"1.5M"`
     * 所以按「明确文案 → 常见 class → 变量/JSON」依次尝试，
     * 都失败再退化到「页面上任何 `数字 + K/M/G` 的片段」。
     *
     * @return 字节数；抓不到返回 0（界面会显示「未知」，而不是假的 0KB）
     */
    private fun sizeFromPage(html: String): Long {
        if (html.isBlank()) return 0L
        val num = "([0-9]+(?:\\.[0-9]+)?)\\s*([KMGTP])?B?"
        val pats = listOf(
            "文件大小[^0-9]{0,12}$num",
            "大小[：:]\\s*$num",
            "class=\"p7\"[^>]*>\\s*$num",
            "class=\"[^\"]*(?:size|p7)[^\"]*\"[^>]*>\\s*$num",
            "var\\s+[A-Za-z0-9_]*[Ss]ize[A-Za-z0-9_]*\\s*=\\s*[\"']$num",
            "\"size\"\\s*:\\s*\"?$num"
        )
        for (p in pats) {
            val m = Regex(p, RegexOption.IGNORE_CASE).find(html) ?: continue
            val v = m.groupValues[1].toDoubleOrNull() ?: continue
            val unit = m.groupValues[2]
            val bytes = (v * when (unit.uppercase()) {
                "K" -> 1024.0
                "M" -> 1024.0 * 1024
                "G" -> 1024.0 * 1024 * 1024
                "T" -> 1024.0 * 1024 * 1024 * 1024
                "P" -> 1024.0 * 1024 * 1024 * 1024 * 1024
                else -> 1.0
            }).toLong()
            if (bytes > 0) {
                android.util.Log.i("PanPushLanzou", "单文件大小命中模式 [$p] = ${m.value} → $bytes")
                return bytes
            }
        }
        // 兜底：页面上任何「数字 + 单位」的片段（取第一个看起来像大小的）
        val any = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([KMG])B?(?![A-Za-z])", RegexOption.IGNORE_CASE)
            .find(html)
        if (any != null) {
            val v = any.groupValues[1].toDoubleOrNull() ?: 0.0
            val bytes = (v * when (any.groupValues[2].uppercase()) {
                "K" -> 1024.0
                "M" -> 1024.0 * 1024
                else -> 1024.0 * 1024 * 1024
            }).toLong()
            if (bytes > 0) {
                android.util.Log.i("PanPushLanzou", "单文件大小走兜底 = ${any.value} → $bytes")
                return bytes
            }
        }
        android.util.Log.i("PanPushLanzou", "单文件大小抓不到（页面长度=${html.length}）")
        return 0L
    }

    private fun listFolder(html: String): List<ShareFile> {
        val fid = Regex("filemoreajax\\.php\\?file=(\\d+)").find(html)?.groupValues?.get(1)
            ?: throw ParseException("蓝奏文件夹页解析失败（未找到 filemoreajax）")
        fun pick(vararg pats: String): String {
            for (p in pats) Regex(p).find(html)?.let { return it.groupValues[1] }
            return ""
        }
        val uid = pick("'uid':'?(\\d+)'?", "uid=(\\d+)")
        val puid = pick("'puid':'([^']+)'")
        val t = pick("var ibd0x5 = '([^']+)'")
        val k = pick("var _hbcyy = '([^']+)'")

        val out = ArrayList<ShareFile>()
        var pg = 1
        while (pg <= 50) {
            val form = linkedMapOf(
                "lx" to "2", "fid" to fid, "uid" to uid, "puid" to puid,
                "pg" to pg.toString(), "rep" to "0", "t" to t, "k" to k, "up" to "1"
            ).entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }
            val r = Http.postForm(
                "$base/filemoreajax.php?file=$fid",
                headers(mapOf("Referer" to url)),
                form, timeoutSec
            )
            val j = Js.obj(r.text) ?: break
            if (j.optInt("zt", 0) != 1) break
            val arr: JSONArray = j.optJSONArray("text") ?: break
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val id = it.optString("id")
                if (id.isBlank()) continue
                val sizeText = it.optString("size")
                out.add(
                    ShareFile(
                        fid = id,
                        name = it.optString("name_all").ifBlank { "未命名" },
                        size = parseSize(sizeText)
                    )
                )
            }
            if (arr.length() < 50) break
            pg++
        }
        if (out.isEmpty()) throw ParseException("蓝奏文件夹里没有文件（或需要提取码）")
        return out
    }

    // ------------------------------------------------------------------
    // 取直链
    // ------------------------------------------------------------------
    /** 取「含 sign 的那一页」html + 该页 URL（可能是 /fn 页） */
    private fun pageForFile(fileId: String): Pair<String, String> {
        val page = "$base/${fileId.trimStart('/')}"
        val r = Http.get(page, headers(), timeoutSec)
        var html = r.text
        val fn = Regex("src=\"(/fn\\?[^\"]+)\"").find(html)
        return if (fn != null) {
            // 相对地址一律相对分享页 host 解析
            val fnUrl = java.net.URI(page).resolve(fn.groupValues[1]).toString()
            val r2 = Http.get(fnUrl, headers(mapOf("Referer" to page)), timeoutSec)
            r2.text to fnUrl
        } else {
            html to page
        }
    }

    private fun direct(fileId: String): DownloadItem {
        // ★ 构建标记：确认装到设备上的包**确实包含**验证流程代码
        android.util.Log.i("PanPushLanzou", "build-marker: direct()+verifyDownloadUrl v2 (fileId=$fileId)")
        val (html, referer) = pageForFile(fileId)

        val sign = sequenceOf(
            "wp_sign = '([^']+)'",
            "var\\s+isngis\\s*=\\s*'([^']+)'",
            "isngis\\s*=\\s*'([^']+)'"
        ).mapNotNull { Regex(it).find(html)?.groupValues?.get(1) }.firstOrNull()

        val endpoint = Regex("(https?://[^'\"]*ajax(?:file|m)\\.php\\?file=\\d+)").find(html)?.groupValues?.get(1)

        if (sign.isNullOrBlank() || endpoint.isNullOrBlank()) {
            throw ParseException("未能提取蓝奏的 sign / 提交端点（页面结构可能变化）")
        }

        val form = linkedMapOf(
            "action" to "downprocess",
            "sign" to sign,
            "kd" to "1",
            "p" to pwd
        )
        Regex("ajaxdata = '([^']+)'").find(html)?.let {
            val ad = it.groupValues[1]
            form["websignkey"] = ad
            form["signs"] = ad
            form["websign"] = ""
            form["ves"] = "1"
        }
        val body = form.entries.joinToString("&") { "${it.key}=${Http.urlEncode(it.value)}" }

        val r = Http.postForm(
            endpoint,
            headers(
                mapOf(
                    "Referer" to referer,
                    "Origin" to base,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ),
            body, timeoutSec
        )
        val j = Js.obj(r.text) ?: throw ParseException("蓝奏取直链失败：响应不是 JSON（HTTP ${r.status}）")
        if (j.optInt("zt", 0) != 1) {
            val info = j.optString("inf").ifBlank { j.optString("info") }
            if (info.contains("密码")) {
                throw ParseException(
                    "需要正确的提取码（当前：\"$pwd\"）",
                    hint = "把提取码填到「提取码」栏，或使用带 ?pwd= 的链接。"
                )
            }
            throw ParseException("蓝奏取直链失败：${info.ifBlank { r.text.take(160) }}")
        }
        val dom = j.optString("dom")
        val path = j.optString("url")
        if (dom.isBlank() || path.isBlank()) throw ParseException("蓝奏未返回直链（dom/url 为空）")
        // ★★ 直链有两种形态，必须按 `url` 字段的形态选（真机对比得出）：
        //
        //  | 形态 | 样子 | 结果 |
        //  |---|---|---|
        //  | **经典** `dom + "/" + url` | `h1050.lanosso.com/…/xxx.apk?fn=1.3.4.12.apk` | ✅ 能下（Gopeed 历史成功任务） |
        //  | **新版** `dom + "/file/" + url` | `slsstm2.dmpdmp.com/file/?<token>` | ❌ 被 WAF 拦成 4.7KB |
        //
        // 判据：`url` 里带文件名/查询（`.apk`、`.zip`、`?fn=`）→ 用经典形态；
        // 只有一串 token → 用新版的 `/file/`。
        val classic = "$dom/$path"
        val modern = "$dom/file/$path"
        val looksLikeFile = path.contains("?fn=") || Regex("\\.[A-Za-z0-9]{2,5}(\\?|$)").containsMatchIn(path)
        val directUrl = if (looksLikeFile) classic else modern
        android.util.Log.i(
            "PanPushLanzou",
            "直链形态=" + (if (looksLikeFile) "经典(dom/url)" else "新版(dom/file/url)") +
                " host=" + Regex("^(https?://[^/]+)").find(directUrl)?.groupValues?.get(1)
        )
        // ★★ 文件名要取**接口返回的 `inf`**：
        //    新版蓝奏（`wwbsm.lanzoub.com` 这类）分享页的 `<title>` 与
        //    `#filenajax` 都是**占位「文件」**，真名只在
        //    `apifile.woozooo.com/ajaxfile.php` 的响应里（页面 JS 就是
        //    `$("#filenajax").text(date.inf); document.title = date.inf;`）。
        //    原来这里用 `titleOf(html)` → 推送出去的文件名会变成「文件」，扩展名都没了。
        val realName = j.optString("inf")
            .ifBlank { titleOf(html) }
            .ifBlank { fileId }
        // ★★ 蓝奏的「验证页」流程（真机把下载到的文件打开后确认）：
        //
        //   GET 直链  → 返回的**不是文件**，而是一个验证页（约 4.8KB），里面有：
        //       <div>系统发现您的网络异常，需要验证后下载文件</div>
        //       $.ajax({ url:'ajax.php',
        //                data:{ file:'<token>', el:2, sign:'<sign>' },
        //                success:function(msg){ if(msg.zt=='1'){ 用 msg.url 下载 } } })
        //
        //   ⇒ 正确流程：解析这个页面 → POST `ajax.php` → 取响应里的 `url` = **真实文件地址**。
        //     浏览器能下就是因为它执行了这段 JS；纯 HTTP 客户端必须自己模拟。
        val verifiedUrl = verifyDownloadUrl(directUrl)
        val finalUrl = verifiedUrl ?: directUrl

        return DownloadItem(
            name = realName,
            size = sizeFromPage(html),
            url = finalUrl,
            headers = downloadHeaders(finalUrl),
            origin = "share"
        )
    }

    /**
     * 取下载所需的请求头（含 WAF Cookie）。
     *
     * ## ★★ 核心约束：**绝不能探测文件链接本身**
     *
     * 真机实测（`slsstm2.dmpdmp.com/file/?<token>`）：
     *
     * ```
     * 第 1 次请求（无 Cookie）→ 200 挑战页 + Set-Cookie: acw_tc
     *   解 arg1 → acw_sc__v2
     * 第 2 次请求（带 Cookie）→ 200 但正文是 <!DOCTYPE HTML><html>… 4714 字节
     * ```
     *
     * 带 Cookie 后**不再返回挑战**，却返回一个 HTML 页 —— 说明这个
     * `/file/?<token>` 是**一次性签名链接**：每访问一次就消耗一次，
     * 探测两次后它已经失效 → Gopeed 再去下就只拿到那 4.7KB 的页面
     * （用户看到的「4.74kb 大小不对」正是这个）。
     *
     * ⇒ 因此这里改成：**只在「域名根路径」上解挑战**（Cookie 是按域名生效的），
     *   **一次都不碰真正的文件 URL**，把它**原封不动**留给 Gopeed 的第一次请求。
     */
    /**
     * 探测 acw_sc__v2 挑战并求解；**只发一次**探测请求（避免触发 CDN 频控）。
     *
     * ⚠️ 已**回退为原始实现**（用户要求：蓝奏相关解析回到免登录/原始状态）。
     *
     * 注意：下载 CDN（如 developer4.lanrar.com）在短时间被反复访问时会返回
     * 「网络异常需验证」落地页。所以这里只解挑战，真正的下载交给 Gopeed
     * （它会带上这里下发的 Cookie / Referer / UA）。
     */
    /**
     * 探测 acw_sc__v2 挑战并求解 —— **原始实现（已验证可用）**。
     *
     * ## 为什么必须是「直接探测文件链接」而不是「在域名根路径解」
     *
     * 用户实测反馈：**早期手动填提取码时下载是正常的**。对比两版实现：
     *
     * | 做法 | Cookie 来源 | 结果 |
     * |---|---|---|
     * | **探测文件链接本身**（原实现） | 该 URL 那次响应的 `arg1` | ✅ 有效，Gopeed 能下 |
     * | 在 `https://<host>/` 上解 | `/` 的 `arg1` | ❌ 对 `/file/?token` 无效 |
     *
     * `arg1` 是**逐响应签发**的，解出的 `acw_sc__v2` 只对**签发它的那次请求**有效。
     * 所以在这里探测一次、把 Cookie 交给 Gopeed 紧跟其后请求 —— 这是能走通的组合。
     *
     * ⚠️ 因此**不要**再改成「先在别处解 Cookie」或「多轮握手」，那会把链接消耗掉或拿到无效 Cookie。
     */
    /**
     * 下载请求头 —— ★ **完全不探测，只给 Referer + UA**。
     *
     * ## 为什么一探就坏（用户实测的最终证据）
     *
     * 用户把下载到的那个小文件打开，内容是：
     * **「网络异常需要验证」** —— 这是蓝奏 CDN 的**反滥用页**，
     * 不是 acw 的 JS 挑战页。它的触发条件是**同一链接被反复快速访问**。
     *
     * ```
     * 我们探测 1 次（Range 请求）→ 已算一次访问
     * Gopeed 再请求         → 立刻被判「网络异常需要验证」
     * ⇒ 下载到的就是这个 4~5KB 的提示页，而 Gopeed 标成 done
     * ```
     *
     * 所以蓝奏这里**绝不能再探测**：把刚解析出来的直链**原样**交给 Gopeed，
     * 让它的第一次请求就是这条链接的**第一次访问**。
     *
     * 注：之前的「在域名根路径解 acw 挑战」同样是错的 —— 解出的 Cookie
     * 只对签发它的响应有效；而且根路径请求本身也可能计入频控。
     */
    /**
     * 模拟蓝奏验证页的「验证并下载」流程：
     * `GET 直链 → 解析 file/sign → POST ajax.php → 取 url`。
     *
     * @return 真实文件地址；解析不到或验证失败时返回 null（调用方退回原直链）
     */
    private fun verifyDownloadUrl(directUrl: String): String? {
        val h = linkedMapOf("Referer" to "$base/", "User-Agent" to UA)
        // ① 取验证页
        val page = try {
            Http.get(directUrl, h, 15)
        } catch (e: Exception) {
            android.util.Log.i("PanPushLanzou", "验证页请求异常：${e.message}")
            return null
        }
        // 单/双引号都认（不同 CDN 页面写法不一致）
        val fileTok = Regex("'file'\\s*:\\s*'([^']+)'").find(page.text)?.groupValues?.get(1)
            ?: Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(page.text)?.groupValues?.get(1)
        val sign = Regex("'sign'\\s*:\\s*'([^']+)'").find(page.text)?.groupValues?.get(1)
            ?: Regex("\"sign\"\\s*:\\s*\"([^\"]+)\"").find(page.text)?.groupValues?.get(1)
        if (fileTok == null || sign == null) {
            android.util.Log.i(
                "PanPushLanzou",
                "不是验证页（status=${page.status} len=${page.text.length}）→ 直链可直接用\n  正文前 500：" + page.text.take(500).replace(Regex("\\\\s+"), " ")
            )
            return null
        }
        // ② POST ajax.php（相对当前页面目录）
        val ajaxUrl = try {
            java.net.URI(directUrl).resolve("ajax.php").toString()
        } catch (e: Exception) {
            "${directUrl.substringBeforeLast('/')}/ajax.php"
        }
        // 页面上有三个入口（1/2/3），「网络异常」走的是 2；失败再试 1、3
        for (el in listOf(2, 1, 3)) {
            val form = listOf(
                "file" to fileTok,
                "el" to el.toString(),
                "sign" to sign
            ).joinToString("&") { "${it.first}=${Http.urlEncode(it.second)}" }
            val r = try {
                Http.postForm(
                    ajaxUrl,
                    h + mapOf(
                        "Referer" to directUrl,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    ),
                    form, 15
                )
            } catch (e: Exception) {
                android.util.Log.i("PanPushLanzou", "ajax.php(el=$el) 异常：${e.message}")
                continue
            }
            val j = Js.obj(r.text)
            val zt = j?.optString("zt").orEmpty()
            val u = j?.optString("url").orEmpty()
            if (zt == "1" && u.isNotBlank()) {
                val abs = if (u.startsWith("http", true)) u else runCatching {
                    java.net.URI(directUrl).resolve(u).toString()
                }.getOrDefault(u)
                android.util.Log.i("PanPushLanzou", "★★ 验证通过（el=$el）→ 真实下载地址已拿到")
                return abs
            }
            android.util.Log.i(
                "PanPushLanzou",
                "ajax.php(el=$el) 未通过：zt=${zt.ifBlank { "-" }} body=${r.text.take(120)}"
            )
        }
        return null
    }

    private fun downloadHeaders(direct: String): Map<String, String> =
        linkedMapOf("Referer" to "$base/", "User-Agent" to UA)
    override fun resolve(selected: List<ShareFile>, progress: (String) -> Unit): List<DownloadItem> {
        val files = selected.ifEmpty { list() }
        if (files.isEmpty()) throw ParseException("蓝奏分享里没有文件")
        val out = ArrayList<DownloadItem>()
        files.forEachIndexed { idx, f ->
            progress("取直链 ${idx + 1}/${files.size}：${f.name}")
            val d = direct(f.fid)
            // ★ 占位名别覆盖真名：新版蓝奏列表页给的是「文件」，
            //   而 `direct()` 已经从接口 `inf` 拿到了真名 → 这时**保留 d.name**。
            val placeholder = f.name.isBlank() ||
                f.name == "文件" || f.name == "蓝奏文件" || f.name == "未命名"
            out.add(
                d.copy(
                    name = if (placeholder) d.name else f.name,
                    size = if (f.size > 0) f.size else d.size
                )
            )
        }
        return out
    }
}
