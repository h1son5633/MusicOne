package com.musicone

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/** 启动图的时间点：开屏动画已移除，静态图取 GatherMillis 处的成品波形（Exit 部分仅为旧时间轴保留）。 */
internal object HomeLaunchTimeline {
    const val GatherMillis = 400L
    const val ExitMillis = 180L
    const val ExitStartMillis = GatherMillis
    const val TotalMillis = ExitStartMillis + ExitMillis

    private val GatherEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    private val ExitEasing = CubicBezierEasing(.2f, 0f, 0f, 1f)

    fun gather(elapsedMillis: Long): Float =
        GatherEasing.transform((elapsedMillis.toFloat() / GatherMillis).coerceIn(0f, 1f))

    fun exit(elapsedMillis: Long): Float =
        ExitEasing.transform(((elapsedMillis - ExitStartMillis).toFloat() / ExitMillis).coerceIn(0f, 1f))
}

/** 启动动画每个进程只播一次；减少动态效果时不播放。 */
internal class HomeLaunchGate {
    private var prepared = false
    private var pending = false

    /** 系统启动窗口还盖在上面，光要等它撤掉才开始走。 */
    var awaitingSplash = false
        private set

    fun prepare(reduceMotion: Boolean, systemSplash: Boolean): Boolean {
        pending = !prepared && !reduceMotion
        prepared = true
        awaitingSplash = pending && systemSplash
        return pending
    }

    fun takeAnimation(): Boolean = pending.also { pending = false }

    fun splashRemoved() {
        awaitingSplash = false
    }

    companion object {
        val process = HomeLaunchGate()
    }
}

/** 启动动画的时钟。按真实时间前进：主线程卡顿丢帧时动画跳过中间帧，但总时长不变，不拖成慢动作。 */
internal class HomeLaunchClock(private val gate: HomeLaunchGate) {
    private var elapsedNanos by mutableLongStateOf(0L)
    private var waitedNanos = 0L

    val elapsedMillis: Long get() = elapsedNanos / 1_000_000L
    val finished: Boolean get() = elapsedMillis >= HomeLaunchTimeline.TotalMillis

    fun advance(frameNanos: Long) {
        val step = frameNanos.coerceAtLeast(0L)
        if (gate.awaitingSplash) {
            // 一直等不到交接，说明本次没有系统窗口，直接开始。
            waitedNanos += step
            if (waitedNanos < SplashWaitNanos) return
        }
        elapsedNanos += step
    }

    private companion object {
        const val SplashWaitNanos = 400_000_000L
    }
}

/** 绘制音频波形：11 根短线一开始全部是最短的小线，位置不动，随后从中间向两侧依次长成波形；比例须与 launch_mark.xml 的静态图一致。 */
internal class HomeLaunchMark {
    fun draw(scope: DrawScope, elapsedMillis: Long, background: Color, ink: Color) = with(scope) {
        val exit = HomeLaunchTimeline.exit(elapsedMillis)
        val visible = 1f - exit
        drawRect(background, alpha = visible)
        val gather = HomeLaunchTimeline.gather(elapsedMillis)
        val step = BarStep.toPx()
        val barWidth = BarWidth.toPx()
        val maxHeight = MaxBarHeight.toPx()
        val scale = 1f + .05f * exit
        Heights.forEachIndexed { index, fraction ->
            val distance = kotlin.math.abs(index - CenterIndex)
            // 中间的线先长，外侧按距中心的距离依次跟上。
            val local = (gather * 1.25f - distance * .05f).coerceIn(0f, 1f)
            val height = maxHeight * (MinFraction + (fraction - MinFraction) * local) * scale
            val x = center.x + (index - CenterIndex) * step * scale
            drawLine(
                color = ink,
                start = Offset(x, center.y - height / 2f),
                end = Offset(x, center.y + height / 2f),
                strokeWidth = barWidth * scale,
                cap = StrokeCap.Round,
                alpha = visible,
            )
        }
    }

    private companion object {
        val Heights = floatArrayOf(.10f, .16f, .32f, .55f, .80f, 1f, .80f, .55f, .32f, .16f, .10f)
        const val MinFraction = .10f
        const val CenterIndex = 5
        val BarStep = 12.2.dp
        val BarWidth = 7.5.dp
        val MaxBarHeight = 60.dp
    }
}
