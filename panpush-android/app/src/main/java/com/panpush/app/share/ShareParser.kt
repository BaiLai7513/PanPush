package com.panpush.app.share

import com.panpush.app.core.SiteId

/** 解析过程中的错误：带站点错误码与「人话」提示 */
class ParseException(
    message: String,
    val code: Int? = null,
    val hint: String? = null
) : Exception(message)

/** 可用外部链接直接构造的解析器 */
interface ShareParser {
    val site: SiteId

    /** 只列文件 */
    fun list(): List<com.panpush.app.core.ShareFile>

    /** 取直链（selected 为空表示全选） */
    fun resolve(
        selected: List<com.panpush.app.core.ShareFile>,
        progress: (String) -> Unit = {}
    ): List<com.panpush.app.core.DownloadItem>
}

object Router {

    private val RULES: List<Pair<Regex, SiteId>> = listOf(
        Regex("pan\\.quark\\.cn", RegexOption.IGNORE_CASE) to SiteId.QUARK,
        Regex("drive\\.uc\\.cn|fast\\.uc\\.cn", RegexOption.IGNORE_CASE) to SiteId.UC,
        Regex("pan\\.baidu\\.com|yun\\.baidu\\.com", RegexOption.IGNORE_CASE) to SiteId.BAIDU,
        // 123 有两类域名：主站 123pan.com/.cn，以及**个人分享** <uid>.share.123pan.cn
        Regex(
            "123pan\\.(com|cn)|123684\\.com|123865\\.com|123912\\.com|123952\\.com|share\\.123pan\\.cn",
            RegexOption.IGNORE_CASE
        ) to SiteId.PAN123,
        Regex("aliyundrive\\.com|alipan\\.com", RegexOption.IGNORE_CASE) to SiteId.ALI,
        Regex("pan\\.xunlei\\.com", RegexOption.IGNORE_CASE) to SiteId.XUNLEI,
        // 蓝奏域名会轮换（lanzoui/lanzoux/lanzouw/lanzouq/lanzouf/lanzn…），用宽松匹配
        Regex(
            "://(?:[a-z0-9-]+\\.)?(?:lanzo[a-z0-9-]*|lanzou[a-z0-9-]*|lanzn[a-z0-9-]*)\\.(?:com|cn)",
            RegexOption.IGNORE_CASE
        ) to SiteId.LANZOU
    )

    fun detect(url: String?): SiteId {
        if (url.isNullOrBlank()) return SiteId.UNKNOWN
        for ((re, site) in RULES) if (re.containsMatchIn(url)) return site
        return SiteId.UNKNOWN
    }

    fun isSupported(site: SiteId): Boolean = site != SiteId.UNKNOWN

    /**
     * 从链接 / 分享文本里抠出提取码。
     *
     * 支持两类写法（真机遇到的都要认）：
     *  ① **URL 参数**：`?pwd=xxxx` / `#pwd=xxxx` / `pass_code=xxxx` / `password=xxxx`
     *  ② **中文标签**（蓝奏/百度分享页复制的文本常见）：
     *     `提取码：gyhe` / `密码:abcd` / `访问码：1234` / `pwd：abcd`
     *
     * ⚠️ 只认 ① 会出现「粘贴整段分享文本后，提取码栏是空的」——
     * 这正是用户反馈的现象。
     */
    fun extractPasscode(url: String?): String {
        if (url.isNullOrBlank()) return ""
        // ① URL 参数（迅雷用 pass_code，其余站点用 pwd / passcode）
        Regex("[?&#](?:pwd|passcode|pass_code|password)=([^&#\\s]+)", RegexOption.IGNORE_CASE)
            .find(url)?.let { m ->
                return try {
                    java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
                } catch (e: Exception) {
                    m.groupValues[1]
                }
            }
        // ② 中文标签：提取码 / 密码 / 访问码 / 提取密码 / pwd（可带冒号，中英文冒号都行）
        val cn = Regex(
            "(?:提取码|提取密码|访问码|密码|口令|pwd)\\s*[:：=]?\\s*([A-Za-z0-9]{4,12})",
            RegexOption.IGNORE_CASE
        ).find(url) ?: return ""
        return cn.groupValues[1]
    }

    /**
     * 从任意文本里找出第一个**受支持**的网盘分享链接。
     *
     * 用途：
     *  · 「分享到 PanPush」的 intent
     *  · 「粘贴」按钮 / 链接输入框 —— 阿里、夸克 App 分享出来的是带前言的**分享口令**：
     *    ```
     *    我用阿里云盘分享了「xxx」，你可以不限速下载🚀
     *    复制这段内容打开「阿里云盘」App 即可获取
     *    链接：https://www.alipan.com/s/xxxxx
     *    ```
     *    必须从这里把真正的 URL 抠出来，否则整段会被当成链接。
     *
     * ⚠️ 要**遍历所有 URL**（不能只看第一个）：分享文本里可能先出现跟踪短链
     * 或「打开 App」的 landing 链接，第一个不一定是我们支持的那个。
     */
    fun findLink(text: String?): String? {
        if (text.isNullOrBlank()) return null
        for (m in Regex("https?://[^\\s\"'<>]+").findAll(text)) {
            val link = m.value.trimEnd(*TRAILING)
            if (link.isNotBlank() && detect(link) != SiteId.UNKNOWN) return link
        }
        return null
    }

    /** URL 后面常见的中英文标点尾巴（分享文本里几乎必带） */
    private val TRAILING = charArrayOf(
        '。', '，', '、', '；', '：', '！', '？', '…',
        ',', '.', ';', ':', '!', '?',
        ')', '）', ']', '】', '}', '》', '」', '』', '\'', '"'
    )

    /**
     * 构造解析器。
     * @param cookie 站点 Cookie / token 字符串（可为空）
     * @param ctx 应用 Context（蓝奏用它读「应用内登录」保存的 cookie；其它站点用不到）
     */
    fun parser(
        url: String,
        passcode: String = "",
        cookie: String? = null,
        timeoutSec: Long = 20,
        ctx: android.content.Context? = null
    ): ShareParser {
        val site = detect(url)
        val pwd = passcode.ifBlank { extractPasscode(url) }
        return when (site) {
            SiteId.QUARK -> QuarkParser(url, pwd, cookie, timeoutSec, SiteId.QUARK)
            SiteId.UC -> QuarkParser(url, pwd, cookie, timeoutSec, SiteId.UC)
            SiteId.PAN123 -> Pan123Parser(url, pwd, cookie, timeoutSec)
            SiteId.BAIDU -> BaiduParser(url, pwd, cookie, timeoutSec)
            // 迅雷 / 阿里：凭据是三件套 / token 组，不走通用 Cookie 串；由界面注入凭据仓库
            SiteId.XUNLEI -> XunleiParser(url, pwd, timeoutSec)
            SiteId.ALI -> AliyunParser(url, pwd, timeoutSec)
            // 蓝奏：分享链接匿名可解析；登过「应用内登录」时会带上 cookie（大文件/私有分享）
            SiteId.LANZOU -> LanZouParser(url, pwd, timeoutSec)
            else -> UnsupportedParser(url, site)
        }
    }

    /** 各站点凭据文件/存储键 */
    fun cookieKey(site: SiteId): String = site.key
}

/** 未实现站点（阿里 / 迅雷 / 未知） */
class UnsupportedParser(private val url: String, override val site: SiteId) : ShareParser {
    override fun list(): List<com.panpush.app.core.ShareFile> = throw ParseException(
        "${site.label} 暂未实现。这类站点需要登录 Cookie 或私有加密接口，" +
            "如有账号 Cookie 可在「凭据」页导入后配合后续版本使用。"
    )

    override fun resolve(
        selected: List<com.panpush.app.core.ShareFile>,
        progress: (String) -> Unit
    ): List<com.panpush.app.core.DownloadItem> = throw ParseException("${site.label} 暂未实现")
}
