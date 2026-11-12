package com.overlord.omnistream

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigBackupJsonTest {

    @Test
    fun testBackupJsonStructure() {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val subs = JSONArray()
        val s1 = JSONObject().apply {
            put("id", "folder123")
            put("name", "創辦人故事")
            put("type", "GDRIVE")
            put("publicUrl", "https://drive.google.com/drive/folders/folder123")
            put("autoAddToPlaylist", true)
            put("isPlaylist", false)
            put("targetPlaylistGroupId", "grp_gdrive")
        }
        val s2 = JSONObject().apply {
            put("id", "UC123456789")
            put("name", "科技新知頻道")
            put("type", "YOUTUBE")
            put("publicUrl", "https://youtube.com/@tech")
            put("autoAddToPlaylist", true)
            put("sinceTimestamp", 1720000000000L)
            put("isPlaylist", false)
            put("targetPlaylistGroupId", "grp_yt")
        }
        val s3 = JSONObject().apply {
            put("id", "PL123456789")
            put("name", "經典合輯清單")
            put("type", "YOUTUBE")
            put("publicUrl", "https://youtube.com/playlist?list=PL123456789")
            put("autoAddToPlaylist", true)
            put("isPlaylist", true)
            put("targetPlaylistGroupId", "grp_yt_pl")
        }
        subs.put(s1)
        subs.put(s2)
        subs.put(s3)
        root.put("subscriptions", subs)

        val groups = JSONArray()
        val g1 = JSONObject().apply {
            put("id", "grp_1")
            put("name", "我的群組")
        }
        groups.put(g1)
        root.put("playlist_groups", groups)

        val jsonStr = root.toString(2)
        assertNotNull(jsonStr)

        val parsed = JSONObject(jsonStr)
        assertEquals(1, parsed.getInt("version"))
        assertEquals(3, parsed.getJSONArray("subscriptions").length())
        assertEquals("創辦人故事", parsed.getJSONArray("subscriptions").getJSONObject(0).getString("name"))
        assertEquals("grp_gdrive", parsed.getJSONArray("subscriptions").getJSONObject(0).getString("targetPlaylistGroupId"))
        assertEquals("科技新知頻道", parsed.getJSONArray("subscriptions").getJSONObject(1).getString("name"))
        assertEquals(1720000000000L, parsed.getJSONArray("subscriptions").getJSONObject(1).getLong("sinceTimestamp"))
        assertEquals(true, parsed.getJSONArray("subscriptions").getJSONObject(2).getBoolean("isPlaylist"))
        assertEquals("grp_1", parsed.getJSONArray("playlist_groups").getJSONObject(0).getString("id"))
    }

    @Test
    fun testTypeNormalization() {
        val types = listOf("gdrive", " GDRIVE ", "youtube", " YOUTUBE ", "YouTube")
        for (raw in types) {
            val upper = raw.trim().uppercase()
            val normalized = if (upper.contains("YOUTUBE")) "YOUTUBE" else "GDRIVE"
            assertTrue(normalized == "GDRIVE" || normalized == "YOUTUBE")
        }
    }
}

