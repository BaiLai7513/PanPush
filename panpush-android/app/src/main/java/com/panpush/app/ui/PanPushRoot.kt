package com.panpush.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** 应用根：底部三个页签（解析 / 任务 / 设置） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanPushRoot(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var showCredentials by remember { mutableStateOf(false) }
    val gopeed by vm.gopeedStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PanPush", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "网盘分享 → 直推 Gopeed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (gopeed.reachable) Color(0xFF4CD07D) else Color(0xFFFF6B6B),
                            shape = CircleShape,
                            modifier = Modifier.size(8.dp)
                        ) { }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            gopeed.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(
                    selected = tab == 0 && !showCredentials,
                    onClick = {
                        tab = 0
                        showCredentials = false
                    },
                    icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
                    label = { Text("解析") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = {
                        tab = 1
                        showCredentials = false
                    },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                showCredentials -> CredentialsScreen(vm, onBack = { showCredentials = false })
                tab == 0 -> ParseScreen(vm)
                else -> SettingsScreen(vm, onOpenCredentials = { showCredentials = true })
            }
        }
    }
}

/** 通用提示卡片 */
@Composable
fun InfoCard(
    title: String,
    body: String,
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    mono: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = if (mono) MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                else MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun Gap(h: Int = 10) = Spacer(Modifier.height(h.dp))
