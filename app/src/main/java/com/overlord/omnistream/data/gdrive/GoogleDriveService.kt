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
        internal const val PUBLIC_DRIVE_API_KEY = "AIzaSyAWGrfCCr7albM3lmCc937gx4uIphbpeKQ"
        private val FOLDER_ID_PATTERN = Pattern.compile("(?:folders/|[?&]id=)([a-zA-Z0-9_-]{25,})")
        internal val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".wav", ".aac", ".ogg", ".flac", ".opus", ".webm")
        internal val LYRIC_EXTENSIONS = listOf(".lrc", ".srt", ".vtt", ".ass", ".ssa", ".txt")
        internal val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp")
        private const val GDOC_MIME = "application/vnd.google-apps.document"

        /**
         * 資料夾內輔助檔案 (歌詞、字幕、封面圖片)
         */
        data class SidecarFile(
            val id: String,
            val name: String,
            val downloadUri: String
        )

        /**
         * 判斷是否為支援的歌詞或字幕副檔名
         */
        fun isLyricOrSubtitleFile(fileName: String, mimeType: String = ""): Boolean {
            val lower = fileName.trim().lowercase()
            if (LYRIC_EXTENSIONS.any { lower.endsWith(it) }) return true
            if (mimeType == GDOC_MIME && (lower.contains("歌詞") || lower.contains("字幕") || lower.contains("lyric") || lower.contains("sub"))) {
                return true
            }
            return false
        }

        /**
         * 僅移除已知媒體/字幕副檔名，避免誤切標題本身的小數點 (如 "v1.0")
         */
        private fun stripKnownExtension(rawTitle: String): String {
            val trimmed = rawTitle.trim()
            val lower = trimmed.lowercase()
            val allKnown = AUDIO_EXTENSIONS + LYRIC_EXTENSIONS + IMAGE_EXTENSIONS + listOf(".md")
            for (ext in allKnown) {
                if (lower.endsWith(ext)) {
                    return trimmed.substring(0, trimmed.length - ext.length)
                }
            }
            return trimmed
        }

        /**
         * 將檔名核心正規化以便跨命名格式模糊比對（如去除 _audio、_歌詞、_字幕、_20260929、_Fin、_OK、全形底線＿ 與空白）
         */
        internal fun normalizeCoreKey(rawTitle: String): String {
            return stripKnownExtension(rawTitle)
                .replace('＿', '_')
                .replace('\u3000', ' ')
                .replace(Regex("(?i)(_audio|audio_|_音訊|_歌詞|_lyrics|_lyric|_字幕|_subtitle|_sub|_cht|_chs|_zh|_tw|_fin|_ok|_final|_日文版)"), "")
                .replace(Regex("_\\d{6,10}"), "")
                .replace(Regex("_\\d{4}"), "")
                .replace(Regex("[\\s_()（）【】\\[\\].-]+"), "")
                .lowercase()
        }

        /**
         * 智慧配對音訊曲目的同資料夾歌詞/字幕檔與封面圖（支援 .lrc > .srt > .vtt > .ass > .txt 優先序）
         */
        fun attachMatchingSidecars(
            audioItems: MutableList<PlaylistItem>,
            lyricFiles: List<SidecarFile>,
            imageFiles: List<SidecarFile>
        ) {
            if (audioItems.isEmpty() || (lyricFiles.isEmpty() && imageFiles.isEmpty())) return

            for (i in audioItems.indices) {
                val item = audioItems[i]
                val rawBase = stripKnownExtension(item.title)
                val coreKey = normalizeCoreKey(rawBase)
                val numPrefix = Regex("^(\\d{1,3})[_\\s-]").find(rawBase)?.groupValues?.get(1)

                var matchedLyricUri = item.lyricsUri
                if (lyricFiles.isNotEmpty()) {
                    val bestLyric = lyricFiles.maxByOrNull { cand ->
                        val candBase = stripKnownExtension(cand.name)
                        val candCore = normalizeCoreKey(candBase)
                        val lowerName = cand.name.lowercase()
                        val extScore = when {
                            lowerName.endsWith(".lrc") -> 60
                            lowerName.endsWith(".srt") -> 50
                            lowerName.endsWith(".vtt") -> 45
                            lowerName.endsWith(".ass") || lowerName.endsWith(".ssa") -> 40
                            else -> 20
                        }
                        val matchScore = when {
                            candBase.equals(rawBase, ignoreCase = true) -> 1000
                            candCore.isNotBlank() && candCore == coreKey -> 500
                            candCore.isNotBlank() && coreKey.isNotBlank() && (coreKey.contains(candCore) || candCore.contains(coreKey)) -> 300
                            numPrefix != null && candBase.startsWith("${numPrefix}_") -> 200
                            else -> 0
                        }
                        if (matchScore > 0) matchScore + extScore else -1
                    }
                    if (bestLyric != null) {
                        val candBase = stripKnownExtension(bestLyric.name)
                        val candCore = normalizeCoreKey(candBase)
                        val isHit = candBase.equals(rawBase, ignoreCase = true) ||
                            (candCore.isNotBlank() && coreKey.isNotBlank() && (candCore == coreKey || coreKey.contains(candCore) || candCore.contains(coreKey))) ||
                            (numPrefix != null && candBase.startsWith("${numPrefix}_"))
                        if (isHit) {
                            matchedLyricUri = bestLyric.downloadUri
                        }
                    }
                }

                var matchedArtUri = item.artworkUri
                if (matchedArtUri.isNullOrBlank() && imageFiles.isNotEmpty()) {
                    val exactImg = imageFiles.firstOrNull {
                        stripKnownExtension(it.name).equals(rawBase, ignoreCase = true)
                    } ?: imageFiles.firstOrNull {
                        val c = normalizeCoreKey(it.name)
                        c.isNotBlank() && c == coreKey
                    } ?: imageFiles.firstOrNull {
                        val base = stripKnownExtension(it.name).lowercase()
                        base == "cover" || base == "folder" || base == "artwork"
                    }
                    if (exactImg != null) {
                        matchedArtUri = exactImg.downloadUri
                    }
                }

                if (matchedLyricUri != item.lyricsUri || matchedArtUri != item.artworkUri) {
                    audioItems[i] = item.copy(
                        lyricsUri = matchedLyricUri,
                        artworkUri = matchedArtUri
                    )
                }
            }
        }

        /**
         * 從完整網址或純文字中精準提取 Google Drive Folder ID
         */
        fun extractFolderId(input: String): String {
            val trimmed = input.trim()
            val matcher = FOLDER_ID_PATTERN.matcher(trimmed)
            return if (matcher.find()) {
                matcher.group(1) ?: matcher.group()
            } else {
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

    private fun buildSidecarDownloadUrl(fileId: String, mimeType: String, token: String?): String {
        if (mimeType == GDOC_MIME) {
            return "https://docs.google.com/document/d/$fileId/export?format=txt"
        }
        return if (token != null) {
            "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
        } else {
            buildPublicStreamUrl(fileId)
        }
    }

    /**
     * 嘗試解析 Google Drive 資料夾的真實名稱
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
     * 抓取 Google Drive 指定資料夾內音訊檔案與對應字幕/歌詞/封面（支援 Drive v3 API 與免登入公開網頁雙軌備援）
     */
    suspend fun fetchFolderAudioFiles(
        folderIdOrUrl: String,
        folderName: String
    ): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val folderId = extractFolderId(folderIdOrUrl)
        val token = currentAccessToken

        // 1. 優先使用 Drive v3 API（支援分頁循環與 pageSize=1000）
        val apiFiles = fetchViaDriveV3Api(folderId, folderName, token)
        if (apiFiles.isNotEmpty()) return@withContext apiFiles

        // 2. 備用方案：免登入公開資料夾網頁解析器
        return@withContext fetchPublicFolderAudioFiles(folderId, folderName)
    }

    internal fun parseDriveV3ResponseJson(
        body: String,
        folderId: String,
        folderName: String,
        token: String?,
        result: MutableList<PlaylistItem>,
        lyricFiles: MutableList<SidecarFile>,
        imageFiles: MutableList<SidecarFile>
    ): String? {
        val json = JSONObject(body)
        val files = json.optJSONArray("files") ?: return null
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val id = file.optString("id")
            val name = file.optString("name")
            val mimeType = file.optString("mimeType", "")
            if (id.isBlank() || name.isBlank()) continue

            val isAudio = mimeType.startsWith("audio/") ||
                AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

            when {
                isAudio -> {
                    val mediaUri = if (token != null) {
                        "https://www.googleapis.com/drive/v3/files/$id?alt=media"
                    } else {
                        buildPublicStreamUrl(id)
                    }
                    result.add(
                        PlaylistItem(
                            id = "gdrive_$id",
                            title = stripKnownExtension(name),
                            artist = folderName,
                            mediaUri = mediaUri,
                            sourceType = MediaSourceType.GDRIVE,
                            driveFolderId = folderId
                        )
                    )
                }
                isLyricOrSubtitleFile(name, mimeType) -> {
                    lyricFiles.add(SidecarFile(id, name, buildSidecarDownloadUrl(id, mimeType, token)))
                }
                IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } || mimeType.startsWith("image/") -> {
                    imageFiles.add(SidecarFile(id, name, buildSidecarDownloadUrl(id, mimeType, token)))
                }
            }
        }
        return json.optString("nextPageToken").takeIf { it.isNotBlank() }
    }

    private fun fetchViaDriveV3Api(
        folderId: String,
        folderName: String,
        token: String?,
        apiKey: String = PUBLIC_DRIVE_API_KEY
    ): List<PlaylistItem> {
        val result = mutableListOf<PlaylistItem>()
        val lyricFiles = mutableListOf<SidecarFile>()
        val imageFiles = mutableListOf<SidecarFile>()
        var pageToken: String? = null
        val query = "'$folderId' in parents and trashed = false"

        do {
            val httpUrlBuilder = "https://www.googleapis.com/drive/v3/files".toHttpUrlOrNull()?.newBuilder()
                ?: break
            httpUrlBuilder.addQueryParameter("q", query)
            httpUrlBuilder.addQueryParameter("pageSize", "1000")
            httpUrlBuilder.addQueryParameter("fields", "nextPageToken,files(id,name,mimeType,size)")
            httpUrlBuilder.addQueryParameter("orderBy", "name asc")

            if (token == null) {
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
                pageToken = parseDriveV3ResponseJson(body, folderId, folderName, token, result, lyricFiles, imageFiles)
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        } while (pageToken != null)

        result.sortWith { a, b -> naturalCompare(a.title, b.title) }
        attachMatchingSidecars(result, lyricFiles, imageFiles)
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
        val lyricFiles = mutableListOf<SidecarFile>()
        val imageFiles = mutableListOf<SidecarFile>()
        val url = "https://drive.google.com/drive/folders/$folderId"

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()

        try {
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext emptyList()
            parsePublicHtmlFolder(html, folderId, folderName, result, lyricFiles, imageFiles)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        result.sortWith { a, b -> naturalCompare(a.title, b.title) }
        attachMatchingSidecars(result, lyricFiles, imageFiles)
        return@withContext result
    }

    internal fun parsePublicHtmlFolder(
        html: String,
        folderId: String,
        folderName: String,
        result: MutableList<PlaylistItem>,
        lyricFiles: MutableList<SidecarFile>,
        imageFiles: MutableList<SidecarFile>
    ) {
        // 1. 優先由 window['_DRIVE_ivd'] 提取完整結構化檔案列表
        val ivdPattern = Pattern.compile("""window\['_DRIVE_ivd'\]\s*=\s*'([^']+)'""")
        val ivdMatcher = ivdPattern.matcher(html)
        if (ivdMatcher.find()) {
            val rawEscaped = ivdMatcher.group(1)
            val decoded = unescapeJsString(rawEscaped)
            try {
                val root = JSONArray(decoded)
                extractFilesFromJson(root, folderId, folderName, result, lyricFiles, imageFiles)
                if (result.isNotEmpty()) {
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. DOM 備援掃描（允許屬性間跨標籤與類型標籤後綴如 " Audio", " Text", " 文字"）
        val idPattern = Pattern.compile("""data-id="([a-zA-Z0-9_-]{25,})"""")
        val namePattern = Pattern.compile("""(?:data-tooltip|aria-label)="([^"]+)"""")
        val idMatcher = idPattern.matcher(html)
        val seenIds = mutableSetOf<String>()

        while (idMatcher.find()) {
            val fileId = idMatcher.group(1) ?: continue
            if (fileId == folderId || !seenIds.add(fileId)) continue

            val searchEnd = (idMatcher.end() + 700).coerceAtMost(html.length)
            val snippet = html.substring(idMatcher.end(), searchEnd)
            val nameMatcher = namePattern.matcher(snippet)
            while (nameMatcher.find()) {
                val rawTooltip = nameMatcher.group(1)?.trim() ?: continue
                // 移除 Google Drive DOM 附加的類型尾綴 (如 "foo.mp3 Audio", "bar.lrc 文字", "baz.srt Text")
                val cleanedName = rawTooltip.replace(
                    Regex("""\s+(Audio|Video|Text|Document|Image|音訊|影片|文字|圖片|檔案)\s*$""", RegexOption.IGNORE_CASE),
                    ""
                ).trim()

                val dl = buildPublicStreamUrl(fileId)
                val isAudio = AUDIO_EXTENSIONS.any { cleanedName.endsWith(it, ignoreCase = true) }
                when {
                    isAudio -> {
                        result.add(
                            PlaylistItem(
                                id = "gdrive_$fileId",
                                title = stripKnownExtension(cleanedName),
                                artist = folderName,
                                mediaUri = dl,
                                sourceType = MediaSourceType.GDRIVE,
                                driveFolderId = folderId
                            )
                        )
                        break
                    }
                    isLyricOrSubtitleFile(cleanedName) -> {
                        lyricFiles.add(SidecarFile(fileId, cleanedName, dl))
                        break
                    }
                    IMAGE_EXTENSIONS.any { cleanedName.endsWith(it, ignoreCase = true) } -> {
                        imageFiles.add(SidecarFile(fileId, cleanedName, dl))
                        break
                    }
                }
            }
        }
    }

    internal fun extractFilesFromJson(
        node: Any?,
        folderId: String,
        folderName: String,
        results: MutableList<PlaylistItem>,
        lyricFiles: MutableList<SidecarFile>,
        imageFiles: MutableList<SidecarFile>
    ) {
        when (node) {
            is JSONArray -> {
                if (node.length() >= 4) {
                    val first = node.opt(0) as? String
                    val third = node.opt(2) as? String
                    // Google Drive 對 .lrc / .srt 等自訂副檔名在網頁陣列中第 4 欄可能為 null，故不可強制要求 non-null String
                    val fourth = node.opt(3) as? String ?: ""
                    if (!first.isNullOrBlank() && !third.isNullOrBlank() && first.length >= 25) {
                        val isAudio = fourth.startsWith("audio/") ||
                            AUDIO_EXTENSIONS.any { third.endsWith(it, ignoreCase = true) }
                        val dl = buildSidecarDownloadUrl(first, fourth, null)
                        when {
                            isAudio -> {
                                results.add(
                                    PlaylistItem(
                                        id = "gdrive_$first",
                                        title = stripKnownExtension(third),
                                        artist = folderName,
                                        mediaUri = buildPublicStreamUrl(first),
                                        sourceType = MediaSourceType.GDRIVE,
                                        driveFolderId = folderId
                                    )
                                )
                            }
                            isLyricOrSubtitleFile(third, fourth) -> {
                                lyricFiles.add(SidecarFile(first, third, dl))
                            }
                            IMAGE_EXTENSIONS.any { third.endsWith(it, ignoreCase = true) } || fourth.startsWith("image/") -> {
                                imageFiles.add(SidecarFile(first, third, dl))
                            }
                        }
                    }
                }
                for (i in 0 until node.length()) {
                    extractFilesFromJson(node.opt(i), folderId, folderName, results, lyricFiles, imageFiles)
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    extractFilesFromJson(node.opt(keys.next()), folderId, folderName, results, lyricFiles, imageFiles)
                }
            }
        }
    }

    /**
     * 安全解碼 Google Drive HTML <script> 內的 JS 單引號字串，保留合法 JSON 跳脫結構
     */
    internal fun unescapeJsString(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] == '\\' && i + 1 < input.length) {
                when (input[i + 1]) {
                    'x' -> {
                        if (i + 3 < input.length) {
                            val hex = input.substring(i + 2, i + 4)
                            val ch = hex.toIntOrNull(16)?.toChar()
                            if (ch != null) {
                                sb.append(ch)
                                i += 4
                                continue
                            }
                        }
                    }
                    'u' -> {
                        if (i + 5 < input.length) {
                            val hex = input.substring(i + 2, i + 6)
                            val ch = hex.toIntOrNull(16)?.toChar()
                            if (ch != null) {
                                sb.append(ch)
                                i += 6
                                continue
                            }
                        }
                    }
                    '/' -> { sb.append('/'); i += 2; continue }
                    '\'' -> { sb.append('\''); i += 2; continue }
                    '"' -> { sb.append('"'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                }
            }
            sb.append(input[i])
            i++
        }
        return sb.toString()
    }
}
