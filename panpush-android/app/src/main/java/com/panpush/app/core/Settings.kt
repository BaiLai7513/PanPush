package com.panpush.app.core

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 轻量配置（SharedPreferences）：
 *  - Gopeed API 地址 / Token / 并发连接数 / 默认下载目录
 *  - 每站点凭据（Cookie / token），保存在应用私有目录，不随备份导出
 */
class Settings(context: Context) {

    private val sp = context.getSharedPreferences("panpush", Context.MODE_PRIVATE)

    var gopeedUrl: String
        get() = sp.getString(KEY_GOPEED, DEFAULT_GOPEED) ?: DEFAULT_GOPEED
        set(v) = sp.edit().putString(KEY_GOPEED, v.trim()).apply()

    var gopeedToken: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    /**
     * 并发连接数。0 = 按站点自动（对齐 CLI 的 default_connections）：
     * 迅雷 16 / 阿里 64 / 其余（夸克/UC/百度/123/蓝奏）512。
     */
    var connections: Int
        get() = sp.getInt(KEY_CONN, 0)
        set(v) = sp.edit().putInt(KEY_CONN, v.coerceIn(0, 1024)).apply()

    /** 实际推送给 Gopeed 的并发数 */
    fun connectionsFor(site: SiteId): Int {
        val c = connections
        return if (c > 0) c else defaultConnections(site)
    }

    val connectionsLabel: String
        get() = connections.let { if (it > 0) it.toString() else "自动" }

    /** 阿里云盘取到直链后是否回收转存副本（等价 ALIYUN_NO_CLEAN 的反） */
    var aliyunAutoClean: Boolean
        get() = sp.getBoolean(KEY_ALI_CLEAN, true)
        set(v) = sp.edit().putBoolean(KEY_ALI_CLEAN, v).apply()

    var downloadDir: String
        get() = sp.getString(KEY_DIR, DEFAULT_DIR) ?: DEFAULT_DIR
        set(v) = sp.edit().putString(KEY_DIR, v.trim()).apply()

    var pan123Mode: String
        get() = sp.getString(KEY_123_MODE, "auto") ?: "auto"
        set(v) = sp.edit().putString(KEY_123_MODE, v).apply()

    /**
     * 迅雷 captcha 令牌过期时，是否允许自动拉起迅雷 APP 触发刷新。
     * （等价 CLI 的 XUNLEI_NO_AUTO_REFRESH 开关，这里默认开启）
     */
    var xunleiAutoRefresh: Boolean
        get() = sp.getBoolean(KEY_XL_REFRESH, true)
        set(v) = sp.edit().putBoolean(KEY_XL_REFRESH, v).apply()

    /** 推送后展示的默认行为：true=只推送已选文件 */
    var autoPush: Boolean
        get() = sp.getBoolean(KEY_AUTO_PUSH, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO_PUSH, v).apply()

    // ---- 凭据 ----
    fun credential(site: SiteId): String = sp.getString(credKey(site), "") ?: ""

    fun setCredential(site: SiteId, value: String) {
        sp.edit().putString(credKey(site), value.trim()).apply()
    }

    fun clearCredential(site: SiteId) {
        sp.edit().remove(credKey(site)).apply()
    }

    fun credentialTime(site: SiteId): Long = sp.getLong(credTimeKey(site), 0L)

    fun markCredentialSaved(site: SiteId, at: Long = System.currentTimeMillis()) {
        sp.edit().putLong(credTimeKey(site), at).apply()
    }

    private fun credKey(site: SiteId) = "cred_${site.key}"
    private fun credTimeKey(site: SiteId) = "cred_time_${site.key}"

    companion object {
        const val DEFAULT_GOPEED = "http://127.0.0.1:9999"

        /** Gopeed 安卓端默认下载目录 */
        val DEFAULT_DIR: String =
            Environment.getExternalStorageDirectory().absolutePath + "/Download/Gopeed"

        private const val KEY_GOPEED = "gopeed_url"
        private const val KEY_TOKEN = "gopeed_token"
        private const val KEY_CONN = "connections"
        private const val KEY_DIR = "download_dir"
        private const val KEY_123_MODE = "pan123_mode"
        private const val KEY_XL_REFRESH = "xunlei_auto_refresh"
        private const val KEY_ALI_CLEAN = "aliyun_auto_clean"

        /**
         * 按站点的默认并发（实测结论，见 docs/平台对比与改进.md §3.4）：
         *  · 迅雷分享链被服务端限到 ~6 Mbps → 8~16 足够，多连无益还更易触发风控
         *  · 阿里直链**按连接限速** → 64 起（实测 64~128 最佳）
         *  · 夸克/百度/123/蓝奏：高并发确实有用 → 512
         */
        fun defaultConnections(site: SiteId): Int = when (site) {
            SiteId.XUNLEI -> 16
            SiteId.ALI -> AliyunDefaultConnections
            else -> 512
        }

        /** 与 AliyunCredStore.DEFAULT_CONNECTIONS 保持一致（避免 core 依赖 cred 包） */
        private const val AliyunDefaultConnections = 64
        private const val KEY_AUTO_PUSH = "auto_push"

        /** 常用下载目录候选 */
        fun dirCandidates(): List<String> {
            val sd = Environment.getExternalStorageDirectory().absolutePath
            return listOf(
                "$sd/Download/Gopeed",
                "$sd/Download",
                "$sd/Downloads",
                "$sd/Gopeed"
            ).filter { runCatching { File(it).exists() }.getOrDefault(false) }
                .ifEmpty { listOf("$sd/Download/Gopeed") }
        }
    }
}
