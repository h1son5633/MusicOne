package com.musicone

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription

@Stable
internal class HomeLaunchState(covering: Boolean = true) {
    var covering by mutableStateOf(covering)
}

@Composable
internal fun rememberHomeLaunchState(): HomeLaunchState = rememberSaveable(
    saver = Saver(save = { it.covering }, restore = { HomeLaunchState(it) }),
) { HomeLaunchState() }

@Composable
internal fun HomeLaunchOverlay(
    launch: HomeLaunchState,
) {
    LaunchedEffect(launch) {
        // 首帧准备好即展示页面，不再等待音源、网络内容或封面；播放页预热也只到这里。
        repeat(2) { withFrameNanos { } }
        launch.covering = false
    }
    // 开屏动画已按用户要求移除：遮罩期间只显示一张静态的成品波形图，与系统启动屏的图一致；
    // 遮罩结束时整张图淡出，下方主页随之显露，让开屏与主页有一个衔接而不是硬切。
    remember { HomeLaunchGate.process.takeAnimation() }
    HomeLaunchStaticMark(covering = launch.covering)
}

@Composable
private fun HomeLaunchStaticMark(covering: Boolean) {
    val background = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    val mark = remember { HomeLaunchMark() }
    var shown by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
        targetValue = if (covering) 1f else 0f,
        animationSpec = tween(durationMillis = 280),
        finishedListener = { if (!covering) shown = false },
        label = "LaunchFade",
    )
    if (!shown) return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .then(if (covering) Modifier.homeLaunchCover { } else Modifier)
            .drawBehind { mark.draw(this, HomeLaunchTimeline.GatherMillis, background, ink) },
    )
}

/** 拦截遮罩下方的触摸，按下时通知调用方。 */
private fun Modifier.homeLaunchCover(onPress: () -> Unit): Modifier = this
    .clearAndSetSemantics { stateDescription = "正在准备首页" }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            onPress()
            do {
                val event = awaitPointerEvent()
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
