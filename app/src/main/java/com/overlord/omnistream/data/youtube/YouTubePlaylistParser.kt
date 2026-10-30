package com.overlord.omnistream.data.youtube

import android.util.Log
import com.overlord.omnistream.core.model.MediaSourceType
import com.overlord.omnistream.core.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.regex.Pattern

data class YouTubePlaylistInfo(
    val playlistId: String,
    val title: String,
    val channelName: String,
    val items: List<PlaylistItem>
)

/**
 * 免 API Key 的 YouTube 播放清單與頻道反查解析器：
 * 支援網頁 HTML + ytInitialData 與 InnerTube 雙軌容錯，精確反查頻道名稱與清單真實標題
 */
class YouTubePlaylistParser(
    private val client: OkHttpClient = OkHttpClient(),
    private val audioExtractor: YouTubeAudioExtractor = YouTubeAudioExtractor(client)
) {

    companion object {
        private const val TAG = "YTPlaylistParser"
        private val PLAYLIST_ID_PATTERN = Pattern.compile("[?&]list=([a-zA-Z0-9_-]+)")

        fun extractPlaylistId(input: String): String {
            val trimmed = input.trim()
            val matcher = PLAYLIST_ID_PATTERN.matcher(trimmed)
            return if (matcher.find()) {
                matcher.group(1) ?: matcher.group()
            } else {
                trimmed.substringBefore("&").substringBefore("?")
            }
        }
    }

    /**
     * 完整解析播放清單資訊（含反查頻道名稱、清單真實標題、曲目清單）
     */
    suspend fun fetchPlaylistDetails(
        playlistIdOrUrl: String,
        customName: String = ""
    ): YouTubePlaylistInfo = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(playlistIdOrUrl)
        val webUrl = "https://www.youtube.com/playlist?list=$playlistId"

        var resolvedTitle = customName.trim()
        var resolvedChannel = ""
        val parsedVideos = mutableListOf<Triple<String, String, String>>() // (vid, title, artist)
        val seen = mutableSetOf<String>()

        // 1. 優先透過 YouTube 網頁端獲取 ytInitialData (最完整，包含 Header/Owner/Title/listItemViewModel)
        try {
            val request = Request.Builder()
                .url(webUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .addHeader("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .addHeader("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+478; SOCS=CAISNQgDEitib3FfaWRlbnRpdHlmcm9udGVuZHVpc2VydmVyXzIwMjMwNjI3LjA2X3AwGgJ6aCIA")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string().orEmpty()
                val idx = html.findYtInitialDataIndex()
                if (idx != -1) {
                    val endIdx = html.indexOf(";</script>", idx)
                    if (endIdx != -1) {
                        val jsonStr = html.substring(idx + "ytInitialData =".length, endIdx).trim()
                        val data = JSONObject(jsonStr)

                        // 提取播放清單標題
                        val header = data.optJSONObject("header")?.optJSONObject("pageHeaderRenderer")
                        if (resolvedTitle.isBlank() || resolvedTitle == "YouTube 清單" || resolvedTitle == "YouTube 播放清單") {
                            val headerTitle = header?.optString("pageTitle").orEmpty()
                            val vmTitle = header?.optJSONObject("content")
                                ?.optJSONObject("pageHeaderViewModel")
                                ?.optJSONObject("title")
                                ?.optJSONObject("dynamicTextViewModel")
                                ?.optJSONObject("text")
                                ?.optString("content").orEmpty()
                            val metaTitle = data.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")?.optString("title").orEmpty()

                            resolvedTitle = headerTitle.ifBlank { vmTitle }.ifBlank { metaTitle }.ifBlank { "YouTube 播放清單" }
                        }

                        // 反查頻道名稱 (Owner Channel)
                        try {
                            val metaRows = header?.optJSONObject("content")
                                ?.optJSONObject("pageHeaderViewModel")
                                ?.optJSONObject("metadata")
                                ?.optJSONObject("contentMetadataViewModel")
                                ?.optJSONArray("metadataRows")

                            if (metaRows != null) {
                                for (r in 0 until metaRows.length()) {
                                    val parts = metaRows.getJSONObject(r).optJSONArray("metadataParts") ?: continue
                                    for (p in 0 until parts.length()) {
                                        val part = parts.getJSONObject(p)
                                        val avatarStack = part.optJSONObject("avatarStack")?.optJSONObject("avatarStackViewModel")
                                        val content = avatarStack?.optJSONObject("text")?.optString("content")
                                        if (!content.isNullOrBlank()) {
                                            resolvedChannel = content.replace("由", "").replace("建立", "").trim()
                                            break
                                        }
                                        val textContent = part.optJSONObject("text")?.optString("content")
                                        if (!textContent.isNullOrBlank()) {
                                            resolvedChannel = textContent.trim()
                                            break
                                        }
                                    }
                                    if (resolvedChannel.isNotBlank()) break
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error extracting channel from header", e)
                        }

                        // 提取所有曲目 (支援 listItemViewModel、playlistVideoRenderer 與 lockupViewModel)
                        extractVideosRecursive(data, resolvedTitle, resolvedChannel, seen, parsedVideos)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse playlist via Web HTML", e)
        }

        // 2. 若曲目仍為空，回退至 InnerTube browse API
        if (parsedVideos.isEmpty()) {
            try {
                val innerTubeVideos = fetchViaInnerTube(playlistId, resolvedTitle, resolvedChannel, seen)
                parsedVideos.addAll(innerTubeVideos)
            } catch (e: Exception) {
                Log.e(TAG, "Failed fallback to InnerTube", e)
            }
        }

        // 2.5 若頻道名稱仍未反查出，從解析出的曲目創作者中最常出現者提取
        if (resolvedChannel.isBlank()) {
            val detectedArtist = parsedVideos.map { it.third }
                .filter { it.isNotBlank() && it != "YouTube 播放清單" && it != "YouTube 官方/創作者" && it != resolvedTitle }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
            if (!detectedArtist.isNullOrBlank()) {
                resolvedChannel = detectedArtist
            }
        }

        val finalTitle = resolvedTitle.ifBlank { "YouTube 播放清單" }
        val finalChannel = resolvedChannel.ifBlank { "YouTube 官方/創作者" }

        // 3. 預解析前 25 首直鏈音訊，後續曲目保持動態延遲解析
        val deferredList = parsedVideos.take(25).map { (vid, title, artist) ->
            async {
                val directUrl = audioExtractor.extractAudioStreamUrl(vid)
                PlaylistItem(
                    id = "yt_$vid",
                    title = title,
                    artist = if (artist.isBlank() || artist == "YouTube 播放清單") finalChannel else artist,
                    mediaUri = directUrl ?: "https://www.youtube.com/watch?v=$vid",
                    sourceType = MediaSourceType.YOUTUBE,
                    artworkUri = "https://img.youtube.com/vi/$vid/hqdefault.jpg"
                )
            }
        }
        val preloaded = deferredList.awaitAll()
        val remaining = parsedVideos.drop(25).map { (vid, title, artist) ->
            PlaylistItem(
                id = "yt_$vid",
                title = title,
                artist = if (artist.isBlank() || artist == "YouTube 播放清單") finalChannel else artist,
                mediaUri = "https://www.youtube.com/watch?v=$vid",
                sourceType = MediaSourceType.YOUTUBE,
                artworkUri = "https://img.youtube.com/vi/$vid/hqdefault.jpg"
            )
        }

        YouTubePlaylistInfo(
            playlistId = playlistId,
            title = finalTitle,
            channelName = finalChannel,
            items = preloaded + remaining
        )
    }

    /**
     * 相容舊呼叫：直接返回 PlaylistItem 清單
     */
    suspend fun fetchPlaylistVideos(
        playlistIdOrUrl: String,
        playlistTitle: String = "YouTube 播放清單"
    ): List<PlaylistItem> {
        return fetchPlaylistDetails(playlistIdOrUrl, playlistTitle).items
    }

    private suspend fun fetchViaInnerTube(
        playlistId: String,
        defaultTitle: String,
        defaultChannel: String,
        seen: MutableSet<String>
    ): List<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Triple<String, String, String>>()
        val url = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false"

        val payload = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20240401.00.00")
                    put("hl", "zh-TW")
                    put("gl", "TW")
                })
            })
            put("browseId", "VL$playlistId")
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        val resp = client.newCall(request).execute()
        if (resp.isSuccessful) {
            val body = resp.body?.string().orEmpty()
            try {
                val json = JSONObject(body)
                extractVideosRecursive(json, defaultTitle, defaultChannel, seen, list)
            } catch (e: Exception) {
                // Ignore parse error
            }

            // 正則保底
            if (list.isEmpty()) {
                val vidPattern = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                val matcher = vidPattern.matcher(body)
                var count = 1
                while (matcher.find()) {
                    val vid = matcher.group(1)
                    if (seen.add(vid)) {
                        list.add(Triple(vid, "$defaultTitle #$count", defaultChannel.ifBlank { "YouTube" }))
                        count++
                    }
                }
            }
        }
        list
    }

    private fun extractVideosRecursive(
        obj: Any,
        defaultTitle: String,
        defaultChannel: String,
        seen: MutableSet<String>,
        results: MutableList<Triple<String, String, String>>
    ) {
        when (obj) {
            is JSONObject -> {
                // 1. 新版 listItemViewModel
                if (obj.has("listItemViewModel")) {
                    val lvm = obj.optJSONObject("listItemViewModel")
                    val title = lvm?.optJSONObject("title")?.optString("content").orEmpty()
                    val lvmStr = lvm.toString()
                    val matcher = Pattern.compile("\"videoId\":\"([a-zA-Z0-9_-]{11})\"").matcher(lvmStr)
                    if (matcher.find()) {
                        val vid = matcher.group(1)
                        if (!vid.isNullOrBlank() && seen.add(vid)) {
                            val finalTitle = title.ifBlank { "$defaultTitle #${seen.size}" }
                            results.add(Triple(vid, finalTitle, defaultChannel.ifBlank { "YouTube" }))
                        }
                    }
                }
                // 2. 傳統 playlistVideoRenderer
                else if (obj.has("playlistVideoRenderer")) {
                    val pvr = obj.optJSONObject("playlistVideoRenderer")
                    val vid = pvr?.optString("videoId")
                    if (!vid.isNullOrBlank() && seen.add(vid)) {
                        val titleObj = pvr.optJSONObject("title")
                        val title = titleObj?.optString("simpleText").orEmpty().ifBlank {
                            titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                        }.ifBlank { "$defaultTitle #${seen.size}" }
                        val artist = pvr.optJSONObject("shortBylineText")
                            ?.optJSONArray("runs")
                            ?.optJSONObject(0)
                            ?.optString("text").orEmpty().ifBlank { defaultChannel.ifBlank { "YouTube" } }
                        results.add(Triple(vid, title, artist))
                    }
                }
                // 3. lockupViewModel
                else if (obj.has("lockupViewModel")) {
                    val lvm = obj.optJSONObject("lockupViewModel")
                    val vid = lvm?.optString("contentId")
                    if (!vid.isNullOrBlank() && seen.add(vid)) {
                        val meta = lvm.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                        val title = meta?.optJSONObject("title")?.optString("content").orEmpty().ifBlank { "$defaultTitle #${seen.size}" }
                        val artist = meta?.optJSONObject("metadata")
                            ?.optJSONObject("contentMetadataViewModel")
                            ?.optJSONArray("metadataRows")
                            ?.optJSONObject(0)
                            ?.optJSONArray("metadataParts")
                            ?.optJSONObject(0)
                            ?.optJSONObject("text")
                            ?.optString("content").orEmpty().ifBlank { defaultChannel.ifBlank { "YouTube" } }
                        results.add(Triple(vid, title, artist))
                    }
                }

                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    obj.opt(key)?.let { extractVideosRecursive(it, defaultTitle, defaultChannel, seen, results) }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until obj.length()) {
                    obj.opt(i)?.let { extractVideosRecursive(it, defaultTitle, defaultChannel, seen, results) }
                }
            }
        }
    }

    private fun String.findYtInitialDataIndex(): Int {
        val target = "ytInitialData ="
        return indexOf(target)
    }
}
