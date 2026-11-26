package com.musicone

import androidx.compose.animation.core.CubicBezierEasing
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
        repeat(2) { withFrameNanos { } }
        launch.covering = false
    }
    // 开屏只显示一张静态的成品波形图（与系统启动屏的图一致），随后直接淡出，不做任何衔接动画
    // （缩进设置按钮的衔接已按用户要求删除）。
    remember { HomeLaunchGate.process.takeAnimation() }
    HomeLaunchStaticMark(covering = launch.covering)
}

@Composable
private fun HomeLaunchStaticMark(covering: Boolean) {
    val background = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    val mark = remember { HomeLaunchMark() }
    val reduceMotion = remember { ExperiencePreferences.options.reduceMotion }
    var shown by remember { mutableStateOf(true) }
    val progress by animateFloatAsState(
        targetValue = if (covering) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else 330,
            easing = CubicBezierEasing(.2f, 0f, 0f, 1f),
        ),
        finishedListener = { if (!covering) shown = false },
        label = "LaunchFade",
    )
    if (!shown) return
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - progress }
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
