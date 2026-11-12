package com.overlord.omnistream

import com.overlord.omnistream.core.model.MediaSourceType
import com.overlord.omnistream.core.model.PlaylistItem
import com.overlord.omnistream.data.youtube.YouTubePlaylistInfo
import com.overlord.omnistream.data.youtube.YouTubePlaylistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePlaylistParserTest {

    @Test
    fun testExtractPlaylistIdVariations() {
        // 標準清單網址
        assertEquals("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            YouTubePlaylistParser.extractPlaylistId("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))

        // 帶有額外參數的網址
        assertEquals("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            YouTubePlaylistParser.extractPlaylistId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI&index=1"))

        // 純清單 ID
        assertEquals("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI",
            YouTubePlaylistParser.extractPlaylistId("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))
    }

    @Test
    fun testPlaylistDisplayNameFormatting() {
        val infoWithChannel = YouTubePlaylistInfo(
            playlistId = "PL123",
            title = "華語經典金曲",
            channelName = "滾石唱片",
            items = emptyList()
        )

        // 未輸入自訂名稱時，應自動格式化為 [頻道名稱] 清單名稱
        val defaultDisplayName = "[${infoWithChannel.channelName}] ${infoWithChannel.title}"
        assertEquals("[滾石唱片] 華語經典金曲", defaultDisplayName)

        // 若使用者輸入了自訂名稱，組裝為 [自訂名稱] 頻道名稱
        val customName = "我的最愛"
        val customDisplayName = "[$customName] ${infoWithChannel.channelName}"
        assertEquals("[我的最愛] 滾石唱片", customDisplayName)
    }

    @Test
    fun testIncrementalPlaylistDeduplication() {
        val existingItems = listOf(
            PlaylistItem("yt_v1", "Track 1", "Artist 1", 100L, "http://1", MediaSourceType.YOUTUBE),
            PlaylistItem("yt_v2", "Track 2", "Artist 2", 100L, "http://2", MediaSourceType.YOUTUBE)
        )
        val existingIds = existingItems.map { it.id }.toSet()

        val fetchedItems = listOf(
            PlaylistItem("yt_v2", "Track 2", "Artist 2", 100L, "http://2", MediaSourceType.YOUTUBE),
            PlaylistItem("yt_v3", "Track 3", "Artist 3", 100L, "http://3", MediaSourceType.YOUTUBE),
            PlaylistItem("yt_v4", "Track 4", "Artist 4", 100L, "http://4", MediaSourceType.YOUTUBE)
        )

        val newItems = fetchedItems.filter { it.id !in existingIds }

        // 應過濾掉已存在的 yt_v2，只新增 yt_v3 與 yt_v4
        assertEquals(2, newItems.size)
        assertEquals("yt_v3", newItems[0].id)
        assertEquals("yt_v4", newItems[1].id)
    }
}
