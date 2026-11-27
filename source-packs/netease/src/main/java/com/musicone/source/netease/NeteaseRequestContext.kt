package com.musicone.source.netease

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 与 api-enhanced 的 EAPI 保持一致：header 是对象，登录凭据也进入加密上下文。 */
internal fun neteaseRequestHeader(cookie: String, deviceId: String): JSONObject {
    val cookies = cookie.split(';').mapNotNull { part ->
        val pair = part.trim().split('=', limit = 2)
        if (pair.size == 2) pair[0] to pair[1] else null
    }.toMap()
    return JSONObject().put("os", "pc").put("osver", "Microsoft-Windows-10-Professional-build-19045-64bit")
        .put("appver", "3.0.18.203152").put("deviceId", deviceId)
        .put("versioncode", "203152").put("buildver", "203152")
        .put("resolution", "1920x1080").put("channel", "netease")
        .put("requestId", "${System.currentTimeMillis()}_${UUID.randomUUID()}")
        .put("__csrf", cookies["__csrf"].orEmpty()).apply {
            listOf("MUSIC_U", "MUSIC_A").forEach { name -> cookies[name]?.let { put(name, it) } }
        }
}

internal const val NETEASE_PC_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36 NeteaseMusicDesktop/3.0.18.203152"

// ---------------------------------------------------------------------------
// 播放日志（weblog）专用身份：macOS 客户端。
// 与 X-Music（hoadl114514/X-Music）逐项核对（2026-11-27，其 client_identity.dart
// 原注：「macOS 客户端：播放日志只认它」）：日志通道与包内其他接口分开——
//   - EAPI 载荷里的 header 是 JSON 字符串（不是对象）；
//   - header / Cookie / UA 三处统一为 macOS 档案（os=osx、appver=3.1.10.5100、osver=15.5）；
//   - Cookie 里用户自带的身份字段先剔除，再追加档案字段，MUSIC_U 等登录字段原样保留。
// 只用于 clientlog 的 feedback/weblog，不影响其他接口的 PC 档案。
// ---------------------------------------------------------------------------

internal const val NETEASE_OSX_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private val WEBLOG_IDENTITY_COOKIE_KEYS = setOf("os", "appver", "osver", "channel", "buildver", "versioncode", "sDeviceId")

private fun parseCookieMap(cookie: String): Map<String, String> = cookie.split(';').mapNotNull { part ->
    val pair = part.trim().split('=', limit = 2)
    if (pair.size == 2 && pair[0].isNotBlank()) pair[0] to pair[1] else null
}.toMap()

/** weblog 的 EAPI header（macOS 档案）：字段与 X-Music _eapi 的组装一致，序列化成字符串后进载荷。 */
internal fun neteaseWeblogHeader(cookie: String, deviceId: String): JSONObject {
    val cookies = parseCookieMap(cookie)
    return JSONObject().put("os", "osx").put("appver", "3.1.10.5100").put("osver", "15.5")
        .put("channel", "netease").put("deviceId", deviceId)
        .put("versioncode", "140").put("buildver", (System.currentTimeMillis() / 1000).toString())
        .put("resolution", "1920x1080")
        .put("requestId", (20_000_000 + (Math.random() * 10_000_000).toInt()).toString())
        .apply {
            cookies["MUSIC_U"]?.let { put("MUSIC_U", it) }
            cookies["__csrf"]?.let { put("__csrf", it) }
        }
}

/** weblog 的 HTTP Cookie：剔除用户 Cookie 中的身份字段，追加 macOS 档案字段；登录字段不动。 */
internal fun neteaseWeblogCookie(cookie: String, deviceId: String): String {
    val kept = parseCookieMap(cookie).filterKeys { it !in WEBLOG_IDENTITY_COOKIE_KEYS }
    val merged = kept + linkedMapOf(
        "os" to "osx", "appver" to "3.1.10.5100", "osver" to "15.5",
        "channel" to "netease", "deviceId" to deviceId,
    )
    return merged.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

/** 组装 weblog 的 EAPI 载荷（加密前）：logs 是日志数组的 JSON 字符串，header 是 JSON 字符串。 */
internal fun buildWeblogPayload(action: String, json: JSONObject, cookie: String, deviceId: String): JSONObject {
    json.put("mainsite", "1").put("mainsiteWeb", "1").put("content", "id=")
    val log = JSONObject().put("action", action).put("json", json)
    return JSONObject()
        .put("logs", JSONArray().put(log).toString())
        .put("header", neteaseWeblogHeader(cookie, deviceId).toString())
}
