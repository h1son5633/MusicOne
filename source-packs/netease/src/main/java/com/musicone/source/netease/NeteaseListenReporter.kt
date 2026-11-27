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

/**
 * 播放会话结算：宿主串行交付会话事件，这里做三件事——
 * 1) 把真实收听时长按增量记进本地账本（今日/本周/累计展示）；
 * 2) 每首歌开始播放时记进本地最近播放；若「同步最近播放」开着，再经 EAPI/clientlog
 *    通道发一条 startplay 日志，让它进官方客户端的最近播放；
 * 3) 听够门槛（时长一半与 30 秒取小）后，经同一通道发一条 play 日志给听歌排行计数。
 * 两条日志各自一个开关（netease_settings），各自记录最近一次请求结果；
 * 请求成功不等于入账成功，入账只能以官方客户端为准。全部尽力而为，失败不重试、
 * 不影响播放（先占位再上报，避免异常节奏触发风控）。
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
        var scrobbled: Boolean = false,
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
                maybeScrobble(owner, session)
                active = null
                lastEndedId = session.id
            }
            else -> {
                val session = active?.takeIf { it.id == event.sessionId } ?: return
                if (!advance(session, event)) return
                if (event.type == SourcePlaybackSessionEventType.PAUSE ||
                    event.playedMs - session.settledMs >= SETTLE_STEP_MS) settle(owner, session)
                maybeScrobble(owner, session)
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

    private fun recordStatus(owner: NeteasePackageState, key: String, ok: Boolean, detail: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val text = if (ok) "$time 请求成功" else "$time 请求失败：${detail.take(60)}"
        syncPrefs(owner).edit().putString(key, text).apply()
    }

    /** 开播同步最近播放：一会话一次，开关关或未登录直接跳过。 */
    private suspend fun maybeStartLog(owner: NeteasePackageState, session: Session) {
        if (session.startLogged) return
        if (!syncPrefs(owner).getBoolean(KEY_SYNC_RECENT, true)) return
        val cookie = owner.credential()
        if (cookie.isBlank() || owner.account == null) return
        session.startLogged = true
        try {
            withContext(Dispatchers.IO) { owner.api.startPlayLog(cookie, session.remoteId) }
            recordStatus(owner, KEY_STATUS_RECENT, true, "")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordStatus(owner, KEY_STATUS_RECENT, false, error.message.orEmpty())
        }
    }

    /** 达标同步听歌排行：一会话一次，门槛 = min(时长一半, 30 秒)。 */
    private suspend fun maybeScrobble(owner: NeteasePackageState, session: Session) {
        if (session.scrobbled) return
        val duration = session.durationMs
        val threshold = if (duration > 0) minOf(duration / 2, SCROBBLE_AFTER_MS) else SCROBBLE_AFTER_MS
        if (session.playedMs < threshold) return
        if (!syncPrefs(owner).getBoolean(KEY_SYNC_RECORD, true)) return
        val cookie = owner.credential()
        if (cookie.isBlank() || owner.account == null) return
        // 先占位再上报：上报失败也不重复打卡，避免异常节奏触发风控。
        session.scrobbled = true
        val seconds = session.playedMs / 1000
        try {
            withContext(Dispatchers.IO) { owner.api.scrobble(cookie, session.remoteId, seconds) }
            recordStatus(owner, KEY_STATUS_RECORD, true, "")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            recordStatus(owner, KEY_STATUS_RECORD, false, error.message.orEmpty())
        }
    }

    private companion object {
        const val SETTLE_STEP_MS = 30_000L
        const val SCROBBLE_AFTER_MS = 30_000L
        const val SYNC_PREFS = "netease_settings"
        const val KEY_SYNC_RECENT = "sync_recent_plays"
        const val KEY_SYNC_RECORD = "sync_play_record"
        const val KEY_STATUS_RECENT = "sync_status_recent"
        const val KEY_STATUS_RECORD = "sync_status_record"
    }
}
