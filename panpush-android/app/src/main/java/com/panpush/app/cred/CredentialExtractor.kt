package com.panpush.app.cred

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.panpush.app.core.SiteId
import java.io.File

/**
 * 凭据提取（root 通道）—— getcookie.py 的 Kotlin 版。
 *
 * 原理（docs §7）：安卓上不少 App 的 WebView Cookie 库是**明文 SQLite**，
 * 123 云盘的登录 token 则放在 `shared_prefs/login_user.xml`（明文）。
 * 这些都在 `/data/data/<pkg>/` 私有目录，非 root 读不到 → 必须经 su 复制出来再解析。
 *
 * 三个通道（与 CLI 版一致）：
 *   A) 内置 WebView 登录 → 自己的 CookieStore，零权限（见 WebLogin / WebCookieReader）
 *   B) root 提取（本类）
 *   C) 手动导入（界面上粘贴）
 */
class CredentialExtractor(private val context: Context) {

    data class Hit(val source: String, val cookie: String)

    /**
     * 候选包名兜底（用于展示「装了哪些 App」，提升成功率）。
     *
     * ⚠️ 这只是**兜底**：真正的包名列表由 [AppScanner] 用 root 扫出来
     * （`pm list packages` + `ls /data/user/0`），因为普通身份受包可见性限制。
     * 覆盖：迅雷 / 阿里 / 123 / 百度 / 夸克 / UC。
     */
    private val knownPackages = listOf(
        "com.xunlei.downloadprovider",
        "com.alicloud.databox",
        "com.mfcloudcalculate.networkdisk",
        "com.baidu.netdisk",
        "com.quark.browser",
        "com.UCMobile",
        // 其它可能的浏览器/网盘壳（历史与多渠道）
        "com.quark.browser.canary",
        "com.ucmobile",
        "com.p123.networkdisk"
    )

    /** 最近一次应用列表扫描结果（供诊断与 UI 复用） */
    @Volatile
    var lastScan: AppScanner.ScanResult? = null
        private set

    /** 跑一次应用列表扫描（第 0 步），结果缓存 */
    fun scanApps(force: Boolean = false): AppScanner.ScanResult {
        lastScan?.let { if (!force) return it }
        // ★ 传 context：非 root 时走 PackageManager（配合 Manifest 的
        //   `<queries>` + `QUERY_ALL_PACKAGES`），这样撤掉 root 也看得见已装的网盘 App
        val r = AppScanner.scan(context)
        lastScan = r
        return r
    }

    /** 扫到的包名（root）—— 仅供诊断展示，**不要拿去做全包遍历** */
    fun scannedPackages(): List<String> = scanApps().allPackages

    private val cacheDir: File = File(context.cacheDir, "cred").apply { mkdirs() }

    /** 扫描时是否遇到「加密库」（v10 加密，非 root 也不能解） */
    private var sawEncryptedDb = false
    private var encryptedDbFiles = emptyList<String>()

    /** 站点 → 关键令牌名 */
    private fun keyName(site: SiteId): String = when (site) {
        SiteId.BAIDU -> "BDUSS"
        SiteId.QUARK, SiteId.UC -> "__puus"
        SiteId.PAN123 -> "token"
        else -> ""
    }

    private fun hostMatches(site: SiteId, host: String): Boolean = when (site) {
        SiteId.QUARK, SiteId.UC -> host.contains("quark.cn", true) || host.contains("uc.cn", true)
        SiteId.BAIDU -> host.contains("baidu.com", true)
        else -> false
    }

    /** Cookie 字段顺序（拼 Cookie 头用） */
    private fun order(site: SiteId): List<String> = when (site) {
        SiteId.QUARK, SiteId.UC -> listOf("__puus", "__pus", "__kp", "__uid", "b-user-id", "__puus.t")
        SiteId.BAIDU -> listOf("BDUSS", "STOKEN", "BAIDUID", "BDUSS_BFESS", "PTOKEN", "SAPTOKEN")
        else -> emptyList()
    }

    private fun hostPriority(site: SiteId): List<String> = when (site) {
        SiteId.BAIDU -> listOf(
            "pan.baidu.com", ".baidu.com", "yun.baidu.com",
            "d.pcs.baidu.com", "c.pcs.baidu.com",
            ".passport.baidu.com", ".wappass.baidu.com"
        )
        else -> emptyList()
    }

    /**
     * 文件是否存在。走全局挂载命名空间（[RootShell.execScript]）——
     * 这是「按文件存在性探测」的基础，也是本次最关键修复。
     */
    private fun fileExists(path: String): Boolean = RootShell.exists(path)

    // ------------------------------------------------------------------
    // 123 云盘：token 在 shared_prefs / 日志里
    // ------------------------------------------------------------------
    private fun extract123(): Hit? {
        val roots = listOf(
            "/data/data/com.mfcloudcalculate.networkdisk",
            "/data/user/0/com.mfcloudcalculate.networkdisk"
        )
        for (r in roots) {
            val prefs = "$r/shared_prefs/login_user.xml"
            RootShell.readFile(prefs)?.let { xml ->
                Regex("name=\"userToken\">\\s*(?:Bearer\\s+)?([A-Za-z0-9._\\-]{20,})")
                    .find(xml)?.let { return Hit(prefs, "token=${it.groupValues[1]}") }
            }
        }
        // 兜底：app_flutter/logs/<date>
        for (r in roots) {
            val files = RootShell.listDir("$r/app_flutter/logs")
            for (f in files.sortedDescending().take(3)) {
                val path = "$r/app_flutter/logs/$f"
                val txt = RootShell.readFile(path, 2 * 1024 * 1024) ?: continue
                Regex("Bearer\\s+([A-Za-z0-9._\\-]{40,})").find(txt)?.let {
                    return Hit(path, "token=${it.groupValues[1]}")
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // 夸克 / 百度：明文 Cookie 库
    // ------------------------------------------------------------------
    /**
     * 某站点的候选 cookie 库（只查该站点自己的 App，不碰别的包）。
     * 顺序：应用列表命中的包名 → 内置候选包名（含多渠道变体）。
     */
    private fun candidateCookieDbs(site: SiteId): List<String> {
        val pkgs = LinkedHashSet<String>()
        scanApps().hits[site]?.let { pkgs.add(it) }
        PKG_FOR_SITE[site]?.forEach { pkgs.add(it) }
        val out = LinkedHashSet<String>()
        for (p in pkgs) out.addAll(findCookieDbs("/data/data/$p"))
        return out.toList()
    }

    companion object {
        /** 站点 → 该站凭据可能所在的包名（含多渠道变体） */
        private val PKG_FOR_SITE: Map<SiteId, List<String>> = mapOf(
            SiteId.QUARK to listOf("com.quark.browser", "com.quark.browser.canary"),
            SiteId.UC to listOf("com.UCMobile", "com.ucmobile"),
            SiteId.BAIDU to listOf("com.baidu.netdisk"),
            SiteId.PAN123 to listOf("com.mfcloudcalculate.networkdisk", "com.p123.networkdisk")
        )
    }

    /**
     * 一把梭找出某站点 App 的 cookie 库（**一条 su 命令搞定**）。
     *
     * ⚠️ toybox 的 `sh -c` 对 `for … do … done` 的处理很脆：直接把 `for` 交给
     * `su -c` 会报 `syntax error: unexpected 'do'`（真机日志实锤，百度/夸克的明文库
     * 读取就是这么静默失败的）。所以这里显式包一层 `sh -c`，
     * 命令里用 `；`（全角分号会被当普通字符）—— 不，正确做法是用单引号包裹脚本、
     * 且**字符串里不放单引号**，这样 `su -c` 收到的是完整的一整条脚本。
     */
    private fun findCookieDbs(pkg: String): List<String> {
        // 注意：整段用双引号包住变量，内部只用双引号转义 —— 不让 su 的 shell 拆开 for 语句
        val dirs = listOf(
            "$pkg/app_u46_webview", "$pkg/app_u4_webview", "$pkg/app_uc_webview",
            "$pkg/app_webview", "$pkg/app_webview_default", "$pkg/app_chrome",
            "$pkg/app_webview_com.baidu.netdisk", "$pkg/app_webview_com.baidu.netdisk:p2p"
        )
        // 直接展开成 N 条 ls（不用 for 循环），彻底绕开 sh 的语法坑；
        // 8 条命令在**一次** su 往返里跑完，开销可以接受。
        val cmd = buildString {
            dirs.forEach { d ->
                append("/system/bin/ls -1 ${RootShell.shQuote("$d/Default/Cookies")} 2>/dev/null; ")
                append("/system/bin/ls -1 ${RootShell.shQuote("$d/Cookies")} 2>/dev/null; ")
            }
            append("echo __END__")
        }
        val out = RootShell.execGlobal(cmd, 25).stdout
            .lines().map { it.trim() }
            .filter { it.isNotEmpty() && it.endsWith("Cookies") && !it.contains("No such file") }
        return out.distinct()
    }

    private fun copyOut(remote: String): File? {
        val safe = remote.trim('/').replace('/', '_')
        val dest = File(cacheDir, safe)
        // 主库 + -wal / -shm（保证 WAL 中的数据也能被读到）
        //
        // ⚠️ 必须走 RootShell.execScript（= nsenter 全局挂载命名空间）：
        //    app 的受限命名空间里 `cp /data/data/<别的App>/...` 会 **静默失败**
        //    （`2>/dev/null` 把 "No such file" 吞了，还照样 echo DONE），
        //    于是复制出一个 0 字节/旧文件 → 后面解析当然啥都读不到。
        //    这正是「root 读不到凭据」的真凶。
        val cmd = buildString {
            append("cp -f ${RootShell.shQuote(remote)} ${RootShell.shQuote(dest.absolutePath)}; ")
            append("cp -f ${RootShell.shQuote("$remote-wal")} ${RootShell.shQuote(dest.absolutePath + "-wal")}; ")
            append("cp -f ${RootShell.shQuote("$remote-shm")} ${RootShell.shQuote(dest.absolutePath + "-shm")}; ")
            append("chmod 666 ${RootShell.shQuote(dest.absolutePath)} ${RootShell.shQuote(dest.absolutePath + "-wal")} ${RootShell.shQuote(dest.absolutePath + "-shm")} 2>/dev/null; ")
            append("echo __DONE__")
        }
        val r = RootShell.execScript(cmd, 30)
        if (!r.stdout.contains("__DONE__")) return null
        // 关键校验：复制结果必须真实可用（>0 且是 SQLite 库）
        if (!dest.exists() || dest.length() <= 0L) return null
        val head = try {
            dest.inputStream().use { ins ->
                val b = ByteArray(16)
                val n = ins.read(b)
                if (n > 0) String(b, 0, n, Charsets.ISO_8859_1) else ""
            }
        } catch (e: Exception) {
            ""
        }
        if (!head.startsWith("SQLite format 3")) {
            // 不是 SQLite 库 → 复制没成功（或路径不对），别当成功
            return null
        }
        return dest
    }

    private fun extractCookieDb(site: SiteId): Hit? {
        val key = keyName(site)
        val encryptedFiles = ArrayList<String>()
        // 每次扫描前重置实况（诊断要的是**本次**结果）
        lastDbRowCounts = emptyMap()
        lastHosts = emptyList()
        lastNames = emptyList()
        lastSawEncrypted = false
        val hosts = LinkedHashSet<String>()
        val names = LinkedHashSet<String>()
        var sawAnyRow = false
        // 只探目标站点 App 的 cookie 库（一条命令 / 每个包），绝不遍历全量包列表
        for (remote in candidateCookieDbs(site)) {
            val local = copyOut(remote) ?: continue
            val rows = readCookies(local)
            if (rows.isEmpty()) {
                encryptedFiles.add(remote)
                continue
            }
            sawAnyRow = true
            var sawEncrypted = false
            val matched = ArrayList<Triple<String, String, String>>() // host,name,value
            for (row in rows) {
                if (row.host.isNotBlank()) hosts.add(row.host)
                if (row.name.isNotBlank()) names.add(row.name)
                if (row.value.isNullOrEmpty()) {
                    if (!row.encrypted.isNullOrEmpty()) sawEncrypted = true
                    continue
                }
                if (hostMatches(site, row.host)) matched.add(Triple(row.host, row.name, row.value))
            }
            if (sawEncrypted) encryptedFiles.add(remote)
            if (matched.isEmpty()) continue
            if (matched.none { it.second == key }) continue
            // 按 host 优先级 + 字段顺序拼 Cookie
            val pri = hostPriority(site)
            val best = HashMap<String, Pair<Int, String>>()
            for ((h, n, v) in matched) {
                val score = pri.indexOf(h).let { if (it < 0) 99 else it }
                val cur = best[n]
                if (cur == null || score < cur.first) best[n] = score to v
            }
            val parts = ArrayList<String>()
            for (n in order(site)) best[n]?.let { parts.add("$n=${it.second}") }
            if (parts.isEmpty()) continue
            return Hit(remote, parts.joinToString("; "))
        }
        sawEncryptedDb = encryptedFiles.isNotEmpty()
        encryptedDbFiles = encryptedFiles
        lastHosts = hosts.toList().sorted()
        lastNames = names.toList().sorted()
        lastSawEncrypted = sawEncryptedDb
        // sawAnyRow 为 false 说明连一行 cookie 都没读到（库打不开 / 表结构变了）
        if (!sawAnyRow) lastHosts = emptyList()
        return null
    }

    private data class CookieRow(
        val host: String,
        val name: String,
        val value: String?,
        val encrypted: String?
    )

    // ---- 最近一次 cookie 库扫描的实况（供诊断精确说明「到底为什么读不到」）----
    /** 扫过的库 → 读到的 cookie 行数（-1 = 库打不开） */
    @Volatile
    var lastDbRowCounts: Map<String, Int> = emptyMap()
        private set
    /** 扫到的所有 host（去重，供人看「库里到底有哪些站点」） */
    @Volatile
    var lastHosts: List<String> = emptyList()
        private set
    /** 扫到的所有 cookie 名（去重） */
    @Volatile
    var lastNames: List<String> = emptyList()
        private set
    /** 是否读到加密值（v10 加密说明库是新的、非 root 也解不开） */
    @Volatile
    var lastSawEncrypted: Boolean = false
        private set

    private fun readCookies(db: File): List<CookieRow> {
        val out = ArrayList<CookieRow>()
        var db2: SQLiteDatabase? = null
        var readable = -1
        try {
            db2 = SQLiteDatabase.openDatabase(
                db.absolutePath, null, SQLiteDatabase.OPEN_READONLY
            )
            db2.rawQuery("select host_key,name,value,encrypted_value from cookies", null).use { c ->
                readable = 0
                while (c.moveToNext()) {
                    out.add(
                        CookieRow(
                            host = c.getString(0) ?: "",
                            name = c.getString(1) ?: "",
                            value = c.getString(2),
                            encrypted = try {
                                c.getString(3)
                            } catch (e: Exception) {
                                null
                            }
                        )
                    )
                    readable++
                }
            }
        } catch (e: Exception) {
            // 读不了当空处理（readable 保持 -1）
        } finally {
            try {
                db2?.close()
            } catch (_: Exception) {
            }
        }
        lastDbRowCounts = lastDbRowCounts + (db.path to readable)
        return out
    }

    // ------------------------------------------------------------------

    /**
     * root 状态。
     *
     * ⚠️ 按《诊断与改进建议》§7 P1-5：这里**只报 su 状态**，不再掺进「找不到 App」，
     * 免得用户第一反应就是「是不是 root / 是不是我冻结了 App」。
     * 「App 是否装了 / 有没有数据」由 [diagnose] 单独回答。
     *
     * 补充（标准 4「冻结场景」）：读凭据走的是**磁盘文件**
     * （`nsenter --mount -t 1 -- cat/ls /data/data/<pkg>/…`），
     * 与 App 是否在运行、是否被「冻结/禁用（`pm disable` / 强行停止）」**无关** ——
     * 冻结只影响 App 自己能不能跑，不会让它的数据目录消失。
     * 所以这里的措辞刻意区分「su 拿不到」和「文件不在」两种情况。
     */
    fun rootStatus(): String {
        if (!RootShell.hasSu()) {
            return "su：未找到（设备未 root，或 APatch/Magisk 对本应用隐藏了 su）" +
                " —— root 直读不可用，但**每个站点都还有「应用内登录」可用**" +
                "（六个站点都支持）"
        }
        val probe = RootShell.exec("id", 12)
        return if (probe.stdout.contains("uid=0")) {
            "su：已获得（uid=0）—— 读凭据走磁盘文件，与目标 App 是否运行/被冻结无关"
        } else {
            "su：存在但当前不可用" + if (probe.combined.isNotBlank()) "（${probe.combined.lines().first()}）" else ""
        }
    }

    /**
     * 站点数据目录探测：**按文件是否存在**判断，不用 `PackageManager`。
     *
     * ⚠️ 这就是本次「更差了」的根因修复（《诊断与改进建议》§3.1）：
     * 原实现用普通 `PackageManager` 判断「App 是否安装」，但本机 `targetSdk=35` 强制
     * **包可见性**，Manifest 的 `<queries>` 没声明这些包名 → 看不到任何第三方 App →
     * 直接判「未检测到 APP」→ **整条 root 直读在第 1 步就被短路**。
     * 现在改成：先 root 扫应用列表（第 0 步）→ 再直接探文件，能读到就用。
     */
    fun diagnose(site: SiteId): List<String> {
        val lines = ArrayList<String>()
        if (!RootShell.hasSu()) {
            lines.add("su：未找到（未 root / 应用被隐藏 su）→ **root 直读这条通道不可用**")
            lines.add("  但本站仍有其它通道：")
            lines.add("   · **应用内登录**（WebView，不需要 root）" +
                if (SiteProfile.of(site).webLoginUrl != null) "" else "（该站点不支持）")
            lines.add("   · 手动粘贴通道已移除")
            return lines
        }
        fun hasFile(p: String): Boolean = fileExists(p)

        // 第 0 步：应用列表（root 扫，绕开包可见性）
        val scan = scanApps()
        val pkg = scan.hits[site]
        lines.add(
            if (pkg != null) "应用列表：命中 $pkg"
            else "应用列表：未命中候选包名（${AppScanner.packageCandidatesOf(site).joinToString(", ")}）"
        )
        val dir = pkg?.let { d ->
            listOf("/data/user/0/$d", "/data/data/$d").firstOrNull { hasFile(it) }
        }
        if (pkg != null) {
            lines.add(if (dir != null) "数据目录：$dir（存在）" else "数据目录：/data/data/$pkg 不存在或不可读")
            // 冻结/禁用只影响 App 自身能不能跑，**不影响** root 直读它的数据目录（标准 4）。
            // 这里明确标出来，避免用户以为「读不到 = su 没授权」。
            if (AppScanner.isFrozen(pkg)) {
                lines.add("该 App 当前处于「冻结/禁用」状态 —— 不影响 root 直读（读的是磁盘文件）；" +
                    "但如果它需要生成新令牌（迅雷 captcha / 续期），就得先在系统里解冻并打开一次")
            }
        }

        when (site) {
            SiteId.XUNLEI -> {
                val base = "/data/data/com.xunlei.downloadprovider/shared_prefs"
                listOf(
                    "auth_pref.xml" to "access_token / refresh_token",
                    "xl-acc-captcha-token.xml" to "captcha 令牌",
                    "__device_info_new__.xml" to "device_id"
                ).forEach { (f, what) ->
                    lines.add("${if (hasFile("$base/$f")) "[有]" else "[无]"} $base/$f  ← $what")
                }
            }
            SiteId.ALI -> {
                val db = "/data/data/com.alicloud.databox/databases/0.db"
                lines.add("${if (hasFile(db)) "[有]" else "[无]"} $db  ← tb_user_info(access/refresh/drive_id)")
            }
            SiteId.PAN123 -> {
                val f = "/data/data/com.mfcloudcalculate.networkdisk/shared_prefs/login_user.xml"
                lines.add("${if (hasFile(f)) "[有]" else "[无]"} $f  ← userToken(JWT)")
            }
            SiteId.BAIDU -> {
                val base = "/data/data/com.baidu.netdisk/app_webview_com.baidu.netdisk/Default"
                listOf("Cookies", "Cookies-journal", "Cookies-wal").forEach { f ->
                    lines.add("${if (hasFile("$base/$f")) "[有]" else "[无]"} $base/$f")
                }
            }
            SiteId.QUARK, SiteId.UC -> {
                val p = pkg ?: if (site == SiteId.QUARK) "com.quark.browser" else "com.UCMobile"
                val dirs = RootShell.execGlobal("ls -d /data/data/$p/*webview*", 15).stdout
                    .lines().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains("No such file") }
                if (dirs.isEmpty()) {
                    lines.add("[无] /data/data/$p/*webview*（App 未装或没有 WebView 目录）")
                } else {
                    dirs.forEach { lines.add("[有] $it") }
                    // ★ 实况诊断：真跑一遍读取，把「库里到底有什么」摊开讲 ——
                    //   目的是**证明 root 读为什么无效**（不是提供读取功能；界面上已无该按钮）
                    extractCookieDb(site)
                    lines.add("── 实况（真读了一遍，用于证明 root 读无效）──")
                    if (lastDbRowCounts.isEmpty()) {
                        lines.add("  ✗ 一个 cookie 库都没能复制/打开")
                    } else {
                        lastDbRowCounts.forEach { (path, n) ->
                            lines.add(
                                if (n < 0) "  ✗ 打不开：${path.substringAfterLast("/data/data/")}"
                                else "  ✓ $n 行：${path.substringAfterLast("/data/data/")}"
                            )
                        }
                    }
                    if (lastHosts.isNotEmpty()) {
                        lines.add("  库里的 host（${lastHosts.size} 个）：" +
                            lastHosts.take(8).joinToString(", ") +
                            if (lastHosts.size > 8) " …" else "")
                    }
                    if (lastNames.isNotEmpty()) {
                        lines.add("  库里的 cookie 名（${lastNames.size} 个）：" +
                            lastNames.take(14).joinToString(", ") +
                            if (lastNames.size > 14) " …" else "")
                    }
                    if (lastSawEncrypted) {
                        lines.add("  ⚠️ 部分值是加密的（Chrome v10）→ 该库需 root + 解密才可用")
                    }
                    // 精确结论
                    val hasQuarkHost = lastHosts.any { it.contains("quark.cn", true) }
                    val hasPanHost = lastHosts.any { it.contains("pan.quark.cn", true) }
                    val hasPuus = lastNames.any { it == "__puus" }
                    lines.add(
                        when {
                            !hasQuarkHost ->
                                "  ✗ 结论：这些库里**根本没有 quark.cn 的 cookie**"
                            !hasPanHost || !hasPuus ->
                                "  ✗ 结论：只有 b./m.quark.cn 的 tracking cookie，" +
                                    "**没有 pan.quark.cn、没有 __puus** → root 读这条路无效"
                            else ->
                                "  ✓ 结论：库里**有 __puus**，但本次没拼出（可能是加密值读不出）"
                        }
                    )
                    lines.add("   → 所以夸克**不提供 root 直读**（本页没有该按钮）")
                    lines.add("   → 正解：点「应用内登录」——桌面 UA 打开 pan.quark.cn 登录，" +
                        "成功后由 WebView 的 CookieManager 读回 __puus" +
                        "（Native 接口，不受 httpOnly 限制）")
                    lines.add("   → 无 root 时请用「应用内登录」（桌面 UA，可拿到 __puus）")
                }
            }
            else -> lines.add("该站点无需 root 直读")
        }
        return lines
    }

    /** 站点是否检测到相关 App（root 扫描；**只用于文案**，不作为流程开关） */
    fun detectedPackages(site: SiteId): List<String> {
        val scan = scanApps()
        val hit = scan.hits[site]
        return if (hit != null) listOf(hit) else emptyList()
    }

    /**
     * 主入口：经 root 提取站点凭据。
     *
     * ⚠️ 不再用「App 是否安装」做前置判断 —— 直接探文件，能读到就用（§10.4）。
     * 失败时用 [diagnose] 给出**精确**原因（su 不可用 / 文件不存在 / 库里没有该令牌），
     * 而不是笼统的「未检测到 APP 或 su 未授权」。
     *
     * ⚠️ **夸克/UC 不在 root 直读范围内**：真机实测三个浏览器 Cookie 库
     * （`app_u46_webview` / `app_u4_webview` / `app_webview`，32+9+0 行）
     * 里只有 `b./m.quark.cn` 的 tracking cookie，**没有 `pan.quark.cn`、没有 `__puus`**
     * → root 读这条路**无效**。夸克改为「应用内登录 + 桌面 UA」，
     * 由 `CookieManager.getCookie()`（Native 接口，不受 httpOnly 限制）读回 `__puus`。
     */
    fun extract(site: SiteId): Result<Hit> {
        return try {
            val hit = when (site) {
                SiteId.PAN123 -> extract123()
                SiteId.BAIDU -> extractCookieDb(site)
                // 夸克/UC：root 读无效，明确告诉调用方走应用内登录
                SiteId.QUARK, SiteId.UC -> null
                else -> null
            }
            if (hit != null) Result.success(hit)
            else Result.failure(ExtractionException(buildFailureMessage(site)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 精确失败原因：先给探测结果，再给可操作建议 */
    private fun buildFailureMessage(site: SiteId): String {
        val probe = diagnose(site)
        val head = when (site) {
            SiteId.PAN123 -> "没能从 123 云盘 APP 里读到登录 token。"
            SiteId.BAIDU -> "没能从百度网盘 APP 的 WebView 库里读到 BDUSS。"
            SiteId.QUARK, SiteId.UC ->
                "夸克/UC **不支持 root 直读**（本机浏览器库里没有 __puus）。"
            else -> "没能读到 ${site.label} 的凭据。"
        }
        val advice = when (site) {
            SiteId.PAN123 ->
                "  · 先在 123 云盘 APP 里登录一次（token 在 shared_prefs/login_user.xml）\n" +
                    "  · 已登录仍失败 → APP 里重新登录一次（会话过期）\n" +
                    "  · 无 root 时请用「应用内登录」"
            SiteId.BAIDU ->
                "  · 先在百度网盘 APP 里登录一次（它的 WebView 库是明文的）\n" +
                    (if (sawEncryptedDb) {
                        "  · 只找到加密库（v10 加密读不了）：${encryptedDbFiles.firstOrNull()}\n"
                    } else "") +
                    "  · 用「应用内登录」即可拿到 BDUSS"
            SiteId.QUARK, SiteId.UC ->
                "  · **夸克/UC 不走 root 直读**：真机逐库核对过（见上「实况」），" +
                    "本机浏览器 Cookie 库里没有 `pan.quark.cn`、没有 `__puus` → 这条路无效。\n" +
                    "  · 正解：点「应用内登录」——登录页会用**桌面 UA**（移动 UA 会被 302 到下载页），" +
                    "登录成功后自动从 WebView 的 `CookieManager` 读回 `__puus`" +
                    "（Native 接口，不受 httpOnly 限制）。\n" +
                    "  · 无 root 时请用「应用内登录」"
            else -> "  · 改用「应用内登录」"
        }
        return buildString {
            append(head).append('\n')
            append("探测结果：\n")
            probe.forEach { append("  ").append(it).append('\n') }
            append(advice)
        }
    }

    class ExtractionException(message: String) : Exception(message)
}
