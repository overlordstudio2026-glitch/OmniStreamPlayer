package com.overlord.omnistream.data.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

/**
 * 動態同步歌詞管理器：
 * 整合全球開源歌詞庫 LRCLIB 與時間戳 LRC 解析器，支援精準時間軸滾動與字詞對齊
 */
class LyricsManager(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        private const val TAG = "LyricsManager"
        private const val LRCLIB_API = "https://lrclib.net/api/get"
        private val LRC_LINE_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{2,3}))?\\](.*)")
    }

    private val lyricsCache = ConcurrentHashMap<String, List<LyricLine>>()

    /**
     * 嘗試獲取指定曲目的時間軸同步歌詞
     */
    suspend fun fetchLyrics(
        cacheKey: String,
        trackTitle: String,
        artistName: String = "",
        durationSec: Long = 0L
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        lyricsCache[cacheKey]?.let { return@withContext it }

        // 清理標題（去除 (Official Music Video)、【MV】、ft. 等雜訊提高比對命中率）
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
                        lyricsCache[cacheKey] = lines
                        Log.i(TAG, "Loaded ${lines.size} synced lyric lines for $cleanTitle")
                        return@withContext lines
                    }
                }

                // 若無同步歌詞，回退至純文字歌詞（以虛擬時間戳呈現）
                val plainLyrics = json.optString("plainLyrics").trim()
                if (plainLyrics.isNotBlank()) {
                    val fallbackLines = plainLyrics.lines().mapIndexed { idx, line ->
                        LyricLine(idx * 5000L, line)
                    }
                    lyricsCache[cacheKey] = fallbackLines
                    return@withContext fallbackLines
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching lyrics for $cleanTitle: ${e.message}")
        }
        return@withContext emptyList()
    }

    /**
     * 解析 LRC 格式字串為帶有毫秒時間戳的清單
     */
    fun parseLrc(lrcContent: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (line in lrcContent.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") || trimmed.startsWith("[al:")) {
                continue
            }
            val matcher = LRC_LINE_PATTERN.matcher(trimmed)
            if (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = matcher.group(3) ?: "0"
                val ms = when (msStr.length) {
                    2 -> (msStr.toLongOrNull() ?: 0L) * 10L
                    3 -> msStr.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val totalMs = (min * 60_000L) + (sec * 1000L) + ms
                val text = matcher.group(4)?.trim().orEmpty()
                if (text.isNotBlank()) {
                    result.add(LyricLine(totalMs, text))
                }
            }
        }
        return result.sortedBy { it.timestampMs }
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
