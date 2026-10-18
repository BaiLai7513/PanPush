package com.panpush.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.panpush.app.ui.theme.PanAmber
import com.panpush.app.ui.theme.PanBlue
import com.panpush.app.ui.theme.PanGreen
import com.panpush.app.ui.theme.PanRed

@Composable
fun ParseScreen(vm: MainViewModel) {
    val link by vm.link.collectAsState()
    val passcode by vm.passcode.collectAsState()
    val passcodeAuto by vm.passcodeAuto.collectAsState()
    val site by vm.site.collectAsState()
    val parsing by vm.parsing.collectAsState()
    val resolving by vm.resolving.collectAsState()
    val result by vm.result.collectAsState()
    val selected by vm.selected.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val outcome by vm.outcome.collectAsState()
    val error by vm.error.collectAsState()
    val log by vm.log.collectAsState()
    val dir by vm.downloadDir.collectAsState()
    val launchMessage by vm.launchMessage.collectAsState()

    val clipboard = LocalClipboardManager.current
    var clipboardHint by remember { mutableStateOf("") }
    /** 点了「取直链并复制」时置位；等直链出来后自动复制一次 */
    var copyAfterResolve by remember { mutableStateOf(false) }

    // 直链出来后自动复制到剪贴板（一次）
    LaunchedEffect(downloads, resolving, copyAfterResolve) {
        if (copyAfterResolve && !resolving && downloads.isNotEmpty()) {
            val text = vm.downloadLinksText()
            if (text.isNotBlank()) {
                clipboard.setText(AnnotatedString(text))
                clipboardHint = "已复制 ${downloads.size} 条直链到剪贴板（仅链接）"
            }
            copyAfterResolve = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ---------- 链接输入 ----------
        OutlinedTextField(
            value = link,
            onValueChange = vm::onLinkChange,
            label = { Text("网盘分享链接") },
            placeholder = { Text("粘贴 https://pan.quark.cn/s/… 或 https://www.123pan.com/s/…") },
            singleLine = false,
            minLines = 2,
            leadingIcon = { Icon(Icons.Filled.Link, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 「粘贴」：从剪贴板取
                    TextButton(onClick = {
                        val t = clipboard.getText()?.text
                        if (t.isNullOrBlank()) {
                            clipboardHint = "剪贴板是空的"
                        } else {
                            vm.pasteFromClipboard(t)
                            clipboardHint = ""
                        }
                    }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        Text("粘贴")
                    }
                    // 「清空」：一键清掉链接 + 提取码 + 上次解析结果（快捷交互）
                    IconButton(
                        onClick = {
                            vm.clearLink()
                            clipboardHint = "已清空"
                        },
                        enabled = link.isNotBlank() || passcode.isNotBlank()
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "清空链接",
                            tint = if (link.isNotBlank() || passcode.isNotBlank()) PanRed
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SiteChip(site.label)
            if (passcodeAuto && passcode.isNotBlank()) SiteChip("提取码已自动识别", PanBlue)
            if (link.isNotBlank() && site.key == "unknown") SiteChip("暂不支持的站点", PanRed)
            if (clipboardHint.isNotBlank()) {
                Text(clipboardHint, style = MaterialTheme.typography.labelSmall, color = PanAmber)
            }
        }

        OutlinedTextField(
            value = passcode,
            onValueChange = vm::onPasscodeChange,
            label = { Text("提取码（可选，链接里带 ?pwd= 时会自动填）") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.parse() },
                enabled = !parsing && !resolving && link.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) {
                if (parsing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(if (parsing) "  解析中…" else "  解析并列出文件")
            }
            OutlinedButton(onClick = { vm.reset() }, enabled = !parsing && !resolving) {
                Text("清空")
            }
        }

        if (error.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // ---------- 解析提示（超门控跳过 / UA 探测 / 频控重试等）----------
        result?.let { r ->
            if (r.notes.isNotEmpty()) {
                InfoCard(
                    title = "解析提示（${r.notes.size}）",
                    body = r.notes.joinToString("\n"),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                )
            }
        }

        // ---------- 文件清单 ----------
        result?.let { r ->
            val files = r.files.filter { it.isFile }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "文件清单（${files.size} 个）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            com.panpush.app.core.humanSize(files.sumOf { it.size }),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = files.isNotEmpty() && selected.size == files.size,
                            onCheckedChange = { vm.selectAll(it) }
                        )
                        Text("全选（已选 ${selected.size}）", style = MaterialTheme.typography.bodyMedium)
                    }
                    files.take(300).forEach { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected.contains(f.fid),
                                onCheckedChange = { vm.toggleSelect(f.fid) }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                val sub = buildString {
                                    append(com.panpush.app.core.humanSize(f.size))
                                    if (f.path.contains('/')) append("  ·  ").append(f.path)
                                }
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (files.size > 300) {
                        Text(
                            "（仅显示前 300 个，推送时仍会处理全部已选文件）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ★ 两个推送入口（按用户实测反馈，都**不走 root**）：
            //
            //  ① 「取直链并推送」= **只判断**：2 秒探针通了就直接推；
            //     不通就只报「不可达」并提示去点 ②。
            //     （原来它还会「探→拉起→等 7 秒→再探」，冷启动常常仍失败、得返回重推，
            //       反而更绕 —— 所以改成快速给结论。）
            //  ② 「手动拉起并推送」= **拉起 + 推送一次完成**：普通拉起 Gopeed →
            //     等它 API 就绪（最多约 15 秒轮询）→ 立刻推送。
            Button(
                onClick = { if (site == com.panpush.app.core.SiteId.LANZOU) vm.resolveAndOpenBrowser() else vm.resolveAndPush(push = true, mode = MainViewModel.PushMode.CHECK) },
                enabled = !resolving && !parsing && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (resolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text("  处理中…")
                } else {
                    Icon(Icons.Filled.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (site == com.panpush.app.core.SiteId.LANZOU) "  解析并用浏览器打开下载" else "  取直链并推送 Gopeed（请将 Gopeed 挂小窗）")
                }
            }

            OutlinedButton(
                onClick = { vm.resolveAndPush(push = true, mode = MainViewModel.PushMode.LAUNCH) },
                enabled = !resolving && !parsing && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  ② 手动拉起并推送（无 Gopeed 后台时使用）")
            }

            // ★ 常显：**只要解析出结果就显示**（不需要先推送）。
            //   若还没取过直链（`downloads` 为空），点它会先自动取直链再打开浏览器，
            //   所以「只解析、不推送」也能一键用浏览器下载。
            val dlAll = vm.downloads.collectAsState().value
            if (selected.isNotEmpty()) {
                OutlinedButton(
                    onClick = {
                        val u = dlAll.firstOrNull()?.url
                        if (!u.isNullOrBlank()) {
                            try {
                                // 交给系统浏览器下载（最简单的路径）
                                val i = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(u)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                vm.getApplication<android.app.Application>().startActivity(i)
                            } catch (e: Exception) {
                                android.util.Log.i("PanPushLanzou", "浏览器打开失败：${e.message}")
                            }
                        } else {
                            // 还没取直链 → 先取，取到后自动打开浏览器
                            vm.resolveAndOpenBrowser()
                        }
                    },
                    enabled = !resolving && !parsing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        if (dlAll.isNotEmpty()) "  用浏览器打开直链（通过浏览器下载）"
                        else "  用浏览器下载（自动取直链并打开）"
                    )
                }
            }
            Text(
                "①**先判断**：Gopeed 通就直接推；不通会说明具体原因。\n" +
                    "②**没在运行时用它**：拉起 Gopeed → 等就绪 → 一次推完。\n" +
                    "提示：推送期间请把 **Gopeed 挂成小窗保持前台** —— 它在后台会被系统冻结，" +
                    "API 就不响应了。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        copyAfterResolve = true
                        vm.resolveAndPush(push = false)
                    },
                    enabled = !resolving && !parsing && selected.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" 取直链并复制")
                }
                Text(
                    "下载目录：$dir",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 12.dp)
                )
            }
            Text(
                "提示：「取直链并复制」会把直链（带文件名）直接放进剪贴板；" +
                    "「取直链并推送」则是交给 Gopeed 下载。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // 迅雷分享直链被服务端限速（~6 Mbps），多连接能明显缓解
            if (site == com.panpush.app.core.SiteId.XUNLEI) {
                Text(
                    "迅雷提示：分享直链被服务端限速（实测约 6 Mbps），并发建议 8~16；" +
                        "个人云盘直链不限速（~48 Mbps），1~4 连接即可。\n" +
                        "下载 UA 由程序对每个 host 自动探测（分享链实测认 okhttp/4.9.0，其它来源可能不同）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanAmber
                )
            }

            // 阿里云盘：体积门控 + 按连接限速
            if (site == com.panpush.app.core.SiteId.ALI) {
                Text(
                    "阿里提示：分享文件不允许第三方直接下载，程序会「转存到你的云盘 → 取 OSS 直链 → 回收副本」；" +
                        "回收后直链仍可用，可在「登录态」页关掉回收。\n" +
                        "⚠️ 体积门控：≥100MB 的文件取不到直链（免费账号，服务端策略）。\n" +
                        "直链按连接限速，并发建议 64（本程序按站点自动给）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanAmber
                )
            }

            // 蓝奏云：全匿名 + WAF 挑战
            if (site == com.panpush.app.core.SiteId.LANZOU) {
                Text(
                    "蓝奏提示（**纯免登录**）：\n" +
                        "· 带提取码的分享：把「提取码」填上（粘贴整段分享文本会自动识别）。\n" +
                        "· 文件名与大小来自官方接口 `ajaxfile.php` 的返回（页面里只有「文件」占位名）。\n" +
                        "· **直链是一次性的**：可能只能请求一次，解析后请尽快推送/下载，别反复试同一链接。\n" +
                        "· 下载 CDN 带 WAF 挑战时，程序只在域名根路径解 Cookie，不碰文件链接本身。",
                    style = MaterialTheme.typography.labelSmall,
                    color = PanAmber
                )
            }
        }

        // ---------- 推送结果 + 一键拉起 Gopeed + 交互式进度 ----------
        outcome?.let { o ->
            val okCount = o.pushed.count { it.ok }
            val ok = o.pushed.isNotEmpty() && okCount == o.pushed.size && o.error.isBlank()
            InfoCard(
                title = if (ok) "推送完成（$okCount 个任务）" else "推送结果：成功 $okCount / ${o.pushed.size}",
                body = buildString {
                    o.pushed.forEach {
                        append(if (it.ok) "[OK] " else "[FAIL] ").append(it.name)
                        if (!it.ok && it.message.isNotBlank()) append(" — ").append(it.message)
                        append('\n')
                    }
                    if (o.error.isNotBlank()) append(o.error)
                },
                color = if (ok) Color(0xFF14301F) else MaterialTheme.colorScheme.errorContainer
            )

            if (okCount > 0) {
                // **不再放「拉起 / 重启 Gopeed」按钮**：推送成功后已经在
                // resolveAndPush() 里**自动普通拉起**（失败会提示手动打开）——
                // 与迅雷刷新令牌同一套拉起逻辑。
                // 任务/进度/暂停删除全部交给 Gopeed 自己，本 App 不重复管理。
                if (launchMessage.isNotBlank()) {
                    Text(
                        launchMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = PanGreen
                    )
                }
                Text(
                    "任务与下载进度都在 Gopeed 里看。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---------- 直链 ----------
        if (downloads.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "已取到 ${downloads.size} 条直链",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(vm.downloadLinksText()))
                            clipboardHint = "已复制 ${downloads.size} 条直链（仅链接）"
                        }) { Text("复制链接") }
                    }
                    if (clipboardHint.isNotBlank()) {
                        Text(
                            clipboardHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = PanGreen
                        )
                    }
                    Text(
                        // 按用户要求：**只显示解析出来的直链本身，不带文件名/大小/来源**
                        downloads.take(20).joinToString("\n") { it.url },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // ---------- 运行日志 ----------
        if (log.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("运行日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.clearLog() }) { Text("清空") }
                    }
                    log.takeLast(40).forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Box(Modifier.height(8.dp))
    }
}

@Composable
fun SiteChip(text: String, color: Color = PanBlue) {
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
