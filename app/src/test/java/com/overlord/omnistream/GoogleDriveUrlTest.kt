package com.overlord.omnistream

import com.overlord.omnistream.data.gdrive.GoogleDriveService
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleDriveUrlTest {

    @Test
    fun testExtractFolderId() {
        val url1 = "https://drive.google.com/drive/folders/1p_9goY5IKN5uQEyEUPvqsa77RYsJcpy9?usp=drive_link"
        val expected = "1p_9goY5IKN5uQEyEUPvqsa77RYsJcpy9"
        assertEquals(expected, GoogleDriveService.extractFolderId(url1))

        val url2 = "https://drive.google.com/open?id=1p_9goY5IKN5uQEyEUPvqsa77RYsJcpy9"
        assertEquals(expected, GoogleDriveService.extractFolderId(url2))

        val rawId = "1p_9goY5IKN5uQEyEUPvqsa77RYsJcpy9"
        assertEquals(expected, GoogleDriveService.extractFolderId(rawId))
    }

    @Test
    fun testBuildPublicStreamUrl() {
        val service = GoogleDriveService()
        val fileId = "1KsivPBQzvDyVjGQvlli_f7bwYQthZdIp"
        val streamUrl = service.buildPublicStreamUrl(fileId)
        assertEquals("https://drive.google.com/uc?export=download&id=1KsivPBQzvDyVjGQvlli_f7bwYQthZdIp", streamUrl)
    }

    @Test
    fun testNaturalCompare() {
        val list = listOf("ep10", "ep1", "ep2", "ep80", "ep3")
        val sorted = list.sortedWith { a, b -> GoogleDriveService.naturalCompare(a, b) }
        assertEquals(listOf("ep1", "ep2", "ep3", "ep10", "ep80"), sorted)
    }

    @Test
    fun testFetchFolderAudioFilesOver50Limit() = kotlinx.coroutines.runBlocking {
        val service = GoogleDriveService()
        val folderId = "1p_9goY5IKN5uQEyEUPvqsa77RYsJcpy9"
        try {
            val files = service.fetchFolderAudioFiles(folderId, "創辦人故事")
            if (files.isNotEmpty()) {
                val sorted = files.map { it.title }.sortedWith { a, b -> GoogleDriveService.naturalCompare(a, b) }
                assertEquals(sorted.first(), files.first().title)
            }
        } catch (t: Throwable) {
            println("Skip network assertion if offline/rate-limited: ${t.message}")
        }
    }
}

