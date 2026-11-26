package com.musicone.source.netease

import com.musicone.source.sdk.SourcePlaybackSessionEndReason
import com.musicone.source.sdk.SourcePlaybackSessionEvent
import com.musicone.source.sdk.SourcePlaybackSessionEventType
import com.musicone.source.sdk.SourcePlaybackSessionObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 播放会话结算：宿主串行交付会话事件，这里做三件事——
 * 1) 把真实收听时长按增量记进本地账本（今日/本周/累计展示）；
 * 2) 听满一半（最多 4 分钟）或自然播完时，向网易云打卡一次，更新账号听歌排行；
 * 3) 每首歌开始播放时记进最近播放。
 * 全部尽力而为，任何失败都不影响播放。
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
            // 记账和打卡都不能影响播放事件链。
        }
    }

    private suspend fun handle(event: SourcePlaybackSessionEvent) {
        val identity = event.track.track.identity
        if (identity.sourceId != NETEASE_SOURCE_ID || event.playedMs < 0) return
        val owner = state()
        when (event.type) {
            SourcePlaybackSessionEventType.START -> {
                if (active?.id == event.sessionId || lastEndedId == event.sessionId) return
                active = Session(event.sessionId, identity.remoteId, event.track.durationMs,
                    event.sequence, event.playedMs)
                owner.listenTracker.recordRecent(NeteaseRecentTrack(
                    stableId = event.track.stableId,
                    remoteId = identity.remoteId,
                    title = event.track.track.title,
                    artists = event.track.track.artists.joinToString(", "),
                    album = event.track.album,
                    durationMs = event.track.durationMs,
                    artworkUrl = event.track.track.artworkUrl,
                ))
            }
            SourcePlaybackSessionEventType.END -> {
                val session = active?.takeIf { it.id == event.sessionId } ?: return
                if (!advance(session, event)) return
                settle(owner, session)
                maybeScrobble(owner, session, completed =
                    event.endReason == SourcePlaybackSessionEndReason.COMPLETED)
                active = null
                lastEndedId = session.id
            }
            else -> {
                val session = active?.takeIf { it.id == event.sessionId } ?: return
                if (!advance(session, event)) return
                if (event.type == SourcePlaybackSessionEventType.PAUSE ||
                    event.playedMs - session.settledMs >= SETTLE_STEP_MS) settle(owner, session)
                maybeScrobble(owner, session, completed = false)
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

    private suspend fun maybeScrobble(owner: NeteasePackageState, session: Session, completed: Boolean) {
        if (session.scrobbled) return
        val duration = session.durationMs
        if (duration in 1 until MIN_SCROBBLE_TRACK_MS) return
        val threshold = if (duration > 0) minOf(duration / 2, SCROBBLE_CAP_MS) else SCROBBLE_CAP_MS
        if (!completed && session.playedMs < threshold) return
        if (completed && session.playedMs < MIN_SCROBBLE_PLAYED_MS) return
        val cookie = owner.credential()
        if (cookie.isBlank() || owner.account == null) return
        // 先占位再上报：上报失败也不重复打卡，避免异常节奏触发风控。
        session.scrobbled = true
        val seconds = session.playedMs / 1000
        try {
            withContext(Dispatchers.IO) { owner.api.scrobble(cookie, session.remoteId, seconds) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 尽力上报，失败静默。
        }
    }

    private companion object {
        const val SETTLE_STEP_MS = 30_000L
        const val SCROBBLE_CAP_MS = 240_000L
        const val MIN_SCROBBLE_TRACK_MS = 30_000L
        const val MIN_SCROBBLE_PLAYED_MS = 15_000L
    }
}
