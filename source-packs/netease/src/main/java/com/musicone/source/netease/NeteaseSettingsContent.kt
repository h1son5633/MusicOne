package com.musicone.source.netease

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp

@Composable internal fun NeteaseSettingsContent(owner: NeteasePackageState, authentication: NeteaseSourceAuthentication) {
    val preferences = remember { owner.environment.context.getSharedPreferences("netease_settings", 0) }
    var selected by remember { mutableStateOf(preferences.getString("quality", NeteaseQuality.LOSSLESS.name)) }
    var confirmLogout by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SettingsHeader("网易云音乐设置") { owner.back() } }
        item { Text("首选音质", style = MaterialTheme.typography.titleLarge) }
        items(NETEASE_AUDIO_QUALITIES.size) { index -> val quality = NETEASE_AUDIO_QUALITIES[index]
            Row(Modifier.fillMaxWidth().selectable(selected == quality.name) { selected = quality.name; check(preferences.edit().putString("quality", quality.name).commit()) },
                verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == quality.name, onClick = null); Text(quality.label, Modifier.padding(12.dp)) }
        }
        item { Text("可播放音质仍取决于歌曲版权、账号权限和真实返回的音频规格。") }
        item { Text("听歌记录同步", style = MaterialTheme.typography.titleLarge) }
        item {
            var syncRecent by remember { mutableStateOf(preferences.getBoolean("sync_recent_plays", true)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同步最近播放")
                    Text("开播时上报到网易云，让歌曲出现在官方 App 的最近播放里", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = syncRecent, onCheckedChange = { syncRecent = it
                    check(preferences.edit().putBoolean("sync_recent_plays", it).commit()) })
            }
        }
        item {
            var syncRecord by remember { mutableStateOf(preferences.getBoolean("sync_play_record", true)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同步听歌排行")
                    Text("歌曲结束或切歌时上报实际收听时长（需先听满歌曲一半或 30 秒），给听歌排行计数", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = syncRecord, onCheckedChange = { syncRecord = it
                    check(preferences.edit().putBoolean("sync_play_record", it).commit()) })
            }
        }
        item {
            val recentStatus = preferences.getString("sync_status_recent", null)
            val recordStatus = preferences.getString("sync_status_record", null)
            Text("最近播放：${recentStatus ?: "尚无上报记录"}\n听歌排行：${recordStatus ?: "尚无上报记录"}\n" +
                "请求成功不等于入账成功，是否入账请以官方 App 的最近播放与听歌排行为准。",
                style = MaterialTheme.typography.bodySmall)
        }
        item { TextButton(onClick = { owner.open("authentication") }) { Text(if (owner.account == null) "登录网易云音乐" else "切换账号") } }
        if (owner.account != null) item { TextButton(onClick = { confirmLogout = true }) { Text("退出登录") } }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出网易云音乐？") },
        text = { Text("仅退出此音源，保留已完成缓存、歌单关联及用户资料。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; owner.action { authentication.logout() } }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}
