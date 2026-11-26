package com.musicone.sourceintegration

import android.content.Context
import com.musicone.MusicSource
import com.musicone.source.runtime.ImportDecision
import com.musicone.source.runtime.InstalledSource
import com.musicone.source.runtime.SourceInstance
import com.musicone.source.runtime.SourceRepository
import com.musicone.source.sdk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 一个应用进程只激活一次；导入、回退只修改待重启版本。 */
internal class SourcePackageController private constructor(private val context: Context) {
    private val mutableInstalled = MutableStateFlow<List<InstalledSource>>(emptyList())
    val installed = mutableInstalled.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message = mutableMessage.asStateFlow()
    private val trustedSigners = mutableMapOf<String, String>()
    private val migration = LegacySourceDataMigration(context, trustedSigners)
    private var actions: SourceHost? = null
    private var playingSource: () -> String? = { null }
    private var stopSourcePlayback: () -> Unit = {}
    private var notifyRemoval: (String) -> Unit = {}
    @Volatile private var activeInstances: Map<String, SourceInstance> = emptyMap()
    private val deferredScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startupTask = SourceStartup(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { initialize() }
    val startup = startupTask.state

    private val repository: SourceRepository by lazy { SourceRepository(context, object : SourceHost {
        override val library get() = actions?.library
        override val cache get() = actions?.cache
        override val ui get() = actions?.ui
        override val contentUi get() = actions?.contentUi
        override val playerState get() = actions?.playerState
        override val reduceMotion get() = actions?.reduceMotion ?: false
        override fun play(tracks: List<SourceTrack>, startIndex: Int) = requireActions().play(tracks, startIndex)
        override fun playDetailed(tracks: List<SourceTrackDetails>, startIndex: Int) = requireActions().playDetailed(tracks, startIndex)
        override fun enqueueDetailed(tracks: List<SourceTrackDetails>, playNext: Boolean) = requireActions().enqueueDetailed(tracks, playNext)
        override fun playRecommendation(track: SourceTrackDetails) = requireActions().playRecommendation(track)
        override fun playRecommendations(tracks: List<SourceTrackDetails>, startIndex: Int) =
            requireActions().playRecommendations(tracks, startIndex)
        override fun importTracks(tracks: List<SourceTrack>, destinationId: String) = requireActions().importTracks(tracks, destinationId)
        override fun exitSource() = requireActions().exitSource()
        override fun reportError(message: String) { mutableMessage.value = message; actions?.reportError(message) }
        override fun prepareSourceStorage(sourceId: String, context: Context) {
            val identity = repository.installed().firstOrNull { it.id == sourceId } ?: return
            migration.migrate(sourceId, identity.signerSha256, context)
        }
    }) }

    fun bind(host: SourceHost, currentPlayingSource: () -> String?, stopPlayback: () -> Unit, onRemoved: (String) -> Unit) {
        actions = host
        playingSource = currentPlayingSource
        stopSourcePlayback = stopPlayback
        notifyRemoval = onRemoved
    }

    fun start() = startupTask.start()

    suspend fun awaitStartup() {
        SourceApplicationHost.get(context).start()
        startupTask.await()
        startup.value.error?.let { throw IllegalStateException(it) }
    }

    private fun initialize() {
        val distribution = File(context.cacheDir, "prebundled-sources").apply { mkdirs() }
        val packages = context.assets.list("source-packs").orEmpty()
            .filter { it.endsWith(".mscm") || it.endsWith(".mscp") }
            .map { name ->
                val copy = File(distribution, name)
                if (copy.exists()) check(copy.delete()) { "无法刷新预装分发副本" }
                context.assets.open("source-packs/$name").use { input -> copy.outputStream().use { input.copyTo(it) } }
                copy
            }
        repository.installPrebundledOnce(packages) { archive ->
            trustedSigners[archive.manifest.descriptor.id] = archive.signerSha256
            MusicSource.register(archive.manifest.descriptor)
        }
        // 先从可信清单补齐旧记录的类型；待删除期间保留账号，下一次启动才清迁移副本。
        repository.installed().filter { it.kind == SourceKind.MAIN && (it.pendingRemoval || it.removed) }
            .forEach { clearRemovedSourceLegacyAccount(context, it.id) }
        // 保留删除记录的音源不参与主入口选择，缺包曲目仍使用原稳定身份。
        val selection = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
        val selected = selection.getString("selected_source", null)?.let { runCatching { MusicSource.valueOf(it) }.getOrNull() }
        // 优先启动：开机只加载当前选中的音源，其余音源延后（被选中、被播放时按需启动，或延时补启动）。
        // QQ 音乐只允许手动启动：不登录几乎不可用，任何自动路径都不拉起它，用户主动选它时才启动。
        repository.startPriority(selected?.takeIf { it != MusicSource.LOCAL }?.id, MANUAL_ONLY_SOURCE_IDS).values
            .forEach { MusicSource.register(it.entry.descriptor) }
        repository.startupErrors.firstOrNull()?.let { mutableMessage.value = it }
        if (selected != null && selected != MusicSource.LOCAL && repository.running(selected.id) == null) {
            selection.edit().putString("selected_source", MusicSource.LOCAL.name).apply()
        }
        refresh()
        scheduleDeferredStart()
    }

    /** 其余音源的延时补启动：开机一段时间后在后台逐个加载，期间被按需启动的自动跳过；只允许手动启动的音源（QQ）不补。 */
    private fun scheduleDeferredStart() {
        deferredScope.launch {
            delay(DEFERRED_START_DELAY_MILLIS)
            repository.installed().forEach { state ->
                if (state.id in MANUAL_ONLY_SOURCE_IDS) return@forEach
                if (repository.running(state.id) == null) ensureStarted(state.id)
            }
        }
    }

    /** 懒启动单个音源（选中、播放、延时补启动共用）；须在后台线程调用，启动失败返回 false。 */
    fun ensureStarted(id: String): Boolean {
        if (activeInstances.containsKey(id)) return true
        val instance = runCatching { repository.startOne(id) }.getOrNull() ?: return false
        MusicSource.register(instance.entry.descriptor)
        refresh()
        return true
    }

    suspend fun ensureStartedAsync(id: String): Boolean {
        if (activeInstances.containsKey(id)) return true
        return withContext(Dispatchers.IO) { ensureStarted(id) }
    }

    // 页面只读已经发布的实例，不能争抢安装、验签和加载过程中持有的仓库锁。
    fun running(id: String): SourceInstance? = activeInstances[id]
    fun available(kind: SourceKind): List<SourceInstance> = activeInstances.values
        .filter { it.entry.descriptor.kind == kind }

    @Synchronized fun importPackage(file: File, allowDowngrade: Boolean = true): ImportDecision {
        check(!startup.value.initializing) { "正在准备音源，请稍后再试" }
        val result = repository.importPackage(file, allowDowngrade)
        refresh()
        mutableMessage.value = if (result == ImportDecision.ALREADY_INSTALLED) "该版本已安装" else "音源已保存，重启应用后生效"
        return result
    }

    fun isPlayingSource(id: String): Boolean = playingSource() == id

    @Synchronized fun remove(id: String, confirmedPlayingRemoval: Boolean = false) {
        check(!startup.value.initializing) { "正在准备音源，请稍后再试" }
        require(id != MusicSource.LOCAL.id) { "本地音源始终内置" }
        if (isPlayingSource(id)) {
            require(confirmedPlayingRemoval) { "该音源正在播放，须确认停止播放后移除" }
            stopSourcePlayback()
        }
        // 先撤下可读实例，再关闭租约；通知观察者时必须已经发布新快照。
        val main = repository.installed().first { it.id == id }.kind == SourceKind.MAIN
        activeInstances = activeInstances - id
        try { if (main) repository.scheduleRemoval(id) else repository.remove(id) }
        catch (failure: Exception) { refresh(); throw failure }
        val preferences = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
        val selected = preferences.getString("selected_source", null)?.let { runCatching { MusicSource.valueOf(it) }.getOrNull() }
        if (selected?.id == id) preferences.edit().putString("selected_source", MusicSource.LOCAL.name).apply()
        refresh()
        notifyRemoval(id)
        mutableMessage.value = if (main) "音源已停用，可撤销；下次启动时删除音源与登录数据，歌曲资料保留"
            else "音源已移除，账号与资料已保留"
    }

    @Synchronized fun undoRemoval(id: String) {
        check(!startup.value.initializing) { "正在准备音源，请稍后再试" }
        repository.undoRemoval(id)?.entry?.descriptor?.let(MusicSource::register)
        refresh()
        mutableMessage.value = "已撤销移除，音源与账号已恢复"
    }

    @Synchronized fun rollback(id: String) {
        check(!startup.value.initializing) { "正在准备音源，请稍后再试" }
        repository.requestRollback(id)
        refresh()
        mutableMessage.value = "回退已安排，重启应用后生效"
    }

    private fun refresh() {
        val snapshot = repository.installed()
        activeInstances = snapshot.mapNotNull { state -> repository.running(state.id)?.let { state.id to it } }.toMap()
        mutableInstalled.value = snapshot
    }
    private fun requireActions(): SourceHost = checkNotNull(actions) { "宿主界面尚未就绪" }

    companion object {
        /** 其余音源延时补启动的等待时间：先保证冷启动与首页使用，之后再逐个补齐。 */
        private const val DEFERRED_START_DELAY_MILLIS = 300_000L

        /** 只允许手动启动的音源：自动路径（开机优先加载、全新安装首次激活、延时补启动）一律跳过。
         * 除当前音源外，自动加载只保留酷狗（不登录也能用）；其余音源用户主动选时才启动。 */
        private val MANUAL_ONLY_SOURCE_IDS = setOf(
            MusicSource.QQ.id, MusicSource.BILIBILI.id,
            "com.musicone.source.subsonic", "com.musicone.source.webdav",
        )

        @Volatile private var instance: SourcePackageController? = null
        fun current(): SourcePackageController? = instance
        fun get(context: Context): SourcePackageController = instance ?: synchronized(this) {
            instance ?: SourcePackageController(context.applicationContext).also { instance = it }
        }
    }
}
