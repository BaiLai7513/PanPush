package com.panpush.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.panpush.app.core.SiteId
import com.panpush.app.cred.SiteProfile
import com.panpush.app.webview.WebViewLoginActivity
import com.panpush.app.ui.theme.PanAmber
import com.panpush.app.ui.theme.PanBlue
import com.panpush.app.ui.theme.PanGreen
import com.panpush.app.ui.theme.PanRed

/**
 * 登录态 / 凭据管理。
 *
 * 界面按**站点凭据方案**（[SiteProfile]）决定显示哪些通道——这是关键：
 *
 * | 站点 | 凭据在哪 | 内置浏览器能拿到吗 | 界面给的入口 |
 * |---|---|---|---|
 * | 夸克 / UC | `__puus`（**httpOnly**） | ❌ 拿不到 | 一键登录（存 Cookie）+ root 直读 / 应用内登录 |
 * | 123 | **APP 的 JWT**（网页 Cookie 里没有） | ❌ 拿不到 | 一键登录 + root 直读 / 应用内登录 |
 * | 百度 | `BDUSS`（非 httpOnly） | ✅ 能拿到 | **应用内登录** + root 直读 |
 * | 迅雷 | 三件套（captcha 只能 APP 生成） | ❌ 拿不到 | root 直读 / 应用内登录 |
 * | 阿里 | token 组（APP 明文 DB） | ❌ 拿不到 | root 直读 / 应用内登录 |
 * | 蓝奏 | 无 | — | 免登录，不需要 |
 */
@Composable
fun CredentialsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val creds by vm.creds.collectAsState()
    val rootStatus by vm.rootStatus.collectAsState()
    val message by vm.credMessage.collectAsState()
    val inspectText by vm.inspectText.collectAsState()
    val rootAvailable by vm.rootAvailable.collectAsState()
    val diagnoseText by vm.diagnoseText.collectAsState()

    var loginSite by remember { mutableStateOf<SiteId?>(null) }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val site = loginSite
        if (site != null) {
            val cookie = result.data?.getStringExtra(WebViewLoginActivity.EXTRA_RESULT_COOKIE)
            val ls = result.data?.getStringExtra(WebViewLoginActivity.EXTRA_RESULT_LOCAL_STORAGE)
            vm.importFromWebView(site, cookie, ls)
        }
    }

    LaunchedEffect(Unit) { vm.refreshRootStatus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" 返回")
            }
            Text(
                "  登录态 / 凭据",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { vm.runDiagnose() }) { Text("一键诊断") }
            TextButton(onClick = { vm.extractAllWithRoot() }) { Text("全部直读") }
        }

        Text(
            rootStatus + if (rootAvailable) "  ·  已检测到 su → 各站点优先走「root 直读」（最快最省事）" else
                "  ·  未检测到 su → 只能用「应用内登录」（百度/夸克可直接拿到 Cookie）",
            style = MaterialTheme.typography.labelSmall,
            color = if (rootAvailable) PanGreen else PanAmber
        )

        // ---- 一键诊断结果（su 状态 / 文件在不在 / 读到了什么 / 令牌是否过期）----
        if (diagnoseText.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "诊断结果",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.clearDiagnose() }) { Text("关闭") }
                    }
                    Text(
                        diagnoseText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 一眼看清「每个站点的凭据到底在哪、该点哪个」
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("凭据获取方式总览", style = MaterialTheme.typography.titleMedium)
                // 每站点一行状态：已就绪 / 未就绪 / 免登录（对齐 §6.3）
                SiteProfile.orderedSites.forEach { s ->
                    val st = vm.siteStatusLine(s)
                    val col = when (st) {
                        "已就绪" -> PanGreen
                        "免登录" -> PanBlue
                        else -> PanAmber
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "▸ ${s.label}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text("● $st", style = MaterialTheme.typography.labelSmall, color = col)
                    }
                    // 各站点「拿凭据有多快」（实测后的排序）
                    Text(
                        "    " + SiteProfile.quickNote(s, rootAvailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "▸ 蓝奏云",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text("● 免登录", style = MaterialTheme.typography.labelSmall, color = PanBlue)
                }
            }
        }

        if (message.isNotBlank()) {
            InfoCard("提示", message, color = MaterialTheme.colorScheme.primaryContainer)
        }

        // ---- 存储自检结果（读自己 App 的 WebView 库，不需要 root）----
        if (inspectText.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "WebView 存储自检（不需要 root）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.clearInspect() }) { Text("关闭") }
                    }
                    Text(
                        inspectText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---- 通用 Cookie 类站点（夸克 / UC / 123 / 百度）----
        creds.filter { it.site != SiteId.XUNLEI && it.site != SiteId.ALI }.forEach { c ->
            CookieCredCard(
                vm = vm,
                site = c.site,
                preview = c.preview,
                configured = c.hasCredential,
                checking = c.checking,
                extracting = c.extracting,
                rootAvailable = rootAvailable,
                keyIssue = c.keyIssue,
                verdictLabel = c.verdict?.label,
                verdictMessage = c.verdict?.message,
                verdictValid = c.verdict?.valid,
                onLogin = {
                    loginSite = c.site
                    vm.clearCredMessage()
                    loginLauncher.launch(
                        WebViewLoginActivity.buildIntent(vm.getApplication(), c.site)
                    )
                },
                onRoot = { vm.extractWithRoot(c.site) },
                onCheck = { vm.checkCredential(c.site) },
                onClear = { vm.clearCredential(c.site) },
                onInspect = { vm.inspectOwnWebViewStore(c.site) }
            )
        }

        // ---- 迅雷：三件套 / 网页 token ----
        XunleiCredCard(
            vm,
            message = message,
            onLogin = {
                loginSite = SiteId.XUNLEI
                vm.clearCredMessage()
                loginLauncher.launch(
                    WebViewLoginActivity.buildIntent(vm.getApplication(), SiteId.XUNLEI)
                )
            }
        )

        // ---- 阿里云盘：token 组 ----
        AliyunCredCard(
            vm,
            message = message,
            onLogin = {
                loginSite = SiteId.ALI
                vm.clearCredMessage()
                loginLauncher.launch(
                    WebViewLoginActivity.buildIntent(vm.getApplication(), SiteId.ALI)
                )
            }
        )

        InfoCard(
            title = "蓝奏云：免登录",
            body = "蓝奏云全匿名可用，**不需要任何凭据**：\n" +
                "  · 直接粘贴形如 https://xxx.lanzoui.com/xxxx 的分享链接即可；\n" +
                "  · 带密码的分享把密码填到「提取码」栏；\n" +
                "  · 域名会轮换（lanzoui / lanzoux / lanzouw / lanzouq / lanzouf / lanzn…），程序自动识别。\n" +
                "下载 CDN 可能带阿里云 WAF 挑战（acw_sc__v2），程序会解出 Cookie 一并交给 Gopeed；\n" +
                "因 CDN 有频控，这里只探测一次，真正的下载交给 Gopeed。"
        )

        InfoCard(
            title = "登录方式说明（两条通道，各站点可用性不同）",
            body = "**五个站点都有「应用内登录」**（WebView），但**不是每条通道都能拿到可用令牌** ——\n" +
                "选错通道就会出现「登录了却解析失败」。按下面的实测结论选：\n" +
                "\n" +
                "【夸克】只能「应用内登录」：登录页必须用**桌面 UA**（手机 UA 会被 302 到「下载 App」页）。\n" +
                "  `__puus` 虽是 httpOnly，但 `CookieManager` 是 Native 接口**能读回**（已实测拿到）。\n" +
                "  root 读**无效**：本机三个浏览器 Cookie 库里只有 `b./m.quark.cn`，没有 `pan.quark.cn`、没有 `__puus`。\n" +
                "\n" +
                "【百度】两条都行。`BDUSS` 非 httpOnly，网页登录能直接拿到；root 也能读 APP 的 WebView 库。\n" +
                "\n" +
                "【123】首选 root（App token，`platform=3`）。网页登录也能拿到 token，但 `platform` 不同、**未必通用**。\n" +
                "\n" +
                "【阿里】首选 root（App token，免登录、可长期续期）。网页登录读 `localStorage.token` 也能用；\n" +
                "  但**网页 client 与 App client 是两套** —— refresh_token **不能跨 client 刷新**" +
                "（实测 `InvalidParameter.ClientId`），所以程序会记住令牌来自哪个 client，续期时配对使用。\n" +
                "\n" +
                "【迅雷】首选 root（App 三件套）。网页登录拿到的 token **同一套 API 也能用**——\n" +
                "  网页 client 的 scope 含 `pan`，只发 `Authorization: Bearer` 即可，**不需要 captcha**。\n" +
                "  但 App 的 access_token 只有 **2 小时**、captcha 只有几分钟，最容易需要续期。\n" +
                "\n" +
                "⚠️ 核心规则（别混用）：**root 拿的是「目标 App 客户端」的令牌，应用内登录拿的是「网页客户端」的令牌**，\n" +
                "两者值不同、各自只对各自的 API 有效。程序已按站点自动选对通道，界面上的按钮顺序就是推荐顺序。\n" +
                "\n" +
                "外部浏览器 / CustomTabs 不在选项里 —— 它的 Cookie 在浏览器沙箱，本 App 永远读不到。\n" +
                "「存储自检」读的是**自己** App 的 app_webview/Default（**不需要 root**），可验证登录有没有存下来。"
        )

        Column(Modifier.height(6.dp)) { }
    }

}

/**
 * 一行小标签：说明该站点推荐的通道
 *
 * （原先这里有一张「第 0 步：应用列表扫描」卡片 + 手动「扫应用」按钮；
 *  现按用户要求改为**打开 App 时自动扫一次**，结果只写进**解析页的运行日志**，
 *  所以卡片与交互按钮都去掉了。）
 */
@Composable
private fun RecommendTag(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}

/**
 * 通用「Cookie 类」站点卡片（夸克 / UC / 123 / 百度）。
 *
 * 关键行为：
 *  · 「一键登录」= **应用内 WebView** 登录（唯一能存下登录态的方式）。
 *    外部浏览器 / CustomTabs 的 Cookie 在浏览器沙箱里，本 App 永远读不到，所以界面不再提供那条路。
 *  · 登录后如果网页拿不到最终令牌（夸克 httpOnly / 123 是 APP 的 JWT / 迅雷阿里是 token 组），
 *    卡片会明确写「登录后仍需 root 直读」，不让人白点。
 *  · 「存储自检」读**自己** App 的 app_webview/Default（**不需要 root**），
 *    用来验证登录到底存没存下来。
 */
@Composable
private fun CookieCredCard(
    vm: MainViewModel,
    site: SiteId,
    preview: String,
    configured: Boolean,
    checking: Boolean,
    extracting: Boolean,
    rootAvailable: Boolean,
    keyIssue: String?,
    verdictLabel: String?,
    verdictMessage: String?,
    verdictValid: Boolean?,
    onLogin: () -> Unit,
    onRoot: () -> Unit,
    onCheck: () -> Unit,
    onClear: () -> Unit,
    onInspect: () -> Unit
) {
    val p = SiteProfile.of(site)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(site.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    when {
                        !configured -> "未配置"
                        keyIssue != null -> "残缺：缺 $keyIssue"
                        else -> "已就绪"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        !configured -> PanRed
                        keyIssue != null -> PanAmber
                        else -> PanGreen
                    }
                )
            }

            if (keyIssue != null && configured) {
                RecommendTag(
                    text = "⚠️ 当前凭据不含 $keyIssue —— 这是内置浏览器里**可见**的 cookie，" +
                        "而取直链/下载需要那个 httpOnly 或 App 私有的令牌。请点「root 直读」拿真的。",
                    color = PanAmber
                )
            }

            RecommendTag(
                text = when {
                    p.tokenSource == SiteProfile.TokenSource.WEB_VIEW ->
                        "本机可用「应用内登录」（BDUSS 非 httpOnly，登录后能直接读回）"
                    rootAvailable ->
                        "★ 本机有 root → 用「root 直读」（唯一能拿到该站点令牌的路）"
                    else ->
                        "该站点不能用网页登录拿令牌 → 请先获得 root 用「root 直读」"
                },
                color = when {
                    p.tokenSource == SiteProfile.TokenSource.WEB_VIEW -> PanBlue
                    rootAvailable -> PanGreen
                    else -> PanAmber
                }
            )

            Text(
                p.tokenWhere,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (preview.isNotBlank()) {
                Text(
                    preview,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (verdictLabel != null) {
                Text(
                    "$verdictLabel  ${verdictMessage ?: ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = when (verdictValid) {
                        true -> PanGreen
                        false -> PanRed
                        else -> PanAmber
                    }
                )
            }

            // 第一行：按**通道优先级**渲染（依据 login-vs-root 选型文档 §6：
            // Root（有 root 且 App 已登录）> WebView 登录）。
            // 每站可用通道不同，由 SiteProfile.channelsFor() 决定：
            //  · 夸克：库里没有 pan.quark.cn/__puus → **没有 root 通道**，只有 应用内登录（桌面 UA）
            //  · 迅雷：解析器调 App API，网页 token 不通 → **没有 WebView 通道**，只有 root 直读
            //  · 123/阿里：root 优先，WebView 作为兜底（网页 token 未必通用，界面会提示）
            //  · 百度：两者皆可（同域 cookie 通用）
            val channels = SiteProfile.channelsFor(p.site, rootAvailable)
            val primary = channels.firstOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                channels.forEach { ch ->
                    val isPrimary = ch == primary
                    val onClick: () -> Unit = when (ch) {
                        SiteProfile.Channel.ROOT -> onRoot
                        SiteProfile.Channel.WEBVIEW -> onLogin
                    }
                    val label = when (ch) {
                        SiteProfile.Channel.ROOT -> " root 直读"
                        SiteProfile.Channel.WEBVIEW -> " 应用内登录"
                    }
                    val icon = when (ch) {
                        SiteProfile.Channel.ROOT -> Icons.Filled.Lock
                        SiteProfile.Channel.WEBVIEW -> Icons.Filled.Public
                    }
                    if (isPrimary) {
                        Button(
                            onClick = onClick,
                            enabled = !extracting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (extracting && ch == SiteProfile.Channel.ROOT) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Text(label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onClick,
                            enabled = !extracting,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (extracting && ch == SiteProfile.Channel.ROOT) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Text(label)
                        }
                    }
                }
            }
            // 令牌类型提醒（login-vs-root §7 #5：
            // 「判断『已就绪』要看令牌类型是否匹配你要调的 API，不能只看有没有令牌」）
            if (p.tokenSource == SiteProfile.TokenSource.ROOT_APP &&
                channels.contains(SiteProfile.Channel.WEBVIEW)
            ) {
                Text(
                    "优先用「root 直读」拿 **App 令牌**（解析器调的就是 App API）；" +
                        "「应用内登录」拿的是**网页 token**，未必通用 —— 用网页登录后若解析报 401/403，" +
                        "请改回 root 直读。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (p.tokenSource == SiteProfile.TokenSource.ROOT_APP &&
                !channels.contains(SiteProfile.Channel.WEBVIEW) &&
                p.localSource != null
            ) {
                Text(
                    "（该站点不给网页登录入口：解析器调的是 **App API**，" +
                        "网页 token 喂不通，登上也没用 → 请用 root 直读）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (p.tokenSource == SiteProfile.TokenSource.WEB_VIEW &&
                channels.contains(SiteProfile.Channel.WEBVIEW)
            ) {
                Text(
                    if (p.desktopUa) {
                        "登录页用**桌面 UA**（手机 UA 会被站点 302 到「下载 App」页，登不上）；" +
                            "登录成功后自动读回令牌（Cookie 走 CookieManager —— native 接口，不受 httpOnly 限制）。"
                    } else {
                        "登录成功后自动读回令牌。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 第二行：校验 / 存储自检 / 清除
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCheck,
                    enabled = !checking && configured,
                    modifier = Modifier.weight(1f)
                ) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(" 校验")
                }
            }

            if (configured) {
                TextButton(onClick = onClear) { Text("清除该站点凭据") }
            }

            TextButton(onClick = onInspect) { Text("存储自检（读自己 App 的 WebView 库，不需要 root）") }

            Text(
                p.localSource?.let { "root 直读来源：$it" }
                    ?: "root 直读：该站点无效（本机 App 里没有该令牌），已隐藏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 迅雷凭据卡片：三件套（Authorization / Captcha / DeviceId）**或**网页 token。
 *
 * 两条通道（依据 `xunlei-file-test/迅雷应用内登录取文件-测试与方案.md`）：
 *  · **root 直读** APP `shared_prefs` → App 令牌（三件套，含 captcha）
 *  · **应用内登录** `pan.xunlei.com` → 网页 token（cookie 里 `credentials_<clientId>`）
 *
 * ⚠️ 令牌不能混用，但**同一个 API** 两种都能调：
 * 网页 token 只发 `Authorization: Bearer`，**不需要 captcha**（解析器自动判别）。
 */
@Composable
private fun XunleiCredCard(
    vm: MainViewModel,
    message: String,
    onLogin: () -> Unit
) {
    val creds by vm.creds.collectAsState()
    val xc by vm.xunleiCreds.collectAsState()
    val installed by vm.xunleiAppInstalled.collectAsState()
    val autoRefresh by vm.xunleiAutoRefresh.collectAsState()
    val rootAvailable by vm.rootAvailable.collectAsState()
    val c = creds.firstOrNull { it.site == SiteId.XUNLEI }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("迅雷云盘", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (c?.hasCredential == true) "三件套已就绪" else "未就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c?.hasCredential == true) PanGreen else PanRed
                )
            }

            // ⚠️ 这句原来写的是「网页端签名已白盒化，登录网页没用」—— **已被实测推翻**：
            //    迅雷网页 client（Xqp0kJBXWhwaTpB6）scope 含 `pan`，
            //    网页登录拿到的 token 配 `api-pan.xunlei.com` **只要 Bearer**（不需 captcha）。
            //    见 xunlei-file-test/迅雷应用内登录取文件-测试与方案.md。
            RecommendTag(
                if (rootAvailable) {
                    "最快：root 直读 APP 三件套（免登录）；没 root 就用「应用内登录」——" +
                        "网页 token 同样能取文件（只是另一种 client 的令牌，不能与 App 混用）"
                } else {
                    "没有 root：用「应用内登录」拿网页 token（只需 Bearer，不需要 captcha）"
                },
                if (rootAvailable) PanGreen else PanBlue
            )

            if (xc != null) {
                val x = xc!!
                // ★ 修「有效期显示 0 秒已过期、但接口校验已通过」的**误导**：
                //   迅雷的 **captcha 令牌没有过期时间**（`captchaExpiresAtSec = 0`），
                //   于是 `remainingSec()` = 0、`fresh()` = false → 原来会打「剩余 0s（已过期）」；
                //   可实际上**接口是通的**（校验显示已就绪）。
                //   所以：**有校验结论时以校验为准**，没有时才显示这个粗糙的本地推算。
                val verdict = c?.verdict
                val expiryLine = when {
                    verdict?.valid == true ->
                        "有效期: 以「校验」为准 —— ${verdict.message}（本地过期时间不可用于网页令牌）"
                    x.captchaExpiresAtSec > 0 ->
                        "有效期: 剩余 ${x.remainingSec()}s" +
                            (if (x.fresh()) "（新鲜）" else "（已过期）")
                    else ->
                        "有效期: 未知（网页令牌的 captcha 不带过期时间）"
                }
                Text(
                    "device_id: ${x.deviceId}\n" +
                        "captcha: ${x.captchaToken.take(14)}…\n" +
                        "$expiryLine\n" +
                        "来源: ${x.source}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    when {
                        !rootAvailable ->
                            "没有 root，读不到 APP 三件套 → 请点上面的「应用内登录」" +
                                "（会一并抓到 access/refresh/device/captcha 四样）"
                        installed -> "已检测到迅雷 APP，但没读到凭据 → 请在迅雷 APP 里登录一次"
                        else -> "未检测到迅雷 APP（com.xunlei.downloadprovider），或 su 未授权"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PanAmber
                )
            }

            c?.verdict?.let { v ->
                Text(
                    "${v.label}  ${v.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = when (v.valid) {
                        true -> PanGreen
                        false -> PanRed
                        else -> PanAmber
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 首选通道按 root 是否可用决定（root 可用时 root 更快、免登录）
                if (rootAvailable) {
                    Button(
                        onClick = { vm.extractXunleiWithRoot() },
                        enabled = c?.extracting != true,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (c?.extracting == true) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Text(" root 读取三件套")
                    }
                    OutlinedButton(
                        onClick = onLogin,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" 应用内登录")
                    }
                } else {
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" 应用内登录")
                    }
                    OutlinedButton(
                        onClick = { vm.extractXunleiWithRoot() },
                        enabled = c?.extracting != true,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (c?.extracting == true) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Text(" root 直读")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.checkXunleiCredential() },
                    enabled = c?.checking != true,
                    modifier = Modifier.weight(1f)
                ) {
                    if (c?.checking == true) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(" 校验")
                }
                OutlinedButton(
                    onClick = { vm.refreshXunleiAccessToken() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(" 续期")
                }
            }

            // ★ **把操作结果显示在卡片内**：原来消息只出现在页面顶部的「提示」卡，
            //   而本卡片在页面下方 → 结果在屏幕外，看起来像「点了没反应」。
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            // refresh_token 失效（error_code 4126）时，唯一能自愈的路是**拉起迅雷 APP** 让它自己续期。
            // 拉起优先用 ROOT（包可见性会让普通 startActivity 拿到 null，见 <queries> 说明），
            // 没 root 时明确提示「手动打开」。
            OutlinedButton(
                onClick = { vm.refreshXunleiByApp() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(" 拉起迅雷 APP 刷新令牌")
            }
            Text(
                "两种令牌**不能混用**（各自只对自己的 client 有效）：\n" +
                    "· **App 三件套**（root 直读）：`Authorization` + `X-Captcha-Token` + `X-Device-Id`，" +
                    "access_token 只有 **2 小时**，captcha 也只有几分钟；\n" +
                    "· **网页 token**（应用内登录）：**只发 `Authorization: Bearer`**，不需要 captcha ——" +
                    "解析器会按「有没有 captcha」自动选模式。\n" +
                    "· 若提示 `4126 invalid refresh token`（太久没打开迅雷）：点「拉起迅雷 APP 刷新令牌」，" +
                    "程序会用 **root 拉起**迅雷（没 root 会提示你手动打开），等它联网续期后自动重读。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoRefresh, onCheckedChange = { vm.setXunleiAutoRefresh(it) })
                Text(
                    "令牌过期时自动拉起迅雷 APP 刷新",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "只启动 APP 首页不会刷新令牌，必须让它处理一次分享深链——" +
                    "所以解析时会用你当前的分享链接把 APP 拉起来一次（约 6~10 秒，会短暂切到前台）。" +
                    "介意被打扰可关掉上面的开关。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        }
    }
}

/**
 * 阿里云盘凭据卡片：access_token / refresh_token / drive_id。
 *
 * 两条通道（依据 `aliyun-login/阿里云盘应用内登录-测试与实现.md`）：
 *  · **root 直读** `0.db` → App client 的令牌（续期走 `auth.aliyundrive.com/v2/account/token`）
 *  · **应用内登录** → 网页 client 的令牌（在 `localStorage.token`，续期走
 *    `api.aliyundrive.com/token/refresh` + `client_id=25dzX3vbYqktVxyX`）
 *
 * ⚠️ 两者的 refresh_token **不能跨 client 刷新**（实测 `InvalidParameter.ClientId`），
 * 所以落盘时必须记住 client（见 `AliyunCredStore.saveManualCreds(clientId=…)`）。
 */
@Composable
private fun AliyunCredCard(
    vm: MainViewModel,
    message: String,
    onLogin: () -> Unit
) {
    val creds by vm.creds.collectAsState()
    val ac by vm.aliyunCreds.collectAsState()
    val installed by vm.aliyunAppInstalled.collectAsState()
    val autoClean by vm.aliyunAutoClean.collectAsState()
    val rootAvailable by vm.rootAvailable.collectAsState()
    val c = creds.firstOrNull { it.site == SiteId.ALI }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("阿里云盘", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    if (c?.hasCredential == true) "凭据已就绪" else "未就绪",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (c?.hasCredential == true) PanGreen else PanRed
                )
            }

            RecommendTag(
                if (rootAvailable) {
                    "最快：root 直读 APP 明文数据库；也可「应用内登录」——" +
                        "阿里网页版把 token 放在 localStorage，WebView 能读回（网页 client 另一套，续期各自用各自的 client）"
                } else {
                    "没有 root：用「应用内登录」读 localStorage.token（网页 client，纯 Bearer，无需签名）"
                },
                if (rootAvailable) PanGreen else PanBlue
            )

            if (ac != null) {
                val a = ac!!
                val left = a.remainingSec()
                val leftText = if (left >= 0) "剩余 ${left}s" else "未知"
                Text(
                    "drive_id: ${a.driveId.ifBlank { "（缺失）" }}\n" +
                        "access_token: ${a.accessToken.take(16)}…\n" +
                        "有效期: $leftText\n" +
                        "来源: ${a.source}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    when {
                        !rootAvailable ->
                            "没有 root，读不到 APP 里的 App 令牌 → 请点上面的「应用内登录」" +
                                "（阿里网页版 token 在 localStorage，WebView 能读回）"
                        installed -> "已检测到阿里云盘 APP，但没读到 token → 请在 APP 里登录一次"
                        else -> "未检测到阿里云盘 APP（com.alicloud.databox），或 su 未授权"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PanAmber
                )
            }

            c?.verdict?.let { v ->
                Text(
                    "${v.label}  ${v.message}",
                    style = MaterialTheme.typography.labelSmall,
                    color = when (v.valid) {
                        true -> PanGreen
                        false -> PanRed
                        else -> PanAmber
                    }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rootAvailable) {
                    Button(
                        onClick = { vm.extractAliyunWithRoot() },
                        enabled = c?.extracting != true,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (c?.extracting == true) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Text(" root 读取 token")
                    }
                    OutlinedButton(
                        onClick = onLogin,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" 应用内登录")
                    }
                } else {
                    Button(
                        onClick = onLogin,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(" 应用内登录")
                    }
                    OutlinedButton(
                        onClick = { vm.extractAliyunWithRoot() },
                        enabled = c?.extracting != true,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (c?.extracting == true) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Text(" root 读取")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.checkAliyunCredential() },
                    enabled = c?.checking != true,
                    modifier = Modifier.weight(1f)
                ) {
                    if (c?.checking == true) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Text(" 校验")
                }
                OutlinedButton(
                    onClick = { vm.inspectOwnWebViewStore(SiteId.ALI) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(" 存储自检")
                }
            }

            // ★ 同迅雷：把操作结果显示在卡片内（否则结果在页面顶部、屏幕外 → 像没反应）
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = autoClean, onCheckedChange = { vm.setAliyunAutoClean(it) })
                Text(
                    "取到直链后回收转存副本",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                "阿里的流程是「转存到我的云盘 → 取 OSS 直链 → 回收副本」：分享文件不允许第三方直接下载。" +
                    "实测回收后直链仍可用（STS 签名独立），所以默认取到就回收；" +
                    "若想保留副本（或担心账号风险）可关掉。\n" +
                    "⚠️ 体积门控：≥100MB 的文件取不到直链（免费账号，服务端策略）。\n" +
                    "⚠️ 直链按连接限速，并发建议 64（本程序按站点自动给）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        }
    }
}

