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
import kotlin.math.roundToInt
import kotlin.math.tanh
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun BottomCapsule(
    page: MusicOnePage,
    backdropLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    backdropBounds: Rect,
    selectionPosition: () -> Float,
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
    val scope = rememberCoroutineScope()
    // FlClash 式透镜动效（样式与数据不变，只换运动）：拖动时透镜用跟随弹簧追手指，
    // 按下时药丸微微鼓起，快速滑动时按速度果冻拉伸。减动效时全部退化为直接跟随。
    val reduceMotion = ExperiencePreferences.options.reduceMotion
    val followSpec = if (reduceMotion) snap<Float>() else spring<Float>(dampingRatio = .9f, stiffness = 1024f)
    val pressSpec = if (reduceMotion) snap<Float>() else spring<Float>(dampingRatio = .593f, stiffness = 311f)
    var dragTarget by remember { mutableStateOf<Float?>(null) }
    val dragAnim = remember { Animatable(0f) }
    var dragActive by remember { mutableStateOf(false) }
    // FlClash 式语义：拖动只动透镜，页面纹丝不动；松手才切页。松手后透镜先停在目标页，
    // 等页面弹簧滑到位（或停在别处）再把位置来源交还给页面，避免透镜跳回。
    var dragStartIndex by remember { mutableStateOf<Int?>(null) }
    var dragReleaseTarget by remember { mutableStateOf<Float?>(null) }
    val pressAnim = remember { Animatable(0f) }
    val jellyAnim = remember { Animatable(0f) }
    var jellyTarget by remember { mutableFloatStateOf(0f) }
    val visualPosition = remember { { if (dragActive) dragAnim.value else dragTarget ?: currentSelectionPosition() } }
    var selectionTravelPx by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(dragReleaseTarget) {
        val target = dragReleaseTarget ?: return@LaunchedEffect
        snapshotFlow { currentSelectionPosition() }.collect { position ->
            val settledHere = abs(position - target) < .01f
            val settledElsewhere = abs(position - position.roundToInt()) < .001f && !settledHere
            if (settledHere || settledElsewhere) {
                dragTarget = null
                dragActive = false
                dragReleaseTarget = null
                dragStartIndex = null
            }
        }
    }
    fun clearDragState() {
        dragTarget = null
        dragActive = false
        dragReleaseTarget = null
        dragStartIndex = null
    }
    // 逐帧采样透镜速度，映射成果冻拉伸量（tanh 饱和，静止时自动回零）。
    LaunchedEffect(Unit) {
        var lastPosition = currentSelectionPosition()
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    val dtSeconds = (nanos - lastNanos).coerceAtLeast(1L) / 1_000_000_000f
                    val position = visualPosition()
                    jellyTarget = if (reduceMotion) 0f else .16f * tanh(abs(position - lastPosition) / dtSeconds / 2f)
                    lastPosition = position
                }
                lastNanos = nanos
            }
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
            dragReleaseTarget = null
            dragStartIndex = currentSelectionPosition().roundToInt()
                .coerceIn(0, MusicOnePage.entries.lastIndex)
            scope.launch {
                dragAnim.snapTo(currentSelectionPosition())
                dragAnim.animateTo(next, followSpec)
            }
        } else {
            scope.launch { dragAnim.animateTo(next, followSpec) }
        }
    }
    fun settleDrag(velocityX: Float) {
        // FlClash 式收尾：按释放速度把落点向前投影 100ms，最多切到相邻一页，松手这一刻才切页。
        val position = (if (dragActive) dragAnim.value else dragTarget ?: selectionPosition())
            .coerceIn(0f, MusicOnePage.entries.lastIndex.toFloat())
        val projected = position + (velocityX / selectionTravelPx.coerceAtLeast(1f)) * .1f
        var targetPage = rootPageForPosition(projected)
        val start = dragStartIndex ?: page.ordinal
        val clamped = targetPage.ordinal.coerceIn(start - 1, start + 1)
            .coerceIn(0, MusicOnePage.entries.lastIndex)
        targetPage = MusicOnePage.entries[clamped]
        onPageChange(targetPage)
        // 透镜自己滑向目标页停住，页面随后弹簧跟上；交接由 dragReleaseTarget 的观察者完成。
        dragReleaseTarget = clamped.toFloat()
        scope.launch { dragAnim.animateTo(clamped.toFloat(), followSpec) }
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
        val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
        val selectedColor = navigationCapsuleSelectionColor(
            neutral, MaterialTheme.colorScheme.primaryContainer,
        )
        MusicOneBackdropGlass(
            backdropLayer = backdropLayer,
            backdropBounds = backdropBounds,
            modifier = Modifier.width(metrics.width),
            shape = RoundedCornerShape(metrics.outerRadius),
            blurRadius = if (rowStyle) 18.dp else 20.dp,
            // 底栏外观全应用统一，不再跟随页面背景的沉浸色（此前"我的"页会变深、主页浅，用户要求统一成浅色基准）。
            containerColor = if (dark) Color(0xFF242526).copy(alpha = .58f) else Color.White.copy(alpha = lightTint),
            fallbackColor = if (dark) Color(0xFF242526) else Color(0xFFF3F4F6),
            border = androidx.compose.foundation.BorderStroke(
                if (rowStyle) 1.dp else .8.dp,
                when {
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
                        clearDragState()
                        if (page == MusicOnePage.HOME && kotlin.math.abs(selectionPosition()) < .001f) {
                            primaryHeaderState.requestScrollToTop(MusicOnePage.HOME)
                        } else {
                            onPageChange(MusicOnePage.HOME)
                        }
                    },
                    onMy = {
                        clearDragState()
                        onPageChange(MusicOnePage.MY)
                    },
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
