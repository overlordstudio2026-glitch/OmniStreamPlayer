package com.overlord.omnistream

import com.overlord.omnistream.data.youtube.YouTubePlaylistParser
import com.overlord.omnistream.data.youtube.YouTubeRssParser
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeUrlAndFilterTest {

    @Test
    fun testExtractChannelId() {
        val url = "https://www.youtube.com/channel/UC_x5XG1OV2P6uZZ5FSM9Ttw"
        val expected = "UC_x5XG1OV2P6uZZ5FSM9Ttw"
        assertEquals(expected, YouTubeRssParser.extractChannelId(url))
    }

    @Test
    fun testExtractPlaylistId() {
        val url = "https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
        val expected = "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
        assertEquals(expected, YouTubePlaylistParser.extractPlaylistId(url))
    }

    @Test
    fun testExtractVideoIdFormats() {
        val extractor = com.overlord.omnistream.data.youtube.YouTubeAudioExtractor
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("yt_dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://youtu.be/dQw4w9WgXcQ?si=abcdef12345"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", extractor.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
    }
}
