package com.musicone.source.netease

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 最近播放：数据来自本地听歌账本（播放会话开始时记录），点任意一首从该首起播整个列表。 */
@Composable
internal fun NeteaseRecentContent(
    owner: NeteasePackageState,
    actions: NeteaseTrackActions,
    background: NeteaseBackgroundUiController,
) {
    val recent = remember(owner.dataRevision) { owner.listenTracker.recent() }
    val tracks = remember(recent) { recent.map { it.toTrack() } }
    val visual = background.visual
    Box(Modifier.fillMaxSize()) {
        NeteaseProfileBackdrop(visual, Modifier.fillMaxSize())
        NeteaseAdaptiveForeground(visual) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp,
                    top = 14.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                    bottom = (owner.bottomInset + 20).dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    SettingsHeader("最近播放") { owner.back() }
                }
                if (tracks.isEmpty()) {
                    item {
                        Text(
                            "还没有播放记录，在这里听过的歌会按时间排在这里。",
                            color = visual.secondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 24.dp),
                        )
                    }
                } else {
                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        NeteaseTrackRow(owner, actions, track, onClick = { owner.play(tracks, index) })
                    }
                }
            }
        }
    }
}
