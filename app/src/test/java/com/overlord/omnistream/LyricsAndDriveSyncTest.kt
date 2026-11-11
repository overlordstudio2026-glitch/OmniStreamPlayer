package com.overlord.omnistream

import com.overlord.omnistream.core.model.PlaylistItem
import com.overlord.omnistream.data.gdrive.GoogleDriveService
import com.overlord.omnistream.data.lyrics.LyricsManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.Charset

class LyricsAndDriveSyncTest {

    @Test
    fun testDriveV3JsonParsingWithMultipleSubtitleFormatsAndAudioPrefix() {
        val service = GoogleDriveService()
        val sampleJson = """
            {
              "files": [
                {"id": "1aaaaaaaaaaaaaaaaaaaaaaaaaa", "name": "01_你的身影_20251010_Fin.mp3", "mimeType": "audio/mpeg"},
                {"id": "1bbbbbbbbbbbbbbbbbbbbbbbbbb", "name": "01_你的身影_歌詞.txt", "mimeType": "text/plain"},
                {"id": "1cccccccccccccccccccccccccc", "name": "01_你的身影_20251010_Fin.lrc", "mimeType": "application/octet-stream"},
                {"id": "1dddddddddddddddddddddddddd", "name": "overlord_founders_audio_ep98_20260701_1600.mp3", "mimeType": "audio/mpeg"},
                {"id": "1eeeeeeeeeeeeeeeeeeeeeeeeee", "name": "overlord_founders_ep98_20260701_1600.srt", "mimeType": "application/x-subrip"},
                {"id": "1ffffffffffffffffffffffffff", "name": "02_demo.m4a", "mimeType": "audio/mp4"},
                {"id": "1gggggggggggggggggggggggggg", "name": "02_demo.vtt", "mimeType": "text/vtt"}
              ]
            }
        """.trimIndent()

        val result = mutableListOf<PlaylistItem>()
        val lyricFiles = mutableListOf<GoogleDriveService.Companion.SidecarFile>()
        val imageFiles = mutableListOf<GoogleDriveService.Companion.SidecarFile>()

        service.parseDriveV3ResponseJson(sampleJson, "folder123", "TestFolder", null, result, lyricFiles, imageFiles)
        GoogleDriveService.attachMatchingSidecars(result, lyricFiles, imageFiles)

        assertEquals(3, result.size)
        assertEquals(3, lyricFiles.size)

        val track1 = result.first { it.title.startsWith("01_") }
        // .lrc 必須優先於 _歌詞.txt 被配對
        assertEquals("https://drive.google.com/uc?export=download&id=1cccccccccccccccccccccccccc", track1.lyricsUri)

        val trackEp98 = result.first { it.title.contains("ep98") }
        // 必須成功跨越 "audio_" 差異並配對 .srt 字幕
        assertEquals("https://drive.google.com/uc?export=download&id=1eeeeeeeeeeeeeeeeeeeeeeeeee", trackEp98.lyricsUri)

        val track2 = result.first { it.title.startsWith("02_") }
        assertEquals("https://drive.google.com/uc?export=download&id=1gggggggggggggggggggggggggg", track2.lyricsUri)
    }

    @Test
    fun testPublicHtmlIvdJsonWithNullMimeAndFullwidthUnderscore() {
        val service = GoogleDriveService()
        // 模擬真實 Google Drive HTML 的 window['_DRIVE_ivd'] 結構：第 4 欄 MIME 為 null，且包含中文與全形底線
        val innerJson = """[["dummy",["11_你的身影 _20260215日文版＿ok.mp3","audio/mpeg"]],["1xxxxxxxxxxxxxxxxxxxxxxxxxx",["folder123"],"11_你的身影 _20260215日文版＿ok.mp3","audio/mpeg",0],["1yyyyyyyyyyyyyyyyyyyyyyyyyy",["folder123"],"11_你的身影 _20260215日文版＿ok.lrc",null,0],["1zzzzzzzzzzzzzzzzzzzzzzzzzz",["folder123"],"11_你的身影_日文版_歌詞.txt","text/plain",0]]"""
        val escaped = innerJson.replace("\"", "\\x22").replace("[", "\\x5b").replace("]", "\\x5d")
        val fakeHtml = "<html><script>window['_DRIVE_ivd'] = '$escaped';</script></html>"

        val result = mutableListOf<PlaylistItem>()
        val lyricFiles = mutableListOf<GoogleDriveService.Companion.SidecarFile>()
        val imageFiles = mutableListOf<GoogleDriveService.Companion.SidecarFile>()

        service.parsePublicHtmlFolder(fakeHtml, "folder123", "Music", result, lyricFiles, imageFiles)
        GoogleDriveService.attachMatchingSidecars(result, lyricFiles, imageFiles)

        assertEquals(1, result.size)
        assertEquals(2, lyricFiles.size)
        // null MIME 的 .lrc 絕不可被丟棄，且優先級高於 .txt
        assertEquals("https://drive.google.com/uc?export=download&id=1yyyyyyyyyyyyyyyyyyyyyyyyyy", result[0].lyricsUri)
    }

    @Test
    fun testPublicHtmlDomFallbackWithNestedTagsAndTypeSuffixes() {
        val service = GoogleDriveService()
        val fakeHtml = """
            <div data-id="1aaaaaaaaaaaaaaaaaaaaaaaaaa" class="card">
                <div class="inner"><span data-tooltip="04_降龍十八掌_20260928_1545_ok_Fin.mp3 Audio"></span></div>
            </div>
            <div data-id="1bbbbbbbbbbbbbbbbbbbbbbbbbb" class="card">
                <div class="inner"><span data-tooltip="04_降龍十八掌_20260928_1545_ok_Fin.lrc 文字"></span></div>
            </div>
        """.trimIndent()

        val result = mutableListOf<PlaylistItem>()
        val lyricFiles = mutableListOf<GoogleDriveService.Companion.SidecarFile>()
        val imageFiles = mutableListOf<GoogleDriveService.Companion.SidecarFile>()

        service.parsePublicHtmlFolder(fakeHtml, "folder123", "Music", result, lyricFiles, imageFiles)
        GoogleDriveService.attachMatchingSidecars(result, lyricFiles, imageFiles)

        assertEquals(1, result.size)
        assertEquals(1, lyricFiles.size)
        assertEquals("https://drive.google.com/uc?export=download&id=1bbbbbbbbbbbbbbbbbbbbbbbbbb", result[0].lyricsUri)
    }

    @Test
    fun testLyricsManagerHttpRedirectAndUtf8Bom() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val lrcContent = "\uFEFF[ti:你的身影]\n[00:12.40]思念是厚重的烏雲\n[00:15.80]悄然地 在心中下雨\n"
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/download/actual.lrc")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(lrcContent))

            val manager = LyricsManager()
            val lines = manager.fetchLyrics(
                cacheKey = "test_redirect_1",
                trackTitle = "你的身影",
                lyricsUri = server.url("/uc?export=download&id=1111").toString()
            )
            assertEquals(2, lines.size)
            assertEquals(12400L, lines[0].timestampMs)
            assertEquals("思念是厚重的烏雲", lines[0].text)
            assertNull(manager.getLastError("test_redirect_1"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testLyricsManagerDiagnosticsOnHtmlBlockPage() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("<!DOCTYPE html><html><body>Sign in</body></html>"))

            val manager = LyricsManager()
            val lines = manager.fetchLyrics(
                cacheKey = "gdrive_fail_test",
                trackTitle = "01_你的身影",
                lyricsUri = server.url("/file.lrc").toString()
            )
            assertTrue(lines.isEmpty())
            val err = manager.getLastError("gdrive_fail_test")
            assertNotNull(err)
            assertTrue(err!!.contains("HTML"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun testLyricsManagerFormatsSrtVttAssAndBig5() {
        val manager = LyricsManager()

        // 1. Big5 位元組解碼檢查
        val big5Text = "[00:05.00]繁體中文測試歌詞\n"
        val big5Bytes = big5Text.toByteArray(Charset.forName("Big5"))
        val decoded = LyricsManager.decodeBytesSmartly(big5Bytes)
        assertTrue(decoded.contains("繁體中文測試歌詞"))

        // 2. SRT 格式解析
        val srt = """
            1
            00:01:02,500 --> 00:01:05,000
            第一句字幕測試

            2
            00:01:06,200 --> 00:01:09,000
            第二句字幕測試
        """.trimIndent()
        val srtLines = manager.parseAnyLyricsFormat(srt)
        assertEquals(2, srtLines.size)
        assertEquals(62500L, srtLines[0].timestampMs)
        assertEquals("第一句字幕測試", srtLines[0].text)

        // 3. WebVTT (.vtt) 格式解析 (含無小時格式與標籤去除)
        val vtt = """
            WEBVTT

            00:10.500 --> 00:13.000
            <b>WebVTT 字幕一</b>

            01:02.000 --> 01:05.500
            <i>WebVTT 字幕二</i>
        """.trimIndent()
        val vttLines = manager.parseAnyLyricsFormat(vtt)
        assertEquals(2, vttLines.size)
        assertEquals(10500L, vttLines[0].timestampMs)
        assertEquals("WebVTT 字幕一", vttLines[0].text)
        assertEquals(62000L, vttLines[1].timestampMs)

        // 4. ASS / SSA 特效字幕解析
        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:15.20,0:00:18.50,Default,,0,0,0,,{\fad(200,200)}ASS 特效歌詞\N第二行
        """.trimIndent()
        val assLines = manager.parseAnyLyricsFormat(ass)
        assertEquals(1, assLines.size)
        assertEquals(15200L, assLines[0].timestampMs)
        assertEquals("ASS 特效歌詞 第二行", assLines[0].text)
    }

    @Test
    fun testCompanyStructuredTxtDynamicMapping() {
        val manager = LyricsManager()
        val rawTxt = """
            [intro]

            [Verse 1]
            思念是厚重的烏雲
            悄然地 在心中下雨

            [Chorus]
            青春的腳印 說好並肩而行
        """.trimIndent()
        val lines = manager.parseAnyLyricsFormat(rawTxt, durationSec = 200L)
        assertTrue(lines.size >= 4)
        assertTrue(lines[0].text.contains("間奏"))
        assertTrue(lines.last().timestampMs in 60_000L..190_000L)
    }
}
