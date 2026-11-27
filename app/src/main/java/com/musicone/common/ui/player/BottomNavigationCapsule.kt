package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
import kotlin.math.tanh
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun BottomCapsule(
    page: MusicOnePage,
    backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    backdropBounds: Rect,
    selectionPosition: () -> Float,
    onSelectionDrag: (Float) -> Unit,
    onPageChange: (MusicOnePage) -> Unit,
    onNavigationContact: () -> Unit,
    navigationDragBlocked: () -> Boolean,
    queueState: PlayerQueueState,
    immersiveVisual: SourceBackdropVisual? = null,
    capsuleMetrics: NavigationCapsuleMetrics? = null,
) {
    val primaryHeaderState = LocalPrimaryPageHeaderState.current
    val currentNavigationContact by rememberUpdatedState(onNavigationContact)
    val currentDragBlocked by rememberUpdatedState(navigationDragBlocked)
    val currentSelectionPosition by rememberUpdatedState(selectionPosition)
    val currentSelectionDrag by rememberUpdatedState(onSelectionDrag)
    val scope = rememberCoroutineScope()
    // FlClash 式透镜动效（样式与数据不变，只换运动）：拖动时透镜用跟随弹簧追手指，
    // 按下时药丸微微鼓起，快速滑动时按速度果冻拉伸。减动效时全部退化为直接跟随。
    val reduceMotion = ExperiencePreferences.options.reduceMotion
    val followSpec = if (reduceMotion) snap<Float>() else spring<Float>(dampingRatio = .9f, stiffness = 1024f)
    val pressSpec = if (reduceMotion) snap<Float>() else spring<Float>(dampingRatio = .593f, stiffness = 311f)
    var dragTarget by remember { mutableStateOf<Float?>(null) }
    val dragAnim = remember { Animatable(0f) }
    var dragActive by remember { mutableStateOf(false) }
    val pressAnim = remember { Animatable(0f) }
    val jellyAnim = remember { Animatable(0f) }
    var jellyTarget by remember { mutableFloatStateOf(0f) }
    val visualPosition = remember { { if (dragActive) dragAnim.value else dragTarget ?: currentSelectionPosition() } }
    var selectionTravelPx by remember { mutableFloatStateOf(1f) }
    // 拖动期间把弹簧后的透镜位置持续交给页面，页面和药丸一起追手指。
    LaunchedEffect(dragActive) {
        if (dragActive) snapshotFlow { dragAnim.value }.collect { currentSelectionDrag(it) }
    }
    // 果冻速度采样改按需驱动：snapshotFlow 只在透镜位置真正变化时才发出新值，
    // 静止时收集器挂起，不再像原来那样 while(true)+withFrameNanos 每帧空转唤醒。
    // 位置停止变化 80ms 后把目标归零，果冻由下面的弹簧平滑收回，不会突然跳变。
    // reduceMotion 时整个采样不启动；开关切换会重启本 Effect 并取消旧收集，无重复协程。
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            jellyTarget = 0f
            return@LaunchedEffect
        }
        var lastPosition = visualPosition()
        var lastNanos = System.nanoTime()
        var decayJob: Job? = null
        try {
            snapshotFlow { visualPosition() }.collect { position ->
                val now = System.nanoTime()
                val dtSeconds = (now - lastNanos) / 1_000_000_000f
                if (dtSeconds > 0f) {
                    jellyTarget = .16f * tanh(abs(position - lastPosition) / dtSeconds / 2f)
                }
                lastPosition = position
                lastNanos = now
                decayJob?.cancel()
                decayJob = launch {
                    delay(80)
                    jellyTarget = 0f
                }
            }
        } finally {
            decayJob?.cancel()
        }
    }
    LaunchedEffect(jellyTarget) {
        jellyAnim.animateTo(jellyTarget, if (reduceMotion) snap() else spring(dampingRatio = .4f, stiffness = 100f))
    }
    fun onDragAmount(amount: Float) {
        val next = nextCapsuleDragPosition(dragTarget, currentSelectionPosition(), amount,
            selectionTravelPx, currentDragBlocked()) ?: return
        val starting = dragTarget == null
        dragTarget = next
        if (starting) {
            dragActive = true
            scope.launch {
                dragAnim.snapTo(currentSelectionPosition())
                dragAnim.animateTo(next, followSpec)
            }
        } else {
            scope.launch { dragAnim.animateTo(next, followSpec) }
        }
    }
    fun settleDrag() {
        val position = (if (dragActive) dragAnim.value else dragTarget ?: selectionPosition())
            .coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
        val targetPage = rootPageForPosition(position)
        onPageChange(targetPage)
        scope.launch {
            // 保留拖动终点一帧，等承接动画建立起步状态后再撤下拖动态，避免短暂闪跳旧选择位置。
            withFrameNanos { }
            dragTarget = null
            dragActive = false
        }
    }
    fun onCapsuleContact() {
        currentNavigationContact()
        scope.launch { pressAnim.animateTo(1f, pressSpec) }
    }
    fun onCapsuleRelease() {
        scope.launch { pressAnim.animateTo(0f, pressSpec) }
    }
    // 并排底栏传入自己的尺寸，描边和模糊半径与迷你播放条取同一套；经典布局保持原样。
    val rowStyle = capsuleMetrics != null
    val lightTint = if (rowStyle) LocalBottomBarStyle.current.lightTint else .55f
    val metrics = capsuleMetrics ?: navigationCapsuleMetrics(
        PhonePlayerOrientationPolicy.isPhone(LocalConfiguration.current.smallestScreenWidthDp),
    )
    Box(Modifier.fillMaxWidth().padding(top = metrics.topGap, bottom = metrics.bottomGap), contentAlignment = Alignment.Center) {
        val dark = MaterialTheme.colorScheme.background.luminance() < .3f
        val neutral = LocalPlatformNeutral.current
        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor = immersiveVisual?.secondary ?: MaterialTheme.colorScheme.onSurfaceVariant
        val selectedColor = navigationCapsuleSelectionColor(
            neutral, MaterialTheme.colorScheme.primaryContainer,
        )
        MusicOneBackdropGlass(
            backdropLayer = backdropLayer,
            backdropBounds = backdropBounds,
            modifier = Modifier.width(metrics.width),
            shape = RoundedCornerShape(metrics.outerRadius),
            blurRadius = if (rowStyle) 18.dp else 20.dp,
            // 深色背景时面板别压得太黑：沉浸面板的黑色罩层减半、普通深色底也降透明，让模糊后的背景透出来一些。
            containerColor = immersiveVisual?.let { if (it.useDarkForeground) it.panelColor else it.panelColor.copy(alpha = .16f) }
                ?: if (dark) Color(0xFF242526).copy(alpha = .58f) else Color.White.copy(alpha = lightTint),
            fallbackColor = immersiveVisual?.panelColor
                ?: if (dark) Color(0xFF242526) else Color(0xFFF3F4F6),
            border = androidx.compose.foundation.BorderStroke(
                if (rowStyle) 1.dp else .8.dp,
                immersiveVisual?.panelBorder ?: when {
                    dark -> Color.White.copy(alpha = .16f)
                    rowStyle -> Color(0xFFE0E2E6)
                    else -> Color.White.copy(alpha = .72f)
                },
            ),
        ) {
            Box(
                Modifier.padding(metrics.innerPadding).fillMaxWidth().height(metrics.innerHeight)
                    .onSizeChanged { selectionTravelPx = (it.width / 2f).coerceAtLeast(1f) }
                    .navigationCapsuleGesture(
                        onContact = { onCapsuleContact() },
                        blocked = { currentDragBlocked() },
                        onHorizontalDrag = { amount -> onDragAmount(amount) },
                        onHorizontalEnd = { if (dragTarget != null) settleDrag() },
                        queueState = queueState,
                        onRelease = { onCapsuleRelease() },
                    ),
            ) {
                NavigationCapsuleSelection(
                    page = page,
                    position = visualPosition,
                    activeColor = activeColor,
                    inactiveColor = inactiveColor,
                    selectionColor = selectedColor,
                    metrics = metrics,
                    stretch = { jellyAnim.value },
                    swell = { pressAnim.value },
                    onHome = {
                        if (page == MusicOnePage.HOME && kotlin.math.abs(selectionPosition()) < .001f) {
                            primaryHeaderState.requestScrollToTop(MusicOnePage.HOME)
                        } else {
                            onPageChange(MusicOnePage.HOME)
                        }
                    },
                    onMy = { onPageChange(MusicOnePage.MY) },
                )
            }
        }
    }
}

internal fun nextCapsuleDragPosition(
    current: Float?,
    selection: Float,
    dragAmount: Float,
    travel: Float,
    blocked: Boolean,
): Float? = if (blocked) null else ((current ?: selection) + dragAmount / travel.coerceAtLeast(1f))
    .coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
