package com.panpush.app.push

import com.panpush.app.core.DownloadItem
import com.panpush.app.core.PushResult
import com.panpush.app.net.Http
import com.panpush.app.net.Js
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gopeed 推送客户端（契约见《开发文档》§6）。
 *
 * ⚠️ 血泪教训：自定义头必须放在 `req.extra.header`，且必须是 **map[string]string（对象）**；
 * `connections` 放在 `opts.extra`。用错结构会把 Gopeed 进程打挂（端口仍 LISTEN 但不响应），
 * 所以这里只发已验证的结构，绝不试错。
 */
class GopeedClient(
    private val base: String,
    private val token: String? = null,
    private val timeoutSec: Long = 20,
    private val connections: Int = 512
) {

    private fun apiHeaders(): Map<String, String> {
        val h = mutableMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json"
        )
        if (!token.isNullOrBlank()) h["X-Api-Token"] = token
        return h
    }

    private fun baseTrimmed(): String = base.trimEnd('/')

    /** 版本信息：判断 API 是否可用 */
    fun info(): Pair<Int, String> {
        val r = Http.get("${baseTrimmed()}/api/v1/info", apiHeaders(), timeoutSec)
        return r.status to r.text
    }

    /**
     * ★ **秒级可达性探针** —— 只用来快速判断「Gopeed 活着吗」。
     *
     * 为什么不能用 [tasks]：那个接口用默认 **20 秒**超时。
     * 而 Gopeed 假死时是「端口 LISTEN 但连接超时」→ 每次探测都要等满 20 秒；
     * 推送流程里原本探测 4 次 → 最坏 **80 秒**才给出「不可达」，
     * 表现就是「解析完卡很久才出结果」。
     *
     * 这里用极短超时（默认 1.5 秒）打最轻的 `/api/v1/info`，
     * 快速拿到「通 / 不通」的结论，立刻给用户反馈。
     *
     * @return true = 可达
     */
    fun ping(timeoutSec: Long = 2): Boolean = try {
        Http.get("${baseTrimmed()}/api/v1/info", apiHeaders(), timeoutSec).status == 200
    } catch (e: Exception) {
        false
    }

    fun versionLabel(): String {
        val (st, txt) = info()
        if (st != 200) return "不可达（HTTP $st）"
        val j = Js.obj(txt)
        val v = j?.optString("version").orEmpty()
        return if (v.isBlank()) "已连接" else "已连接 · v$v"
    }

    /** 单个任务 */
    fun addTask(item: DownloadItem, name: String?, path: String?): PushResult {
        val req = JSONObject().apply {
            put("url", item.url)
            if (item.headers.isNotEmpty()) {
                put(
                    "extra", JSONObject().apply {
                        put("method", "GET")
                        put("header", JSONObject(item.headers as Map<*, *>))
                    }
                )
            }
        }
        val opts = JSONObject().apply {
            put("name", name ?: item.name)
            if (!path.isNullOrBlank()) put("path", path)
            put("extra", JSONObject().put("connections", connections))
        }
        val payload = JSONObject().apply {
            put("req", req)
            put("opts", opts)
        }
        val r = Http.postJson("${baseTrimmed()}/api/v1/tasks", apiHeaders(), payload.toString(), timeoutSec)
        val j = Js.obj(r.text)
        val ok = j?.optInt("code", -1) == 0
        val id = j?.optJSONObject("data")?.optString("id")
            ?: j?.optString("data").takeIf { it?.startsWith("{") == true }?.let { Js.obj(it)?.optString("id") }
        return PushResult(
            name = name ?: item.name,
            ok = ok,
            taskId = id,
            message = if (ok) "" else "HTTP ${r.status} ${r.text.take(160)}"
        )
    }

    /** 批量任务 */
    fun addBatch(items: List<DownloadItem>, path: String?): PushResult {
        val reqs = JSONArray()
        for (item in items) {
            val req = JSONObject().apply {
                put("url", item.url)
                if (item.headers.isNotEmpty()) {
                    put(
                        "extra", JSONObject().apply {
                            put("method", "GET")
                            put("header", JSONObject(item.headers as Map<*, *>))
                        }
                    )
                }
            }
            val opts = JSONObject().apply {
                put("name", item.name)
                if (!path.isNullOrBlank()) put("path", path)
                put("extra", JSONObject().put("connections", connections))
            }
            reqs.put(JSONObject().apply { put("req", req); put("opts", opts) })
        }
        val payload = JSONObject().put("reqs", reqs)
        val r = Http.postJson("${baseTrimmed()}/api/v1/tasks/batch", apiHeaders(), payload.toString(), timeoutSec)
        val j = Js.obj(r.text)
        val ok = j?.optInt("code", -1) == 0
        return PushResult(
            name = "${items.size} 个文件（批量）",
            ok = ok,
            message = if (ok) "" else "HTTP ${r.status} ${r.text.take(160)}"
        )
    }

    /** 任务列表（用于「任务」页） */
    fun tasks(): List<GopeedTask> {
        val r = Http.get("${baseTrimmed()}/api/v1/tasks", apiHeaders(), timeoutSec)
        val j = Js.obj(r.text) ?: return emptyList()
        val arr = j.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<GopeedTask>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            out.add(parseTask(it))
        }
        return out
    }

    /** 单个任务状态（「解析后」的交互式进度用） */
    fun task(id: String): GopeedTask? {
        val r = Http.get("${baseTrimmed()}/api/v1/tasks/$id", apiHeaders(), timeoutSec)
        val j = Js.obj(r.text) ?: return null
        val d = j.optJSONObject("data") ?: return null
        return parseTask(d)
    }

    private fun parseTask(it: JSONObject): GopeedTask {
        var downloaded = 0L
        var total = 0L
        it.optJSONObject("progress")?.let { p ->
            downloaded = p.optLong("downloaded", 0L)
            total = p.optLong("used", 0L).takeIf { v -> v > 0 } ?: p.optLong("total", 0L)
            if (total <= 0) {
                // 有些版本把总量放在 progress.files[].size 里
                val arr = p.optJSONArray("files")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        total += arr.optJSONObject(i)?.optLong("size", 0L) ?: 0L
                    }
                }
            }
        }
        val pct = if (total > 0) downloaded * 100.0 / total else 0.0
        return GopeedTask(
            id = it.optString("id"),
            name = it.optString("name"),
            status = it.optString("status"),
            progress = pct,
            downloaded = downloaded,
            total = total
        )
    }

    /** 暂停 / 继续：Gopeed 用 `PATCH /api/v1/tasks/{id}` body `{status}` */
    fun setStatus(id: String, status: String): Boolean {
        val body = JSONObject().put("status", status).toString()
        val builder = okhttp3.Request.Builder()
            .url("${baseTrimmed()}/api/v1/tasks/$id")
            .patch(
                okhttp3.RequestBody.create(
                    "application/json; charset=utf-8".toMediaType(), body
                )
            )
        apiHeaders().forEach { (k, v) -> builder.header(k, v) }
        return try {
            Http.client.newCall(builder.build()).execute().use { resp ->
                val j = Js.obj(resp.body?.string())
                resp.isSuccessful || j?.optInt("code", -1) == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    fun pause(id: String): Boolean = setStatus(id, "pause")
    fun resume(id: String): Boolean = setStatus(id, "running")

    /** 删除任务（默认连文件一起删） */
    fun remove(id: String, force: Boolean = true): Boolean {
        val builder = okhttp3.Request.Builder()
            .url("${baseTrimmed()}/api/v1/tasks/$id?force=$force")
            .delete()
        apiHeaders().forEach { (k, v) -> builder.header(k, v) }
        return try {
            Http.client.newCall(builder.build()).execute().use { resp ->
                val j = Js.obj(resp.body?.string())
                resp.isSuccessful || j?.optInt("code", -1) == 0
            }
        } catch (e: Exception) {
            false
        }
    }
}

data class GopeedTask(
    val id: String,
    val name: String,
    val status: String,
    val progress: Double,
    val downloaded: Long = 0L,
    val total: Long = 0L
)
