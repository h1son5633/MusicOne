package com.musicone.source.netease

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 最近播放里保存的一首歌，字段足够重建一首可播放的网易云曲目。 */
internal data class NeteaseRecentTrack(
    val stableId: String,
    val remoteId: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String?,
) {
    fun toTrack(): NeteaseTrack = NeteaseTrack(
        id = stableId,
        source = NeteaseOrigin.NETEASE,
        title = title,
        artists = artists,
        album = album,
        durationMs = durationMs,
        artworkStart = 0,
        artworkEnd = 0,
        artworkMark = "",
        previewUrl = "",
        artworkUrl = artworkUrl,
        remoteKey = remoteId,
    )
}

/**
 * 本地听歌账本：按天累计实际收听毫秒数（今日/本周/累计展示用），
 * 并保存最近播放的 50 首歌。从装上这一版开始记，没有历史回填。
 */
internal class NeteaseListenTracker(context: Context) {
    private val prefs = context.getSharedPreferences("netease_listen", Context.MODE_PRIVATE)

    fun addListenMs(ms: Long, atMs: Long) {
        if (ms <= 0) return
        val dayKey = "day_" + dayStamp(atMs)
        prefs.edit()
            .putLong(dayKey, prefs.getLong(dayKey, 0L) + ms)
            .putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0L) + ms)
            .apply()
        pruneOldDays(atMs)
    }

    fun todayMs(nowMs: Long = System.currentTimeMillis()): Long =
        prefs.getLong("day_" + dayStamp(nowMs), 0L)

    fun weekMs(nowMs: Long = System.currentTimeMillis()): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMs
        var sum = 0L
        repeat(7) {
            sum += prefs.getLong("day_" + dayStamp(calendar.timeInMillis), 0L)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun totalMs(): Long = prefs.getLong(KEY_TOTAL, 0L)

    fun recordRecent(track: NeteaseRecentTrack) {
        if (track.remoteId.isBlank()) return
        val list = recent().filterNot { it.remoteId == track.remoteId }.toMutableList()
        list.add(0, track)
        val array = JSONArray()
        list.take(MAX_RECENT).forEach { item ->
            array.put(JSONObject()
                .put("stableId", item.stableId)
                .put("remoteId", item.remoteId)
                .put("title", item.title)
                .put("artists", item.artists)
                .put("album", item.album)
                .put("durationMs", item.durationMs)
                .put("artworkUrl", item.artworkUrl ?: JSONObject.NULL))
        }
        prefs.edit().putString(KEY_RECENT, array.toString()).apply()
    }

    fun recent(): List<NeteaseRecentTrack> {
        val raw = prefs.getString(KEY_RECENT, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val remoteId = item.optString("remoteId")
            if (remoteId.isBlank()) return@mapNotNull null
            NeteaseRecentTrack(
                stableId = item.optString("stableId"),
                remoteId = remoteId,
                title = item.optString("title"),
                artists = item.optString("artists"),
                album = item.optString("album"),
                durationMs = item.optLong("durationMs"),
                artworkUrl = item.optString("artworkUrl").ifBlank { null },
            )
        }
    }

    private fun pruneOldDays(nowMs: Long) {
        val cutoff = dayStamp(nowMs - KEEP_DAYS * 24L * 60 * 60 * 1000)
        val editor = prefs.edit()
        var changed = false
        for (key in prefs.all.keys) {
            if (key.startsWith("day_") && key.removePrefix("day_") < cutoff) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private companion object {
        const val KEY_TOTAL = "total_ms"
        const val KEY_RECENT = "recent_json"
        const val MAX_RECENT = 50
        const val KEEP_DAYS = 370L
        val DAY_FORMAT = SimpleDateFormat("yyyyMMdd", Locale.US)
        fun dayStamp(ms: Long): String = DAY_FORMAT.format(Date(ms))
    }
}

/** 时长展示：满 1 小时显示“X 小时 Y 分钟”，不足显示分钟，不足 1 分钟显示“不到 1 分钟”。 */
internal fun formatListenDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "$hours 小时 $minutes 分钟"
        minutes > 0 -> "$minutes 分钟"
        else -> "不到 1 分钟"
    }
}
