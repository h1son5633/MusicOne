package com.musicone

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 状态栏全页透明：不再绘制任何底色条，内容直接顶到状态栏下面；这里只按页面实际底色决定状态栏图标明暗。 */
@Composable
internal fun EntitySystemBar(navigation: EntityNavigation, player: PageMotion,
    recommendation: RecommendationNavigation, settingsMotion: PageMotion,
    rootColor: Color = Color.Unspecified, rootDarkIcons: Boolean? = null) {
    val base = MaterialTheme.colorScheme.background
    val root = if (rootColor == Color.Unspecified) base else rootColor
    // 歌单详情页的实际底色就是页面背景（封面取色只曾用来给状态栏条上色），图标明暗不再跟它走。
    var pageColor = root
    navigation.pages.forEach { frame -> key(frame.key) {
        val album = (frame.target as? EntityTarget.Album)?.playlist
        val target = if (album != null) rememberPlaylistStatusColor(album) else frame.statusBarColor ?: pageColor
        pageColor = lerp(pageColor, target, frame.motion.value)
    } }
    val color = lerp(pageColor, base, settingsMotion.value)
    val view = LocalView.current
    val activity = remember(view) {
        generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    SideEffect {
        activity?.window?.let {
            val controller = WindowCompat.getInsetsController(it, view)
            val atRoot = recommendation.motion.value < .01f &&
                navigation.pages.all { frame -> frame.motion.value < .01f } && settingsMotion.value < .01f
            val darkStatus = if (atRoot && rootDarkIcons != null && player.value < .85f) {
                rootDarkIcons
            } else {
                useDarkStatusIcons(color.luminance(), player.value)
            }
            val darkNavigation = if (atRoot && rootDarkIcons != null && player.value < .5f) {
                rootDarkIcons
            } else {
                base.luminance() > .4f && player.value < .5f
            }
            if (controller.isAppearanceLightStatusBars != darkStatus) controller.isAppearanceLightStatusBars = darkStatus
            if (controller.isAppearanceLightNavigationBars != darkNavigation) controller.isAppearanceLightNavigationBars = darkNavigation
        }
    }
    Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
}

// 参数使用页面进度，准备阶段不提前切换图标；深色播放页接近顶部时改为浅色图标。
internal fun useDarkStatusIcons(backgroundLuminance: Float, playerProgress: Float): Boolean =
    backgroundLuminance > .4f && playerProgress < .85f
