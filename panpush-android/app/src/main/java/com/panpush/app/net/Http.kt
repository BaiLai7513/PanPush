package com.panpush.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 一次 HTTP 响应 */
class HttpResult(
    val status: Int,
    val text: String,
    val headerSetCookies: List<String> = emptyList(),
    val exceptions: String = ""
) {
    val ok: Boolean get() = status in 200..299
}

/**
 * 极简 HTTP 封装（对齐 panpush.py 的 _http/_get）。
 *
 * 刻意不启用 CookieJar：全站点都自己拼 Cookie 头，
 * 避免夸克/百度的 Set-Cookie 污染其它站点请求。
 */
object Http {

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val FORM = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Long = 20
    ): HttpResult = request("GET", url, headers, null, timeoutSec)

    fun postJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        json: String,
        timeoutSec: Long = 20
    ): HttpResult = request("POST", url, headers, json.toRequestBody(JSON), timeoutSec)

    fun postForm(
        url: String,
        headers: Map<String, String> = emptyMap(),
        form: String,
        timeoutSec: Long = 20
    ): HttpResult = request("POST", url, headers, form.toRequestBody(FORM), timeoutSec)

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: okhttp3.RequestBody?,
        timeoutSec: Long
    ): HttpResult {
        val client = if (timeoutSec == 20L) client else client.newBuilder()
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(maxOf(timeoutSec, 30), TimeUnit.SECONDS)
            .build()
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> if (v.isNotEmpty()) builder.header(k, v) }
        builder.method(method, body)
        return try {
            client.newCall(builder.build()).execute().use { resp -> read(resp) }
        } catch (e: Exception) {
            HttpResult(0, "ERR:${e.javaClass.simpleName}: ${e.message}", exceptions = e.toString())
        }
    }

    private fun read(resp: Response): HttpResult {
        // ★★ 先取**原始字节**，再自己判压缩 —— 不能直接 `body.string()`。
        //
        // 真机踩到：蓝奏下载 CDN（ESA/Tengine）**无视 `Accept-Encoding: identity`**，
        // 照样返回 gzip 正文；而 OkHttp 只要看到我们**手动设置了** Accept-Encoding，
        // 就**关闭自动解压** → `body.string()` 得到一堆二进制乱码
        // （表现：`arg1='…'` 正则匹配不到 → 解不出 WAF Cookie → Gopeed 下到 2612 字节垃圾）。
        //
        // 所以这里按 magic number 自己解：`1F 8B` = gzip，`78 01/9C/DA` = zlib(deflate)。
        val text = try {
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            when {
                bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte() ->
                    java.util.zip.GZIPInputStream(bytes.inputStream()).use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                bytes.size >= 2 && bytes[0] == 0x78.toByte() &&
                    (bytes[1] == 0x01.toByte() || bytes[1] == 0x9C.toByte() || bytes[1] == 0xDA.toByte()) ->
                    java.util.zip.InflaterInputStream(bytes.inputStream()).use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                else -> String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
        return HttpResult(
            status = resp.code,
            text = text,
            headerSetCookies = resp.headers.values("Set-Cookie")
        )
    }

    fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** 极简 JSON 取值助手（用 org.json，避免额外依赖） */
object Js {
    fun obj(text: String?): org.json.JSONObject? = try {
        if (text.isNullOrBlank()) null else org.json.JSONObject(text)
    } catch (e: Exception) {
        null
    }

    fun arr(text: String?): org.json.JSONArray? = try {
        if (text.isNullOrBlank()) null else org.json.JSONArray(text)
    } catch (e: Exception) {
        null
    }
}
