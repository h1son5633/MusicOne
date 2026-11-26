package com.musicone

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
    settingsOrigin: () -> Rect,
) {
    LaunchedEffect(launch) {
        // 首帧准备好即展示页面，不再等待音源、网络内容或封面；播放页预热也只到这里。
        repeat(2) { withFrameNanos { } }
        launch.covering = false
    }
    // 开屏动画已按用户要求移除：遮罩期间只显示一张静态的成品波形图，与系统启动屏的图一致；
    // 遮罩结束时整张图以右上角设置按钮的中心为支点缩成一个点，下方主页随之显露，作为开屏与主页的衔接。
    remember { HomeLaunchGate.process.takeAnimation() }
    HomeLaunchStaticMark(covering = launch.covering, settingsOrigin = settingsOrigin)
}

@Composable
private fun HomeLaunchStaticMark(covering: Boolean, settingsOrigin: () -> Rect) {
    val background = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground
    val mark = remember { HomeLaunchMark() }
    val reduceMotion = remember { ExperiencePreferences.options.reduceMotion }
    var shown by remember { mutableStateOf(true) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val progress by animateFloatAsState(
        targetValue = if (covering) 0f else 1f,
        animationSpec = tween(
            durationMillis = if (reduceMotion) 0 else 330,
            easing = CubicBezierEasing(.2f, 0f, 0f, 1f),
        ),
        finishedListener = { if (!covering) shown = false },
        label = "LaunchShrink",
    )
    if (!shown) return
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .graphicsLayer {
                val origin = settingsOrigin()
                if (origin.isEmpty || size == IntSize.Zero) {
                    // 还没拿到设置按钮的位置时退化为淡出。
                    alpha = 1f - progress
                } else {
                    transformOrigin = TransformOrigin(
                        (origin.center.x / size.width).coerceIn(0f, 1f),
                        (origin.center.y / size.height).coerceIn(0f, 1f),
                    )
                    val scale = (1f - progress).coerceAtLeast(.001f)
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape((progress * 44).dp)
                    clip = progress > 0f
                    alpha = 1f - ((progress - .7f) / .3f).coerceIn(0f, 1f)
                }
            }
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
