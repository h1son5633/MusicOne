package com.musicone

import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.musicone.nativeaudio.NativeAudioOutputPolicy
import com.musicone.nativeaudio.NativeAudioRoutingMediaSourceFactory

@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null
    private var systemMediaPlayer: SystemMediaPlayer? = null
    private var usbAudioCoordinator: UsbAudioServiceCoordinator? = null
    private var detailsLoader: PlaybackDetailsLoader? = null
    private var audioFocusCoordinator: PlaybackAudioFocusCoordinator? = null
    private var routingProvider: RoutingAudioTrackProvider? = null
    private var runtime: MusicPlaybackRuntime? = null
    private var notificationDiagnostics: SystemMediaNotificationDiagnostics? = null
    // 小米超级岛歌词已停用：实测无法使用，澎湃系统按白名单准入，未入白名单的应用不会显示。
    // private var hyperOsLyrics: HyperOsLyricsCoordinator? = null
    // private var hyperOsNotifications: HyperOsMediaNotificationProvider? = null
    private var desktopLyrics: DesktopLyricsCoordinator? = null
    private val presentationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private val timerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val timerTick = object : Runnable {
        override fun run() {
            if (PlaybackSleepTimer.expired()) { exoPlayer?.pause(); PlaybackSleepTimer.set(0) }
            timerHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationDiagnostics = SystemMediaNotificationDiagnostics(this, foreground = { isPlaybackOngoing })
        val notifications = requireNotNull(notificationDiagnostics)
        // 超级岛停用后各品牌统一使用默认媒体通知。
        // if (HyperOsIslandAccess.supported(this)) {
        //     hyperOsNotifications = HyperOsMediaNotificationProvider(this, notifications)
        //     setMediaNotificationProvider(requireNotNull(hyperOsNotifications))
        // } else
        setMediaNotificationProvider(notifications)
        PlaybackOptions.initialize(this)
        LgHiResPreferences.initialize(this)
        DevicePlaybackOptions.initialize(this)
        val preloadBandwidth = PlaybackPreloadBandwidth()
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            // Media3 默认连接/读取超时均为 8 秒，移动网络下 CDN 供数短暂卡顿即触发读取超时、
            // 播放中断（错误恢复有时自动续播、有时停住）。放宽到连接 15 秒 / 读取 30 秒，
            // 与本应用 API 客户端的 12s/25s 同量级，只影响容错，不改变请求内容。
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            // 工厂专用 UA 会覆盖 DataSpec 的请求头；默认属性允许补充音源携带自己的 UA。
            .setDefaultRequestProperties(mapOf("User-Agent" to PlatformHttp.DEFAULT_USER_AGENT))
        val upstreamDataSourceFactory = SupplementalPlaybackRequests.factory(
            MusicDiskCache.get(this).factory(DefaultDataSource.Factory(this,
                playbackPreloadNetworkFactory(httpDataSourceFactory, preloadBandwidth))),
        )
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(upstreamDataSourceFactory)
        val mediaSourceFactory = NativeAudioRoutingMediaSourceFactory(
            defaultFactory = defaultMediaSourceFactory,
            dataSourceFactory = upstreamDataSourceFactory,
            targetDsdSampleRate = NativeAudioOutputPolicy.DEFAULT_DSD_PCM_SAMPLE_RATE,
            context = this,
        )
        val renderersFactory = HiResCompatibleRenderersFactory(this)
        routingProvider = renderersFactory.routingProvider
        // 默认缓冲（50 秒上限）在移动网络抖动下容易见底、频繁重缓冲。改为音乐流媒体参数：
        // 目标缓冲 60–120 秒抗抖动；起播仍只需 2.5 秒、重缓冲后 5 秒恢复，不拖慢切歌起播。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 120_000, 2_500, 5_000)
            .build()
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            // 在线音源息屏后仍需维持网络接收，避免 USB 写入端因上游断流而停顿。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                !DevicePlaybackOptions.concurrentPlayback.value,
            )
            setHandleAudioBecomingNoisy(true)
        }
        exoPlayer = player
        audioFocusCoordinator = PlaybackAudioFocusCoordinator(player).also { it.start() }
        player.addListener(PlaybackServiceDiagnostics { isPlaybackOngoing })
        val playback = MusicPlaybackRuntimeOwner.acquire(application, this)
        runtime = playback
        detailsLoader = PlaybackDetailsLoader(this, player).also(player::addListener)
        usbAudioCoordinator = renderersFactory.usbAudioSink?.let { sink ->
            UsbAudioServiceCoordinator(this, player, sink, playback::pauseForUsbOutputLoss,
                renderersFactory.lgAudioSink).also { it.start() }
        }
        timerHandler.post(timerTick)
        val audioMetadata = PlaybackAudioMetadataListener(player)
        player.addListener(audioMetadata)
        player.addAnalyticsListener(audioMetadata)
        val diagnostics = SystemMediaDiagnostics()
        val sessionPlayer = SystemMediaPlayer(player, playback::setPlaying,
            playback::previous, playback::next, playback::seekFromSystem,
            recordCommand = { diagnostics.command(it, mediaSession?.controllerForCurrentRequest) },
        )
        systemMediaPlayer = sessionPlayer
        sessionPlayer.addListener(diagnostics)
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val sessionBuilder = MediaSession.Builder(this, sessionPlayer).setCallback(diagnostics)
        if (launchIntent != null) {
            sessionBuilder.setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        mediaSession = sessionBuilder.build()
        // 在恢复待播放请求之前注册会话，后台通知管理不依赖页面控制器连接时机。
        addSession(requireNotNull(mediaSession))
        playback.attachPlayer(player, preloadBandwidth)
        val presentations = playbackSystemPresentations(this, playback, player).sharedSystemPresentations(presentationScope)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            desktopLyrics = DesktopLyricsCoordinator(this, presentations, playback::togglePlay).also { it.start() }
        }
        // if (HyperOsIslandAccess.supported(this)) {
        //     hyperOsLyrics = HyperOsLyricsCoordinator(
        //         this, presentations, requireNotNull(hyperOsNotifications),
        //         loadArtwork = ::loadSystemArtwork,
        //     ).also { it.start() }
        // }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onBind(intent: Intent?): IBinder? {
        val browserIntent = VivoSystemMedia.browserIntent(intent)
        val binder = super.onBind(browserIntent)
        if (intent?.action == VivoSystemMedia.SERVICE_ACTION) {
            android.util.Log.i("MusicOneSystemMedia", "vivo 媒体服务绑定 已提供接口=${binder != null}")
        }
        return binder
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)
        notificationDiagnostics?.inspectAfterUpdate()
        // if (android.os.Build.VERSION.SDK_INT >= 26) hyperOsLyrics?.refreshAccess()
    }

    override fun onDestroy() {
        android.util.Log.i("MusicPlaybackService", "播放服务销毁")
        notificationDiagnostics?.close()
        notificationDiagnostics = null
        // if (android.os.Build.VERSION.SDK_INT >= 26) hyperOsNotifications?.close()
        // hyperOsNotifications = null
        // if (android.os.Build.VERSION.SDK_INT >= 26) hyperOsLyrics?.close()
        // hyperOsLyrics = null
        if (android.os.Build.VERSION.SDK_INT >= 31) desktopLyrics?.close()
        desktopLyrics = null
        presentationScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        exoPlayer?.let { runtime?.detachPlayer(it) }
        MusicPlaybackRuntimeOwner.release(this)
        runtime = null
        detailsLoader?.release()
        audioFocusCoordinator?.release()
        audioFocusCoordinator = null
        timerHandler.removeCallbacks(timerTick)
        usbAudioCoordinator?.release()
        usbAudioCoordinator = null
        mediaSession?.release()
        mediaSession = null
        routingProvider?.release()
        routingProvider = null
        systemMediaPlayer?.release() ?: exoPlayer?.release()
        systemMediaPlayer = null
        exoPlayer = null
        retainedPlaybackSession.clear()
        MusicDiskCache.available()?.activeKey = null
        super.onDestroy()
    }
}
