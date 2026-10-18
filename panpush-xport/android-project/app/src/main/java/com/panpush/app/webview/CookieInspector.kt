package com.panpush.app.webview

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CookieInspector —— `webview_ck.py` 的 **Kotlin 等价物**（App 内版本）。
 *
 * 与 Python 版的区别：
 *   - 它读的是【自己 App】的 WebView 库 → **完全不需要 root**（App 拥有自己的 data 目录）。
 *   - 只有要读【别的 App】时，才需要 root（那用 webview_ck.py 走 `su`）。
 *
 * 覆盖 webview_ck.py 的全部职责：
 *   1. 列出 WebView profile 路径与文件清单         → webviewDir() / profileFiles()
 *   2. 读 Cookie（含 host 过滤，明文/加密标注）      → readCookies()
 *   3. dump Local Storage（leveldb 可打印串）        → dumpLocalStorage()
 *   4. 导出 JSON                                     → exportJson()
 */
object CookieInspector {

    /** 自己的 WebView profile 目录：<dataDir>/app_webview/Default */
    fun webviewDir(ctx: Context): File = File(ctx.dataDir, "app_webview/Default")

    /** 列出 profile 下的关键文件/目录（对应 --ls）。 */
    fun profileFiles(ctx: Context): Map<String, Boolean> {
        val d = webviewDir(ctx)
        return mapOf(
            "Default/Cookies" to File(d, "Cookies").exists(),
            "Default/Cookies-journal" to File(d, "Cookies-journal").exists(),
            "Default/Cookies-wal" to File(d, "Cookies-wal").exists(),
            "Default/Local Storage/leveldb" to localStorageDir(ctx).exists(),
            "Default/IndexedDB" to File(d, "IndexedDB").exists(),
            "Default/Session Storage" to File(d, "Session Storage").exists(),
        )
    }

    /** Local Storage leveldb 目录。 */
    fun localStorageDir(ctx: Context): File = File(webviewDir(ctx), "Local Storage/leveldb")

    /**
     * 读自己的 Cookie。
     * 用 CookieStore.copyAndRead（复制 DB + journal 后读），规避「活库读空」。
     * @param hostFilter 只保留 host 含该子串的行；null = 全部。
     */
    fun readCookies(ctx: Context, hostFilter: String? = null): List<CookieStore.CookieRow> {
        // 先落盘，保证内存里的 Cookie 也进库
        CookieStore.flush()
        return CookieStore.copyAndRead(ctx, hostFilter)
    }

    // ------------------------------------------------------------------
    // Local Storage（leveldb）—— 只做可打印串抽取（与 Python 版一致）
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // JSON 导出（对应 --json）
    // ------------------------------------------------------------------
    fun exportJson(ctx: Context, hostFilter: String? = null): String {
        val root = JSONObject()
        root.put("package", ctx.packageName)
        root.put("profile", webviewDir(ctx).absolutePath)

        val files = JSONObject()
        profileFiles(ctx).forEach { (k, v) -> files.put(k, v) }
        root.put("profileFiles", files)

        val arr = JSONArray()
        readCookies(ctx, hostFilter).forEach { r ->
            arr.put(JSONObject().apply {
                put("host", r.host)
                put("name", r.name)
                put("value", r.value)
                put("encrypted", r.encrypted)
                put("secure", r.secure)
                put("httpOnly", r.httpOnly)
            })
        }
        root.put("cookies", arr)

        val ls = JSONObject()
        dumpLocalStorage(ctx).forEach { (k, list) ->
            ls.put(k, JSONArray(list))
        }
        root.put("localStorage", ls)

        return root.toString(2)
    }
}