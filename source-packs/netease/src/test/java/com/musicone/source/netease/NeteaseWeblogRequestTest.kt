package com.musicone.source.netease

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * 播放日志（weblog）请求结构：与 X-Music 的实现逐项对齐——
 * header 必须是 JSON 字符串且为 macOS 档案、Cookie 剔除用户身份字段后追加 macOS 档案、
 * play 门槛与诊断行格式。测试数据里的 Cookie 是假值，只验结构，不涉及真实凭据。
 */
class NeteaseWeblogRequestTest {
    private val fakeCookie = "MUSIC_U=fake-u; MUSIC_A=fake-a; __csrf=fake-csrf; os=pc; appver=9.9.9; osver=win; channel=netease; buildver=1; versioncode=2; sDeviceId=zzz; NMTID=keepme"
    private val deviceId = "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCD"

    @Test fun weblogPayloadHeaderIsJsonStringWithOsxProfile() {
        val payload = buildWeblogPayload("startplay",
            JSONObject().put("id", 123456L).put("type", "song"), fakeCookie, deviceId)
        val headerRaw = payload.get("header")
        assertTrue("header 必须是字符串而不是对象", headerRaw is String)
        val header = JSONObject(headerRaw as String)
        assertEquals("osx", header.getString("os"))
        assertEquals("3.1.10.5100", header.getString("appver"))
        assertEquals("15.5", header.getString("osver"))
        assertEquals("netease", header.getString("channel"))
        assertEquals("140", header.getString("versioncode"))
        assertEquals(deviceId, header.getString("deviceId"))
        assertEquals("fake-u", header.getString("MUSIC_U"))
        assertEquals("fake-csrf", header.getString("__csrf"))
    }

    @Test fun weblogPayloadLogsWrapActionAndJson() {
        val payload = buildWeblogPayload("play", JSONObject()
            .put("download", 0).put("end", "playend").put("id", 42L).put("sourceId", "")
            .put("time", 213L).put("type", "song").put("wifi", 0).put("source", "list"),
            fakeCookie, deviceId)
        val logs = JSONArray(payload.getString("logs"))
        assertEquals(1, logs.length())
        val entry = logs.getJSONObject(0)
        assertEquals("play", entry.getString("action"))
        val json = entry.getJSONObject("json")
        assertEquals(42L, json.getLong("id"))
        assertEquals(213L, json.getLong("time"))
        assertEquals("playend", json.getString("end"))
        assertEquals("1", json.getString("mainsite"))
        assertEquals("1", json.getString("mainsiteWeb"))
        assertEquals("id=", json.getString("content"))
    }

    @Test fun weblogCookieStripsIdentityKeysAndAppendsOsxProfile() {
        val result = neteaseWeblogCookie(fakeCookie, deviceId)
        val pairs = result.split(';').map { it.trim() }.associate {
            val kv = it.split('=', limit = 2); kv[0] to kv[1]
        }
        // 登录与无关字段保留
        assertEquals("fake-u", pairs["MUSIC_U"])
        assertEquals("fake-a", pairs["MUSIC_A"])
        assertEquals("fake-csrf", pairs["__csrf"])
        assertEquals("keepme", pairs["NMTID"])
        // 身份字段被 macOS 档案覆盖，用户自带值不得残留
        assertEquals("osx", pairs["os"])
        assertEquals("3.1.10.5100", pairs["appver"])
        assertEquals("15.5", pairs["osver"])
        assertEquals("netease", pairs["channel"])
        assertEquals(deviceId, pairs["deviceId"])
        assertFalse(result.contains("9.9.9"))
        assertFalse(result.contains("sDeviceId"))
        assertFalse(result.contains("buildver"))
        assertFalse(result.contains("versioncode"))
    }

    @Test fun playThresholdIsMinOfHalfAndThirtySeconds() {
        assertEquals(30_000L, neteasePlayThresholdMs(240_000L))
        assertEquals(20_000L, neteasePlayThresholdMs(40_000L))
        assertEquals(10_000L, neteasePlayThresholdMs(20_000L))
        assertEquals(30_000L, neteasePlayThresholdMs(0L))
    }

    @Test fun syncDiagTextCarriesTimeTypeSecondsAndResult() {
        val text = syncDiagText("16:20:31", "play", 213, "请求成功")
        assertEquals("16:20:31 · play · 累计 213 秒 · 请求成功", text)
        assertFalse(text.contains("MUSIC_U"))
        assertFalse(text.contains("Cookie"))
    }
}
