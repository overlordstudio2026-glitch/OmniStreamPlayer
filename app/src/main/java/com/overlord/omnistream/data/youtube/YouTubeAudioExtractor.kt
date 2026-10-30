package com.overlord.omnistream.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class YouTubeMediaInfo(
    val audioUrl: String,
    val title: String,
    val author: String,
    val durationMs: Long = 0L
)

/**
 * YouTube 純音訊與直接串流提取器：
 * 支援 ANDROID_VR (1.61.48) 與 ANDROID (21.26.364) 官方客戶端，免 API Key 提取高音質音訊串流或 MP4 串流與真實影片標題
 */
class YouTubeAudioExtractor(private val client: OkHttpClient = OkHttpClient()) {

    companion object {
        private const val TAG = "YTAudioExtractor"
        private const val INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private val VIDEO_ID_PATTERN = java.util.regex.Pattern.compile("(?:v=|v\\/|vi=|vi\\/|youtu\\.be\\/|embed\\/|shorts\\/|^)([a-zA-Z0-9_-]{11})(?:[^a-zA-Z0-9_-]|$)")

        /**
         * 從各種 YouTube 網址格式 (watch?v=, youtu.be/, shorts/, embed/, yt_ 前綴) 精準提取 11 位元 Video ID
         */
        fun extractVideoId(input: String): String {
            val trimmed = input.trim().removePrefix("yt_")
            val matcher = VIDEO_ID_PATTERN.matcher(trimmed)
            if (matcher.find()) {
                return matcher.group(1) ?: trimmed
            }
            if (trimmed.length == 11 && !trimmed.contains("/") && !trimmed.contains("?")) {
                return trimmed
            }
            return trimmed.substringBefore("&").substringBefore("?")
        }

        // 客戶端備援清單：ANDROID (高相容度 Format 18 MP4 / 純音訊) -> ANDROID_VR (優質 Opus 音訊)
        private val CLIENT_CONFIGS = listOf(
            ClientConfig(
                name = "ANDROID",
                version = "21.26.364",
                userAgent = "com.google.android.youtube/21.26.364 (Linux; U; Android 11)",
                extraContext = mapOf(
                    "androidSdkVersion" to 30,
                    "osName" to "Android",
                    "osVersion" to "11"
                )
            ),
            ClientConfig(
                name = "ANDROID_VR",
                version = "1.61.48",
                userAgent = "com.google.android.youtube/19.29.37",
                extraContext = emptyMap()
            )
        )
    }

    private data class ClientConfig(
        val name: String,
        val version: String,
        val userAgent: String,
        val extraContext: Map<String, Any>
    )

    suspend fun extractAudioStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        extractMediaInfo(videoId)?.audioUrl
    }

    suspend fun extractMediaInfo(videoId: String): YouTubeMediaInfo? = withContext(Dispatchers.IO) {
        val cleanId = extractVideoId(videoId)
        if (cleanId.isBlank()) return@withContext null

        for (cfg in CLIENT_CONFIGS) {
            try {
                val clientObj = JSONObject().apply {
                    put("clientName", cfg.name)
                    put("clientVersion", cfg.version)
                    put("hl", "zh-TW")
                    put("gl", "TW")
                    for ((k, v) in cfg.extraContext) {
                        put(k, v)
                    }
                }

                val payload = JSONObject().apply {
                    put("context", JSONObject().apply {
                        put("client", clientObj)
                    })
                    put("videoId", cleanId)
                }

                val request = Request.Builder()
                    .url(INNERTUBE_PLAYER_URL)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", cfg.userAgent)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val json = JSONObject(body)
                    val streamingData = json.optJSONObject("streamingData") ?: continue
                    val videoDetails = json.optJSONObject("videoDetails")

                    val title = videoDetails?.optString("title").orEmpty().ifBlank { "YouTube 音訊" }
                    val author = videoDetails?.optString("author").orEmpty().ifBlank { "YouTube" }
                    val lengthSeconds = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: 0L
                    val durationMs = lengthSeconds * 1000L

                    // 1. 優先尋找 adaptiveFormats 中的純音訊串流 (Opus / M4A)
                    val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
                    var bestAudioUrl: String? = null
                    var maxAudioBitrate = 0

                    if (adaptiveFormats != null) {
                        for (i in 0 until adaptiveFormats.length()) {
                            val fmt = adaptiveFormats.getJSONObject(i)
                            val mimeType = fmt.optString("mimeType")
                            if (mimeType.contains("audio", ignoreCase = true)) {
                                val directUrl = fmt.optString("url")
                                val bitrate = fmt.optInt("bitrate", 0)
                                if (directUrl.isNotBlank() && bitrate > maxAudioBitrate) {
                                    maxAudioBitrate = bitrate
                                    bestAudioUrl = directUrl
                                }
                            }
                        }
                    }

                    // 2. 若無獨立純音訊 (例如某些特定影片被限制)，則回退至 progressive formats (如 ITAG 18: MP4+AAC)
                    if (bestAudioUrl == null) {
                        val formats = streamingData.optJSONArray("formats")
                        if (formats != null) {
                            var maxProgBitrate = 0
                            for (i in 0 until formats.length()) {
                                val fmt = formats.getJSONObject(i)
                                val directUrl = fmt.optString("url")
                                val bitrate = fmt.optInt("bitrate", 0)
                                if (directUrl.isNotBlank() && bitrate > maxProgBitrate) {
                                    maxProgBitrate = bitrate
                                    bestAudioUrl = directUrl
                                }
                            }
                        }
                    }

                    if (bestAudioUrl != null) {
                        Log.d(TAG, "Successfully extracted direct stream for $cleanId via ${cfg.name}")
                        return@withContext YouTubeMediaInfo(
                            audioUrl = bestAudioUrl,
                            title = title,
                            author = author,
                            durationMs = durationMs
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed extracting via ${cfg.name}: ${e.message}")
            }
        }

        Log.e(TAG, "All extraction methods failed for video $cleanId")
        return@withContext null
    }
}
