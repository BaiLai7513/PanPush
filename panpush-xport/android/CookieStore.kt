package com.panpush.app.webview

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.webkit.CookieManager
import android.webkit.WebView
import java.io.File

/**
 * CookieStore —— WebView Cookie 的持久化与读取工具。
 *
 * 两个通道：
 *  A) 运行期（推荐）：走 CookieManager。读【自己】的登录态，无需 root。
 *  B) 直接读库（兜底/离线）：读自己 WebView 的 Cookies SQLite。
 *     —— 【自己 App 读自己的 data 目录不需要 root】；只有读【别的 App】才要 root
 *        （那种场景请用配套的 webview_ck.py）。
 *
 * 重要事实：
 *   - 本类依赖的 Cookies 库路径： context.dataDir/app_webview/Default/Cookies
 *   - value 列是明文；encrypted_value 非空才是加密cookie。
 */
object CookieStore {

    /** 【坑1】立即把内存 Cookie 落盘。凡是登录成功/退后台/退出前都要调用。 */
    fun flush() {
        CookieManager.getInstance().flush()
    }

    /**
     * 【坑2】给某个 WebView 打开 Cookie 持久化 + 第三方 Cookie。
     * 每个用于登录/浏览目标站点的 WebView 都要调一次。
     */
    fun enablePersistentCookies(webView: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }

    /** 取某域名下的完整 Cookie 头（形如 "a=1; b=2"）。没有则返回 null。 */
    fun cookieHeader(host: String): String? {
        val url = if (host.startsWith("http")) host else "https://$host"
        return CookieManager.getInstance().getCookie(url)
    }

    /** 取单个 cookie 值。 */
    fun cookieValue(host: String, name: String): String? {
        val all = cookieHeader(host) ?: return null
        for (kv in all.split(";")) {
            val t = kv.trim()
            val i = t.indexOf('=')
            if (i > 0 && t.substring(0, i) == name) return t.substring(i + 1)
        }
        return null
    }

    /** 组装给下载器（如 Gopeed / OkHttp）用的请求头。 */
    fun toRequestHeaders(host: String): Map<String, String> {
        val h = mutableMapOf<String, String>()
        cookieHeader(host)?.let { h["Cookie"] = it }
        return h
    }

    // ------------------------------------------------------------------
    // 通道 B：直接读自己 WebView 的 Cookies 库（无需 root）
    // ------------------------------------------------------------------

    private fun cookiesDbPath(ctx: Context): File =
        File(ctx.dataDir, "app_webview/Default/Cookies")

    data class CookieRow(
        val host: String, val name: String, val value: String,
        val encrypted: Boolean, val secure: Boolean, val httpOnly: Boolean
    )

    /**
     * 读取自己 App 的 WebView cookie（只读打开）。
     * 建议先在 flush() 之后、或 App 启动早期（WebView 尚未占用）调用。
     * WebView 正在写时，sqlite 只读打开一般仍可读到已落盘部分；
     * 若要绝对一致，拷贝 DB + "-journal" 到 cache 目录再读（见 copyAndRead）。
     */
    fun readOwnCookies(ctx: Context, hostFilter: String? = null): List<CookieRow> {
        val db = cookiesDbPath(ctx)
        if (!db.exists()) return emptyList()
        return try {
            val sqlite = SQLiteDatabase.openDatabase(
                db.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            sqlite.use { q(it, hostFilter) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 拷贝 DB + journal 到私有 cache 后读取，规避"活库"读空问题。 */
    fun copyAndRead(ctx: Context, hostFilter: String? = null): List<CookieRow> {
        val src = cookiesDbPath(ctx)
        if (!src.exists()) return emptyList()
        val dir = File(ctx.cacheDir, "ck_snapshot").apply { mkdirs() }
        val dst = File(dir, "Cookies")
        src.copyTo(dst, overwrite = true)
        for (suffix in listOf("-journal", "-wal")) {
            val f = File(src.parentFile, "Cookies$suffix")
            if (f.exists()) f.copyTo(File(dir, "Cookies$suffix"), overwrite = true)
        }
        return try {
            val sqlite = SQLiteDatabase.openDatabase(
                dst.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            sqlite.use { q(it, hostFilter) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun q(sqlite: SQLiteDatabase, hostFilter: String?): List<CookieRow> {
        val out = ArrayList<CookieRow>()
        val selection = if (hostFilter != null) "host_key like ?" else null
        val args = if (hostFilter != null) arrayOf("%$hostFilter%") else null
        sqlite.query("cookies",
            arrayOf("host_key", "name", "value", "encrypted_value",
                "is_secure", "is_httponly"),
            selection, args, null, null, "host_key,name").use { c ->
            while (c.moveToNext()) {
                val enc = c.getBlob(3)
                out.add(CookieRow(
                    host = c.getString(0) ?: "",
                    name = c.getString(1) ?: "",
                    value = c.getString(2) ?: "",
                    encrypted = (enc != null && enc.isNotEmpty()),
                    secure = c.getInt(4) == 1,
                    httpOnly = c.getInt(5) == 1
                ))
            }
        }
        return out
    }
}