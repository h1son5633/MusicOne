package com.musicone.sourceintegration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicone.ExpandingPageSurface
import com.musicone.MusicOnePage
import com.musicone.MusicSource
import com.musicone.PageMotion
import com.musicone.contentReveal
import com.musicone.playerSurfaceReveal
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.sdk.SourcePageState
import com.musicone.source.sdk.SourceRoute

/** 宿主继续负责两张根页的滑动，包负责其内部页面和返回栈。 */
@Stable
internal class SourceRootPages(private val instance: SourceInstance?, private val pageState: State<SourcePageState>,
    private val startup: SourceStartupState) {
    val initializing: Boolean get() = startup.initializing
    val preparing: Boolean get() = startup.initializing || (instance == null && startup.error != null)
    @Composable fun Preparation(source: MusicSource, page: MusicOnePage, bottomInset: Dp, onChooseSource: () -> Unit) {
        SourcePreparationContent(source, page, bottomInset, startup.error, onChooseSource)
    }
    val isCustom: Boolean get() = instance?.entry?.descriptor?.useCustomPages == true
    val atRoot: Boolean get() = pageState.value.atRoot
    val hostRootTransition: Boolean get() = isCustom && instance?.entry?.descriptor?.id == MusicSource.KUGOU.id
    fun rootIdentity(page: MusicOnePage): SourceRootIdentity? =
        if (hostRootTransition) SourceRootIdentity(MusicSource.KUGOU.id, page) else null
    val supportsSearch: Boolean get() = instance?.entry?.search != null
    /** 网易云包支持把搜索页交给宿主以展开表面呈现（从右上角按钮扩大打开，与设置页一致）；
     * 其余包仍沿用页内换页的旧方式。 */
    val useSearchOverlay: Boolean get() = instance?.entry?.descriptor?.id == MusicSource.NETEASE.id
    private var searchPage by mutableStateOf<MusicOnePage?>(null)
    private var requestRevision by mutableLongStateOf(0)
    var searchOpen by mutableStateOf(false)
        private set
    fun openSearch(page: MusicOnePage) {
        if (useSearchOverlay) searchOpen = true else searchPage = page
        requestRevision++
    }
    fun closeSearch() { searchOpen = false }
    fun rootChanged() { searchPage = null; searchOpen = false }
    @Composable fun Content(page: MusicOnePage, active: Boolean, bottomInset: Dp, modifier: Modifier = Modifier,
        onExit: () -> Unit = {}, interactive: Boolean = true) {
        instance ?: return
        val route = if (!useSearchOverlay && active && searchPage == page) "search" else page.name.lowercase()
        SourceExternalSurface(instance, SourceRoute(route, mapOf("embedded" to "true", "active" to active.toString(),
            "bottomInsetDp" to bottomInset.value.toString(), "requestRevision" to requestRevision.toString())), modifier,
            onExit = onExit, interactive = active && interactive, rootIdentity = rootIdentity(page))
    }
    /** 搜索展开层：与设置页同一套 ExpandingPageSurface，从头部按钮的真实边界扩大打开，底页保持不动。 */
    @Composable fun SearchOverlay(motion: PageMotion, origin: () -> Rect, bottomInset: Dp) {
        val current = instance ?: return
        if (!useSearchOverlay) return
        var capturedOrigin by remember { mutableStateOf(Rect.Zero) }
        LaunchedEffect(searchOpen) {
            if (searchOpen && !motion.mounted) capturedOrigin = origin()
            motion.request(searchOpen)
        }
        BackHandler(enabled = searchOpen) { closeSearch() }
        if (!motion.mounted) return
        ExpandingPageSurface(
            origin = capturedOrigin,
            progress = { motion.value },
            surfaceReveal = ::playerSurfaceReveal,
            contentReveal = ::contentReveal,
            sourceCorner = 24.dp,
        ) {
            SourceExternalSurface(current, SourceRoute("search-overlay", mapOf("embedded" to "true", "active" to "true",
                "bottomInsetDp" to bottomInset.value.toString(), "requestRevision" to requestRevision.toString())),
                Modifier.fillMaxSize(), interactive = true)
        }
    }
    @Composable fun Back(enabled: Boolean) {
        BackHandler(enabled = enabled && !searchOpen && isCustom && (!atRoot || searchPage != null)) {
            // 包内返回优先；搜索根页再由宿主收起，避免直接退出应用。
            if (!runCatching { instance?.onBack() == true }.getOrDefault(false)) searchPage = null
        }
    }
}

@Composable
internal fun rememberSourceRootPages(source: MusicSource, page: MusicOnePage): SourceRootPages {
    val controller = SourcePackageController.get(LocalContext.current)
    val installed by controller.installed.collectAsState()
    val startup by controller.startup.collectAsState()
    val instance = remember(source, installed) { controller.running(source.id) }
    val state = instance?.entry?.pageState?.collectAsState() ?: remember(instance) { mutableStateOf(SourcePageState()) }
    val result = remember(instance, startup) { SourceRootPages(instance, state, startup) }
    LaunchedEffect(result, page) { result.rootChanged() }
    return result
}
