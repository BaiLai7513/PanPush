package com.panpush.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.panpush.app.PanPushApp
import com.panpush.app.core.DownloadItem
import com.panpush.app.core.ParseResult
import com.panpush.app.core.PushResult
import com.panpush.app.core.SiteId
import com.panpush.app.core.humanSize
import com.panpush.app.cred.AliyunCredStore
import com.panpush.app.cred.CredentialValidator
import com.panpush.app.cred.RootShell
import com.panpush.app.cred.SiteProfile
import com.panpush.app.cred.WebCookieReader
import com.panpush.app.cred.XunleiCredStore
import com.panpush.app.webview.WebViewInspector
import com.panpush.app.push.GopeedTask
import com.panpush.app.share.AliyunParser
import com.panpush.app.share.ParseException
import com.panpush.app.share.Pan123Parser
import com.panpush.app.share.Router
import com.panpush.app.share.ShareParser
import com.panpush.app.share.XunleiParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 单个站点凭据的界面状态 */
data class CredState(
    val site: SiteId,
    val configured: Boolean = false,
    val preview: String = "",
    val verdict: CredentialValidator.Verdict? = null,
    val checking: Boolean = false,
    val extracting: Boolean = false,
    val lastSavedAt: Long = 0L,
    val message: String = "",
    /** 关键令牌是否残缺（非 null 表示缺什么，例如 "__puus" / "BDUSS"） */
    val keyIssue: String? = null
) {
    val hasCredential: Boolean get() = configured
}

data class PushOutcome(
    val pushed: List<PushResult> = emptyList(),
    val error: String = ""
)

data class GopeedStatus(
    val reachable: Boolean = false,
    val label: String = "未检测",
    val message: String = ""
)

/** 全局界面状态 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as PanPushApp).container
    private val settings = container.settings

    // ---------------- 配置 ----------------
    private val _gopeedUrl = MutableStateFlow(settings.gopeedUrl)
    val gopeedUrl: StateFlow<String> = _gopeedUrl.asStateFlow()

    private val _gopeedToken = MutableStateFlow(settings.gopeedToken)
    val gopeedToken: StateFlow<String> = _gopeedToken.asStateFlow()

    private val _connections = MutableStateFlow(settings.connections)
    val connections: StateFlow<Int> = _connections.asStateFlow()

    private val _downloadDir = MutableStateFlow(settings.downloadDir)
    val downloadDir: StateFlow<String> = _downloadDir.asStateFlow()

    private val _pan123Mode = MutableStateFlow(settings.pan123Mode)
    val pan123Mode: StateFlow<String> = _pan123Mode.asStateFlow()

    private val _gopeedStatus = MutableStateFlow(GopeedStatus())
    val gopeedStatus: StateFlow<GopeedStatus> = _gopeedStatus.asStateFlow()

    // ---------------- 解析 ----------------
    private val _link = MutableStateFlow("")
    val link: StateFlow<String> = _link.asStateFlow()

    private val _passcode = MutableStateFlow("")
    val passcode: StateFlow<String> = _passcode.asStateFlow()

    private val _passcodeAuto = MutableStateFlow(false)
    val passcodeAuto: StateFlow<Boolean> = _passcodeAuto.asStateFlow()

    private val _site = MutableStateFlow(SiteId.UNKNOWN)
    val site: StateFlow<SiteId> = _site.asStateFlow()

    private val _parsing = MutableStateFlow(false)
    val parsing: StateFlow<Boolean> = _parsing.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    private val _result = MutableStateFlow<ParseResult?>(null)
    val result: StateFlow<ParseResult?> = _result.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _outcome = MutableStateFlow<PushOutcome?>(null)
    val outcome: StateFlow<PushOutcome?> = _outcome.asStateFlow()

    // ---------------- 凭据 ----------------
    private val _creds = MutableStateFlow(loadCreds())
    val creds: StateFlow<List<CredState>> = _creds.asStateFlow()

    private val _rootStatus = MutableStateFlow("root：未检测")
    val rootStatus: StateFlow<String> = _rootStatus.asStateFlow()

    /** 设备上是否有 su 可执行文件（不触发授权弹窗）——有则 root 通道优先 */
    private val _rootAvailable = MutableStateFlow(false)
    val rootAvailable: StateFlow<Boolean> = _rootAvailable.asStateFlow()

    // ⚠️ 这些 StateFlow 必须声明在 init{} **之前**：
    // init 里调用 refreshAppScan()，若它引用的 flow 还没初始化就是 null → 崩溃
    // （真机踩过：`MutableStateFlow.getValue() on a null object reference`）。
    private val _appScanText = MutableStateFlow("")
    val appScanText: StateFlow<String> = _appScanText.asStateFlow()

    private val _appScanning = MutableStateFlow(false)
    val appScanning: StateFlow<Boolean> = _appScanning.asStateFlow()

    /** 一键诊断结果（同上：声明在 init 之前，避免初始化顺序踩坑） */
    private val _diagnoseText = MutableStateFlow("")
    val diagnoseText: StateFlow<String> = _diagnoseText.asStateFlow()

    /** WebView 存储自检结果 */
    private val _inspectText = MutableStateFlow("")
    val inspectText: StateFlow<String> = _inspectText.asStateFlow()

    fun clearAppScan() {
        _appScanText.value = ""
    }

    fun clearInspect() {
        _inspectText.value = ""
    }

    fun clearDiagnose() {
        _diagnoseText.value = ""
    }

    private val _credMessage = MutableStateFlow("")
    val credMessage: StateFlow<String> = _credMessage.asStateFlow()

    // 任务列表 / 进度 / 暂停删除**不在这里做** —— 全部交给 Gopeed 自己（用户明确要求）。
    // GopeedClient 仍保留 tasks()/task()/pause()/remove() 供「假死探测」与将来复用。

    private val _xunleiCreds = MutableStateFlow<XunleiCredStore.Creds?>(null)
    val xunleiCreds: StateFlow<XunleiCredStore.Creds?> = _xunleiCreds.asStateFlow()

    private val _xunleiAppInstalled = MutableStateFlow(false)
    val xunleiAppInstalled: StateFlow<Boolean> = _xunleiAppInstalled.asStateFlow()

    private val _aliyunCreds = MutableStateFlow<AliyunCredStore.Creds?>(null)
    val aliyunCreds: StateFlow<AliyunCredStore.Creds?> = _aliyunCreds.asStateFlow()

    private val _aliyunAppInstalled = MutableStateFlow(false)
    val aliyunAppInstalled: StateFlow<Boolean> = _aliyunAppInstalled.asStateFlow()

    private val _aliyunAutoClean = MutableStateFlow(settings.aliyunAutoClean)
    val aliyunAutoClean: StateFlow<Boolean> = _aliyunAutoClean.asStateFlow()

    // ---------------- 初始化 ----------------
    init {
        probeGopeed()
        refreshRootStatus()
        refreshXunleiCreds()
        refreshAliyunCreds()
        // 打开就自动扫一次应用列表（第 0 步），界面上不再需要手动按钮。
        // 走 refreshRootStatus() 内部也会触发，但那里依赖 su 检测结果；
        // 这里再显式跑一次，保证 UI 一进来就有结论。
        refreshAppScan(force = true)
    }

    /**
     * 凭据页的站点列表。
     *
     * **UC 已去除**（UC 同源、网盘已收拢到夸克，凭据共用 `cred_quark`）。
     */
    private fun loadCreds(): List<CredState> = listOf(
        // ★ 必须包含 LANZOU：凭据页的卡片是**按这个列表渲染**的，
        //   少一个站点就没有那张卡片 —— 之前蓝奏没有「应用内登录」入口就是这个原因。
        SiteId.QUARK, SiteId.PAN123, SiteId.BAIDU, SiteId.XUNLEI, SiteId.ALI
    ).map { s ->
        when (s) {
            // 迅雷 / 阿里：凭据是「多字段组合」，不走通用 Cookie 串，由各自卡片管理
            SiteId.XUNLEI, SiteId.ALI -> CredState(site = s, configured = false)
            else -> {
                val v = settings.credential(s)
                CredState(
                    site = s,
                    configured = v.isNotBlank(),
                    preview = maskCredential(s, v),
                    lastSavedAt = settings.credentialTime(s),
                    // 关键令牌残缺时标记出来（例如只有 ctoken 没有 __puus）
                    keyIssue = if (v.isNotBlank()) CredentialValidator.keyMissing(s, v) else "未配置"
                )
            }
        }
    }

    private fun maskCredential(site: SiteId, v: String): String {
        if (v.isBlank()) return ""
        return when (site) {
            SiteId.PAN123 -> {
                val m = Regex("token=([^;\\s]+)").find(v) ?: Regex("([A-Za-z0-9._\\-]{20,})").find(v)
                val t = m?.groupValues?.get(1) ?: v
                "token=" + t.take(10) + "…" + t.takeLast(6)
            }
            else -> {
                val parts = v.split(";").map { it.trim() }.filter { it.contains("=") }
                val key = if (site == SiteId.BAIDU) "BDUSS" else "__puus"
                val hit = parts.firstOrNull { it.startsWith("$key=") }
                if (hit != null) {
                    val val0 = hit.substringAfter("=")
                    "$key=" + val0.take(8) + "…" + val0.takeLast(4) + "（共 ${parts.size} 个字段）"
                } else {
                    "${parts.size} 个字段（未包含 $key）"
                }
            }
        }
    }

    // ---------------- 站内日志 ----------------
    private fun log(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _log.value = (_log.value + "[$t] $msg").takeLast(200)
        // 同时写 logcat（tag=PanPushLog），便于用 adb 抓解析全过程做诊断
        try {
            android.util.Log.i("PanPushLog", msg)
        } catch (_: Exception) {
        }
    }

    private fun logErr(msg: String) {
        log(msg)
        try {
            android.util.Log.w("PanPushParse", msg)
        } catch (_: Exception) {
        }
    }

    fun clearLog() {
        _log.value = emptyList()
    }

    /** 链接脱敏（日志里不要把完整 token 打出来）：只留域名 + share_id 前 6 位 */
    private fun maskUrl(u: String): String = try {
        val m = Regex("https?://([^/]+)(/s/([^/?#]+))?").find(u)
        val host = m?.groupValues?.get(1) ?: "?"
        val sid = m?.groupValues?.get(3).orEmpty()
        if (sid.isBlank()) host else "$host/s/${sid.take(6)}…"
    } catch (e: Exception) {
        "?"
    }

    // ---------------- 链接输入 ----------------
    /**
     * 链接输入变化。
     *
     * ⚠️ 这里要容忍**带前言的分享口令**：阿里/夸克 App 分享出来的是这种东西 ——
     * ```
     * 我用阿里云盘分享了「xxx」，你可以不限速下载🚀
     * 复制这段内容打开「阿里云盘」App 即可获取
     * 链接：https://www.alipan.com/s/xxxxx
     * ```
     * 直接把整段当 URL 会导致识别/取 share_id 出问题，所以先尝试**从文本里抠出真正的链接**。
     */
    fun onLinkChange(v: String) {
        // 文本里含受支持的链接 → 只取那一段；否则原样保留（用户可能在手打）
        val extracted = Router.findLink(v)
        val effective = extracted ?: v
        _link.value = effective
        val detected = Router.detect(effective)
        _site.value = detected
        // ★ 提取码要从**原始粘贴文本**里找，不能只看抽出的链接 ——
        //   分享文本常写成「链接 提取码：gyhe」，`findLink()` 已把后半段丢掉了，
        //   只在 `effective` 里找会永远找不到（用户反馈的「粘贴后提取码栏不显示」）。
        val pwd = Router.extractPasscode(effective)
            .ifBlank { Router.extractPasscode(v) }
        if (pwd.isNotBlank()) {
            _passcode.value = pwd
            _passcodeAuto.value = true
        } else if (_passcodeAuto.value) {
            _passcode.value = ""
            _passcodeAuto.value = false
        }
    }

    fun onPasscodeChange(v: String) {
        _passcode.value = v
        _passcodeAuto.value = false
    }

    /**
     * 一键清空：链接 + 提取码 + 上次解析/取链结果 + 错误提示。
     *
     * 快捷交互按钮用（输入框右侧的 ✕）。**不清凭据、不清运行日志** ——
     * 那两样是跨次留存的诊断信息，清掉反而妨碍排查。
     */
    fun clearLink() {
        _link.value = ""
        _passcode.value = ""
        _passcodeAuto.value = false
        _site.value = SiteId.UNKNOWN
        _result.value = null
        _downloads.value = emptyList()
        _outcome.value = null
        _error.value = ""
        _selected.value = emptySet()
        _launchMessage.value = ""
    }

    fun takeSharedText(text: String?) {
        val link = Router.findLink(text) ?: return
        onLinkChange(link)
    }

    /**
     * 「粘贴」按钮：从剪贴板取。
     *
     * 同样要容忍带前言的分享口令 —— 先 `findLink` 抠链接，抠不到才退回原文
     * （原文可能是不受支持的站点，那时 `onLinkChange` 会把它当普通文本处理）。
     */
    fun pasteFromClipboard(text: String?) {
        if (text.isNullOrBlank()) return
        val t = text.trim()
        onLinkChange(Router.findLink(t) ?: t)
    }

    // ---------------- 解析器构造（注入站点专属凭据） ----------------
    private fun makeParser(url: String, cred: String): ShareParser {
        // 传 ctx：蓝奏要读「应用内登录」保存的 cookie（大文件 / 私有分享）
        val p = Router.parser(url, _passcode.value, cred.ifBlank { null }, ctx = getApplication())
        return when (p) {
            is XunleiParser -> XunleiParser(
                url = url,
                passcode = _passcode.value,
                timeoutSec = 20,
                credStore = container.xunleiCreds,
                autoRefresh = settings.xunleiAutoRefresh
            )
            is AliyunParser -> AliyunParser(
                url = url,
                passcode = _passcode.value,
                timeoutSec = 20,
                credStore = container.aliyunCreds,
                // 传 Context：用于取稳定的 x-device-id（对齐官方网页客户端身份）
                appContext = getApplication(),
                autoClean = settings.aliyunAutoClean
            )
            else -> p
        }
    }

    // ---------------- 解析 ----------------
    fun parse(onlyList: Boolean = false) {
        val url = _link.value.trim()
        if (url.isBlank()) {
            _error.value = "请先粘贴网盘分享链接"
            return
        }
        val site = Router.detect(url)
        if (!Router.isSupported(site)) {
            _error.value = "${site.label} 暂未支持（当前支持：夸克/UC、123 云盘、百度网盘）"
            return
        }
        _error.value = ""
        _outcome.value = null
        _downloads.value = emptyList()
        _log.value = emptyList()
        viewModelScope.launch {
            _parsing.value = true
            log("开始解析：$url")
            log("站点：${site.label}")
            val cred = settings.credential(site)
            if (cred.isBlank()) {
                log("提示：未配置 ${site.label} 登录态（大文件/下载会被登录墙拦）")
            } else {
                val v = withContext(Dispatchers.IO) { CredentialValidator.validate(site, cred) }
                log("登录态自检：${v.label} — ${v.message}")
            }
            val r = withContext(Dispatchers.IO) {
                try {
                    val parser = makeParser(url, cred)
                    if (parser is Pan123Parser) parser.mode = settings.pan123Mode
                    val files = parser.list()
                    val pNotes = when (parser) {
                        is AliyunParser -> parser.notes.toList()
                        is XunleiParser -> parser.notes.toList()
                        else -> emptyList()
                    }
                    ParseResult(site = site, files = files, downloads = emptyList(), notes = pNotes)
                } catch (e: ParseException) {
                    _error.value = buildString {
                        append(e.message ?: "解析失败")
                        e.hint?.let { append("\n↳ ").append(it) }
                    }
                    // 精确记录：站点 / 链接（脱敏）/ 错误 / 栈顶
                    logErr(
                        "解析失败 site=${site.key} url=${maskUrl(url)} err=${e.message} " +
                            "hint=${e.hint ?: "-"} at=${e.stackTrace.firstOrNull()?.toString()}"
                    )
                    null
                } catch (e: Exception) {
                    _error.value = "解析失败：${e.message}"
                    logErr(
                        "解析异常 site=${site.key} url=${maskUrl(url)} " +
                            "${e.javaClass.simpleName}: ${e.message} at=${e.stackTrace.firstOrNull()?.toString()}"
                    )
                    null
                }
            }
            if (r != null) {
                _result.value = r
                _selected.value = r.files.filter { it.isFile }.map { it.fid }.toSet()
                log("列目录完成：${r.files.size} 个文件")
                val total = r.files.filter { it.isFile }.sumOf { it.size }
                log("合计：${humanSize(total)}")
                if (_passcode.value.isBlank() && r.files.isEmpty()) {
                    _error.value = "没有列到文件：该分享可能需要提取码"
                }
                if (onlyList) _log.value = _log.value + "（仅列目录，未取直链）"
            }
            _parsing.value = false
        }
    }

    fun toggleSelect(fid: String) {
        val cur = _selected.value.toMutableSet()
        if (!cur.add(fid)) cur.remove(fid)
        _selected.value = cur
    }

    fun selectAll(all: Boolean) {
        val files = _result.value?.files ?: return
        _selected.value = if (all) files.filter { it.isFile }.map { it.fid }.toSet() else emptySet()
    }

    // ---------------- 取直链 + 推送 ----------------
    /**
     * 推送前「拉起/重启 Gopeed」的策略。
     *
     * 背景（真机定位）：原来这里对 `_gopeedStatus` 做**硬门禁** —— 不可达就 `return`，
     * 既不重启也不推送，什么都不发生。而 `_gopeedStatus` 只在 init / 保存设置时刷新，
     * Gopeed 之后被杀或假死就变成陈旧值 → 表现为「解析并推送依旧无法拉起」。
     * 现在改成**先按策略把 Gopeed 拉起来，再推送**。
     *
     * 同时真机确认：root 拉起是**被系统放行**的
     * （`ActivityTaskManager: START … from uid 0 (BAL_ALLOW_ALLOWLISTED_UID)`），
     * 所以 root 路径可用；普通路径作为无 root 时的降级。
     */
    /**
     * ~~推送模式~~ —— **已废弃**。
     *
     * 用户实测：**「普通拉起」这条路是好的**；而「root 杀掉 Gopeed 再重启」会把界面
     * 跳到 Gopeed、还得手动返回 PanPush 才能再推送。所以按要求**只保留普通拉起**，
     * 这个枚举不再参与任何判断（保留仅为兼容引用）。
     */
    @Deprecated("只保留普通拉起，见 ensureGopeedForPush")
    enum class GopeedMode {
        AUTO,
        ROOT_FORCE,
        NORMAL
    }

    /**
     * 探明「Gopeed 不可达」的**具体原因**（给用户可操作的指引）。
     *
     * 真机实测两种完全不同的状态，处理方式也不同：
     *  · **进程假死**：进程在、9999 端口 **LISTEN**，但连接 **超时**（`SocketTimeoutException`）。
     *    只能**重启 Gopeed 进程**才恢复；而应用无权杀别的 App（`am force-stop` 需 root）→
     *    **必须由用户手动划掉后台**。
     *  · **没在运行**：连接被拒（`ConnectException` / refused）→ 拉起一次就行。
     */
    private fun gopeedDownReason(): String {
        val txt = try {
            container.gopeed().info().second
        } catch (e: Exception) {
            e.message.orEmpty()
        }
        return when {
            txt.contains("SocketTimeout", true) || txt.contains("timed out", true) ->
                "Gopeed **进程假死**（9999 端口还在监听，但连接一直超时、API 不响应）。\n" +
                    "这种状态只能**重启 Gopeed 进程**；应用没有杀其它 App 的权限，所以请你手动：" +
                    "打开最近任务 → 把 Gopeed **划掉** → 重新打开它。"
            txt.contains("refused", true) || txt.contains("ConnectException", true) ->
                "Gopeed 没在运行（连接被拒）。拉起它即可。"
            else -> "Gopeed API 不可达（端口 9999 无响应）。"
        }
    }

    /**
     * 推送前的 Gopeed 处理方式 —— 按用户实测反馈定的两种，**都不走 root**。
     */
    enum class PushMode {
        /**
         * ① **只判断**：2 秒探针通了就直接推送；**不通就只报「不可达」**，
         * 不主动拉起、不等待。
         *
         * 为什么改成「只判断」：原来的第 1 个按钮是「探 → 拉起 → 等 7 秒 → 再探」，
         * 冷启动时常常仍推不上、得返回重推第 2 次 —— 反而更绕。
         * 现在让它**快速给结论**，失败就明确引导去点第 2 个。
         */
        /**
     * ① **只判断**（用户明确要求：点击只触发这一条，不要后续逻辑）。
     *
     * 行为：**2 秒探一次** → 通就直接推；不通就**立刻**返回一句「不可达，请用手动拉起并推送」。
     * 不拉起、不等待、不重试。
     */
    CHECK,

        /**
         * ② **手动拉起并推送**：拉起 Gopeed → 等 API 就绪 → 推送。
         *
         * ★ 有 root 时会**先停掉进程再拉起** —— 因为 Gopeed 会「假死」
         * （端口 LISTEN、连接 ESTABLISHED，但服务端永不回包），
         * 这种情况下单纯拉起只是把它切到前台，**进程还是坏的**，必须重启进程。
         * 没 root 时只能普通拉起（假死就救不了，会提示你手动划掉）。
         */
        LAUNCH
    }

    /**
     * 用 root 强停一个 App（`am force-stop`）。
     *
     * 为什么需要：Gopeed **假死**时端口还在监听、连接也能建立，但服务端永不响应，
     * 唯一解是重启进程。普通 App 无权 `force-stop` 别的应用，只有 root 能。
     *
     * @return true = 已停掉
     */
    private suspend fun forceStopApp(pkg: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val r = com.panpush.app.cred.RootShell.execGlobal("am force-stop $pkg", 15)
            logL("force-stop $pkg：ok=${r.ok} out=${r.stdout.take(120)}")
            r.ok
        } catch (e: Exception) {
            logL("force-stop $pkg 异常：${e.message}")
            false
        }
    }

    /**
     * 推送前确保 Gopeed API 可用。
     *
     * ## ★★ 真机实测：「假死」其实是 **Android 把后台的 Gopeed 冻结了**
     *
     * | Gopeed 状态 | 端口 | 表现 |
     * |---|---|---|
     * | **后台**（被 cached-app freezer 冻住） | 仍 `LISTEN`，连接能建立 | **永远不回包**（20 秒超时） |
     * | **前台** | 同上 | **立刻秒回**（同一秒返回 HTTP 响应） |
     *
     * 实测证据：后台时 `nc` 超时；`monkey` 把它拉到前台后**立刻**返回 `HTTP/1.1 400`（服务活着）。
     * ⇒ 根因不是进程坏了，而是**被系统冻结**；解法是**拉到前台**，不是重启。
     *
     * @param mode see [PushMode]
     * @return null = 可用；非 null = 失败原因（调用方据此中止）
     */
    private suspend fun ensureGopeedForPush(mode: PushMode): String? = withContext(Dispatchers.IO) {
        // 秒级探一次（2 秒）
        if (container.gopeed().ping(2)) return@withContext null

        // ① CHECK：**只判断**（用户要求：点击只触发这一条，不拉起、不等待、不重试）
        if (mode == PushMode.CHECK) {
            logL("ensureGopeedForPush[CHECK]：2 秒探针不可达 → 立即返回提示")
            return@withContext "Gopeed 现在没响应（多半在后台被系统冻结了）\n" +
                "→ 请点下面的「② 手动拉起并推送」"
        }

        // ② LAUNCH：**拉到前台**（解冻）→ 一有响应就继续
        logL("ensureGopeedForPush[LAUNCH]：拉起 Gopeed 到前台（解冻）")
        val msg = launchApp(GOPEED_PKG, "Gopeed", rootFirst = false)
        logL("ensureGopeedForPush[LAUNCH] 拉起：$msg")

        // 解冻很快（实测 1~3 秒）；探到就立刻继续，别让用户干等
        for (i in 1..6) {
            kotlinx.coroutines.delay(1500)
            if (container.gopeed().ping(1)) {
                logL("ensureGopeedForPush：第 $i 轮探到响应，继续推送")
                return@withContext null
            }
        }

        // 兜底（真·进程坏了）：有 root 才强停重启一次
        if (_rootAvailable.value) {
            logL("ensureGopeedForPush：拉起后仍无响应 → root 强停重启兜底")
            forceStopApp(GOPEED_PKG)
            kotlinx.coroutines.delay(800)
            launchApp(GOPEED_PKG, "Gopeed", rootFirst = false)
            for (i in 1..6) {
                kotlinx.coroutines.delay(1500)
                if (container.gopeed().ping(1)) {
                    logL("ensureGopeedForPush：强停重启后第 $i 轮通过")
                    return@withContext null
                }
            }
            return@withContext "Gopeed 已拉起并强停重启过，仍然不可达 —— $msg\n" +
                "请打开 Gopeed 确认「设置 → API」已开启（端口 9999）。"
        }
        "Gopeed 拉起后仍不可达 —— $msg\n" +
            "没有 root 时无法强停它；请手动划掉 Gopeed 后台再打开，" +
            "然后点「③ 重新推送（用上次直链）」。"
    }

    /**
     * 把 Gopeed 加进**电池优化白名单**（改别人的白名单需要 root）。
     *
     * 为什么：真机实测 Gopeed 在后台会被系统冻结 → 端口在、不回包（看起来像假死）。
     * 加白名单后系统不会把它当 cached app 冻掉，后台推任务更稳。
     * 纯优化项，失败静默。
     */
    suspend fun whitelistGopeedFromBatterySaver() {
        if (!_rootAvailable.value) return
        withContext(Dispatchers.IO) {
            try {
                val r = com.panpush.app.cred.RootShell.execGlobal(
                    "dumpsys deviceidle whitelist +$GOPEED_PKG", 15
                )
                logL("Gopeed 电池白名单：ok=${r.ok} ${r.stdout.take(80)}")
            } catch (e: Exception) {
                logL("Gopeed 电池白名单失败：${e.message}")
            }
        }
    }
    /** 粗判是不是「后台被冻结」：端口能连上但 20 秒内不回包（`SocketTimeout` 而非 refused） */
    private fun gopeedLooksWedged(): Boolean {
        val txt = try {
            container.gopeed().info().second
        } catch (e: Exception) {
            e.message.orEmpty()
        }
        return txt.contains("SocketTimeout", true) || txt.contains("timed out", true)
    }

    /**
     * 上次成功取到的直链（用于「重新推送」）。
     * Gopeed 假死时用户手动重启它之后，可**不重新解析**直接再推一次。
     */
    private var _lastPushItems: List<DownloadItem> = emptyList()
    private var _lastPushSite: SiteId = SiteId.UNKNOWN

    /** 是否有可复用的直链（UI 决定要不要显示「重新推送」按钮） */
    val hasLastPush: Boolean get() = _lastPushItems.isNotEmpty()

    /**
     * **用上次的直链重新推送**（不再解析、不再取直链）。
     *
     * 为什么需要：Gopeed「假死」（端口 LISTEN 但连接超时）只能**手动重启它的进程**，
     * 应用无权杀别的 App。用户重启完 Gopeed 后，最省事的做法是直接再推一次，
     * 而不是从头解析（那又要走一遍分享页 / 转存 / 取直链，慢且可能触发风控）。
     */
    /**
     * ★ 蓝奏专用：**解析 → 自动用浏览器打开直链**（一键）。
     *
     * ## 为什么蓝奏只走浏览器（用户实测结论）
     *
     * 蓝奏下载 CDN 对不带 WAF Cookie 的请求一律
     * `X-Tengine-Error: denied by http_custom`，返回 JS 挑战页（约 2.7KB）。
     *
     * | 客户端 | 结果 |
     * |---|---|
     * | 浏览器 | ✅ 执行 JS 解掉 `acw_sc__v2` → 正常下载 |
     * | Gopeed / 纯 HTTP | ❌ 把挑战页当文件存下（done 但只有 4.7KB） |
     *
     * 我们替它解 Cookie 也走不通：`arg1` **逐响应签发**，
     * 解出的 `acw_sc__v2` 只对签发它的那次请求有效；而多试几次又会把链接消耗掉。
     * 所以蓝奏**不再推 Gopeed**，解析完直接把直链交给浏览器。
     */
    fun resolveAndOpenBrowser() {
        resolveAndPush(push = false)
        viewModelScope.launch {
            repeat(45) {
                kotlinx.coroutines.delay(1000)
                val u = _downloads.value.firstOrNull()?.url
                if (!u.isNullOrBlank()) {
                    try {
                        val i = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(u)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        getApplication<android.app.Application>().startActivity(i)
                        log("[浏览器] 已用浏览器打开直链")
                    } catch (e: Exception) {
                        _error.value = "打开浏览器失败：${e.message}"
                        logErr("[浏览器] 打开失败：${e.message}")
                    }
                    return@launch
                }
                if (!_resolving.value) return@launch
            }
            _error.value = "取直链超时，请重试或检查提取码"
        }
    }

    fun pushLastItems() {
        val items = _lastPushItems
        if (items.isEmpty()) {
            _error.value = "还没有直链 —— 请先解析"
            return
        }
        val site = _lastPushSite
        _error.value = ""
        _outcome.value = null
        // ★ 每轮重置：清掉上一轮的直链/提示，保证「重新解析后功能回到初始状态」
        _downloads.value = emptyList()
        _launchMessage.value = ""
        viewModelScope.launch {
            _resolving.value = true
            _launchMessage.value = "正在拉起 Gopeed（解冻）…"
            // ★ 用 LAUNCH：直链已在手，拉起后**立刻**推 —— 间隔最短，不会被系统冻结
            val fail = ensureGopeedForPush(PushMode.LAUNCH)
            if (fail != null) {
                _error.value = fail
                _launchMessage.value = ""
                _resolving.value = false
                return@launch
            }
            log("重新推送 ${items.size} 条（复用上次直链，未重新解析）")
            val res = withContext(Dispatchers.IO) { pushItems(items, site) }
            _outcome.value = res
            res.pushed.forEach { p ->
                log(if (p.ok) "[OK] 已推送 ${p.name}" else "[FAIL] ${p.name} — ${p.message}")
            }
            if (res.pushed.any { it.ok }) {
                _launchMessage.value = launchApp(GOPEED_PKG, "Gopeed", rootFirst = false)
            } else {
                _launchMessage.value = ""
            }
            _resolving.value = false
        }
    }

    /**
     * 取直链（并按需推送）。
     *
     * ## ★★ 关键顺序（真机踩出来的）
     *
     * Gopeed 在**后台会被 Android 冻结**：端口 LISTEN、连接能建立，但永不回包。
     * 而**PanPush 一到前台，Gopeed 就退到后台被冻** —— 所以：
     *
     * | 旧顺序 | 后果 |
     * |---|---|
     * | 拉起 Gopeed → **解析（几十秒）** → 推送 | 推的时候它早冻住了 → **20 秒超时失败** |
     *
     * ⇒ 必须**先解析、推送前才拉起**，让 Gopeed 在推送那一刻处于前台。
     * 这也是「第 1 次推不上、返回再推第 2 次才行」的真正原因
     * （第 2 次解析更快/直链已缓存，间隔短，它还没被冻）。
     *
     * @param push true = 取直链后推送到 Gopeed
     * @param mode `CHECK` = 只判断（2 秒探一次，不通就快速提示去点 ②）；
     *   `LAUNCH` = 手动拉起并推送（解析完再拉起 → 立刻推）。
     */
    fun resolveAndPush(push: Boolean, mode: PushMode = PushMode.CHECK) {
        val r = _result.value ?: run {
            _error.value = "请先解析分享链接"
            return
        }
        val chosen = r.files.filter { it.isFile && _selected.value.contains(it.fid) }
        if (chosen.isEmpty()) {
            _error.value = "请至少选择一个文件"
            return
        }
        _error.value = ""
        _outcome.value = null
        // ★ 每轮重置：清掉上一轮的直链/提示，保证「重新解析后功能回到初始状态」
        _downloads.value = emptyList()
        _launchMessage.value = ""
        viewModelScope.launch {
            _resolving.value = true

            // 1) ①只在**开头**探一次（2 秒）；不通立刻提示去点 ②，不做任何后续逻辑
            if (push && mode == PushMode.CHECK) {
                _launchMessage.value = "正在判断 Gopeed（最多 2 秒）…"
                val fail = ensureGopeedForPush(PushMode.CHECK)
                if (fail != null) {
                    _error.value = fail
                    _launchMessage.value = ""
                    logErr("[推送] CHECK 未通过：$fail")
                    _resolving.value = false
                    return@launch
                }
            }

            val site = _site.value
            val cred = settings.credential(site)
            var pNotes: List<String> = emptyList()
            val items = withContext(Dispatchers.IO) {
                try {
                    val parser = makeParser(_link.value.trim(), cred)
                    if (parser is Pan123Parser) parser.mode = settings.pan123Mode
                    val got = parser.resolve(chosen) { m -> log(m) }
                    // 阿里/迅雷在取直链阶段才会产出提示（超门控跳过、频控重试、UA 探测）
                    pNotes = when (parser) {
                        is AliyunParser -> parser.notes.toList()
                        is XunleiParser -> parser.notes.toList()
                        else -> emptyList()
                    }
                    got
                } catch (e: ParseException) {
                    // ★ 阿里 / 迅雷：这两家的凭据会过期，且**刷新方式按权限不同** ——
                    //   · 非 root：只能靠「应用内登录」重新登录一遍来刷新（网页令牌是唯一来源）
                    //   · 有 root：直接「root 读取」重新读一遍 App 的三件套 / token 组最省事
                    //   所以这里按当前 root 状态追加**可操作**的指引，而不是只报错。
                    val renewHint = when {
                        site == SiteId.ALI || site == SiteId.XUNLEI ->
                            if (_rootAvailable.value)
                                "\n↳ 凭据可能已过期 → 请到「凭据」页点「root 读取」重新读取。"
                            else
                                "\n↳ 凭据可能已过期 → 请到「凭据」页点「应用内登录」重新登录刷新。"
                        else -> ""
                    }
                    _error.value = buildString {
                        append(e.message ?: "取直链失败")
                        e.hint?.let { append("\n↳ ").append(it) }
                        append(renewHint)
                    }
                    logErr(
                        "[取直链] site=${site.key} url=${maskUrl(_link.value)} err=${e.message} " +
                            "hint=${e.hint ?: "-"} root=${_rootAvailable.value} " +
                            "renew=${if (renewHint.isBlank()) "-" else renewHint.trim()} " +
                            "at=${e.stackTrace.firstOrNull()?.toString()}"
                    )
                    emptyList()
                } catch (e: Exception) {
                    _error.value = "取直链失败：${e.message}"
                    logErr(
                        "[取直链] site=${site.key} ${e.javaClass.simpleName}: ${e.message} " +
                            "at=${e.stackTrace.firstOrNull()?.toString()}"
                    )
                    emptyList()
                }
            }
            if (pNotes.isNotEmpty()) {
                val cur = _result.value
                if (cur != null) {
                    _result.value = cur.copy(notes = (cur.notes + pNotes).distinct())
                }
                pNotes.forEach { log(it) }
            }
            _downloads.value = items
            if (items.isNotEmpty()) {
                log("取得 ${items.size} 条直链")
                // ★ 缓存本次直链：Gopeed 假死时用户手动重启它之后，
                //   可以直接「重新推送」而**不用重新解析**（省一次取直链的时间）。
                _lastPushItems = items
                _lastPushSite = site
                if (push) {
                    // ★★ **推送前一刻**才处理 Gopeed（关键顺序，见上面文档）：
                    //    解析可能要几十秒，期间 Gopeed 会被冻结；
                    //    所以必须在真正 push 之前把它拉到前台/确认活着。
                    _launchMessage.value = if (mode == PushMode.LAUNCH) {
                        "正在拉起 Gopeed 并推送…"
                    } else {
                        "正在确认 Gopeed 并推送…"
                    }
                    val fail = ensureGopeedForPush(mode)
                    if (fail != null) {
                        _error.value = fail
                        _launchMessage.value = ""
                        logErr("[推送] 推前 Gopeed 未就绪：$fail")
                        _resolving.value = false
                        return@launch
                    }
                    val res = withContext(Dispatchers.IO) { pushItems(items, site) }
                    _outcome.value = res
                    res.pushed.forEach { p ->
                        log(if (p.ok) "[OK] 已推送 ${p.name}" else "[FAIL] ${p.name} — ${p.message}")
                    }
                    // 推送成功后把 Gopeed 拉到前台（**只走普通拉起**，见 ensureGopeedForPush）。
                    // 任务/进度/暂停删除全部交给 Gopeed 自己，本 App 不重复管理。
                    if (res.pushed.any { it.ok }) {
                        val msg = launchApp(GOPEED_PKG, "Gopeed", rootFirst = false)
                        _launchMessage.value = msg
                        log("[Gopeed] " + msg.replace('\n', ' '))
                    } else {
                        _launchMessage.value = ""
                    }
                }
            }
            _resolving.value = false
        }
    }

    private fun pushItems(items: List<DownloadItem>, site: SiteId): PushOutcome {
        val gp = container.gopeed(site)
        val dir = settings.downloadDir
        return try {
            val res = if (items.size == 1) {
                listOf(gp.addTask(items[0], null, dir))
            } else {
                listOf(gp.addBatch(items, dir))
            }
            val bad = res.firstOrNull { !it.ok }
            PushOutcome(pushed = res, error = bad?.message ?: "")
        } catch (e: Exception) {
            PushOutcome(error = "推送异常：${e.message}")
        }
    }

    fun reset() {
        _result.value = null
        _selected.value = emptySet()
        _downloads.value = emptyList()
        _outcome.value = null
        _error.value = ""
        _log.value = emptyList()
    }

    // ---------------- Gopeed ----------------
    fun probeGopeed() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    val gp = container.gopeed()
                    val (st, txt) = gp.info()
                    val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(txt)?.groupValues?.get(1)
                    if (st == 200) GopeedStatus(true, if (version != null) "已连接 · v$version" else "已连接", "")
                    else GopeedStatus(false, "不可达", "HTTP $st ${txt.take(80)}")
                } catch (e: Exception) {
                    GopeedStatus(false, "不可达", e.message ?: "网络异常")
                }
            }
            _gopeedStatus.value = r
            log("Gopeed：${r.label}${if (r.message.isBlank()) "" else "（${r.message}）"}")
        }
    }

    fun saveGopeed(url: String, token: String, conn: Int, dir: String) {
        settings.gopeedUrl = url
        settings.gopeedToken = token
        settings.connections = conn
        settings.downloadDir = dir
        _gopeedUrl.value = settings.gopeedUrl
        _gopeedToken.value = settings.gopeedToken
        _connections.value = settings.connections
        _downloadDir.value = settings.downloadDir
        log("已保存 Gopeed 设置")
        probeGopeed()
    }

    fun setPan123Mode(mode: String) {
        settings.pan123Mode = mode
        _pan123Mode.value = mode
    }


    // ---------------- 拉起 App（Gopeed / 迅雷）----------------

    private val _launchMessage = MutableStateFlow("")
    val launchMessage: StateFlow<String> = _launchMessage.asStateFlow()

    /**
     * 用 root 解析某个包的 launcher Activity（**不要硬编码 `.MainActivity`**）。
     *
     * 真机血泪：迅雷的 launcher 是 `com.xunlei.downloadprovider/.launch.LaunchActivity`，
     * 而 Gopeed 是 `com.gopeed.gopeed/.MainActivity` —— 硬编码任何一个都会有一个挂掉
     * （真机实测：`am start -n com.xunlei.downloadprovider/.MainActivity`
     *  → `Error type 3: Activity class does not exist`）。
     *
     * 这里用 `cmd package resolve-activity --brief <pkg>` 拿真实组件名，
     * 取最后一行（形如 `com.pkg/.Act`），失败返回 null 交给 monkey 兜底。
     */
    private fun resolveLauncherActivity(pkg: String): String? {
        val r = RootShell.execGlobal("cmd package resolve-activity --brief $pkg", 20)
        return (r.stdout + "\n" + r.stderr).lines()
            .map { it.trim() }
            .lastOrNull { it.contains('/') && it.startsWith(pkg) }
            ?.removePrefix("package:")
            ?.trim()
    }

    /**
     * 拉起目标 App，**可选「root 优先」**。
     *
     * @param rootFirst true = 先试 root（`am start -n <真实组件>` → `monkey`），
     *                  失败再退回普通 `startActivity`。
     *                  「取直链并推送」用它：有 root 时**直接 root 拉起**最可靠。
     *                  false = 先普通、再 root（默认）。
     * @param pkg 目标包名
     * @param hint 给用户看的中文名
     */
    private suspend fun launchApp(
        pkg: String,
        hint: String,
        rootFirst: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (rootFirst) {
            val r = launchByRoot(pkg, hint)
            if (r != null) return@withContext r
            // root 不可用/失败 → 退回普通方式（普通用户降级路径）
        }
        // ① 常规方式（依赖 <queries>；被包可见性挡住时返回 null）
        val plain = try {
            val ctx = getApplication<android.app.Application>()
            val it = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (it != null) {
                it.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                ctx.startActivity(it)
                logL("$hint：常规方式拉起成功")
                "已拉起 $hint"
            } else {
                logL("$hint：常规方式拿不到启动 Intent（包可见性）")
                null
            }
        } catch (e: Exception) {
            logL("$hint：常规方式异常 ${e.message}")
            null
        }
        if (plain != null) return@withContext plain
        if (rootFirst) {
            // 已经试过 root 了
            return@withContext "拉起 $hint 失败 —— 请手动打开 $hint 查看下载进度。"
        }
        // 普通失败 → root 兜底（普通用户没有 root 就会走到「请手动打开」）
        return@withContext (launchByRoot(pkg, hint)
            ?: "没有 root，也无法自动拉起 $hint —— 请**手动打开 $hint**查看下载进度。")
    }

    /**
     * 纯 root 拉起；root 不可用或全部失败返回 null。
     *
     * 四级兜底：`am start -n <解析出的真实组件>` → `am start -a MAIN -c LAUNCHER -p`
     * → `monkey` → null。
     */
    private fun launchByRoot(pkg: String, hint: String): String? {
        val hasSu = try {
            RootShell.hasSu()
        } catch (e: Exception) {
            false
        }
        if (!hasSu) {
            logL("$hint：无 root，跳过 root 拉起")
            return null
        }
        // 用解析出来的真实 launcher 组件名（**不硬编码 `.MainActivity`**：
        // 真机实测迅雷是 `.launch.LaunchActivity`，Gopeed 是 `.MainActivity`）
        val act = resolveLauncherActivity(pkg)
        if (act != null) {
            val r = RootShell.execGlobal("am start -n $act", 20)
            val t = r.stdout + r.stderr
            logL("$hint：am start -n $act → ${t.lines().firstOrNull()}")
            if (t.contains("Starting:") || t.contains("current task has been brought to the front")) {
                return "已用 root 拉起 $hint"
            }
        }
        val r3 = RootShell.execGlobal(
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg", 20
        )
        val t3 = r3.stdout + r3.stderr
        if (t3.contains("Starting:") || t3.contains("current task has been brought to the front")) {
            logL("$hint：am start -a MAIN 成功")
            return "已用 root 拉起 $hint"
        }
        val r4 = RootShell.execGlobal(
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1", 20
        )
        val t4 = r4.stdout + r4.stderr
        val ok4 = t4.contains("Events injected: 1") || !t4.contains("No activities found")
        logL("$hint：monkey ${if (ok4) "成功" else "失败"}（$t3 / $t4）")
        return if (ok4) "已用 root 拉起 $hint" else null
    }

    private fun logL(msg: String) {
        try {
            android.util.Log.i("PanPushLaunch", msg)
        } catch (_: Exception) {
        }
    }

    /**
     * 迅雷：拉起迅雷 APP 让它自己续期 captcha / access_token。
     *
     * 仅适用于 **App 令牌模式**：captcha 只能由 APP 生成，refresh_token 失效时也只能靠 APP 重新登录。
     * （网页令牌模式不需要 captcha，但也没有「拉起 APP 就能续期」这回事 —— 过期就重新登录一次。）
     */
    fun launchXunleiApp() {
        viewModelScope.launch {
            _credMessage.value = "正在拉起迅雷 APP…（让它自己刷新 captcha / 续期 token）"
            val r = launchApp(XUNLEI_PKG, "迅雷")
            _credMessage.value = r
            logL("迅雷拉起结果：$r")
        }
    }

    /**
     * 迅雷：一键「拉起 APP 并等它刷新，然后重新读取三件套」。
     * 这是 refresh_token 失效后唯一能自愈的路。
     */
    fun refreshXunleiByApp() {
        viewModelScope.launch {
            _credMessage.value = "正在拉起迅雷 APP 刷新令牌…"
            val launch = launchApp(XUNLEI_PKG, "迅雷")
            logL("迅雷刷新流程：$launch")
            // 给 APP 几秒钟联网续期，再重新读 shared_prefs
            _credMessage.value = "$launch\n等待 8 秒后自动重新读取三件套…"
            kotlinx.coroutines.delay(8000)
            val c = withContext(Dispatchers.IO) {
                try {
                    container.xunleiCreds.load()
                } catch (e: Exception) {
                    null
                }
            }
            _xunleiCreds.value = c
            _credMessage.value = if (c == null) {
                "$launch\n重新读取：✗ 仍没读到三件套"
            } else {
                "$launch\n重新读取：✓ captcha 剩余 ${c.remainingSec()}s" +
                    "，access_token " + if (c.accessExpired()) "仍过期（可再点「续期 token」）" else "已刷新"
            }
            refreshXunleiCreds()
        }
    }

    fun clearLaunchMessage() {
        _launchMessage.value = ""
    }

    // ---------------- 凭据 ----------------
    fun refreshRootStatus() {
        viewModelScope.launch {
            val (s, canSu) = withContext(Dispatchers.IO) {
                val status = try {
                    container.extractor.rootStatus()
                } catch (e: Exception) {
                    "root：检测失败（${e.message}）"
                }
                // 只看 su 文件是否存在，不触发授权弹窗
                val can = try {
                    RootShell.hasSu()
                } catch (e: Exception) {
                    false
                }
                status to can
            }
            _rootStatus.value = s
            _rootAvailable.value = canSu
            // su 可用就扫一次应用列表（第 0 步）。只在**还没扫过**时扫，避免打开 App 时重复跑。
            if (canSu && _appScanText.value.isBlank()) refreshAppScan(force = false)
            // ★ 顺手把 Gopeed 加进电池白名单：它一被系统冻结就「端口在、不回包」，
            //   推送必然超时（真机实测）。纯优化项，失败静默。
            if (canSu) whitelistGopeedFromBatterySaver()
        }
    }

    /** 第 0 步：扫应用列表，决定哪些站点可直读。打开 App 时自动跑一次。 */
    fun refreshAppScan(force: Boolean = false) {
        viewModelScope.launch {
            if (_appScanning.value) return@launch
            _appScanning.value = true
            val r = withContext(Dispatchers.IO) {
                try {
                    container.extractor.scanApps(force)
                } catch (e: Exception) {
                    null
                }
            }
            // ★ 只保留**一行结论**（有/无 + 网盘名），不再拼多行长文本 ——
            //   原来拼 summary+detail 塞进运行日志会被截断，显示不全。
            val text = r?.oneLine() ?: "应用列表扫描失败"
            _appScanText.value = text
            log("[应用列表] $text")
            try {
                android.util.Log.i("PanPushScan", text)
            } catch (_: Exception) {
            }
            _appScanning.value = false
        }
    }

    fun saveCredential(site: SiteId, value: String) {
        if (value.isBlank()) {
            _credMessage.value = "内容为空"
            return
        }
        settings.setCredential(site, value)
        settings.markCredentialSaved(site)
        _creds.value = loadCreds()
        _credMessage.value = "已保存 ${site.label} 凭据"
        checkCredential(site)
    }

    fun clearCredential(site: SiteId) {
        settings.clearCredential(site)
        _creds.value = loadCreds()
        _credMessage.value = "已清除 ${site.label} 凭据"
    }

    fun checkCredential(site: SiteId) {
        when (site) {
            SiteId.XUNLEI -> {
                checkXunleiCredential()
                return
            }
            SiteId.ALI -> {
                checkAliyunCredential()
                return
            }
            SiteId.LANZOU -> {
                _credMessage.value = "蓝奏云免登录，无需凭据"
                return
            }
            else -> Unit
        }
        val cred = settings.credential(site)
        _creds.value = _creds.value.map { if (it.site == site) it.copy(checking = true) else it }
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) { CredentialValidator.validate(site, cred) }
            _creds.value = _creds.value.map {
                if (it.site == site) it.copy(checking = false, verdict = v) else it
            }
        }
    }

    /**
     * 探一次 Gopeed API 是否真的活着。
     *
     * 真机踩坑：Gopeed 进程**假死**时，端口仍然 LISTEN（`/proc/net/tcp` 能看到 127.0.0.1:9999），
     * 但连上去会**超时**（不是 refused）—— 表现就是「API 设置没问题却不可达」。
     * 这时唯一的解是重启 Gopeed（本方法会顺手提示这一点）。
     */
    fun probeGopeedApi() {
        viewModelScope.launch {
            _credMessage.value = "正在探测 Gopeed API…"
            val msg = withContext(Dispatchers.IO) {
                try {
                    val list = container.gopeed().tasks()
                    "✓ Gopeed API 可达（${list.size} 个任务）"
                } catch (e: Exception) {
                    val m = e.message ?: e.javaClass.simpleName
                    buildString {
                        append("✗ Gopeed API 不可达：").append(m).append('\n')
                        append("排查顺序：\n")
                        append("  1) 打开 Gopeed → 设置里确认「API / HTTP 服务」已开启且端口 9999；\n")
                        append("  2) 若端口开着还是不通（连接**超时**而非拒绝）→ Gopeed 进程假死，\n")
                        append("     去系统设置里「强行停止」Gopeed 再重开（真机验证过：重启即恢复）；\n")
                        append("  3) 确认地址就是 http://127.0.0.1:9999（本机回环，不走代理）。")
                    }
                }
            }
            _credMessage.value = msg
            try {
                android.util.Log.i("PanPushDiag", "[Gopeed 探测] $msg")
            } catch (_: Exception) {
            }
            // 结果已显示在凭据页的提示卡里，不再重复写解析页日志
        }
    }

    fun checkAllCredentials() {
        _creds.value.forEach { checkCredential(it.site) }
    }

    /**
     * 一次性对本机可直读的站点全部执行「root 直读」（并发跑，省得逐个点）。
     *
     * ⚠️ 串行会让用户等很久（每个站点要拖几次 su 往返），所以并发。
     * su 本身是串行化的，但不同站点的命令互不依赖，并发能显著缩短总时间。
     */
    fun extractAllWithRoot() {
        viewModelScope.launch {
            _credMessage.value = "正在对全部可直读站点执行 root 直读…"
            // 详细的逐站点结果由 _credMessage 呈现（本页提示卡），不重复写日志
            // 只处理需要凭据且不是纯 WebView 的站点
            val targets = listOf(SiteId.BAIDU, SiteId.PAN123, SiteId.XUNLEI, SiteId.ALI, SiteId.QUARK)
            val results = withContext(Dispatchers.IO) {
                targets.map { s ->
                    s to try {
                        when (s) {
                            SiteId.XUNLEI -> {
                                val c = container.xunleiCreds.load()
                                if (c != null) "✓ 三件套（captcha 剩余 ${c.remainingSec()}s）" else "✗ 未读到"
                            }
                            SiteId.ALI -> {
                                val c = container.aliyunCreds.readAppCreds()
                                if (c != null) "✓ token 组（drive_id=${c.driveId}）" else {
                                    "✗ 未读到" + container.aliyunCreds.lastError.takeIf { it.isNotBlank() }
                                        ?.let { "（$it）" }.orEmpty()
                                }
                            }
                            else -> {
                                val r = container.extractor.extract(s)
                                val hit = r.getOrNull()
                                if (hit != null) {
                                    // 走同一套「残缺不覆盖 / 有效静默保留」规则
                                    applyExtractedCredential(s, hit.cookie, hit.source)
                                } else {
                                    "✗ " + (r.exceptionOrNull()?.message?.lines()?.firstOrNull() ?: "未读到")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        "✗ 异常：${e.message}"
                    }
                }
            }
            _creds.value = loadCreds()
            val okCount = results.count { it.second.startsWith("✓") }
            val report = buildString {
                append("批量 root 直读：成功 $okCount/${results.size}\n")
                results.forEach { (s, r) -> append("  · ${s.label}：$r\n") }
                if (okCount < results.size) {
                    append("未成功的请看卡片提示（缺什么、该点哪个）。")
                }
            }
            _credMessage.value = report
            log("[全部直读] " + report.replace('\n', ' '))
            // 同步写 logcat（便于 adb 抓取）
            try {
                android.util.Log.i("PanPushDiag", "[全部直读] " + report)
            } catch (_: Exception) {
            }
            refreshRootStatus()
        }
    }

    /**
     * 每站点就绪状态一句话 —— **复用 [SiteProfile.readiness] 这一份判据**，
     * 保证界面 / 一键诊断 / adb 验收探测三者永不打架（验收标准 5）。
     *
     * 为什么不能只看 SharedPreferences：**迅雷 / 阿里 的凭据不落盘**，
     * 它们每次解析时由 `XunleiCredStore` / `AliyunCredStore` 直接 root 直读，
     * 所以判据要按站点的「真相来源」分别取。
     */
    fun siteStatusLine(site: SiteId): String {
        val saved = settings.credential(site)
        val x = _xunleiCreds.value
        val a = _aliyunCreds.value
        return SiteProfile.readinessLabel(
            SiteProfile.readiness(
                site = site,
                savedCredential = saved,
                savedReady = CredentialValidator.ready(site, saved),
                xunleiReadable = x != null,
                xunleiAccessExpired = x?.accessExpired() ?: true,
                xunleiRefreshable = x != null && container.xunleiCreds.canRefresh(x),
                aliyunAccessTokenLength = a?.accessToken?.length ?: 0
            )
        )
    }

    /** 通道 B：root 提取 */
    /**
     * 保存一条新读到的凭据 —— **带「有效才覆盖，无效则保留原有」的保护**。
     *
     * 用户反馈的痛点（原话）：「每次打开检测失效就丢弃，成功静默保留原本的」。
     * 原实现的问题：[extractWithRoot] 只要拿到非空结果就无条件 `saveCredential` 覆盖，
     * 而探到的经常是**残缺**值（夸克的 `ctoken` 有、`__puus` 没有；123 的 64 位 hex 不是 JWT），
     * 于是把早先读到的好凭据冲掉了 → 表现为「越修越没有」。
     *
     * 现在的规则：
     *  1. **残缺结果不覆盖已有凭据**（只提示缺什么）；
     *  2. 若原有凭据**有效**，则原样静默保留，不打扰；
     *  3. 只有「新结果关键字段齐全」时才覆盖；
     *  4. 覆盖后顺手落盘时间戳（`markCredentialSaved`）。
     */
    private fun applyExtractedCredential(
        site: SiteId,
        newCookie: String,
        source: String
    ): String {
        val old = settings.credential(site)
        val newIssue = CredentialValidator.keyMissing(site, newCookie)
        val oldIssue = CredentialValidator.keyMissing(site, old)

        // ① 新结果关键字段残缺 → 绝不覆盖
        if (newIssue != null) {
            return if (old.isNotBlank() && oldIssue == null) {
                // ② 原有凭据是好的 → 静默保留，只说一句
                "已保留原有的有效 ${site.label} 凭据（本次读到的残缺：$newIssue）"
            } else if (old.isNotBlank()) {
                "本次读到残缺凭据（$newIssue），保留原有值；" +
                    "请点「root 直读」或「应用内登录」补齐"
            } else {
                "读到残缺凭据（$newIssue）——该站点需要完整令牌，请用应用内登录重新获取：$source"
            }
        }

        // ③ 新结果齐全 → 覆盖
        saveCredential(site, newCookie)
        settings.markCredentialSaved(site)
        return "已保存（来源：$source）"
    }

    fun extractWithRoot(site: SiteId) {
        when (site) {
            SiteId.XUNLEI -> {
                extractXunleiWithRoot()
                return
            }
            SiteId.ALI -> {
                extractAliyunWithRoot()
                return
            }
            SiteId.LANZOU -> {
                _credMessage.value = "蓝奏云免登录，无需提取凭据"
                return
            }
            else -> Unit
        }
        _creds.value = _creds.value.map { if (it.site == site) it.copy(extracting = true) else it }
        _credMessage.value = "正在经 root 提取 ${site.label} 凭据…（可能弹授权）"
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { container.extractor.extract(site) }
            res.onSuccess { hit ->
                val msg = applyExtractedCredential(site, hit.cookie, hit.source)
                _credMessage.value = msg
                log("[${site.label} 直读] $msg")
            }.onFailure { e ->
                val old = settings.credential(site)
                val msg = if (old.isNotBlank() &&
                    CredentialValidator.keyMissing(site, old) == null
                ) {
                    // 原有凭据有效就不打扰
                    "已保留原有 ${site.label} 凭据（本次读取失败：${e.message?.lines()?.firstOrNull()}）"
                } else {
                    e.message ?: "root 提取失败"
                }
                _credMessage.value = msg
                log("[${site.label} 直读] ✗ $msg")
            }
            _creds.value = loadCreds()
        }
    }

    /**
     * 通道 B：内置 WebView 登录回来后取其凭据。
     *
     * 关键前提：登录**必须**走应用内 WebView（[com.panpush.app.webview.WebViewLoginActivity]），
     * 因为外部浏览器的 Cookie 在浏览器沙箱里，本 App 永远读不到。
     *
     * ## 抓取方式（按站点分流，依据 `webview-login-3` 文档）
     *  · **Cookie** → `CookieManager.getCookie(url)`（Native 接口，**无视 httpOnly**）
     *    → 夸克 `__puus`、百度 `BDUSS`
     *  · **localStorage** → `evaluateJavascript`（**阿里云盘的 token 就在这里**，123 的 JWT 也在）
     *
     * @param liveCookie WebView 回传的 Cookie 头（有则优先用它，避免 flush 竞态）
     * @param localStorageJson WebView 回传的 localStorage 快照（JSON 串）
     */
    fun importFromWebView(site: SiteId, liveCookie: String? = null, localStorageJson: String? = null) {
        // ① 阿里云盘：token 在 localStorage.token（JSON：access_token/refresh_token/expires_in）
        //    → 走阿里自己的凭据仓库（它要的是 token 组，不是 cookie 串）
        if (site == SiteId.ALI) {
            val aliMsg = importAliyunFromLocalStorage(localStorageJson)
            if (aliMsg != null) {
                _credMessage.value = aliMsg
                log("[阿里 WebView] $aliMsg")
                refreshAliyunCreds()
                return
            }
            _credMessage.value = "内置浏览器里没找到阿里的 token。\n" +
                "请在页面里完成登录（扫码），等出现文件列表后会自动抓取。"
            return
        }
        // ② 123：JWT 在 localStorage
        val fromLs = pickTokenFromLocalStorage(site, localStorageJson)
        if (fromLs != null) {
            saveCredential(site, fromLs)
            _credMessage.value = "已从内置浏览器的 localStorage 读取 ${site.label} token"
            log("[${site.label} WebView] 已从 localStorage 取到 token")
            return
        }

        val r = WebCookieReader.read(site, extraBlob = localStorageJson)
        val ck = r.cookie ?: liveCookie?.takeIf { it.isNotBlank() }

        // ③ 迅雷：网页令牌（cookie/localStorage 的 credentials_* → access_token）
        //    走独立通道：它没有 captcha，解析器会自动切「网页令牌模式」（只发 Bearer）。
        if (site == SiteId.XUNLEI) {
            if (ck.isNullOrBlank()) {
                _credMessage.value = r.message()
                log("[迅雷 WebView] 未读到 access_token：${r.message()}")
                return
            }
            val auth = Regex("authorization=([^;]+)").find(ck)?.groupValues?.get(1)?.trim().orEmpty()
            val rt = Regex("refresh_token=([^;]+)").find(ck)?.groupValues?.get(1)?.trim().orEmpty()
            // ★ device_id：网页模式**也必须带**（缺它 API 报 "device_id is empty"）
            val dev = Regex("device_id=([^;]+)").find(ck)?.groupValues?.get(1)?.trim().orEmpty()
            // ★ captcha_token：网页模式**也必须带**（缺它报 "captcha_token is empty"）
            val cap = Regex("captcha_token=([^;]+)").find(ck)?.groupValues?.get(1)?.trim().orEmpty()
            if (auth.isBlank()) {
                _credMessage.value = "读到了迅雷 cookie，但里面没有 access_token。\n${r.message()}"
                return
            }
            container.xunleiCreds.saveWebToken(auth, rt, dev, cap)
            settings.markCredentialSaved(SiteId.XUNLEI)
            _credMessage.value = "✓ 已保存迅雷网页令牌，正在校验…"
            log(
                "[迅雷 WebView] 已保存网页令牌（refresh=${if (rt.isBlank()) "无" else "有"}，" +
                    "device=${if (dev.isBlank()) "无" else "有"}，" +
                    "captcha=${if (cap.isBlank()) "无" else "有"}），开始校验"
            )
            refreshXunleiCreds()
            // ★ 参考实现（XunleiLoginActivity）的做法：**真调一次列文件接口验证**，
            //   否则「保存了但用不了」用户不知道。校验在协程里做，避免阻塞 UI。
            viewModelScope.launch {
                val verified = withContext(Dispatchers.IO) { verifyXunleiWebToken(auth, dev) }
                _credMessage.value = buildString {
                    append("✓ 已读到迅雷网页令牌（网页令牌模式：只发 Bearer，不需要 captcha）")
                    if (rt.isBlank()) append("\n⚠️ 没有 refresh_token，过期后需重新登录")
                    append('\n').append(verified)
                    append("\n注意：网页令牌与 root 直读的 App 三件套**不是同一套**，不能混用。")
                }
                log("[迅雷 WebView] 校验：$verified")
            }
            return
        }

        if (ck.isNullOrBlank()) {
            _credMessage.value = r.message()
            return
        }
        // 同样走「残缺不覆盖」规则：网页侧常见只有可见 cookie（缺 __puus / BDUSS），
        // 不能把已有的好凭据冲掉。
        val msg = applyExtractedCredential(site, ck, "内置浏览器")
        _credMessage.value = "$msg\n${r.message()}"
        log("[${site.label} WebView] $msg")
    }

    /**
     * 从 WebView 回传的 localStorage 快照里解析阿里云盘 token 组。
     *
     * 严格对齐参考实现 `aliyun-login/AliyunLoginActivity.parseAliyunToken()`：
     *
     *  1. 先 **URL 解码**（`evaluateJavascript` 的返回值可能被转义）；
     *  2. 优先读**顶层键 `token`** —— 阿里把整包 JSON 存在这里
     *     （`{refresh_token, access_token, default_drive_id, user_id, expires_in, …}`）；
     *  3. 读不到就**正则从整个 dump 里抠** `refresh_token` / `access_token` / `default_drive_id`。
     *
     * ⚠️ 落盘时必须带上 **网页 client_id**（[AliyunCredStore.WEB_CLIENT_ID]）——
     * 因为 refresh_token 与签发它的 client 绑定，续期必须同 client
     * （`aliyun-login` §1.2 的对照实验：App token + 网页 client_id → `InvalidParameter.ClientId`）。
     *
     * @return 成功时返回给用户看的一句话；没找到返回 null
     */
    private fun importAliyunFromLocalStorage(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val decoded = try {
            java.net.URLDecoder.decode(json, "UTF-8")
        } catch (e: Exception) {
            json
        }
        var at = ""
        var rt = ""
        var drive = ""
        var key = ""
        // ★ 过期时间：没有它 expireSoon() 判断不出「快过期」→ 永远不主动续期
        var expireTime = ""

        // ① 优先顶层 "token" 字段（阿里把整包 JSON 存这儿）
        try {
            val root = org.json.JSONObject(decoded)
            val tv = root.optString("token", "")
            if (tv.isNotBlank()) {
                val t = try {
                    org.json.JSONObject(tv)
                } catch (e: Exception) {
                    // 有的版本 token 是被转义的字符串 → 再解一层
                    org.json.JSONObject(tv.removeSurrounding("\"").replace("\\\"", "\""))
                }
                if (t.optString("refresh_token").isNotBlank()) {
                    at = t.optString("access_token")
                    rt = t.optString("refresh_token")
                    drive = t.optString("default_drive_id").ifBlank { t.optString("drive_id") }
                    expireTime = t.optString("expire_time")
                    key = "token"
                }
            }
        } catch (e: Exception) {
            // 忽略，走正则兜底
        }

        // ② 兜底：正则从整库抠
        if (rt.isBlank()) {
            rt = Regex("\"refresh_token\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1).orEmpty()
            if (rt.isNotBlank()) {
                at = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"").find(decoded)?.groupValues?.get(1).orEmpty()
                drive = Regex("\"default_drive_id\"\\s*:\\s*\"([^\"]+)\"")
                    .find(decoded)?.groupValues?.get(1).orEmpty()
                expireTime = Regex("\"expire_time\"\\s*:\\s*\"([^\"]+)\"")
                    .find(decoded)?.groupValues?.get(1).orEmpty()
                key = "正则兜底"
            }
        }
        if (rt.isBlank() && at.isBlank()) return null

        // 落盘（带网页 client_id）
        container.aliyunCreds.saveManualCreds(
            accessToken = at,
            refreshToken = rt,
            driveId = drive,
            clientId = com.panpush.app.cred.AliyunCredStore.WEB_CLIENT_ID,
            source = "应用内网页登录",
            // ★ 带上过期时间：否则 expireSoon() 判断不出「快过期」→ 永远不主动续期
            expireTime = expireTime
        )
        settings.markCredentialSaved(SiteId.ALI)
        refreshAliyunCreds()
        // ★ 对齐参考实现 `AliyunLoginActivity.saveAndVerify()`：
        //   保存后用**网页 client** 刷新一次 access_token，再调文件列表接口校验。
        //   只保存不校验的话，用户看不出「存了但用不了」。
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) { verifyAliyunWebCreds(rt, drive) }
            _credMessage.value = buildString {
                append("✓ 已从内置浏览器 localStorage(`$key`) 读到阿里 token")
                if (rt.isNotBlank()) {
                    append("（含 refresh_token，续期会用**网页 client** ${com.panpush.app.cred.AliyunCredStore.WEB_CLIENT_ID}）")
                } else {
                    append("（⚠️ 无 refresh_token，过期后需重新登录）")
                }
                append('\n').append(msg)
                append("\n注意：网页令牌与 root 直读的 App 令牌**不是同一套**（client 不同，不能混用）。")
            }
            log("[阿里 WebView] 校验：$msg")
            refreshAliyunCreds()
        }
        return "✓ 已读到阿里 token，正在刷新并校验…"
    }

    /**
     * 校验阿里**网页令牌**：先用网页 client 刷新，再列一次文件。
     *
     * 依据 `aliyun-login` §3.1 的流程与 §1.2 的对照实验：
     *  · 刷新：`POST api.aliyundrive.com/token/refresh` + `{client_id: 25dzX3vbYqktVxyX, refresh_token}`
     *    （**必须用网页 client**，用 App 的会被拒 `InvalidParameter.ClientId`）
     *  · 校验：`POST api.aliyundrive.com/adrive/v3/file/list` + `Authorization: Bearer`
     *
     * 刷新成功会把新 access_token **落盘**（refresh_token 才是最长期的那份凭证）。
     */
    private fun verifyAliyunWebCreds(refreshToken: String, driveId: String): String {
        val webClient = com.panpush.app.cred.AliyunCredStore.WEB_CLIENT_ID
        var at = ""
        var drive = driveId
        // ① 用网页 client 刷新
        if (refreshToken.isNotBlank()) {
            val fresh = try {
                container.aliyunCreds.refresh(refreshToken, driveId, webClient)
            } catch (e: Exception) {
                null
            }
            if (fresh != null) {
                at = fresh.accessToken
                if (fresh.driveId.isNotBlank()) drive = fresh.driveId
                container.aliyunCreds.saveManualCreds(
                    accessToken = at,
                    refreshToken = fresh.refreshToken.ifBlank { refreshToken },
                    driveId = drive,
                    clientId = webClient,
                    source = "应用内网页登录",
                    // ★ 存下新的过期时间，后续才能主动续期（非 root 场景的关键）
                    expireTime = fresh.expireTime
                )
            } else {
                // 刷新失败：退回用当前 access_token 试一次，并给出准确原因
                val cur = container.aliyunCreds.readManualCreds()?.accessToken.orEmpty()
                if (cur.isBlank()) {
                    return "✗ 网页 client 刷新失败（refresh_token 可能已失效或被拒 InvalidParameter.ClientId）"
                }
                at = cur
            }
        } else {
            at = container.aliyunCreds.readManualCreds()?.accessToken.orEmpty()
        }
        if (at.isBlank()) return "✗ 没有可用的 access_token"
        // ② 列文件校验
        return try {
            val body = org.json.JSONObject().apply {
                put("drive_id", drive)
                put("parent_file_id", "root")
                put("limit", 100)
                put("order_by", "name")
                put("order_direction", "ASC")
            }
            val r = com.panpush.app.net.Http.postJson(
                "${com.panpush.app.cred.AliyunCredStore.API}/adrive/v3/file/list",
                mapOf(
                    "Authorization" to "Bearer $at",
                    "Content-Type" to "application/json",
                    "User-Agent" to com.panpush.app.cred.AliyunCredStore.UA,
                    "Referer" to "https://www.alipan.com/"
                ),
                body.toString(), 20
            )
            val j = com.panpush.app.net.Js.obj(r.text)
            if (r.status == 200 && j != null) {
                val n = j.optJSONArray("items")?.length() ?: 0
                "✓ 校验通过：网页 token 能列文件（根目录 $n 项，drive_id=$drive）"
            } else {
                val desc = j?.optString("message").orEmpty()
                    .ifBlank { j?.optString("code").orEmpty() }
                    .ifBlank { r.text.take(160) }
                "✗ 校验失败：HTTP ${r.status} $desc"
            }
        } catch (e: Exception) {
            "✗ 校验异常：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 校验迅雷**网页令牌**是否真能列文件。
     *
     * 对齐参考实现 `XunleiLoginActivity.saveToken()` 的做法（它会调一次
     * `api-pan.xunlei.com/drive/v1/files` 并 toast 结果）——
     * 只保存不校验的话，用户看不出「存了但用不了」。
     *
     * 关键：网页令牌模式**只发 `Authorization: Bearer`**，不带 X-Captcha-Token
     * （带了反而会被判无效 —— 见 `xunlei-file-test` 文档 §2 的三种失败情形）。
     */
    private fun verifyXunleiWebToken(authorization: String, deviceId: String = ""): String = try {
        val h = mutableMapOf(
            "Authorization" to authorization,
            "Accept" to "application/json, text/plain, */*",
            "User-Agent" to com.panpush.app.share.XunleiParser.UA_WEB,
            "X-Client-Id" to com.panpush.app.share.XunleiParser.CLIENT_ID,
            "X-Client-Version" to com.panpush.app.share.XunleiParser.CLIENT_VERSION,
            "Referer" to "https://pan.xunlei.com/"
        )
        // ★ 网页模式也要 device_id（缺它 → 400 "device_id is empty"）
        if (deviceId.isNotBlank()) h["X-Device-Id"] = deviceId
        val r = com.panpush.app.net.Http.get(
            "https://api-pan.xunlei.com/drive/v1/files?limit=50",
            h,
            20
        )
        val j = com.panpush.app.net.Js.obj(r.text)
        if (r.status == 200 && j != null) {
            val n = j.optJSONArray("files")?.length() ?: 0
            "✓ 校验通过：能列文件（本页 $n 项）"
        } else {
            val desc = j?.optString("error_description").orEmpty()
                .ifBlank { j?.optString("error").orEmpty() }
                .ifBlank { r.text.take(160) }
            "✗ 校验失败：HTTP ${r.status} $desc"
        }
    } catch (e: Exception) {
        "✗ 校验异常：${e.javaClass.simpleName}: ${e.message}"
    }

    /** 从 localStorage 快照里挑站点需要的 token（123 的 JWT 可能在这里） */
    private fun pickTokenFromLocalStorage(site: SiteId, json: String?): String? {
        if (site != SiteId.PAN123 || json.isNullOrBlank()) return null
        val o = try {
            org.json.JSONObject(json)
        } catch (e: Exception) {
            return null
        }
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optString(k)
            if (v.length > 20 && Regex("^[A-Za-z0-9._\\-]{40,}$").matches(v)) {
                return "token=$v"
            }
        }
        return null
    }

    /**
     * 自检：读**自己** App 的 WebView 存储（**不需要 root**），
     * 用来验证「登录到底存没存下来」——这是唯一能证明修复有效的判据。
     */
    fun inspectOwnWebViewStore(site: SiteId? = null) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val ctx = getApplication<android.app.Application>()
                    val hostFilter = site?.let { SiteProfile.of(it).checkHost.substringAfter("www.") }
                    val rows = WebViewInspector.readCookies(ctx, hostFilter)
                    val files = WebViewInspector.profileFiles(ctx)
                    buildString {
                        append("profile：${WebViewInspector.webviewDir(ctx).absolutePath}\n")
                        append(files.entries.joinToString("\n") { (k, v) -> "  ${if (v) "[有]" else "[无]"} $k" })
                        append("\n\nCookie（${rows.size} 条")
                        append(if (hostFilter != null) "，过滤 $hostFilter" else "")
                        append("）：\n")
                        if (rows.isEmpty()) {
                            append("  （空）—— 说明还没登录成功，或 WebView 还没把 Cookie 落盘\n")
                        } else {
                            rows.take(60).forEach { r ->
                                val v = if (r.value.isBlank() && r.encrypted) "（加密）" else r.value.take(28)
                                append("  ${r.host}  ${r.name} = $v")
                                if (r.httpOnly) append("  [httpOnly]")
                                append('\n')
                            }
                            if (rows.size > 60) append("  …（其余 ${rows.size - 60} 条省略）\n")
                        }
                        val ls = WebViewInspector.dumpLocalStorage(ctx)
                        if (ls.isNotEmpty()) {
                            append("\nLocal Storage（leveldb，${ls.size} 个文件，抽可打印串）：\n")
                            ls.entries.take(4).forEach { (f, list) ->
                                append("  $f：${list.take(6).joinToString(" | ").take(300)}\n")
                            }
                        }
                    }
                } catch (e: Exception) {
                    "自检失败：${e.message}"
                }
            }
            _inspectText.value = text
            try {
                android.util.Log.i("PanPushDiag", "[WebViewStore] " + text)
            } catch (_: Exception) {
            }
            _credMessage.value = "已读取自己 App 的 WebView 存储（无需 root）"
        }
    }

    /**
     * 供界面在「取直链并复制」完成后把结果复制到剪贴板用。
     *
     * 按用户要求：**只复制链接本身，一行一个** —— 文件名/大小对粘贴到下载器没用，
     * 反而是噪声（很多下载器会把它当成链接的一部分）。
     */
    fun downloadLinksText(): String =
        _downloads.value.joinToString("\n") { it.url.trim() }

    /** 与 [downloadLinksText] 相同（保留旧名，界面里语义已是「纯链接」） */
    fun downloadUrlsOnlyText(): String = downloadLinksText()

    /** 站点凭据方案（界面据此决定显示哪些入口） */
    fun profileOf(site: SiteId): SiteProfile.Profile = SiteProfile.of(site)

    /**
     * 一键诊断：把「su 状态 / 目标文件在不在 / 读到了什么 / 令牌是否过期」一次说清楚。
     *
     * 对应《诊断与改进建议》的核心诉求：**别再把「找不到 App」和「su 被拒」混成一句话**。
     * 每项都来自真机可复现的探测，不是猜。
     */
    fun runDiagnose(site: SiteId? = null) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                sb.append("su 可执行文件：")
                    .append(if (RootShell.hasSu()) "已找到" else "未找到").append('\n')
                sb.append("root 探测：").append(
                    try {
                        container.extractor.rootStatus()
                    } catch (e: Exception) {
                        "失败 ${e.message}"
                    }
                ).append('\n')
                sb.append("运行身份：")
                    .append(if (RootShell.isRoot()) "uid=0（本进程已是 root）" else "非 root（靠 su 提权）")
                    .append('\n')

                // 第 0 步：应用列表扫描（root）
                sb.append('\n').append("── 第 0 步：应用列表扫描 ──").append('\n')
                try {
                    val scan = container.extractor.scanApps(true)
                    sb.append("  ").append(scan.summary()).append('\n')
                    scan.detail().lines().forEach { sb.append("  ").append(it).append('\n') }
                } catch (e: Exception) {
                    sb.append("  扫描失败：").append(e.message).append('\n')
                }

                val targets = if (site != null) listOf(site) else SiteProfile.orderedSites
                for (s in targets) {
                    sb.append('\n').append("── ").append(s.label).append(" ──").append('\n')
                    sb.append("令牌位置：").append(SiteProfile.of(s).tokenWhere).append('\n')
                    // ① 文件探测（不用 PackageManager，见《诊断与改进建议》§7 P0）
                    try {
                        container.extractor.diagnose(s).forEach { sb.append("  ").append(it).append('\n') }
                    } catch (e: Exception) {
                        sb.append("  探测失败：").append(e.message).append('\n')
                    }
                    // ② 实际读取结果
                    when (s) {
                        SiteId.XUNLEI -> {
                            val c = try {
                                container.xunleiCreds.load()
                            } catch (e: Exception) {
                                null
                            }
                            if (c == null) {
                                sb.append("  读取：✗ 未取到三件套\n")
                            } else {
                                val left = c.accessRemainingSec()
                                sb.append("  读取：✓ device_id=").append(c.deviceId.take(12)).append("…\n")
                                sb.append("        access_token：")
                                    .append(if (left >= 0) "剩余 ${left}s" else "无过期信息")
                                    .append(if (c.accessExpired()) "  ⚠️ 已过期 → 会用 refresh_token 续期" else "")
                                    .append('\n')
                                sb.append("        refresh_token：")
                                    .append(if (c.refreshToken.isNotBlank()) "有（可续期）" else "无")
                                    .append('\n')
                                sb.append("        captcha：剩余 ").append(c.remainingSec()).append("s")
                                    .append(if (c.fresh()) "（新鲜）" else "（已过期 → 会拉起 APP 刷新）").append('\n')
                            }
                        }
                        SiteId.ALI -> {
                            val c = try {
                                container.aliyunCreds.load()
                            } catch (e: Exception) {
                                null
                            }
                            if (c == null) {
                                val why = container.aliyunCreds.lastError
                                sb.append("  读取：✗ 未取到 token 组")
                                if (why.isNotBlank()) sb.append("  ← ").append(why)
                                sb.append('\n')
                                sb.append("        wal 字节 = ").append(container.aliyunCreds.lastWalBytes)
                                    .append("（-1 = 没复制到；表只在 wal 里，必须是正数）\n")
                                sb.append("        读到的表 = ")
                                    .append(container.aliyunCreds.lastTables.take(6).joinToString(", ").ifBlank { "（无）" })
                                    .append('\n')
                            } else {
                                val left = c.remainingSec()
                                sb.append("  读取：✓ drive_id=").append(c.driveId).append('\n')
                                sb.append("        access_token：")
                                    .append(if (left >= 0) "剩余 ${left}s" else "无过期信息")
                                    .append(if (c.expireSoon()) "  ⚠️ 已过期/将过期 → 会自动续期" else "")
                                    .append('\n')
                                sb.append("        refresh_token：")
                                    .append(if (c.refreshToken.isNotBlank()) "有（可续期）" else "无")
                                    .append('\n')
                            }
                        }
                        else -> {
                            val cred = settings.credential(s)
                            if (cred.isBlank()) {
                                sb.append("  已保存凭据：✗ 无\n")
                            } else {
                                sb.append("  已保存凭据：✓ ").append(maskCredential(s, cred)).append('\n')
                                val v = try {
                                    CredentialValidator.validate(s, cred)
                                } catch (e: Exception) {
                                    CredentialValidator.Verdict(null, "校验异常 ${e.message}")
                                }
                                sb.append("  在线校验：").append(v.label).append(" — ").append(v.message).append('\n')
                            }
                        }
                    }
                }
                // 结论行：逐站点「已就绪 / 未就绪 / 免登录」（对齐 §6.3 与验收标准 5）
                sb.append('\n').append("── 结论（与顶部状态一致）──").append('\n')
                SiteId.entries.forEach { s ->
                    val p = SiteProfile.of(s)
                    val st = if (p.tokenSource == SiteProfile.TokenSource.NONE) "免登录"
                    else siteStatusLine(s)
                    sb.append("  ").append(s.label).append("：").append(st).append('\n')
                }
                sb.append('\n').append("说明：文件探测走 root 直读，不受 Android「包可见性」影响；")
                sb.append("夸克/UC 的浏览器 Cookie 库实测没有 __puus，那条路走不通 → 请用「应用内登录」。")
                sb.toString()
            }
            _diagnoseText.value = text
            // 同步写 logcat，便于用 `adb logcat` 抓取（界面上也能看到）
            try {
                android.util.Log.i("PanPushDiag", text)
            } catch (_: Exception) {
            }

            log("[一键诊断] 完成（详细结果见凭据页卡片）")
        }
    }

    companion object {
        /** Gopeed 安卓端包名（真机核对：com.gopeed.gopeed） */
        const val GOPEED_PKG = "com.gopeed.gopeed"

        /** 迅雷 APP 包名（真机核对；captcha 只能由它生成） */
        const val XUNLEI_PKG = "com.xunlei.downloadprovider"
    }

    /**
     * 迅雷：手动触发一次 access_token 续期（它只有 2 小时，实测经常已过期）。
     * 真机验证过：端点 `xluser-ssl.xunlei.com/v1/auth/token` + `okhttp/4.9.0` UA 可成功换新。
     */
    fun refreshXunleiAccessToken() {
        viewModelScope.launch {
            _credMessage.value = "正在用 refresh_token 续期迅雷 access_token…"
            val r = withContext(Dispatchers.IO) {
                try {
                    val c = container.xunleiCreds.load()
                    if (c == null) {
                        return@withContext "✗ 还没读到任何迅雷凭据。\n" +
                            "  · 有 root：点「root 读取三件套」（最快，免登录）\n" +
                            "  · 没有 root：点「应用内登录」登 pan.xunlei.com"
                    }
                    // ⚠️ 分模式给准确指引（原来只说「没有 refresh_token」，用户不知道该干嘛）
                    if (c.refreshToken.isBlank()) {
                        return@withContext if (c.isWebToken) {
                            "✗ 当前是**网页令牌模式**，网页登录这次没拿到 refresh_token，无法续期。\n" +
                                "  → 说明：网页 token 本身就是会话型凭证，**过期后重新登录一次**即可" +
                                "（再点「应用内登录」，程序会重取）。\n" +
                                "  → 若想要能长期续期的：有 root 就用「root 读取三件套」" +
                                "（App 令牌带 refresh_token，仅 2 小时但可续）。"
                        } else {
                            "✗ 当前凭据里没有 refresh_token（来源：${c.source}）。\n" +
                                "  → 有 root：点「root 读取三件套」重新读一次；\n" +
                                "  → 应用内登录会一并写入 refresh_token。"
                        }
                    }
                    // ★ **按模式自动选通道**：
                    //  · 网页令牌 → `refreshWebToken()`（用网页 client `Xqp0kJBXWhwaTpB6`）
                    //  · App 令牌 → `refreshAccessToken()`（用 App client）
                    //  混用会被拒（见 login-vs-root §3），原来这里**只会走 App 通道** →
                    //  网页令牌点「续期」必然失败。
                    val n = if (c.isWebToken) {
                        container.xunleiCreds.refreshWebToken(c.refreshToken)
                    } else {
                        container.xunleiCreds.refreshAccessToken(c.refreshToken)
                    }
                    if (n == null) {
                        val why = container.xunleiCreds.lastRefreshError
                        "✗ 续期失败：${why.ifBlank { "未知原因（看 logcat: PanPushXunlei）" }}"
                    } else if (c.isWebToken) {
                        // 网页令牌：写回网页令牌槽（captcha 保持为空 = 仍是网页模式）
                        container.xunleiCreds.saveWebToken(
                            n.authorization,
                            n.refreshToken.ifBlank { c.refreshToken }
                        )
                        _xunleiCreds.value = n
                        "✓ 续期成功（网页通道），新 access_token 有效期 " +
                            "${n.accessRemainingSec()}s（已落盘）"
                    } else {
                        // 立刻把新 token 反映到界面（captcha/device_id 保留）
                        _xunleiCreds.value = n.copy(
                            captchaToken = c.captchaToken,
                            captchaExpiresAtSec = c.captchaExpiresAtSec,
                            deviceId = c.deviceId
                        )
                        "✓ 续期成功（App 通道），新 access_token 有效期 " +
                            "${n.accessRemainingSec()}s（已落盘缓存）"
                    }
                } catch (e: Exception) {
                    "✗ 续期异常：${e.javaClass.simpleName}: ${e.message}"
                }
            }
            _credMessage.value = r
            // 按用户要求：所有记录都进**解析页的运行日志**（不再各页各显示一份）
            log("[迅雷续期] $r")
            try {
                android.util.Log.i("PanPushDiag", "[迅雷续期] $r")
            } catch (_: Exception) {
            }
            refreshXunleiCreds()
        }
    }

    fun clearCredMessage() {
        _credMessage.value = ""
    }

    // ---------------- 迅雷：三件套凭据 ----------------

    /** 重新直读 APP 三件套（并检测 APP 是否在设备上） */
    fun refreshXunleiCreds() {
        viewModelScope.launch {
            val (c, installed) = withContext(Dispatchers.IO) {
                val store = container.xunleiCreds
                val installed = try {
                    store.appInstalled()
                } catch (e: Exception) {
                    false
                }
                store.load() to installed
            }
            _xunleiCreds.value = c
            _xunleiAppInstalled.value = installed
            _creds.value = _creds.value.map {
                if (it.site == SiteId.XUNLEI) {
                    it.copy(
                        configured = c != null,
                        preview = c?.let { cc ->
                            "device ${cc.deviceId.take(12)}…  ·  captcha 剩余 ${cc.remainingSec()}s  ·  ${cc.source}"
                        } ?: ""
                    )
                } else it
            }
        }
    }

    /** 迅雷：root 直读三件套 */
    fun extractXunleiWithRoot() {
        _creds.value = _creds.value.map { if (it.site == SiteId.XUNLEI) it.copy(extracting = true) else it }
        _credMessage.value = "正在经 root 读取迅雷 APP 三件套凭据…（可能弹授权）"
        viewModelScope.launch {
            val (c, installed) = withContext(Dispatchers.IO) {
                val store = container.xunleiCreds
                val installed = try {
                    store.appInstalled()
                } catch (e: Exception) {
                    false
                }
                val creds = try {
                    store.readAppCreds()
                } catch (e: Exception) {
                    null
                }
                creds to installed
            }
            _xunleiAppInstalled.value = installed
            if (c != null) {
                _xunleiCreds.value = c
                _credMessage.value =
                    "迅雷凭据读取成功（${c.source}）\n  captcha 剩余 ${c.remainingSec()}s" +
                        if (c.fresh()) "" else "  →  已过期，解析时会用分享链接自动拉起一次 APP 刷新"
            } else {
                _credMessage.value = buildString {
                    append("没读到迅雷 App 三件套凭据。\n")
                    if (!installed) append("  · 设备上似乎没装迅雷 APP（com.xunlei.downloadprovider）\n")
                    else append("  · 迅雷 APP 已装，但 shared_prefs 里缺少凭据 → 请在 APP 里登录一次；\n")
                    append("  · 也可能 su 未授权本应用（root 直读这条路才需要 root）；\n")
                    append("  · **无 root 也能用**：点「应用内登录」登 pan.xunlei.com，" +
                        "程序会从 cookie 抠出网页 token（只需 Bearer，不需要 captcha）；\n")
                    append("  · 或点「应用内登录」登 pan.xunlei.com（四样会自动抓全）。")
                }
            }
            _creds.value = _creds.value.map { if (it.site == SiteId.XUNLEI) it.copy(extracting = false) else it }
            refreshXunleiCreds()
        }
    }

    fun setXunleiAutoRefresh(enabled: Boolean) {
        settings.xunleiAutoRefresh = enabled
        _xunleiAutoRefresh.value = enabled
    }

    private val _xunleiAutoRefresh = MutableStateFlow(settings.xunleiAutoRefresh)
    val xunleiAutoRefresh: StateFlow<Boolean> = _xunleiAutoRefresh.asStateFlow()

    /**
     * 迅雷凭据校验：**按凭据实际调一次接口**（非 root 也能用）。
     *
     * ## 修的是什么
     *
     * 以前这里只看 `c.fresh()`（captcha 的过期时间）。而**网页令牌的 captcha
     * 没有过期时间**（`captchaExpiresAtSec = 0`）→ `fresh()` 恒为 false
     * → 界面永远显示「未知」，可解析明明是好的（正是用户反馈的现象）。
     *
     * 现在改成 **`GET /drive/v1/files?limit=1`**，HTTP 200 才算「已就绪」；
     * 失败时按错误码/文案区分是 captcha 失效、device_id 缺失还是 access_token 过期，
     * 并给出对应操作（打开登录页刷新 / 重新登录 / 点续期）。
     *
     * 关键：头由 [com.panpush.app.share.XunleiParser.authHeaders] 生成，
     * 与**解析器走同一条构造路径** —— 校验通过 ⇒ 解析一定也能通过。
     */
    fun checkXunleiCredential() {
        _creds.value = _creds.value.map { if (it.site == SiteId.XUNLEI) it.copy(checking = true) else it }
        viewModelScope.launch {
            val c = withContext(Dispatchers.IO) {
                try {
                    container.xunleiCreds.load()
                } catch (e: Exception) {
                    null
                }
            }
            if (c == null) {
                _xunleiCreds.value = null
                setXunleiVerdict(
                    CredentialValidator.Verdict(
                        false,
                        "未找到迅雷凭据（有 root 可「root 读取三件套」，无 root 用「应用内登录」）"
                    )
                )
                return@launch
            }
            _xunleiCreds.value = c
            val (ok, msg) = withContext(Dispatchers.IO) {
                try {
                    val res = com.panpush.app.net.Http.get(
                        "${com.panpush.app.share.XunleiParser.API_BASE}/files?limit=1",
                        com.panpush.app.share.XunleiParser.authHeaders(c),
                        20
                    )
                    if (res.status == 200) {
                        true to "已就绪（接口校验通过）"
                    } else {
                        val j = com.panpush.app.net.Js.obj(res.text)
                        val code = j?.optInt("error_code", -1) ?: -1
                        val desc = j?.optString("error_description").orEmpty()
                            .ifBlank { j?.optString("error").orEmpty() }
                            .ifBlank { res.text.take(160) }
                        val why = when {
                            code == 9 || desc.contains("captcha", true) ->
                                "captcha 已失效 → 点「应用内登录」打开一次登录页即可刷新"
                            desc.contains("device_id", true) ->
                                "device_id 缺失 → 点「应用内登录」重新取一次"
                            code == 16 || desc.contains("unauthenticated", true) ||
                                desc.contains("expired", true) ->
                                "access_token 已过期 → 点「续期」自动换新"
                            else -> desc
                        }
                        false to "校验未通过：$why"
                    }
                } catch (e: Exception) {
                    false to "校验异常：${e.javaClass.simpleName}: ${e.message}"
                }
            }
            setXunleiVerdict(
                CredentialValidator.Verdict(ok, if (ok) msg else msg + renewAdvice())
            )
        }
    }

    /** 更新迅雷卡片上的校验结论（并结束 checking 动画） */
    /**
     * 凭据校验失败时，按**当前权限模式**给出该点哪个按钮。
     *
     * 阿里 / 迅雷的凭据都会过期，但刷新路径完全不同：
     * · **非 root** → 只能靠「应用内登录」重新登录一遍刷新网页令牌（唯一来源）
     * · **有 root** → 直接「root 读取」重新读 App 里的凭据最省事
     *
     * 之前两种情况给同一句笼统提示，用户不知道该点哪个。
     */
    private fun renewAdvice(): String =
        if (_rootAvailable.value) "\n→ 点「root 读取」重新读取凭据"
        else "\n→ 点「应用内登录」重新登录刷新凭据"

    private fun setXunleiVerdict(v: CredentialValidator.Verdict) {
        _creds.value = _creds.value.map {
            if (it.site == SiteId.XUNLEI) it.copy(checking = false, verdict = v) else it
        }
    }

    // ---------------- 阿里云盘：token 凭据 ----------------

    fun refreshAliyunCreds() {
        viewModelScope.launch {
            val (c, installed) = withContext(Dispatchers.IO) {
                val store = container.aliyunCreds
                val installed = try {
                    store.appInstalled()
                } catch (e: Exception) {
                    false
                }
                val creds = try {
                    store.load()
                } catch (e: Exception) {
                    null
                }
                creds to installed
            }
            _aliyunCreds.value = c
            _aliyunAppInstalled.value = installed
            _creds.value = _creds.value.map {
                if (it.site == SiteId.ALI) {
                    it.copy(
                        configured = c != null,
                        preview = c?.let { cc ->
                            val left = cc.remainingSec()
                            "drive_id: ${cc.driveId.ifBlank { "?" }}\n" +
                                "access_token: ${cc.accessToken.take(16)}…\n" +
                                (if (left >= 0) "有效期: 剩余 ${left}s" else "有效期: 未知") +
                                "  ·  ${cc.source}"
                        } ?: ""
                    )
                } else it
            }
        }
    }

    /** 阿里：root 直读 APP 明文 DB（0.db → tb_user_info） */
    fun extractAliyunWithRoot() {
        _creds.value = _creds.value.map { if (it.site == SiteId.ALI) it.copy(extracting = true) else it }
        _credMessage.value = "正在经 root 读取阿里云盘 APP 的明文 token 数据库…（可能弹授权）"
        viewModelScope.launch {
            val (c, installed) = withContext(Dispatchers.IO) {
                val store = container.aliyunCreds
                val installed = try {
                    store.appInstalled()
                } catch (e: Exception) {
                    false
                }
                val creds = try {
                    store.readAppCreds()
                } catch (e: Exception) {
                    null
                }
                creds to installed
            }
            _aliyunAppInstalled.value = installed
            if (c != null) {
                _aliyunCreds.value = c
                val left = c.remainingSec()
                _credMessage.value = buildString {
                    append("阿里云盘凭据读取成功（${c.source}）\n")
                    append("  drive_id: ${c.driveId.ifBlank { "（缺失，请手动补）" }}\n")
                    if (left >= 0) append("  access_token 剩余 ${left}s（快过期时会自动续期）")
                }
            } else {
                _credMessage.value = buildString {
                    append("没读到阿里云盘凭据。\n")
                    if (!installed) append("  · 设备上似乎没装阿里云盘 APP（com.alicloud.databox）\n")
                    else append("  · APP 已装，但 databases/0.db 的 tb_user_info 里没有可用 token → 请在 APP 里登录一次\n")
                    append("  · 也可能是 su 未授权本应用；\n")
                    append("  · 无 root 时请点「应用内登录」登 www.alipan.com。")
                    val why = container.aliyunCreds.lastError
                    if (why.isNotBlank()) append("\n  · 细节：").append(why)
                }
            }
            _creds.value = _creds.value.map { if (it.site == SiteId.ALI) it.copy(extracting = false) else it }
            refreshAliyunCreds()
        }
    }

    fun setAliyunAutoClean(enabled: Boolean) {
        settings.aliyunAutoClean = enabled
        _aliyunAutoClean.value = enabled
    }

    /** 阿里凭据校验：能否读到 + drive_id 是否齐全 + token 剩余有效期（可自动续期） */
    fun checkAliyunCredential() {
        _creds.value = _creds.value.map { if (it.site == SiteId.ALI) it.copy(checking = true) else it }
        viewModelScope.launch {
            val c = withContext(Dispatchers.IO) {
                try {
                    container.aliyunCreds.load()
                } catch (e: Exception) {
                    null
                }
            }
            _aliyunCreds.value = c
            val v = when {
                c == null -> CredentialValidator.Verdict(
                    false,
                    "未找到阿里云盘凭据（需 root 直读 APP 明文 DB，或用「应用内登录」）"
                )
                c.driveId.isBlank() && c.refreshToken.isBlank() -> CredentialValidator.Verdict(
                    null,
                    "有 access_token 但没有 drive_id / refresh_token，转存与续期可能失败"
                )
                else -> {
                    val left = c.remainingSec()
                    val leftText = if (left >= 0) "token 剩余 ${left}s" else "有效期未知"
                    CredentialValidator.Verdict(
                        true,
                        "凭据就绪（drive_id=${c.driveId.ifBlank { "?" }}，$leftText）"
                    )
                }
            }
            // ★ 阿里校验失败时同样按权限给刷新指引（与迅雷一致，见 renewAdvice）
            val vv = if (v.valid == true) v
                     else CredentialValidator.Verdict(v.valid, (v.message ?: "") + renewAdvice())
            _creds.value = _creds.value.map {
                if (it.site == SiteId.ALI) it.copy(checking = false, verdict = vv) else it
            }
        }
    }
}
