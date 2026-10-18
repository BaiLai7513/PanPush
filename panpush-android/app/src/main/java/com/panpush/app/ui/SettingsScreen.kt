package com.panpush.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.panpush.app.ui.theme.PanGreen
import com.panpush.app.ui.theme.PanRed

@Composable
fun SettingsScreen(vm: MainViewModel, onOpenCredentials: () -> Unit) {
    val url by vm.gopeedUrl.collectAsState()
    val token by vm.gopeedToken.collectAsState()
    val conn by vm.connections.collectAsState()
    val dir by vm.downloadDir.collectAsState()
    val mode by vm.pan123Mode.collectAsState()
    val gopeed by vm.gopeedStatus.collectAsState()
    val creds by vm.creds.collectAsState()
    val rootStatus by vm.rootStatus.collectAsState()

    var urlEdit by remember(url) { mutableStateOf(url) }
    var tokenEdit by remember(token) { mutableStateOf(token) }
    var connEdit by remember(conn) { mutableStateOf(conn.toString()) }
    var dirEdit by remember(dir) { mutableStateOf(dir) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ---- 登录态入口 ----
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("网盘登录态", style = MaterialTheme.typography.titleMedium)
                Text(
                    creds.joinToString(" · ") {
                        "${it.site.label}${if (it.hasCredential) " ✓" else " ✗"}"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    rootStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onOpenCredentials, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Key, contentDescription = null)
                    Text("  管理登录态 / 凭据")
                }
                Text(
                    "夸克/百度大文件必须登录；123 大文件需要 token。\n" +
                        "两种获取方式：① 内置浏览器登录（不需要 root） ② root 直读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- Gopeed ----
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gopeed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        gopeed.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (gopeed.reachable) PanGreen else PanRed
                    )
                }
                OutlinedTextField(
                    value = urlEdit,
                    onValueChange = { urlEdit = it },
                    label = { Text("API 地址") },
                    placeholder = { Text("http://127.0.0.1:9999") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = tokenEdit,
                    onValueChange = { tokenEdit = it },
                    label = { Text("API Token（未开启鉴权可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = connEdit,
                    onValueChange = { connEdit = it.filter { c -> c.isDigit() } },
                    label = { Text("并发连接数（0 = 按站点自动）") },
                    supportingText = {
                        Text(
                            "实测建议：迅雷 16（分享链被限 ~6Mbps，多连无益）· 阿里 64（按连接限速，越多越快）" +
                                "· 夸克/百度/123/蓝奏 512",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dirEdit,
                    onValueChange = { dirEdit = it },
                    label = { Text("下载目录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.saveGopeed(
                                urlEdit, tokenEdit,
                                connEdit.toIntOrNull() ?: 512,
                                dirEdit
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("保存并测试连接")
                    }
                    OutlinedButton(onClick = { vm.probeGopeed() }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Text(" 测试")
                    }
                }
                // 按用户要求：**去掉「拉起 / 重启 Gopeed」按钮与功能**。
                // 现在「取直链并推送」成功后会自动 root 拉起 Gopeed，
                // 并且**假死会自愈**（API 不可达 → root force-stop → 重新拉起 → 复探）。
                Text(
                    "不需要手动拉起/重启：每次「取直链并推送」成功后会自动用 root 拉起 Gopeed；" +
                        "若它进程假死（API 连接超时），推送时会自动强制重启并复探。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 123 取链模式 ----
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("123 云盘取链模式", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "auto" to "自动（先分享直链，失败转存）",
                        "share" to "只用分享直链",
                        "netdisk" to "强制转存后取链"
                    ).forEach { (k, label) ->
                        FilterChip(
                            selected = mode == k,
                            onClick = { vm.setPan123Mode(k) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    "大文件（5112）需要登录 token；「强制转存后取链」会先把文件保存到你的 123 网盘再取直链。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ---- 说明 ----
        InfoCard(
            title = "使用说明",
            body = "1) 在 Gopeed 安卓端「设置 → API」里打开 API（默认 127.0.0.1:9999）。\n" +
                "2) 首次使用先去「管理登录态」把网盘登录好（内置浏览器登录最省事）。\n" +
                "3) 回「解析」页粘贴分享链接 → 解析 → 勾选文件 → 取直链并推送。\n" +
                "4) 直链是带签名、有时效的临时地址，过期后重新解析即可。"
        )
    }
}
