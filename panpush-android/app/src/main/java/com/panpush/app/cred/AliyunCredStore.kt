package com.panpush.app.cred

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.panpush.app.net.Http
import com.panpush.app.net.Js
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 阿里云盘凭据（对齐 aliyun.py 的凭据层）。
 *
 * 阿里云盘 APP 把 token **明文**存在 SQLite：
 *   /data/data/com.alicloud.databox/databases/0.db → 表 tb_user_info
 *   字段：user_id / access_token / refresh_token / expire_time / drive_id
 *
 * 因此与迅雷一样走 root 直读；非 root 用「应用内登录」。
 * access_token 过期或快过期时用 refresh_token 调 `POST /v2/account/token` 续期。
 *
 * 注意：其它文件（AUTH_APP_INFO.xml / injectCookieNew / AUTH_DEVICEINFO.xml）都是**加密态**，无用。
 */
class AliyunCredStore(private val context: Context) {

    companion object {
        const val PKG = "com.alicloud.databox"
        const val DB = "/data/data/$PKG/databases/0.db"

        /** 阿里云盘登录库所在目录 */
        const val ALI_DB_DIR = "/data/data/$PKG/databases"

        /** 登录库文件名（`tb_user_info` 在这个库 + 它的 `-wal` 里，真机核对过） */
        const val ALI_DB_NAME = "0.db"

        const val API = "https://api.aliyundrive.com"
        const val AUTH = "https://auth.aliyundrive.com"

        /**
         * **网页端** client_id（`www.alipan.com` 前端 JS 里的值，也出现在阿里 App 内嵌 H5）。
         *
         * 依据 `aliyun-login/阿里云盘应用内登录-测试与实现.md` §1.1。
         * WebView 登录拿到的 refresh_token 必须用它来续期（同 client 才认）。
         */
        const val WEB_CLIENT_ID = "25dzX3vbYqktVxyX"

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        const val X_CANARY = "client=web,app=adrive,version=v2.3.1"
        const val REFERER = "https://www.alipan.com/"

        /** 第三方直链体积门控（实测 66MB 可 / 127MB 不可；取 100MB 作保守阈值） */
        const val SIZE_GATE = 100L * 1024 * 1024

        /** 阿里直链按连接限速（实测 64~128 连接最佳） */
        const val DEFAULT_CONNECTIONS = 64

        /** 打开分享链接的兜底（非会员也能用阿里 App 打开落地页） */
        const val COOKIE_GUIDE =
            "阿里云盘需要登录态（取直链必须 Authorization）：\n" +
                "  A) root 直读（推荐）：确保【阿里云盘 APP 已登录】，本程序会自动读其明文数据库；\n" +
                "  B) 无 root：用「应用内登录」登 www.alipan.com（自动抓 access/refresh/drive_id）\n" +
                "       access_token=eyJ...\n" +
                "       refresh_token=xxxx\n" +
                "       drive_id=123456789\n" +
                "  ⚠️ 阿里对第三方直链有体积门控：≥100MB 的文件取不到直链（免费账号）。"

        /** 'yyyy-MM-ddTHH:mm:ssZ' → epoch 秒；失败返回 0 */
        fun parseIso(s: String?): Long {
            if (s.isNullOrBlank() || s.length < 19) return 0
            return try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                (fmt.parse(s.substring(0, 19))?.time ?: 0L) / 1000
            } catch (e: Exception) {
                0L
            }
        }
    }

    data class Creds(
        val accessToken: String,
        val refreshToken: String,
        val driveId: String,
        /** 'yyyy-MM-ddTHH:mm:ssZ' */
        val expireTime: String,
        val source: String,
        /**
         * 签发这组令牌的 client。
         * 空 = App client；[WEB_CLIENT_ID] = 网页 client（WebView 登录来的）。
         *
         * ⚠️ 续期必须用**同一个 client**（见 [saveManualCreds] 的说明与
         * `aliyun-login` 文档 §1.2 的对照实验）。
         */
        val clientId: String = ""
    ) {
        /** 是否是「网页登录」拿到的令牌 */
        val isWebToken: Boolean get() = clientId == WEB_CLIENT_ID
        /** 距离过期还有多少秒；无过期信息返回 -1 */
        fun remainingSec(): Long {
            val exp = parseIso(expireTime)
            return if (exp <= 0) -1 else exp - System.currentTimeMillis() / 1000
        }

        /**
         * 是否该续期。
         *
         * ⚠️ 真机实测：阿里 APP DB 里落盘的 `expire_time`（2 小时）**几乎总是已经过期**
         * （APP 只在内存里持有新 token，不一定回写 DB），所以这里必须把「已经过期」
         * 也算作需要续期 —— 原来的 `left in 0..margin` 会把负数漏掉，导致永远不续期。
         */
        fun expireSoon(marginSec: Long = 120): Boolean {
            val exp = parseIso(expireTime)
            if (exp <= 0) return false        // 无过期信息 → 当作可用，出错再续期
            val left = exp - System.currentTimeMillis() / 1000
            return left <= marginSec          // 已过期（负）或即将过期 → 都要续期
        }
    }

    class CredsError(message: String) : Exception(message)

    private val manualPrefs = context.getSharedPreferences("aliyun_manual", Context.MODE_PRIVATE)
    private val cacheDir: File = File(context.cacheDir, "ali").apply { mkdirs() }

    /** 最近一次复制出来的 wal 字节数（诊断用；-1 = 不存在） */
    @Volatile
    var lastWalBytes: Long = -1L
        private set

    /** 最近一次读到的表名（诊断用） */
    @Volatile
    var lastTables: List<String> = emptyList()
        private set

    /** 最近一次失败原因（诊断用；空 = 没失败） */
    @Volatile
    var lastError: String = ""
        private set

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------
    private fun parseIso(s: String?): Long = Companion.parseIso(s)

    // ------------------------------------------------------------------
    // root 直读 SQLite
    // ------------------------------------------------------------------
    /**
     * 经 root 复制阿里云盘登录库（db + wal + shm）到应用缓存，再查询 `tb_user_info`。
     *
     * ⚠️ 两个真机踩到的坑，都必须处理，否则一定「读不到 token」：
     *  1. **必须走全局挂载命名空间**（[RootShell.execGlobal]）：APatch/Magisk 的应用隔离
     *     会给 app 一个被过滤的挂载视图，在里面 `cp /data/data/com.alicloud.databox/...`
     *     会**静默失败**（`No such file`），只留下 0 字节/旧文件。
     *  2. **表只在 WAL 里**：实测 `databases/0.db` 是 **12KB 存根**（本身不含 `tb_user_info`），
     *     真正的表在 `0.db-wal`（24752B）里；所以：
     *       · `-wal` / `-shm` **必须一起复制**；
     *       · 打开时要用**可写模式**让 SQLite 回放 WAL；
     *       · 用 `cd` + 裸文件名复制，避免嵌套引号造成的静默失败。
     *
     * 📌 真机核对过：`databases/0.db` 的库+wal 里 `tb_user_info` 出现 5 次，
     * 而 `06fa…<user_id>.db`（3.4MB）**是任务/文件缓存库**（0 次）——
     * 所以**不要**去猜主库名，就用 `0.db` + 它的 wal。
     */
    fun readAppCreds(): Creds? {
        val dest = File(cacheDir, "ali_0.db")
        try {
            dest.delete()
            File(dest.absolutePath + "-wal").delete()
            File(dest.absolutePath + "-shm").delete()
        } catch (_: Exception) {
        }
        if (!copySet(ALI_DB_DIR, ALI_DB_NAME, dest)) return null
        return readCredsFrom(dest)
    }

    /** 复制一组活库（db + wal + shm）；用 `cd` + 裸文件名，避免嵌套引号静默失败 */
    private fun copySet(srcDir: String, name: String, destDb: File): Boolean {
        val cmd = buildString {
            append("cd ${RootShell.shQuote(cacheDir.absolutePath)}; ")
            append("rm -f ali_0.db ali_0.db-wal ali_0.db-shm; ")
            append("cp -f ${RootShell.shQuote("$srcDir/$name")} ali_0.db; ")
            append("cp -f ${RootShell.shQuote("$srcDir/$name-wal")} ali_0.db-wal; ")
            append("cp -f ${RootShell.shQuote("$srcDir/$name-shm")} ali_0.db-shm; ")
            append("chmod 666 ali_0.db ali_0.db-wal ali_0.db-shm 2>/dev/null; ")
            append("echo __DONE__")
        }
        val r = try {
            RootShell.execGlobal(cmd, 30)
        } catch (e: Exception) {
            lastError = "执行复制命令异常：${e.javaClass.simpleName}: ${e.message}"
            logA("copySet 异常：$lastError")
            return false
        }
        logA("copySet cmd=<<<$cmd>>>")
        if (!r.stdout.contains("__DONE__")) {
            lastError = "复制命令没跑完（exit=${if (r.ok) 0 else 1}）：" +
                (r.stderr.ifBlank { r.stdout }.take(200))
            logA("copySet 失败：$lastError")
            return false
        }
        if (!destDb.exists() || destDb.length() <= 0L) {
            lastError = "复制后 ali_0.db 不存在或为空（cwd 应为 ${cacheDir.absolutePath}）" +
                " | stdout=[${r.stdout.trim().take(180)}] stderr=[${r.stderr.trim().take(180)}]"
            logA("copySet 失败：$lastError")
            return false
        }
        val head = try {
            destDb.inputStream().use { ins ->
                val b = ByteArray(16)
                val n = ins.read(b)
                if (n > 0) String(b, 0, n, Charsets.ISO_8859_1) else ""
            }
        } catch (e: Exception) {
            ""
        }
        if (!head.startsWith("SQLite format 3")) {
            lastError = "复制出来的不是 SQLite 库（头=${head.take(16)}）"
            logA("copySet 失败：$lastError")
            return false
        }
        return true
    }

    private fun logA(msg: String) {
        try {
            android.util.Log.i("PanPushAli", msg)
        } catch (_: Exception) {
        }
    }

    /** 诊断用：列出某个库（含已回放的 wal）里的表名 */
    private fun listTables(db: SQLiteDatabase): List<String> {
        val out = ArrayList<String>()
        try {
            db.rawQuery("select name from sqlite_master where type='table'", null).use { c ->
                while (c.moveToNext()) out.add(c.getString(0) ?: "")
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 从已复制的库（含 wal/shm）读凭据 */
    private fun readCredsFrom(dest: File): Creds? {
        val wal = File(dest.absolutePath + "-wal")
        lastWalBytes = if (wal.exists()) wal.length() else -1L
        // 表可能只存在于 WAL 里 → 允许可写打开，让 SQLite 回放 WAL；不行再退回只读
        var db: SQLiteDatabase? = try {
            SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            try {
                SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            } catch (e2: Exception) {
                null
            }
        }
        if (db == null) {
            lastError = "库打不开（wal=${lastWalBytes}B）"
            return null
        }
        lastTables = listTables(db)
        if (!lastTables.contains("tb_user_info")) {
            lastError = "库里没有 tb_user_info（表：${lastTables.take(6).joinToString(", ")}； wal=${lastWalBytes}B）"
            try {
                db.close()
            } catch (_: Exception) {
            }
            return null
        }

        val rows = ArrayList<Map<String, String>>()
        try {
            db.rawQuery("select * from tb_user_info", null).use { c ->
                while (c.moveToNext()) {
                    val row = HashMap<String, String>()
                    for (i in 0 until c.columnCount) {
                        val name = c.getColumnName(i)
                        row[name] = try {
                            c.getString(i) ?: ""
                        } catch (e: Exception) {
                            ""
                        }
                    }
                    rows.add(row)
                }
            }
        } catch (e: Exception) {
            lastError = "查询 tb_user_info 失败：${e.message}"
            return null
        } finally {
            try {
                db.close()
            } catch (_: Exception) {
            }
        }
        if (rows.isEmpty()) {
            lastError = "tb_user_info 是空表（wal=${lastWalBytes}B）"
            return null
        }
        lastError = ""
        // 按 expire_time 倒序取最新
        val best = rows.maxByOrNull { parseIso(it["expire_time"]) } ?: return null
        val at = best["access_token"] ?: ""
        val rt = best["refresh_token"] ?: ""
        if (at.isBlank() || rt.isBlank()) return null
        return Creds(
            accessToken = at,
            refreshToken = rt,
            driveId = best["drive_id"] ?: "",
            expireTime = best["expire_time"] ?: "",
            source = "APP 直读（$DB）"
        )
    }

    fun appInstalled(): Boolean = RootShell.exists("/data/data/$PKG") || RootShell.exists(DB)

    // ------------------------------------------------------------------
    // 手动落盘 / 文本解析
    // ------------------------------------------------------------------
    fun readManualCreds(): Creds? {
        val at = manualPrefs.getString("access_token", "") ?: ""
        if (at.isBlank()) return null
        return Creds(
            accessToken = at,
            refreshToken = manualPrefs.getString("refresh_token", "") ?: "",
            driveId = manualPrefs.getString("drive_id", "") ?: "",
            expireTime = manualPrefs.getString("expire_time", "") ?: "",
            source = manualPrefs.getString("source", "") ?: "已保存",
            // 记住「这组令牌是哪个 client 签发的」——续期必须用同一个 client
            clientId = manualPrefs.getString("client_id", "") ?: ""
        )
    }

    /**
     * 落盘。
     *
     * @param clientId **签发这组令牌的 client**。
     *   空 = App client（走 `auth.aliyundrive.com/v2/account/token` 续期）；
     *   [WEB_CLIENT_ID] = 网页 client（走 `api.aliyundrive.com/token/refresh` 续期）。
     * @param expireTime access_token 的过期时间（ISO8601，来自续期响应）。
     *   ★ **必须存** —— 否则 [Creds.expireSoon] 判断不出「快过期」，
     *   `ensureFresh()` 就**永远不会主动续期**，只能等接口报错后才被动续一次。
     *   这是「非 root 下应用内登录的凭据过一会儿就不能用」的直接原因。
     *
     * ⚠️ 依据 `aliyun-login/阿里云盘应用内登录-测试与实现.md` §1.2 的真机对照实验：
     * **refresh_token 与签发它的 client 绑定** —— 拿 App 的 refresh_token 配网页 client_id
     * 会被拒（`InvalidParameter.ClientId`）。所以必须记下 client，续期时配对使用，**不能混用**。
     */
    fun saveManualCreds(
        accessToken: String,
        refreshToken: String,
        driveId: String,
        clientId: String = "",
        source: String = "已保存",
        expireTime: String = ""
    ) {
        val e = manualPrefs.edit()
            .putString("access_token", accessToken.trim())
            .putString("refresh_token", refreshToken.trim())
            .putString("drive_id", driveId.trim())
            .putString("client_id", clientId.trim())
            .putString("source", source)
        // 只在拿到新过期时间时覆盖，避免续期响应缺字段反而把已知的过期时间清掉
        if (expireTime.isNotBlank()) e.putString("expire_time", expireTime)
        e.apply()
    }

    fun clearManualCreds() {
        manualPrefs.edit().clear().apply()
    }

    fun hasManualCreds(): Boolean = readManualCreds() != null

    /** 解析 `key=value` 文本（支持换行 / 分号分隔，或整串就是 access_token） */
    fun parseCredsText(text: String?): Creds? {
        if (text.isNullOrBlank()) return null
        val d = HashMap<String, String>()
        for (line in text.split(Regex("[;\\n]+"))) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val i = l.indexOf('=')
            if (i > 0) d[l.substring(0, i).trim()] = l.substring(i + 1).trim()
        }
        var at = d["access_token"] ?: ""
        if (at.isBlank()) {
            at = Regex("access_token=([A-Za-z0-9._\\-]+)").find(text)?.groupValues?.get(1) ?: ""
        }
        if (at.isBlank() && Regex("^[A-Za-z0-9._\\-]{20,}$").matches(text.trim())) {
            at = text.trim()
        }
        if (at.isBlank()) return null
        return Creds(
            accessToken = at,
            refreshToken = d["refresh_token"] ?: "",
            driveId = d["drive_id"] ?: "",
            expireTime = "",
            source = "已保存"
        )
    }

    /** 实时直读优先（最新），其次手动落盘 */
    fun load(): Creds? = readAppCreds() ?: readManualCreds()

    /** 给解析器用：读到凭据后自动续期（阿里 DB 里的 token 常常已过期） */
    fun loadReady(): Creds? {
        val c = load() ?: return null
        return try {
            ensureFresh(c)
        } catch (e: Exception) {
            c
        }
    }

    // ------------------------------------------------------------------
    // 续期
    // ------------------------------------------------------------------
    /** 最近一次续期失败的原因（排障用：否则只看到「续期失败」没法定位） */
    @Volatile
    var lastRefreshError: String = ""
        private set

    /**
     * 用 refresh_token 换新 token；失败返回 null。
     *
     * ## 两条**不能混用**的续期通道
     *
     * 依据 `aliyun-login/阿里云盘应用内登录-测试与实现.md` §1.2 的真机对照实验：
     *
     * | 令牌来源 | 续期端点 | 入参 |
     * |---|---|---|
     * | **App**（`0.db`，client 空） | `POST auth.aliyundrive.com/v2/account/token` | `grant_type=refresh_token` + `refresh_token` |
     * | **网页**（`localStorage.token`） | `POST api.aliyundrive.com/token/refresh` | `client_id=25dzX3vbYqktVxyX` + `refresh_token` |
     *
     * 实测：拿 App 的 refresh_token 配网页 client_id → `InvalidParameter.ClientId`
     * （说明 token 本身有效，只是**与签发它的 client 绑定**）。
     */
    fun refresh(refreshToken: String, driveId: String? = null, clientId: String = ""): Creds? {
        lastRefreshError = ""
        if (refreshToken.isBlank()) return null

        // ★ **双通道回退**（对齐 `panpush借鉴Voyage阿里登录-方案.md` §2.3 的建议）：
        //   真机实测发现「网页令牌 + 网页 client」也可能被拒
        //   （`HTTP 400 InvalidParameter.ClientId`），说明**令牌实际归属的 client
        //   未必等于我们以为的那个**（阿里网页登录链路可能签发另一 client 的令牌）。
        //   所以先按记录里的 client 试，失败再换另一条通道 —— 能刷成功就行，
        //   并把成功通道的 clientId 回传（下次直接用对的）。
        val preferWeb = clientId == WEB_CLIENT_ID
        val order = if (preferWeb) listOf(WEB_CLIENT_ID, "") else listOf("", WEB_CLIENT_ID)
        var firstErr = ""
        for (cid in order) {
            val r = refreshVia(refreshToken, driveId, cid)
            if (r != null) {
                lastRefreshError = ""
                return r
            }
            if (firstErr.isBlank()) firstErr = lastRefreshError
        }
        lastRefreshError = firstErr.ifBlank { "两条通道都失败" }
        return null
    }

    /** 单通道续期：`cid` 为空 = App client；= [WEB_CLIENT_ID] = 网页 client */
    private fun refreshVia(refreshToken: String, driveId: String?, cid: String): Creds? {
        // ---- 通道 A：网页 client ----
        if (cid == WEB_CLIENT_ID) {
            val body = org.json.JSONObject().apply {
                put("client_id", WEB_CLIENT_ID)
                put("refresh_token", refreshToken)
            }
            val r = Http.postJson(
                "$API/token/refresh",
                mapOf(
                    "User-Agent" to UA,
                    "Accept" to "application/json, text/plain, */*",
                    // 参考实现明确带上这个头（阿里网页端的 XHR 习惯）
                    "x-requested-with" to "XMLHttpRequest",
                    "Referer" to "https://www.alipan.com/"
                ),
                body.toString(), 20
            )
            val j = Js.obj(r.text)
            val at = j?.optString("access_token").orEmpty()
            if (at.isBlank()) {
                // ★ 记下**真实原因**（否则只看到「续期失败」没法定位）
                lastRefreshError = "HTTP ${r.status} " + (
                    j?.optString("code").orEmpty()
                        .ifBlank { j?.optString("message").orEmpty() }
                        .ifBlank { r.text.take(200) }
                    )
                return null
            }
            return Creds(
                accessToken = at,
                refreshToken = j?.optString("refresh_token").orEmpty().ifBlank { refreshToken },
                driveId = (j?.optString("default_drive_id").orEmpty()
                    .ifBlank { j?.optString("drive_id").orEmpty() })
                    .ifBlank { driveId ?: "" },
                expireTime = j?.optString("expire_time").orEmpty(),
                source = "网页 token 续期",
                clientId = WEB_CLIENT_ID
            )
        }

        // ---- 通道 B：App client（原逻辑不变）----
        val body = org.json.JSONObject().apply {
            put("grant_type", "refresh_token")
            put("refresh_token", refreshToken)
        }
        val r = Http.postJson(
            "$AUTH/v2/account/token",
            mapOf("User-Agent" to UA, "Accept" to "application/json, text/plain, */*"),
            body.toString(), 20
        )
        val j = Js.obj(r.text)
        val at = j?.optString("access_token").orEmpty()
        if (at.isBlank()) {
            lastRefreshError = "HTTP ${r.status} " + (
                j?.optString("code").orEmpty()
                    .ifBlank { j?.optString("message").orEmpty() }
                    .ifBlank { r.text.take(200) }
                )
            return null
        }
        return Creds(
            accessToken = at,
            refreshToken = j?.optString("refresh_token").orEmpty().ifBlank { refreshToken },
            driveId = (j?.optString("default_drive_id").orEmpty()
                .ifBlank { j?.optString("drive_id").orEmpty() })
                .ifBlank { driveId ?: "" },
            expireTime = j?.optString("expire_time").orEmpty(),
            source = "refresh"
        )
    }

    /**
     * 确保凭据新鲜：快过期则续期一次（**用签发它的那个 client**）。
     *
     * ★ 非 root 场景（应用内登录拿到的**网页**令牌）的关键保障 ——
     * 网页 access_token 只活 2 小时，没有这一步「过一会儿就不能用」。
     *
     * 两种触发：
     *  1. **已知过期时间**且快到期 → 直接续（[Creds.expireSoon]）；
     *  2. **过期时间未知**但有 refresh_token → 也**主动试一次**续期
     *     （宁可多打一次接口，也不要等真报错；失败则原样返回，不影响使用）。
     *     这条专治「网页登录保存时没带 expire_time → 永远不续期」的老问题。
     */
    fun ensureFresh(c: Creds): Creds {
        if (c.refreshToken.isBlank()) return c
        val known = parseIso(c.expireTime) > 0
        val need = if (known) c.expireSoon() else true
        if (!need) return c
        val n = refresh(c.refreshToken, c.driveId, c.clientId)
        if (n == null) return c
        // ★ 续期成功 → **立刻落盘**（含新的 expire_time），否则下次启动又回到旧令牌。
        //   注意 `n.clientId`：双通道回退时**以实际成功的那条通道为准**，下次直接用对的。
        val okClient = n.clientId.ifBlank { c.clientId }
        saveManualCreds(
            accessToken = n.accessToken,
            refreshToken = n.refreshToken.ifBlank { c.refreshToken },
            driveId = n.driveId.ifBlank { c.driveId },
            clientId = okClient,
            source = if (okClient == WEB_CLIENT_ID) "网页 token 续期" else "refresh",
            expireTime = n.expireTime
        )
        return if (n.driveId.isBlank()) n.copy(driveId = c.driveId) else n
    }
}
