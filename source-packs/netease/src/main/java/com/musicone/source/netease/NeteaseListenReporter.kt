package com.musicone.source.netease

import android.content.SharedPreferences
import com.musicone.source.sdk.SourcePlaybackSessionEvent
import com.musicone.source.sdk.SourcePlaybackSessionEventType
import com.musicone.source.sdk.SourcePlaybackSessionObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** play 日志的有效播放门槛：时长一半与 30 秒取小（与 X-Music 的门槛一致）。 */
internal fun neteasePlayThresholdMs(durationMs: Long): Long =
    if (durationMs > 0) minOf(durationMs / 2, 30_000L) else 30_000L

/** 同步诊断行：时间 · 日志类型 · 累计播放秒数 · 结果。不含任何凭据信息。 */
internal fun syncDiagText(time: String, action: String, playedSeconds: Long, result: String): String =
    "$time · $action · 累计 $playedSeconds 秒 · $result"

/**
 * 播放会话结算：宿主串行交付会话事件，这里做三件事——
 * 1) 把真实收听时长按增量记进本地账本（今日/本周/累计展示）；
 * 2) 每首歌开始播放时记进本地最近播放；若「同步最近播放」开着，再经 EAPI/clientlog
 *    通道发一条 startplay 日志，让它进官方客户端的最近播放；
 * 3) 会话结束（播完、切歌、停止）时结算：听够门槛（时长一半与 30 秒取小）就经同一
 *    通道发一条 play 日志，秒数是整段会话的真实累计收听时长，给听歌排行计数。
 *    发送时机与秒数口径与 X-Music 一致（结束/切走时发全量秒数，不在门槛点抢发）。
 * 两条日志各自一个开关（netease_settings），每次尝试（含被开关/登录门跳过）都在
 * 设置页留一行诊断（时间、类型、累计秒数、结果或跳过原因），不含任何凭据。
 * 请求成功不等于入账成功，入账只能以官方客户端为准。全部尽力而为，失败不重试、
 * 不影响播放（先占位再上报，避免异常节奏触发风控）；唯一的例外是 startplay：
 * 开播时账号还没就绪（未登录门挡下）时不占位，后续进度事件会补发一次。
 */
internal class NeteaseListenReporter(
    private val state: () -> NeteasePackageState,
) : SourcePlaybackSessionObserver {
    private data class Session(
        val id: String,
        val remoteId: String,
        val durationMs: Long,
        var sequence: Long,
        var playedMs: Long,
        var settledMs: Long = 0,
        var startLogged: Boolean = false,
        var startSkipLogged: Boolean = false,
        var playLogged: Boolean = false,
    )

    private var active: Session? = null
    private var lastEndedId: String? = null

    override suspend fun onPlaybackSession(event: SourcePlaybackSessionEvent) {
        try {
            handle(event)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 记账和上报都不能影响播放事件链。
        }
    }

    private suspend fun handle(event: SourcePlaybackSessionEvent) {
        val identity = event.track.track.identity
        if (identity.sourceId != NETEASE_SOURCE_ID || event.playedMs < 0) return
        val owner = state()
        when (event.type) {
            SourcePlaybackSessionEventType.START -> {
                if (active?.id == event.sessionId || lastEndedId == event.sessionId) return
                // 上一首没等到 END 就切过来了：先按它已累计的时长结算并补发 play，
                // 不能让它的听歌记录随会话对象一起被丢掉。
                active?.let { previous ->
                    settle(owner, previous)
                    finalizePlay(owner, previous)
                }
                val session = Session(event.sessionId, identity.remoteId, event.track.durationMs,
                    event.sequence, event.playedMs)
                active = session
                owner.listenTracker.recordRecent(NeteaseRecentTrack(
                    stableId = event.track.stableId,
                    remoteId = identity.remoteId,
                    title = event.track.track.title,
                    artists = event.track.track.artists.joinToString(", "),
                    album = event.track.album,
                    durationMs = event.track.durationMs,
                    artworkUrl = event.track.track.artworkUrl,
                ))
                maybeStartLog(owner, session)
            }
            SourcePlaybackSessionEventType.END -> {
                val session = active?.takeIf { it.id == event.sessionId } ?: return
                if (!advance(session, event)) return
                settle(owner, session)
                finalizePlay(owner, session)
                active = null
                lastEndedId = session.id
            }
            else -> {
                val session = active?.takeIf { it.id == event.sessionId } ?: return
                if (!advance(session, event)) return
                if (event.type == SourcePlaybackSessionEventType.PAUSE ||
                    event.playedMs - session.settledMs >= SETTLE_STEP_MS) settle(owner, session)
                // startplay 若开播时被未登录门挡下，这里随进度事件补发（成功后占位，不再重发）。
                maybeStartLog(owner, session)
            }
        }
    }

    /** 事件必须单调前进且仍是同一首歌，否则丢弃。 */
    private fun advance(session: Session, event: SourcePlaybackSessionEvent): Boolean {
        if (event.sequence <= session.sequence || event.playedMs < session.playedMs) return false
        if (event.track.track.identity.remoteId != session.remoteId) return false
        session.sequence = event.sequence
        session.playedMs = event.playedMs
        return true
    }

    private fun settle(owner: NeteasePackageState, session: Session) {
        val delta = session.playedMs - session.settledMs
        if (delta <= 0) return
        session.settledMs = session.playedMs
        owner.listenTracker.addListenMs(delta, System.currentTimeMillis())
    }

    private fun syncPrefs(owner: NeteasePackageState): SharedPreferences =
        owner.environment.context.getSharedPreferences(SYNC_PREFS, 0)

    private fun recordDiag(owner: NeteasePackageState, key: String, action: String,
                           playedSeconds: Long, result: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        syncPrefs(owner).edit().putString(key, syncDiagText(time, action, playedSeconds, result)).apply()
    }

    /**
     * 开播同步最近播放：一会话一次。开关关或未登录时写一行跳过诊断（每会话只写一次），
     * 未登录不占位——账号随后就绪时由进度事件补发；真正发出前才占位，失败也不重发。
     */
    private suspend fun maybeStartLog(owner: NeteasePackageState, session: Session) {
        if (session.startLogged) return
        val seconds = session.playedMs / 1000
        if (!syncPrefs(owner).getBoolean(KEY_SYNC_RECENT, true)) {
            if (!session.startSkipLogged) {
                session.startSkipLogged = true
                recordDiag(owner, KEY_STATUS_RECENT, "startplay", seconds, "已跳过：同步开关关闭")
            }
            return
        }
        val cookie = owner.credential()
        if (cookie.isBlank() || owner.account == null) {
            if (!session.startSkipLogged) {
                session.startSkipLogged = true
                recordDiag(owner, KEY_STATUS_RECENT, "startplay", seconds, "已跳过：未登录")
            }
            return
        }
        session.startLogged = true
        try {
            withContext(Dispatchers.IO) { owner.api.startPlayLog(cookie, session.remoteId) }
            recordDiag(owner, KEY_STATUS_RECENT, "startplay", seconds, "请求成功")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordDiag(owner, KEY_STATUS_RECENT, "startplay", seconds,
                "请求失败：${error.message.orEmpty().take(60)}")
        }
    }

    /**
     * 会话结算时同步听歌排行：一会话一次，只在结束/切歌时调用，秒数为整段真实累计时长。
     * 未达门槛、开关关、未登录都写一行跳过诊断，不再静默消失。
     */
    private suspend fun finalizePlay(owner: NeteasePackageState, session: Session) {
        if (session.playLogged) return
        val seconds = session.playedMs / 1000
        if (session.playedMs < neteasePlayThresholdMs(session.durationMs)) {
            recordDiag(owner, KEY_STATUS_RECORD, "play", seconds, "已跳过：未达有效播放门槛")
            return
        }
        if (!syncPrefs(owner).getBoolean(KEY_SYNC_RECORD, true)) {
            recordDiag(owner, KEY_STATUS_RECORD, "play", seconds, "已跳过：同步开关关闭")
            return
        }
        val cookie = owner.credential()
        if (cookie.isBlank() || owner.account == null) {
            recordDiag(owner, KEY_STATUS_RECORD, "play", seconds, "已跳过：未登录")
            return
        }
        // 先占位再上报：上报失败也不重复打卡，避免异常节奏触发风控。
        session.playLogged = true
        try {
            withContext(Dispatchers.IO) { owner.api.scrobble(cookie, session.remoteId, seconds) }
            recordDiag(owner, KEY_STATUS_RECORD, "play", seconds, "请求成功")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordDiag(owner, KEY_STATUS_RECORD, "play", seconds,
                "请求失败：${error.message.orEmpty().take(60)}")
        }
    }

    private companion object {
        const val SETTLE_STEP_MS = 30_000L
        const val SYNC_PREFS = "netease_settings"
        const val KEY_SYNC_RECENT = "sync_recent_plays"
        const val KEY_SYNC_RECORD = "sync_play_record"
        const val KEY_STATUS_RECENT = "sync_status_recent"
        const val KEY_STATUS_RECORD = "sync_status_record"
    }
}
