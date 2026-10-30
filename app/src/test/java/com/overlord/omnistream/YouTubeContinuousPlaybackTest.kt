package com.overlord.omnistream

import org.junit.Assert.*
import org.junit.Test

class YouTubeContinuousPlaybackTest {

    data class MockMediaItem(
        val mediaId: String,
        val uri: String
    )

    private fun needsFreshExtraction(item: MockMediaItem): Boolean {
        return item.mediaId.startsWith("yt_") &&
                (item.uri.contains("youtube.com/watch") || item.uri.isBlank())
    }

    private fun simulatePreloadNext(
        currentIndex: Int,
        playlist: List<MockMediaItem>
    ): MockMediaItem? {
        val nextIndex = currentIndex + 1
        if (nextIndex < playlist.size) {
            val next = playlist[nextIndex]
            if (needsFreshExtraction(next)) {
                return next
            }
        }
        return null
    }

    @Test
    fun testDetectUnextractedYouTubeItem() {
        val rawItem = MockMediaItem("yt_12345678901", "https://www.youtube.com/watch?v=12345678901")
        assertTrue(needsFreshExtraction(rawItem))

        val blankItem = MockMediaItem("yt_12345678901", "")
        assertTrue(needsFreshExtraction(blankItem))

        val resolvedItem = MockMediaItem("yt_12345678901", "https://rr1---sn-xxx.googlevideo.com/videoplayback?expire=123")
        assertFalse(needsFreshExtraction(resolvedItem))

        val localItem = MockMediaItem("local_1", "content://media/external/audio/media/1")
        assertFalse(needsFreshExtraction(localItem))
    }

    @Test
    fun testPreloadNextItemInQueue() {
        val playlist = listOf(
            MockMediaItem("yt_first", "https://rr1---sn-xxx.googlevideo.com/videoplayback?expire=123"),
            MockMediaItem("yt_second", "https://www.youtube.com/watch?v=second"),
            MockMediaItem("yt_third", "https://www.youtube.com/watch?v=third")
        )

        val nextToPreload = simulatePreloadNext(0, playlist)
        assertNotNull(nextToPreload)
        assertEquals("yt_second", nextToPreload?.mediaId)

        // 若下一首已經是有效直鏈，則不需要重複 preload
        val resolvedPlaylist = listOf(
            MockMediaItem("yt_first", "https://rr1---sn-xxx.googlevideo.com/videoplayback?expire=123"),
            MockMediaItem("yt_second", "https://rr2---sn-xxx.googlevideo.com/videoplayback?expire=456")
        )
        val nextResolved = simulatePreloadNext(0, resolvedPlaylist)
        assertNull(nextResolved)
    }
}
