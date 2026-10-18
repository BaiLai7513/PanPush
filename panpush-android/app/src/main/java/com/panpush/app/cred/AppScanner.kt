package com.panpush.app.cred

import com.panpush.app.core.SiteId

/**
 * 应用列表扫描 —— **读取凭据之前的第 0 步**
 * （对齐 `applist-scan/应用列表扫描.md`）。
 *
 * ## 为什么必须先扫，且必须用 root 扫
 *
 * 1. **决定哪些站点可 root 直读**：目标 App 没装 → root 直读无意义，
 *    但仍可走「应用内登录」（通道按站点能力分流，见 SiteProfile.channelsFor）。
 * 2. **绕开包可见性**：Android 11+ 的包可见性会把普通 App 的
 *    `PackageManager.getPackageInfo` / `pm list packages` 挡住 —— 实测同一台机
 *    **本应用身份只能看到 1 个包，root 能看到 688 个**。
 *    这正是「未检测到 APP」的根因，所以**绝不用非 root 的包查询**。
 * 3. **包名会变 / 多渠道 / 大小写敏感**：同一站点可能有多个包名
 *    （夸克可能 `com.quark.*`，UC 是 `com.UCMobile` **大写**，123 可能 `com.p123.*`），
 *    所以用「**候选列表 + 忽略大小写兜底 + 子串模糊兜底**」，而不是写死一个。
 * 4. **文件存在性才是最终判据**：扫不到包名 ≠ 不能读；文件在 → 就能读。
 *    所以应用列表只作**提示**，真正的开关是 [CredentialExtractor.diagnose] 的文件探测。
 */
object AppScanner {

    /**
     * 站点 → 候选包名（第一个是首选）。
     * 来源：`applist-scan/应用列表扫描.md` §2 + 两个导出包的实测记录。
     */
    private val CANDIDATES: Map<SiteId, List<String>> = mapOf(
        SiteId.XUNLEI to listOf("com.xunlei.downloadprovider"),
        SiteId.ALI to listOf("com.alicloud.databox"),
        SiteId.PAN123 to listOf(
            "com.mfcloudcalculate.networkdisk",
            "com.p123.networkdisk", "com.p123.disk", "com.123pan"
        ),
        SiteId.BAIDU to listOf("com.baidu.netdisk"),
        SiteId.QUARK to listOf("com.quark.browser"),
        // UC 大小写敏感：首选就是大写那个
        SiteId.UC to listOf("com.UCMobile", "com.ucmobile"),
        // ★ 蓝奏云**没有官方 Android 版**，只有第三方客户端 —— 候选按真机实测填。
        //   真机上装了两个「蓝云」：`com.tooyoung.lanzou`（Lua）与
        //   `com.lancloud.lancloud`（Flutter）。
        //   ⚠️ 这两个的凭据**都是加密的**（前者 `files/account.lua` 为二进制密文，
        //   后者用 `FlutterSecureStorage`）→ 所以蓝奏**不做 root 凭据读取**，
        //   这两个包名只用来告诉你「装了哪个」，凭据请走「应用内登录」。
        SiteId.LANZOU to listOf(
            "com.tooyoung.lanzou",
            "com.lancloud.lancloud",
            "com.lanzou.cloud",
            "com.lanzou.app"
        )
    )

        /** 站点 → 模糊兜底关键字（包名包含其中之一就算命中） */
    private val FUZZY: Map<SiteId, List<String>> = mapOf(
        SiteId.XUNLEI to listOf("xunlei"),
        SiteId.ALI to listOf("alicloud.databox", "aliyun", "alipan"),
        SiteId.PAN123 to listOf("mfcloudcalculate", "123pan", "p123"),
        SiteId.BAIDU to listOf("baidu.netdisk"),
        SiteId.QUARK to listOf("quark"),
        SiteId.UC to listOf("ucmobile", "uc.browser")
    )

    data class ScanResult(
        /** root 身份扫到的全部包名 */
        val allPackages: List<String>,
        /** 包名来源：pm / dataDir / 都失败 */
        val source: String,
        /** 站点 → 命中的包名（null = 没扫到） */
        val hits: Map<SiteId, String?>,
        /** 每个站点的一条人话结论 */
        val lines: List<String>
    ) {
        /** 本机可 root 直读的站点 */
        val readableSites: List<SiteId> get() = hits.filterValues { it != null }.keys.toList()

        /**
         * **一行简短结论**（运行日志用）。
         *
         * 用户反馈：原来打的是「[有] 百度网盘 → com.baidu.netdisk (pm path)」这种
         * 多行长文本，进运行日志后**被截断显示不全**。这里只保留：
         * 「有没有 + 网盘名」，不显示包名与来源。
         */
        fun oneLine(): String {
            val have = readableSites
            val total = hits.size
            return if (have.isEmpty()) {
                "未检测到网盘应用（0/$total）"
            } else {
                "已装 ${have.size}/$total：" + have.joinToString("、") { it.label }
            }
        }

        fun summary(): String = oneLine()

        fun detail(): String = lines.joinToString("\n")
    }

    /**
     * 查该包是否被**冻结/禁用**（`pm disable` → 出现在 `pm list packages -d` 里）。
     *
     * ⚠️ 只用于**文案**，绝不作为读取开关：冻结只影响 App 自己能不能跑，
     * 不会让 `/data/data/<pkg>` 消失 —— 所以冻结状态下 root 直读**照样成功**
     * （《诊断与改进建议》§7 P2-6：优先直接读缓存令牌，冻结提示要明确而不误报「su 未授权」）。
     */
    fun isFrozen(pkg: String): Boolean {
        val out = RootShell.execGlobal("pm list packages -d", 20).stdout
        return out.lines().any { it.trim() == "package:$pkg" }
    }

    /** 某站点的候选包名（供 UI / 诊断展示） */
    fun packageCandidatesOf(site: SiteId): List<String> = CANDIDATES[site].orEmpty()

    /**
     * 扫应用列表。
     *
     * ⚠️ 只查**目标站点的候选包名**，不做「列出设备全部 688 个包」的全量遍历 ——
     * 后者又慢又把日志刷爆（真机踩过：把全量包名喂给 cookie 库探测 = 上万次 `su` 调用，
     * 卡死几十秒还在刷 `com.oplus.aimemory` 之类无关包）。这里逐候选问一次，命中即停。
     *
     * ## 来源优先级（★ 现在**非 root 也能用**）
     *
     * | 顺序 | 手段 | 是否需要 root |
     * |---|---|---|
     * | ① | `PackageManager.getPackageInfo`（配合 Manifest 的 `<queries>` + `QUERY_ALL_PACKAGES`） | ❌ 不需要 |
     * | ② | `su pm path <pkg>` | ✅ |
     * | ③ | `su` 看 `/data/data/<pkg>` 是否存在 | ✅ |
     *
     * 为什么要加 ①：原来**只有 root 通道**，撤掉 root 后整个列表全「无」，
     * 运行日志里就出现「读取不到网盘应用」——那不是没装，是**看不见**。
     * 声明 `QUERY_ALL_PACKAGES` 后 PackageManager 就能枚举，属于**普通权限**（安装即生效，无弹窗）。
     *
     * @param ctx 传了才走 PackageManager 通道（推荐总是传）
     */
    fun scan(ctx: android.content.Context? = null): ScanResult {
        val lines = ArrayList<String>()
        val hits = LinkedHashMap<SiteId, String?>()
        val seen = ArrayList<String>()
        var okCount = 0
        val pm = ctx?.packageManager

        for (site in SiteId.entries) {
            val cands = CANDIDATES[site] ?: continue
            var hit: String? = null
            var usedSrc = ""
            for (c in cands) {
                // ① PackageManager（**不需要 root**）
                if (pm != null && installedByPm(pm, c)) {
                    hit = c
                    usedSrc = "pm"
                    break
                }
                // ② pm path（root）：存在 → 输出 "package:<apk路径>"
                if (RootShell.execGlobal("pm path $c", 15).stdout.contains("package:")) {
                    hit = c
                    usedSrc = "pm path"
                    break
                }
                // ③ 数据目录兜底（比 pm 更抗屏蔽）
                if (RootShell.exists("/data/data/$c") || RootShell.exists("/data/user/0/$c")) {
                    hit = c
                    usedSrc = "dataDir"
                    break
                }
            }
            hits[site] = hit
            if (hit != null) {
                okCount++
                seen.add(hit)
            }
            // 只写「有 / 无 + 网盘名」——包名与来源不进日志（用户要求简洁）
            lines.add(if (hit != null) "[有] ${site.label}" else "[无] ${site.label}")
        }

        val src = when {
            okCount == 0 -> "全未命中（未装 / 包名不符）"
            else -> "PackageManager / pm path / dataDir"
        }
        return ScanResult(
            allPackages = seen,
            source = src,
            hits = hits,
            lines = lines
        )
    }

    /** PackageManager 查一个包是否安装（异常/不可见都当「没装」） */
    private fun installedByPm(pm: android.content.pm.PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 给某个站点找出**实际存在**的数据目录。
     *
     * 好处：包名对不上（多渠道/改名）时，靠目录存在性仍能读到凭据。
     * 返回 [SiteId] → 目录绝对路径（null = 不存在）。
     */
    fun dataDirs(r: ScanResult): Map<SiteId, String?> {
        val out = LinkedHashMap<SiteId, String?>()
        for ((site, pkg) in r.hits) {
            if (pkg == null) {
                out[site] = null
                continue
            }
            val candidates = listOf("/data/user/0/$pkg", "/data/data/$pkg")
            out[site] = candidates.firstOrNull { RootShell.exists(it) }
        }
        return out
    }
}
