package com.overlord.omnistream.data.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

data class LyricsCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Long,
    val isSynced: Boolean,
    val lines: List<LyricLine>
)

/**
 * 動態同步歌詞管理器（全格式 + 調校記憶 + 打軸覆寫 + 多版本搜尋支援版）：
 * 1. 最優先讀取使用者「邊聽邊打軸 / 手動挑選」儲存的本機自訂 LRC
 * 2. 其次讀取曲目自帶的雲端 / 本機歌詞檔案 (lyricsUri)
 * 3. 支援標準 .lrc (含單行多標籤與 offset)、.srt 字幕、以及無時間軸結構化/純文字自動映射
 * 4. 若無專屬檔案，查詢全球開源歌詞庫 LRCLIB（含多版本候選搜尋與單曲偏移記憶）
 */
class LyricsManager(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        private const val TAG = "LyricsManager"
        private const val LRCLIB_API = "https://lrclib.net/api/get"
        private const val LRCLIB_SEARCH_API = "https://lrclib.net/api/search"
        private const val PREFS_OFFSET = "omnistream_lyrics_prefs"
        private const val CUSTOM_LYRICS_DIR = "custom_lyrics"
        private val LRC_TAG_PATTERN = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
        private val SRT_TIME_PATTERN = Pattern.compile("(?:(\\d{1,2}):)?(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->")
        private val SECTION_TAG_PATTERN = Pattern.compile("^\\[(intro|verse|chorus|bridge|inter|outro|solo|pre-chorus|hook|instrumental).*\\]$", Pattern.CASE_INSENSITIVE)
    }

    private val lyricsCache = ConcurrentHashMap<String, List<LyricLine>>()

    // --- 單曲偏移量記憶 (ms) ---
    fun getOffsetMs(context: Context, mediaId: String): Long {
        return context.getSharedPreferences(PREFS_OFFSET, Context.MODE_PRIVATE)
            .getLong("offset_$mediaId", 0L)
    }

    fun saveOffsetMs(context: Context, mediaId: String, offsetMs: Long) {
        context.getSharedPreferences(PREFS_OFFSET, Context.MODE_PRIVATE)
            .edit()
            .putLong("offset_$mediaId", offsetMs)
            .apply()
    }

    // --- 單曲打軸 / 自訂版本本機持久化 LRC ---
    private fun getCustomLrcFile(context: Context, mediaId: String): File {
        val dir = File(context.filesDir, CUSTOM_LYRICS_DIR)
        if (!dir.exists()) dir.mkdirs()
        val safeName = mediaId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$safeName.lrc")
    }

    fun hasCustomSyncedLrc(context: Context, mediaId: String): Boolean {
        val f = getCustomLrcFile(context, mediaId)
        return f.exists() && f.length() > 10L
    }

    fun saveCustomSyncedLrc(context: Context, mediaId: String, lines: List<LyricLine>) {
        val sorted = lines.sortedBy { it.timestampMs }
        val sb = StringBuilder()
        for (ln in sorted) {
            val totalMs = ln.timestampMs.coerceAtLeast(0L)
            val min = totalMs / 60_000L
            val sec = (totalMs % 60_000L) / 1000L
            val cs = (totalMs % 1000L) / 10L
            sb.append(String.format(Locale.US, "[%02d:%02d.%02d]%s\n", min, sec, cs, ln.text))
        }
        getCustomLrcFile(context, mediaId).writeText(sb.toString(), Charsets.UTF_8)
        invalidateCache(mediaId)
    }

    fun loadCustomSyncedLrc(context: Context, mediaId: String): List<LyricLine>? {
        val f = getCustomLrcFile(context, mediaId)
        if (!f.exists() || !f.canRead()) return null
        val parsed = parseLrc(f.readText(Charsets.UTF_8))
        return parsed.takeIf { it.isNotEmpty() }
    }

    fun clearCustomSyncedLrc(context: Context, mediaId: String) {
        val f = getCustomLrcFile(context, mediaId)
        if (f.exists()) f.delete()
        invalidateCache(mediaId)
    }

    fun invalidateCache(mediaIdPrefix: String) {
        val keys = lyricsCache.keys.filter { it.startsWith("$mediaIdPrefix|") }
        keys.forEach { lyricsCache.remove(it) }
    }

    /**
     * 嘗試獲取指定曲目的時間軸同步歌詞
     */
    suspend fun fetchLyrics(
        cacheKey: String,
        trackTitle: String,
        artistName: String = "",
        durationSec: Long = 0L,
        lyricsUri: String? = null,
        accessToken: String? = null,
        context: Context? = null
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        // 0. 最優先檢查使用者是否有親手打軸或選定的本機覆寫版本
        if (context != null) {
            loadCustomSyncedLrc(context, cacheKey)?.let { custom ->
                Log.i(TAG, "Loaded ${custom.size} custom/synced lines for $trackTitle")
                return@withContext custom
            }
        }

        val effectiveCacheKey = "${cacheKey}|${lyricsUri.orEmpty()}|${if (durationSec > 0L) 1 else 0}"
        lyricsCache[effectiveCacheKey]?.let { return@withContext it }

        // 1. 若有綁定專屬雲端或本機歌詞檔 (lyricsUri)，優先讀取並自動依格式解析
        if (!lyricsUri.isNullOrBlank()) {
            val rawText = readLyricsFromUri(lyricsUri, accessToken, context)
            if (!rawText.isNullOrBlank()) {
                val parsed = parseAnyLyricsFormat(rawText, durationSec)
                if (parsed.isNotEmpty()) {
                    lyricsCache[effectiveCacheKey] = parsed
                    Log.i(TAG, "Loaded ${parsed.size} lines from dedicated lyricsUri for $trackTitle")
                    return@withContext parsed
                }
            }
        }

        // 2. 回退查詢開源歌詞庫 LRCLIB
        val cleanTitle = cleanTrackTitle(trackTitle)
        val cleanArtist = artistName.replace("YouTube.*".toRegex(), "").trim()

        try {
            val urlBuilder = LRCLIB_API.toHttpUrlOrNull()?.newBuilder() ?: return@withContext emptyList()
            urlBuilder.addQueryParameter("track_name", cleanTitle)
            if (cleanArtist.isNotBlank()) {
                urlBuilder.addQueryParameter("artist_name", cleanArtist)
            }
            if (durationSec > 0) {
                urlBuilder.addQueryParameter("duration", durationSec.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .addHeader("User-Agent", "OmniStreamPlayer/2026 (https://github.com/overlordstudio2026-glitch/OmniStreamPlayer)")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val syncedLrc = json.optString("syncedLyrics").trim()
                if (syncedLrc.isNotBlank()) {
                    val lines = parseLrc(syncedLrc)
                    if (lines.isNotEmpty()) {
                        lyricsCache[effectiveCacheKey] = lines
                        Log.i(TAG, "Loaded ${lines.size} synced lyric lines from LRCLIB for $cleanTitle")
                        return@withContext lines
                    }
                }

                // 若無同步歌詞，回退至純文字歌詞自動分佈時間軸
                val plainLyrics = json.optString("plainLyrics").trim()
                if (plainLyrics.isNotBlank()) {
                    val fallbackLines = parsePlainOrStructuredLyrics(plainLyrics, durationSec)
                    if (fallbackLines.isNotEmpty()) {
                        lyricsCache[effectiveCacheKey] = fallbackLines
                        return@withContext fallbackLines
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching lyrics for $cleanTitle: ${e.message}")
        }
        return@withContext emptyList()
    }

    /**
     * 搜尋多個歌詞候選版本（供使用者切換 Live 版 / MV 版 / Studio 版或替換錯誤歌詞）
     */
    suspend fun searchLyricsCandidates(
        trackTitle: String,
        artistName: String = "",
        durationSec: Long = 0L
    ): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(trackTitle)
        if (cleanTitle.isBlank()) return@withContext emptyList()

        val result = mutableListOf<LyricsCandidate>()
        try {
            val urlBuilder = LRCLIB_SEARCH_API.toHttpUrlOrNull()?.newBuilder() ?: return@withContext emptyList()
            urlBuilder.addQueryParameter("q", cleanTitle)

            val request = Request.Builder()
                .url(urlBuilder.build())
                .addHeader("User-Agent", "OmniStreamPlayer/2026 (https://github.com/overlordstudio2026-glitch/OmniStreamPlayer)")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val arr = JSONArray(body)
                val limit = arr.length().coerceAtMost(12)
                for (i in 0 until limit) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optLong("id", i.toLong())
                    val tName = obj.optString("trackName", cleanTitle)
                    val aName = obj.optString("artistName", artistName)
                    val album = obj.optString("albumName", "")
                    val dur = obj.optDouble("duration", 0.0).toLong()
                    val syncedStr = obj.optString("syncedLyrics", "").trim()
                    val plainStr = obj.optString("plainLyrics", "").trim()

                    val parsed = when {
                        syncedStr.isNotBlank() -> parseLrc(syncedStr)
                        plainStr.isNotBlank() -> parsePlainOrStructuredLyrics(plainStr, if (dur > 0L) dur else durationSec)
                        else -> emptyList()
                    }
                    if (parsed.isNotEmpty()) {
                        result.add(
                            LyricsCandidate(
                                id = id,
                                trackName = tName,
                                artistName = aName,
                                albumName = album,
                                durationSec = dur,
                                isSynced = syncedStr.isNotBlank(),
                                lines = parsed
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed searching lyrics candidates for $cleanTitle: ${e.message}")
        }
        // 優先將帶有精準同步時間軸且時長最接近的排在前面
        result.sortedWith(compareByDescending<LyricsCandidate> { it.isSynced }.thenBy {
            if (durationSec > 0L && it.durationSec > 0L) kotlin.math.abs(it.durationSec - durationSec) else 9999L
        })
    }

    private fun readLyricsFromUri(uriStr: String, accessToken: String?, context: Context?): String? {
        return try {
            when {
                uriStr.startsWith("http://") || uriStr.startsWith("https://") -> {
                    val primaryText = fetchHttpText(uriStr, accessToken)
                    if (!primaryText.isNullOrBlank()) return primaryText

                    // Google Drive 雙重備援：若 uc?export=download 遭到限制或回傳 HTML，嘗試 Drive v3 alt=media 直接拉取原文
                    val gdriveIdMatch = Regex("[?&]id=([a-zA-Z0-9_-]{25,})").find(uriStr)
                        ?: Regex("/files/([a-zA-Z0-9_-]{25,})").find(uriStr)
                    val fileId = gdriveIdMatch?.groupValues?.get(1)
                    if (!fileId.isNullOrBlank()) {
                        val apiUrl = if (!accessToken.isNullOrBlank()) {
                            "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
                        } else {
                            "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&key=${com.overlord.omnistream.data.gdrive.GoogleDriveService.PUBLIC_DRIVE_API_KEY}"
                        }
                        val fallbackText = fetchHttpText(apiUrl, accessToken)
                        if (!fallbackText.isNullOrBlank()) return fallbackText
                    }
                    null
                }
                uriStr.startsWith("content://") && context != null -> {
                    context.contentResolver.openInputStream(Uri.parse(uriStr))?.bufferedReader()?.use { it.readText() }
                }
                else -> {
                    val filePath = if (uriStr.startsWith("file://")) Uri.parse(uriStr).path else uriStr
                    if (!filePath.isNullOrBlank()) {
                        val f = File(filePath)
                        if (f.exists() && f.canRead()) f.readText() else null
                    } else null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read lyrics from $uriStr: ${e.message}")
            null
        }
    }

    private fun fetchHttpText(url: String, accessToken: String?): String? {
        return try {
            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            if (!accessToken.isNullOrBlank() && url.contains("googleapis.com")) {
                reqBuilder.addHeader("Authorization", "Bearer $accessToken")
            }
            val resp = client.newCall(reqBuilder.build()).execute()
            if (resp.isSuccessful) {
                val content = resp.body?.string().orEmpty()
                if (content.contains("<!DOCTYPE html>", ignoreCase = true) || content.contains("<html", ignoreCase = true)) {
                    null
                } else {
                    content
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 多格式智慧分派解析器：支援標準 LRC、SRT 字幕、與結構化純文字
     */
    fun parseAnyLyricsFormat(rawText: String, durationSec: Long = 0L): List<LyricLine> {
        val cleaned = rawText.removePrefix("\uFEFF").trim()
        if (cleaned.isBlank()) return emptyList()

        // 1. 檢查是否為標準 LRC 格式 ([mm:ss.xx])
        if (LRC_TAG_PATTERN.matcher(cleaned).find()) {
            val lrcLines = parseLrc(cleaned)
            if (lrcLines.isNotEmpty()) return lrcLines
        }

        // 2. 檢查是否為 SRT 字幕格式 (--> 時間箭頭)
        if (SRT_TIME_PATTERN.matcher(cleaned).find()) {
            val srtLines = parseSrt(cleaned)
            if (srtLines.isNotEmpty()) return srtLines
        }

        // 3. 其餘為純文字或帶有 [Verse]/[Chorus] 段落標記的文字歌詞 -> 智慧結構時間軸映射
        return parsePlainOrStructuredLyrics(cleaned, durationSec)
    }

    /**
     * 解析標準 LRC 格式（支援單行多時間標籤如 [01:12.00][02:45.00]副歌 與 [offset:] 偏移）
     */
    fun parseLrc(lrcContent: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        var offsetMs = 0L

        for (rawLine in lrcContent.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) continue

            if (trimmed.startsWith("[offset:", ignoreCase = true) && trimmed.endsWith("]")) {
                offsetMs = trimmed.substring(8, trimmed.length - 1).trim().toLongOrNull() ?: 0L
                continue
            }
            if (trimmed.matches(Regex("^\\[(ti|ar|al|by|re|ve):.*\\]$", RegexOption.IGNORE_CASE))) {
                continue
            }

            val matcher = LRC_TAG_PATTERN.matcher(trimmed)
            val timestamps = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = matcher.group(3) ?: "0"
                val ms = when (msStr.length) {
                    1 -> (msStr.toLongOrNull() ?: 0L) * 100L
                    2 -> (msStr.toLongOrNull() ?: 0L) * 10L
                    3 -> msStr.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val ts = ((min * 60_000L) + (sec * 1000L) + ms - offsetMs).coerceAtLeast(0L)
                timestamps.add(ts)
                lastMatchEnd = matcher.end()
            }

            if (timestamps.isNotEmpty()) {
                val text = trimmed.substring(lastMatchEnd).trim()
                if (text.isNotBlank()) {
                    for (ts in timestamps) {
                        result.add(LyricLine(ts, text))
                    }
                }
            }
        }
        return result.sortedBy { it.timestampMs }
    }

    /**
     * 解析 SRT 字幕格式 (00:01:23,450 --> 00:01:27,000)
     */
    fun parseSrt(srtContent: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val blocks = srtContent.split(Regex("\\r?\\n\\r?\\n"))
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val timeLineIdx = lines.indexOfFirst { SRT_TIME_PATTERN.matcher(it).find() }
            if (timeLineIdx == -1) continue

            val matcher = SRT_TIME_PATTERN.matcher(lines[timeLineIdx])
            if (matcher.find()) {
                val hr = matcher.group(1)?.toLongOrNull() ?: 0L
                val min = matcher.group(2)?.toLongOrNull() ?: 0L
                val sec = matcher.group(3)?.toLongOrNull() ?: 0L
                val ms = matcher.group(4)?.toLongOrNull() ?: 0L
                val ts = (hr * 3_600_000L) + (min * 60_000L) + (sec * 1000L) + ms
                val text = lines.drop(timeLineIdx + 1).joinToString(" ").trim()
                if (text.isNotBlank()) {
                    result.add(LyricLine(ts, text))
                }
            }
        }
        return result.sortedBy { it.timestampMs }
    }

    /**
     * 解析無時間戳之純文字與段落結構化歌詞 (如公司原創 [intro]/[Verse]/[Chorus] .txt)
     * 依據曲目真實時長與句子長度權重自動生成自然時間軸
     */
    fun parsePlainOrStructuredLyrics(rawText: String, durationSec: Long = 0L): List<LyricLine> {
        data class ParsedSegment(val isBreak: Boolean, val text: String, val weight: Double)

        val segments = mutableListOf<ParsedSegment>()
        for (line in rawText.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            if (SECTION_TAG_PATTERN.matcher(trimmed).matches()) {
                val lower = trimmed.lowercase()
                val breakWeight = when {
                    lower.contains("intro") -> 14.0
                    lower.contains("inter") || lower.contains("solo") -> 12.0
                    lower.contains("outro") -> 10.0
                    else -> 2.0
                }
                if (lower.contains("intro") || lower.contains("inter") || lower.contains("solo")) {
                    segments.add(ParsedSegment(true, "♪ 間奏 / 樂器演奏 ♪", breakWeight))
                } else {
                    segments.add(ParsedSegment(true, "", breakWeight))
                }
            } else {
                val weight = 4.0 + (trimmed.length * 0.35)
                segments.add(ParsedSegment(false, trimmed, weight))
            }
        }

        val totalWeight = segments.sumOf { it.weight }.coerceAtLeast(1.0)
        val totalDurationMs = if (durationSec > 10L) (durationSec * 1000L * 0.94).toLong() else 210_000L

        val result = mutableListOf<LyricLine>()
        var accumulatedWeight = 0.0
        for (seg in segments) {
            val timestampMs = ((accumulatedWeight / totalWeight) * totalDurationMs).toLong()
            if (seg.text.isNotBlank()) {
                result.add(LyricLine(timestampMs, seg.text))
            }
            accumulatedWeight += seg.weight
        }
        return result
    }

    private fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("\\[.*?\\]|\\(.*?\\)|【.*?】"), "")
            .replace(Regex("(?i)(official\\s*(music)?\\s*video|audio|mv|lyrics?|full\\s*version|hq|hd)"), "")
            .trim()
    }

    fun clear() {
        lyricsCache.clear()
    }
}
