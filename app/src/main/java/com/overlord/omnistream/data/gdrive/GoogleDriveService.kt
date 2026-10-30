package com.overlord.omnistream.data.gdrive

import com.overlord.omnistream.core.model.MediaSourceType
import com.overlord.omnistream.core.model.PlaylistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Google 雲端硬碟服務：
 * 支援 OAuth2 Token 串流，以及免登入公開資料夾爬取與 HTTP 206 Range 直接串流
 */
class GoogleDriveService(private val client: OkHttpClient = OkHttpClient()) {

    var currentAccessToken: String? = null

    companion object {
        private const val PUBLIC_DRIVE_API_KEY = "AIzaSyAWGrfCCr7albM3lmCc937gx4uIphbpeKQ"
        private val FOLDER_ID_PATTERN = Pattern.compile("(?:folders/|[?&]id=)([a-zA-Z0-9_-]{25,})")

        /**
          * 從完整網址或純文字中精準提取 Google Drive Folder ID
          */
        fun extractFolderId(input: String): String {
            val trimmed = input.trim()
            val matcher = FOLDER_ID_PATTERN.matcher(trimmed)
            return if (matcher.find()) {
                matcher.group(1) ?: matcher.group()
            } else {
                // 若本身為純 ID
                trimmed.substringBefore("?").substringBefore("&")
            }
        }

        /**
         * 自然排序比較器（如 ep1, ep2, ..., ep10）
         */
        fun naturalCompare(s1: String, s2: String): Int {
            val regex = "(\\d+)|(\\D+)".toRegex()
            val m1 = regex.findAll(s1).map { it.value }.iterator()
            val m2 = regex.findAll(s2).map { it.value }.iterator()
            while (m1.hasNext() && m2.hasNext()) {
                val t1 = m1.next()
                val t2 = m2.next()
                val num1 = t1.toLongOrNull()
                val num2 = t2.toLongOrNull()
                val cmp = if (num1 != null && num2 != null) {
                    num1.compareTo(num2)
                } else {
                    t1.compareTo(t2, ignoreCase = true)
                }
                if (cmp != 0) return cmp
            }
            return if (m1.hasNext()) 1 else if (m2.hasNext()) -1 else 0
        }
    }

    /**
     * 構建公開檔案直接串流 URL（支援 ExoPlayer HTTP Range 206）
     */
    fun buildPublicStreamUrl(fileId: String): String {
        return "https://drive.google.com/uc?export=download&id=$fileId"
    }

    /**
     * 嘗試解析 Google Drive 資料夾的真實名稱
     * 1. 優先透過 Drive v3 API 獲取資料夾 metadata
     * 2. 備援透過公開資料夾 HTML 解析 og:title 或 title
     */
    suspend fun fetchFolderName(folderIdOrUrl: String): String? = withContext(Dispatchers.IO) {
        val folderId = extractFolderId(folderIdOrUrl)
        val token = currentAccessToken

        // 1. 嘗試 Drive v3 API
        try {
            val urlBuilder = "https://www.googleapis.com/drive/v3/files/$folderId".toHttpUrlOrNull()?.newBuilder()
            if (urlBuilder != null) {
                urlBuilder.addQueryParameter("fields", "name")
                if (token == null) {
                    urlBuilder.addQueryParameter("key", PUBLIC_DRIVE_API_KEY)
                }
                val reqBuilder = Request.Builder().url(urlBuilder.build())
                if (token != null) {
                    reqBuilder.addHeader("Authorization", "Bearer $token")
                }
                val resp = client.newCall(reqBuilder.build()).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val name = json.optString("name").trim()
                    if (name.isNotBlank()) return@withContext name
                }
            }
        } catch (e: Exception) {
            // API 獲取失敗，嘗試 HTML
        }

        // 2. 嘗試公開網頁解析
        try {
            val webUrl = "https://drive.google.com/drive/folders/$folderId"
            val req = Request.Builder()
                .url(webUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val html = resp.body?.string().orEmpty()
                val ogMatcher = Pattern.compile("<meta\\s+property=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']").matcher(html)
                if (ogMatcher.find()) {
                    val title = ogMatcher.group(1)?.replace(" - Google 雲端硬碟", "")?.replace(" - Google Drive", "")?.trim()
                    if (!title.isNullOrBlank() && title != "Google 雲端硬碟" && title != "Google Drive") {
                        return@withContext title
                    }
                }
                val titleMatcher = Pattern.compile("<title>([^<]+)</title>").matcher(html)
                if (titleMatcher.find()) {
                    val title = titleMatcher.group(1)?.replace(" - Google 雲端硬碟", "")?.replace(" - Google Drive", "")?.trim()
                    if (!title.isNullOrBlank() && title != "Google 雲端硬碟" && title != "Google Drive") {
                        return@withContext title
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext null
    }

    /**
     * 抓取 Google Drive 指定資料夾內音訊檔案（支援 1000+ 首全量分頁與公開/私有資料夾）
     */
    suspend fun fetchFolderAudioFiles(
        folderIdOrUrl: String,
        folderName: String
    ): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val folderId = extractFolderId(folderIdOrUrl)
        val token = currentAccessToken

        // 優先使用 Drive v3 API（支援分頁循環與 pageSize=1000，一次拉取全量檔案）
        val apiFiles = fetchViaDriveV3Api(folderId, folderName, token)
        if (apiFiles.isNotEmpty()) return@withContext apiFiles

        // 備用方案：免登入公開資料夾網頁解析器
        return@withContext fetchPublicFolderAudioFiles(folderId, folderName)
    }

    private fun fetchViaDriveV3Api(
        folderId: String,
        folderName: String,
        token: String?,
        apiKey: String = PUBLIC_DRIVE_API_KEY
    ): List<PlaylistItem> {
        val result = mutableListOf<PlaylistItem>()
        val audioExtensions = listOf(".mp3", ".m4a", ".wav", ".aac", ".ogg", ".flac", ".opus", ".webm")
        var pageToken: String? = null
        val query = "'$folderId' in parents and trashed = false"

        do {
            val httpUrlBuilder = "https://www.googleapis.com/drive/v3/files".toHttpUrlOrNull()?.newBuilder()
                ?: break
            httpUrlBuilder.addQueryParameter("q", query)
            httpUrlBuilder.addQueryParameter("pageSize", "1000")
            httpUrlBuilder.addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size)")
            httpUrlBuilder.addQueryParameter("orderBy", "name asc")

            if (token != null) {
                // 有 OAuth token 優先透過 Bearer Header 驗證
            } else {
                httpUrlBuilder.addQueryParameter("key", apiKey)
            }

            if (pageToken != null) {
                httpUrlBuilder.addQueryParameter("pageToken", pageToken)
            }

            val reqBuilder = Request.Builder().url(httpUrlBuilder.build())
            if (token != null) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            try {
                val response = client.newCall(reqBuilder.build()).execute()
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    break
                }
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: break
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    val id = file.optString("id")
                    val name = file.optString("name")
                    val mimeType = file.optString("mimeType", "")

                    val isAudio = mimeType.startsWith("audio/") ||
                            audioExtensions.any { name.endsWith(it, ignoreCase = true) }

                    if (isAudio && id.isNotBlank()) {
                        val mediaUri = if (token != null) {
                            "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                        } else {
                            buildPublicStreamUrl(id)
                        }
                        result.add(
                            PlaylistItem(
                                id = "gdrive_$id",
                                title = name.substringBeforeLast("."),
                                artist = folderName,
                                mediaUri = mediaUri,
                                sourceType = MediaSourceType.GDRIVE,
                                driveFolderId = folderId
                            )
                        )
                    }
                }
                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        } while (pageToken != null)

        // 自然排序：讓 ep1, ep2, ..., ep10 按人類直覺數字順序排列
        result.sortWith { a, b -> naturalCompare(a.title, b.title) }
        return result
    }

    /**
     * 免登入公開資料夾爬取：解析 Google Drive 資料夾網頁並提取檔案清單
     */
    suspend fun fetchPublicFolderAudioFiles(
        folderId: String,
        folderName: String
    ): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<PlaylistItem>()
        val url = "https://drive.google.com/drive/folders/$folderId"

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        try {
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext emptyList()

            // 1. 嘗試由 window['_DRIVE_ivd'] 提取檔案列表
            val ivdPattern = Pattern.compile("""window\['_DRIVE_ivd'\]\s*=\s*'([^']+)'""")
            val ivdMatcher = ivdPattern.matcher(html)
            if (ivdMatcher.find()) {
                val rawEscaped = ivdMatcher.group(1)
                val decoded = unescapeUnicode(rawEscaped)
                try {
                    val root = JSONArray(decoded)
                    extractFilesFromJson(root, folderId, folderName, result)
                    if (result.isNotEmpty()) return@withContext result
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 2. 備用方案：透過 DOM data-id 與 tooltip 標籤提取
            val fallbackPattern = Pattern.compile("""data-id="([a-zA-Z0-9_-]{25,})"[^>]*data-tooltip="([^"]+\.(?:mp3|m4a|wav|aac|ogg))""")
            val matcher = fallbackPattern.matcher(html)
            val seenIds = mutableSetOf<String>()
            while (matcher.find()) {
                val fileId = matcher.group(1)
                val fileName = matcher.group(2)
                if (seenIds.add(fileId)) {
                    result.add(
                        PlaylistItem(
                            id = "gdrive_$fileId",
                            title = fileName.substringBeforeLast("."),
                            artist = folderName,
                            mediaUri = buildPublicStreamUrl(fileId),
                            sourceType = MediaSourceType.GDRIVE,
                            driveFolderId = folderId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 自然排序
        result.sortWith { a, b -> naturalCompare(a.title, b.title) }
        return@withContext result
    }

    private fun extractFilesFromJson(
        node: Any?,
        folderId: String,
        folderName: String,
        results: MutableList<PlaylistItem>
    ) {
        when (node) {
            is JSONArray -> {
                if (node.length() >= 4) {
                    val first = node.opt(0)
                    val third = node.opt(2)
                    val fourth = node.opt(3)
                    if (first is String && third is String && fourth is String) {
                        val isAudio = fourth.startsWith("audio/") ||
                                third.endsWith(".mp3", ignoreCase = true) ||
                                third.endsWith(".m4a", ignoreCase = true) ||
                                third.endsWith(".wav", ignoreCase = true)
                        if (isAudio && first.length >= 25) {
                            results.add(
                                PlaylistItem(
                                    id = "gdrive_$first",
                                    title = third.substringBeforeLast("."),
                                    artist = folderName,
                                    mediaUri = buildPublicStreamUrl(first),
                                    sourceType = MediaSourceType.GDRIVE,
                                    driveFolderId = folderId
                                )
                            )
                        }
                    }
                }
                for (i in 0 until node.length()) {
                    extractFilesFromJson(node.opt(i), folderId, folderName, results)
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    extractFilesFromJson(node.opt(keys.next()), folderId, folderName, results)
                }
            }
        }
    }

    private fun unescapeUnicode(input: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\') {
                if (i + 1 < input.length) {
                    when (input[i + 1]) {
                        'x' -> {
                            if (i + 3 < input.length) {
                                val hex = input.substring(i + 2, i + 4)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                                continue
                            }
                        }
                        'u' -> {
                            if (i + 5 < input.length) {
                                val hex = input.substring(i + 2, i + 6)
                                sb.append(hex.toInt(16).toChar())
                                i += 6
                                continue
                            }
                        }
                        '/' -> { sb.append('/'); i += 2; continue }
                        '"' -> { sb.append('"'); i += 2; continue }
                        '\\' -> { sb.append('\\'); i += 2; continue }
                        'n' -> { sb.append('\n'); i += 2; continue }
                        'r' -> { sb.append('\r'); i += 2; continue }
                        't' -> { sb.append('\t'); i += 2; continue }
                    }
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }
}
