package com.panpush.app.core

/**
 * 领域模型：分享文件 / 直链 / 解析结果 / 推送结果
 */

/** 站点标识 */
enum class SiteId(val label: String) {
    QUARK("夸克网盘"),
    UC("UC 网盘"),
    PAN123("123 云盘"),
    BAIDU("百度网盘"),
    ALI("阿里云盘"),
    XUNLEI("迅雷云盘"),
    LANZOU("蓝奏云"),
    UNKNOWN("未知站点");

    /** 该站点是否需要登录态才能拿到可用直链 */
    val needsCredential: Boolean
        get() = this == QUARK || this == UC || this == PAN123 || this == BAIDU ||
            this == XUNLEI || this == ALI

    /** 是否只能靠 root 拿凭据（迅雷 captcha 只能由 APP 生成；阿里 token 在 APP 明文 DB） */
    val rootPreferred: Boolean
        get() = this == XUNLEI || this == ALI

    /** 凭据存储键（也是 `--site` 的取值） */
    val key: String
        get() = when (this) {
            QUARK, UC -> "quark"
            PAN123 -> "123"
            BAIDU -> "baidu"
            ALI -> "aliyun"
            XUNLEI -> "xunlei"
            LANZOU -> "lanzou"
            UNKNOWN -> "unknown"
        }

    /** 登录页（内置 WebView 一键登录用） */
    val loginUrl: String
        get() = when (this) {
            QUARK, UC -> "https://pan.quark.cn/"
            PAN123 -> "https://www.123pan.com/"
            BAIDU -> "https://pan.baidu.com/"
            ALI -> "https://www.alipan.com/"
            XUNLEI -> "https://pan.xunlei.com/"
            LANZOU -> "https://www.lanzou.com/"
            UNKNOWN -> "about:blank"
        }

    companion object {
        fun fromKey(key: String?): SiteId = when (key) {
            "quark" -> QUARK
            "uc" -> UC
            "123" -> PAN123
            "baidu" -> BAIDU
            "aliyun", "ali" -> ALI
            "xunlei" -> XUNLEI
            "lanzou" -> LANZOU
            else -> UNKNOWN
        }
    }
}

/** 分享内的一个文件 */
data class ShareFile(
    val fid: String,
    val name: String,
    val size: Long = 0L,
    val dir: Boolean = false,
    val etag: String = "",
    val s3KeyFlag: String = "",
    val path: String = ""
) {
    val isFile: Boolean get() = !dir
}

/** 一条可推送的下载项 */
data class DownloadItem(
    val name: String,
    val size: Long,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /** 直链来源说明：share / netdisk，便于界面提示 */
    val origin: String = ""
)

/** 解析结果 */
data class ParseResult(
    val site: SiteId,
    val files: List<ShareFile>,
    val downloads: List<DownloadItem>,
    /** 解析过程中的提示（登录态自检、回退路径说明等） */
    val notes: List<String> = emptyList()
)

/** 推送结果 */
data class PushResult(
    val name: String,
    val ok: Boolean,
    val taskId: String? = null,
    val message: String = ""
)

/** 通用结果包装：不抛异常，把错误交给界面展示 */
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    data class Err(val message: String, val detail: String = "") : Outcome<Nothing>()

    fun getOrNull(): T? = (this as? Ok)?.value
    val isOk: Boolean get() = this is Ok

    inline fun <R> map(block: (T) -> R): Outcome<R> = when (this) {
        is Ok -> Ok(block(value))
        is Err -> this
    }
}

/** 人类可读的体积 */
fun humanSize(bytes: Long?): String {
    val n = bytes ?: return "-"
    if (n < 0) return "-"
    if (n < 1024) return "${n}B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var f = n.toDouble() / 1024.0
    var i = 0
    while (f >= 1024 && i < units.size - 1) {
        f /= 1024.0
        i++
    }
    return String.format(java.util.Locale.US, "%.2f%s", f, units[i])
}
