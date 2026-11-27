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
import kotlin.math.abs
import kotlin.math.tanh
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
    // 与根页面滑动相同的甩动阈值（600dp/s），定落点时换算成像素速度比较。
    // 必须声明在 settleDrag 等本地函数之前，本地函数只能捕获先于它声明的变量。
    val flingThresholdPx = with(LocalDensity.current) { 600.dp.toPx() }
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
    // 拖动跟随只保留一个收集器（补丁 1.1）：onDragAmount 只更新 dragTarget，
    // 这里用 collectLatest 追最新目标——新目标到来自动取消上一个未完成的跟随动画，
    // 同一时间只有一个跟随动画在跑，不再每个触摸事件都 launch 一个新协程互相打断。
    // dragTarget 变回 null（松手）时正在跑的跟随立即取消，位置交给切页动画。
    val currentFollowSpec by rememberUpdatedState(followSpec)
    LaunchedEffect(Unit) {
        var sessionActive = false
        snapshotFlow { dragTarget }.collectLatest { target ->
            if (target == null) {
                sessionActive = false
            } else {
                if (!sessionActive) {
                    sessionActive = true
                    dragAnim.snapTo(currentSelectionPosition())
                }
                dragAnim.animateTo(target, currentFollowSpec)
            }
        }
    }
    fun onDragAmount(amount: Float) {
        val next = nextCapsuleDragPosition(dragTarget, currentSelectionPosition(), amount,
            selectionTravelPx, currentDragBlocked()) ?: return
        if (dragTarget == null) dragActive = true
        dragTarget = next
    }
    fun settleDrag(velocityX: Float) {
        // 落点依据改用手指最后位置 dragTarget，不用弹簧追随值 dragAnim.value：
        // 弹簧滞后于手指，慢拖到中间松手时会被取整到错误一侧。
        // 落点规则与根页面滑动共用 rootPageSwipeTarget：快速甩动按方向翻页，
        // 否则就近吸附；恰好正中且无速度时向上取整，落点始终确定、不会停在半页。
        val basis = (dragTarget ?: selectionPosition())
            .coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
        val targetPage = rootPageSwipeTarget(basis, velocityX, flingThresholdPx)
        // 先停转发再切页：旧顺序是先切页、隔一帧才清拖动态，这一帧里转发器继续把
        // 弹簧位置喂给 dragTo，而 dragTo 与切页动画共用同一个任务槽，会把切页动画
        // 取消掉；转发停下后页面就停在最后一次锚点上，长期卡在半页。现在先清状态，
        // 切页动画独占任务槽，PagerState 必然归位到整页。
        dragTarget = null
        dragActive = false
        onPageChange(targetPage)
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
                        onHorizontalEnd = { velocityX -> if (dragTarget != null) settleDrag(velocityX) },
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
