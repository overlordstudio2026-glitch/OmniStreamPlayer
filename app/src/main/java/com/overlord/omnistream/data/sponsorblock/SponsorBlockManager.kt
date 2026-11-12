package com.overlord.omnistream.data.sponsorblock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

data class SponsorSegment(
    val category: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * SponsorBlock 開源業配與贊助廣告跳過管理器
 * 自動檢測 YouTube 影片中的業配、片頭片尾與訂閱互動，實現秒級無感跳過
 */
class SponsorBlockManager(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        private const val TAG = "SponsorBlock"
        private const val SPONSOR_BLOCK_API = "https://sponsor.ajay.app/api/skipSegments"
    }

    private val segmentCache = ConcurrentHashMap<String, List<SponsorSegment>>()

    /**
     * 非同步載入指定 YouTube Video ID 的跳過時間區間
     */
    suspend fun fetchSegments(videoId: String): List<SponsorSegment> = withContext(Dispatchers.IO) {
        val cleanId = videoId.removePrefix("yt_").trim()
        if (cleanId.length != 11) return@withContext emptyList()

        segmentCache[cleanId]?.let { return@withContext it }

        try {
            val url = "$SPONSOR_BLOCK_API?videoID=$cleanId&categories=[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\"]"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "OmniStreamPlayer/2026")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val jsonArr = JSONArray(body)
                val list = mutableListOf<SponsorSegment>()

                for (i in 0 until jsonArr.length()) {
                    val obj = jsonArr.getJSONObject(i)
                    val category = obj.optString("category", "sponsor")
                    val segmentArr = obj.optJSONArray("segment")
                    if (segmentArr != null && segmentArr.length() >= 2) {
                        val startSec = segmentArr.getDouble(0)
                        val endSec = segmentArr.getDouble(1)
                        val startMs = (startSec * 1000).toLong()
                        val endMs = (endSec * 1000).toLong()
                        if (endMs > startMs) {
                            list.add(SponsorSegment(category, startMs, endMs))
                        }
                    }
                }
                segmentCache[cleanId] = list
                Log.i(TAG, "Loaded ${list.size} sponsor segments for video $cleanId")
                return@withContext list
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load SponsorBlock segments for $cleanId: ${e.message}")
        }
        return@withContext emptyList()
    }

    /**
     * 檢查目前播放時間戳是否落在任何跳過區間內。若落在區間內，回傳跳過後的目標毫秒；否則回傳 null
     */
    fun checkSkipTarget(videoId: String, currentPositionMs: Long): Long? {
        val cleanId = videoId.removePrefix("yt_").trim()
        val segments = segmentCache[cleanId] ?: return null

        for (seg in segments) {
            // 當播放位置在 [startMs - 200ms, endMs - 500ms] 區間時觸發跳轉
            if (currentPositionMs in (seg.startMs - 200L) until (seg.endMs - 300L)) {
                Log.i(TAG, "Skipping [${seg.category}] from $currentPositionMs ms to ${seg.endMs} ms")
                return seg.endMs
            }
        }
        return null
    }

    fun clear() {
        segmentCache.clear()
    }
}
