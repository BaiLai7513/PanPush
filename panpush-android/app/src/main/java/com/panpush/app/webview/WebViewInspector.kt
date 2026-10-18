package com.panpush.app.webview

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * WebViewInspector —— 交付包 `CookieInspector.kt` 的等价物（`webview_ck.py` 的 App 内版本）。
 *
 * 与 Python 版的区别：它读的是【自己 App】的 WebView 库 → **完全不需要 root**
 * （App 拥有自己的 data 目录）。只有要读【别的 App】才需要 root。
 *
 * ⚠️ 实测纠正：本 App **没有** `databases/`，`shared_prefs/` 里也**不含 token**。
 * 登录态全在：
 * ```
 * <dataDir>/app_webview/Default/Cookies                  ← Cookie（SQLite，明文）
 * <dataDir>/app_webview/Default/Local Storage/leveldb/   ← localStorage（如 123pan 的 token）
 * <dataDir>/app_webview/Default/IndexedDB/
 * <dataDir>/app_webview/Default/Session Storage/
 * ```
 * 所以「root 读不到 token」不是权限问题，而是**找错目录**（且 `run-as` 对非 debuggable 包必失败）。
 */
object WebViewInspector {

    /** 自己的 WebView profile 目录：`<dataDir>/app_webview/Default` */
    fun webviewDir(ctx: Context): File = CookieStore.webviewDir(ctx)

    fun localStorageDir(ctx: Context): File = File(webviewDir(ctx), "Local Storage/leveldb")

    fun indexedDbDir(ctx: Context): File = File(webviewDir(ctx), "IndexedDB")

    fun sessionStorageDir(ctx: Context): File = File(webviewDir(ctx), "Session Storage")

    /** profile 下的关键文件/目录是否存在（对应 `webview_ck.py --ls`） */
    fun profileFiles(ctx: Context): Map<String, Boolean> {
        val d = webviewDir(ctx)
        return linkedMapOf(
            "app_webview/Default/Cookies" to File(d, "Cookies").exists(),
            "app_webview/Default/Cookies-journal" to File(d, "Cookies-journal").exists(),
            "app_webview/Default/Cookies-wal" to File(d, "Cookies-wal").exists(),
            "app_webview/Default/Local Storage/leveldb" to localStorageDir(ctx).exists(),
            "app_webview/Default/IndexedDB" to indexedDbDir(ctx).exists(),
            "app_webview/Default/Session Storage" to sessionStorageDir(ctx).exists()
        )
    }

    /**
     * 读自己的 Cookie（先 flush，再复制 DB + journal 后读，规避「活库读空」）。
     * @param hostFilter 只保留 host 含该子串的行；null = 全部
     */
    fun readCookies(ctx: Context, hostFilter: String? = null): List<CookieStore.CookieRow> {
        CookieStore.flush()
        return CookieStore.copyAndRead(ctx, hostFilter)
    }

    /** 当前内存里的 Cookie 头（运行期权威来源） */
    fun liveCookieHeader(host: String): String? = CookieStore.cookieHeader(host)

    /** dump Local Storage（leveldb 在关闭时才整洁，这里只抽可打印串） */
    fun dumpLocalStorage(ctx: Context): Map<String, List<String>> {
        val dir = localStorageDir(ctx)
        if (!dir.isDirectory) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.isFile && f.length() > 0) {
                out[f.name] = printable(f.readBytes()).filter { it.length >= 6 }.take(200)
            }
        }
        return out
    }

    private fun printable(bytes: ByteArray, minLen: Int = 4): List<String> {
        val res = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.length >= minLen) res.add(cur.toString())
            cur.setLength(0)
        }
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            if (u in 32..126 || u >= 0x80) cur.append(u.toChar()) else flush()
        }
        flush()
        return res
    }

    /** 导出 JSON（对应 `webview_ck.py --json`） */
    fun exportJson(ctx: Context, hostFilter: String? = null): String {
        val root = JSONObject()
        root.put("package", ctx.packageName)
        root.put("dataDir", ctx.dataDir.absolutePath)
        root.put("profile", webviewDir(ctx).absolutePath)

        val files = JSONObject()
        profileFiles(ctx).forEach { (k, v) -> files.put(k, v) }
        root.put("profileFiles", files)

        val arr = JSONArray()
        readCookies(ctx, hostFilter).forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("host", r.host)
                    put("name", r.name)
                    put("value", r.value)
                    put("encrypted", r.encrypted)
                    put("secure", r.secure)
                    put("httpOnly", r.httpOnly)
                }
            )
        }
        root.put("cookies", arr)

        val ls = JSONObject()
        dumpLocalStorage(ctx).forEach { (k, list) -> ls.put(k, JSONArray(list)) }
        root.put("localStorage", ls)

        return root.toString(2)
    }

    /** 从现存 Cookie 库里挑出「像登录令牌」的条目（给界面做提示用） */
    fun tokenLikeCookies(ctx: Context, hostFragment: String): List<CookieStore.CookieRow> {
        val interesting = setOf(
            "__puus", "ctoken", "BDUSS", "STOKEN", "PANWEB", "token", "_c_WBKFRo",
            "b-user-id", "BAIDUID", "access_token"
        )
        return readCookies(ctx, hostFragment).filter { it.name in interesting }
    }
}
