package com.panpush.app.webview

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.webkit.CookieManager
import android.webkit.WebView
import java.io.File

/**
 * CookieStore —— WebView Cookie 的持久化与读取（对齐交付包的 `CookieStore.kt`）。
 *
 * 两个通道：
 *  A) 运行期（推荐）：走 `CookieManager`。读**自己**的登录态，**不需要 root**。
 *  B) 直接读库（兜底/离线/验证持久化）：读自己 WebView 的 Cookies SQLite。
 *
 * 关键事实（真机实测）：
 *  - 自己的 Cookies 库路径：`<dataDir>/app_webview/Default/Cookies`
 *  - `value` 列是明文；`encrypted_value` 非空才是加密 cookie
 *  - 读**自己的** data 目录不需要 root；只有读**别的 App** 才要 root
 */
object CookieStore {

    /**
     * 【坑1】立即把内存 Cookie 落盘。
     * 凡是登录成功 / 退到后台 / 退出前都要调用，否则进程被杀即丢。
     */
    fun flush() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
    }

    /**
     * 【坑2】给某个 WebView 打开 Cookie 持久化 + 第三方 Cookie。
     * 跨域 SSO 必需（百度 `wappass → pan.baidu.com`、夸克 passport）。
     */
    fun enablePersistentCookies(webView: WebView) {
        try {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        } catch (_: Exception) {
        }
    }

    /** 取某域名下的完整 Cookie 头（形如 `a=1; b=2`）。没有则返回 null。 */
    fun cookieHeader(host: String): String? {
        val url = if (host.startsWith("http")) host else "https://$host"
        return try {
            CookieManager.getInstance().getCookie(url)
        } catch (e: Exception) {
            null
        }
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

    /** 是否已登录：指定域名下出现该 cookie 即视为已登录 */
    fun hasCookie(host: String, name: String): Boolean = cookieValue(host, name) != null

    /** 组装给下载器（Gopeed / OkHttp）用的请求头。 */
    fun toRequestHeaders(host: String): Map<String, String> {
        val h = mutableMapOf<String, String>()
        cookieHeader(host)?.let { if (it.isNotBlank()) h["Cookie"] = it }
        return h
    }

    // ------------------------------------------------------------------
    // 通道 B：直接读自己 WebView 的 Cookies 库（无需 root）
    // ------------------------------------------------------------------

    fun webviewDir(ctx: Context): File = File(ctx.dataDir, "app_webview/Default")

    fun cookiesDbPath(ctx: Context): File = File(webviewDir(ctx), "Cookies")

    data class CookieRow(
        val host: String,
        val name: String,
        val value: String,
        val encrypted: Boolean,
        val secure: Boolean,
        val httpOnly: Boolean
    )

    /**
     * 读**自己** App 的 WebView cookie（只读打开）。
     * 建议先 [flush]；若要绝对一致，用 [copyAndRead]。
     */
    fun readOwnCookies(ctx: Context, hostFilter: String? = null): List<CookieRow> {
        val db = cookiesDbPath(ctx)
        if (!db.exists()) return emptyList()
        return try {
            SQLiteDatabase.openDatabase(db.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                .use { q(it, hostFilter) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 【坑5】拷贝 DB + `-journal` / `-wal` 到私有 cache 后读取，
     * 规避「活的 Cookies 库读空」问题（WebView 进程持锁 + journal 模式）。
     */
    fun copyAndRead(ctx: Context, hostFilter: String? = null): List<CookieRow> {
        val src = cookiesDbPath(ctx)
        if (!src.exists()) return emptyList()
        val dir = File(ctx.cacheDir, "ck_snapshot").apply { mkdirs() }
        return try {
            src.copyTo(File(dir, "Cookies"), overwrite = true)
            for (suffix in listOf("-journal", "-wal")) {
                val f = File(src.parentFile, "Cookies$suffix")
                if (f.exists()) f.copyTo(File(dir, "Cookies$suffix"), overwrite = true)
            }
            SQLiteDatabase.openDatabase(
                File(dir, "Cookies").absolutePath, null, SQLiteDatabase.OPEN_READONLY
            ).use { q(it, hostFilter) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun q(sqlite: SQLiteDatabase, hostFilter: String?): List<CookieRow> {
        val out = ArrayList<CookieRow>()
        val selection = if (hostFilter != null) "host_key like ?" else null
        val args = if (hostFilter != null) arrayOf("%$hostFilter%") else null
        sqlite.query(
            "cookies",
            arrayOf("host_key", "name", "value", "encrypted_value", "is_secure", "is_httponly"),
            selection, args, null, null, "host_key,name"
        ).use { c ->
            while (c.moveToNext()) {
                val enc = try {
                    c.getBlob(3)
                } catch (e: Exception) {
                    null
                }
                out.add(
                    CookieRow(
                        host = c.getString(0) ?: "",
                        name = c.getString(1) ?: "",
                        value = c.getString(2) ?: "",
                        encrypted = (enc != null && enc.isNotEmpty()),
                        secure = c.getInt(4) == 1,
                        httpOnly = c.getInt(5) == 1
                    )
                )
            }
        }
        return out
    }
}
