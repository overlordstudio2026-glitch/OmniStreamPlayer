package com.overlord.omnistream.data.backup

import android.content.Context
import android.os.Environment
import com.overlord.omnistream.data.local.AppDatabase
import com.overlord.omnistream.data.local.entity.PlaylistGroupEntity
import com.overlord.omnistream.data.local.entity.PlaylistItemEntity
import com.overlord.omnistream.data.local.entity.SubscriptionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 使用者設定與訂閱清單自動備份/還原管理器
 * 確保移除 App 後重新安裝時，能無縫接回所有 Google Drive、YouTube 頻道/清單與播放群組
 */
class ConfigBackupManager(private val context: Context, private val database: AppDatabase) {

    companion object {
        const val BACKUP_FILE_NAME = "omnistream_backup.json"
        const val PREV_BACKUP_FILE_NAME = "omnistream_backup_prev.json"
    }

    fun getBackupFiles(): List<File> {
        val files = mutableListOf<File>()
        // 1. 公共 Download 目錄（App 移除後檔案仍長存，重灌後可直接接回）
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir != null) {
                downloadDir.mkdirs()
                files.add(File(downloadDir, BACKUP_FILE_NAME))
                files.add(File(downloadDir, PREV_BACKUP_FILE_NAME))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 常見的標準 Android 外部存儲路徑容錯
        try {
            files.add(File("/storage/emulated/0/Download", BACKUP_FILE_NAME))
            files.add(File("/storage/emulated/0/Download", PREV_BACKUP_FILE_NAME))
        } catch (e: Exception) {
            // ignore
        }

        // 2. 外部應用文件目錄
        context.getExternalFilesDir(null)?.let {
            files.add(File(it, BACKUP_FILE_NAME))
            files.add(File(it, PREV_BACKUP_FILE_NAME))
        }
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            files.add(File(it, BACKUP_FILE_NAME))
            files.add(File(it, PREV_BACKUP_FILE_NAME))
        }

        // 3. 內部私有目錄 (最安全、永遠有權限)
        files.add(File(context.filesDir, BACKUP_FILE_NAME))
        files.add(File(context.filesDir, PREV_BACKUP_FILE_NAME))

        return files.distinctBy { it.absolutePath }
    }

    /**
     * 建立完整設定與清單備份
     */
    suspend fun createBackup(): Boolean = withContext(Dispatchers.IO) {
        try {
            val subs = database.subscriptionDao().getAll()
            val groups = database.playlistGroupDao().getAll()
            val items = database.playlistDao().getAll()

            // 關鍵防護：若當前資料庫完全無任何資料，絕不覆蓋先前非空的備份檔案！
            if (subs.isEmpty() && items.isEmpty()) {
                val hasExistingData = getBackupFiles().any { it.exists() && it.length() > 50 }
                if (hasExistingData) {
                    return@withContext false
                }
            }

            val rootJson = JSONObject()
            rootJson.put("version", 1)
            rootJson.put("timestamp", System.currentTimeMillis())


            // 1. Subscriptions
            val subsArray = JSONArray()
            for (sub in subs) {
                val obj = JSONObject()
                obj.put("id", sub.id)
                obj.put("name", sub.name)
                obj.put("type", sub.type)
                obj.put("publicUrl", sub.publicUrl)
                obj.put("lastSyncedTime", sub.lastSyncedTime)
                obj.put("autoAddToPlaylist", sub.autoAddToPlaylist)
                obj.put("sinceTimestamp", sub.sinceTimestamp ?: -1L)
                obj.put("isPlaylist", sub.isPlaylist)
                obj.put("targetPlaylistGroupId", sub.targetPlaylistGroupId)
                subsArray.put(obj)
            }
            rootJson.put("subscriptions", subsArray)

            // 2. Playlist Groups
            val groupsArray = JSONArray()
            for (g in groups) {
                val obj = JSONObject()
                obj.put("id", g.id)
                obj.put("name", g.name)
                obj.put("createdAt", g.createdAt)
                groupsArray.put(obj)
            }
            rootJson.put("playlist_groups", groupsArray)

            // 3. Playlist Items
            val itemsArray = JSONArray()
            for (item in items) {
                val obj = JSONObject()
                obj.put("id", item.id)
                obj.put("title", item.title)
                obj.put("artist", item.artist)
                obj.put("durationMs", item.durationMs)
                obj.put("mediaUri", item.mediaUri)
                obj.put("sourceType", item.sourceType)
                obj.put("artworkUri", item.artworkUri)
                obj.put("driveFolderId", item.driveFolderId)
                obj.put("ytChannelId", item.ytChannelId)
                obj.put("sortOrder", item.sortOrder)
                obj.put("addedTime", item.addedTime)
                obj.put("playlistGroupId", item.playlistGroupId)
                itemsArray.put(obj)
            }
            rootJson.put("playlist_items", itemsArray)

            val jsonString = rootJson.toString(2)
            for (file in getBackupFiles().filter { it.name == BACKUP_FILE_NAME }) {
                try {
                    file.parentFile?.mkdirs()
                    if (file.exists() && file.length() > 50) {
                        val prevFile = File(file.parentFile, PREV_BACKUP_FILE_NAME)
                        try {
                            file.copyTo(prevFile, overwrite = true)
                        } catch (e: Exception) {
                            // ignore copy error
                        }
                    }
                    file.writeText(jsonString, Charsets.UTF_8)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 若本機存在備份且目前資料庫為空，無縫還原
     * 支援直接 File 讀取以及 MediaStore.Downloads 查詢雙軌
     */
    suspend fun restoreBackupIfAvailable(): Int = withContext(Dispatchers.IO) {
        // 軌道 1: 直接 File 檢查
        for (targetFile in getBackupFiles()) {
            try {
                if (targetFile.exists() && targetFile.length() > 0) {
                    val content = targetFile.readText(Charsets.UTF_8)
                    val count = restoreBackupFromJsonString(content)
                    if (count > 0) return@withContext count
                }
            } catch (e: Exception) {
                // Scoped Storage 限制時可能拋異常，進入軌道 2
            }
        }

        // 軌道 2: MediaStore.Downloads 查詢 (針對 Android 10+ 跨應用程式下載資料夾)
        try {
            val queryUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            } else {
                android.provider.MediaStore.Files.getContentUri("external")
            }
            val projection = arrayOf(
                android.provider.MediaStore.MediaColumns._ID,
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME
            )
            val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ? OR ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(BACKUP_FILE_NAME, PREV_BACKUP_FILE_NAME)

            context.contentResolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID)
                    val id = cursor.getLong(idCol)
                    val contentUri = android.content.ContentUris.withAppendedId(queryUri, id)
                    context.contentResolver.openInputStream(contentUri)?.use { input ->
                        val content = input.bufferedReader(Charsets.UTF_8).readText()
                        val count = restoreBackupFromJsonString(content)
                        if (count > 0) return@withContext count
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        0
    }

    /**
     * 從任意 InputStream（如 SAF 選擇器返回的 content:// Uri）讀取並還原
     */
    suspend fun restoreFromStream(inputStream: java.io.InputStream): Int = withContext(Dispatchers.IO) {
        try {
            val content = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            restoreBackupFromJsonString(content)
        } catch (e: Exception) {
            e.printStackTrace()
            0
        }
    }

    /**
     * 核心 JSON 解析還原引擎：相容 Google Drive、YouTube 頻道/清單、播放群組與各別項目
     */
    suspend fun restoreBackupFromJsonString(jsonString: String): Int = withContext(Dispatchers.IO) {
        var restoredCount = 0
        try {
            val rootJson = JSONObject(jsonString)

            // 1. 還原 Subscriptions (含 Google Drive 與 YouTube 頻道/清單)
            val subsArray = rootJson.optJSONArray("subscriptions")
            if (subsArray != null) {
                for (i in 0 until subsArray.length()) {
                    val obj = subsArray.getJSONObject(i)
                    val sinceTs = obj.optLong("sinceTimestamp", -1L).takeIf { it > 0 }
                    val rawType = obj.optString("type", "GDRIVE").trim().uppercase()
                    val normType = if (rawType.contains("YOUTUBE")) "YOUTUBE" else "GDRIVE"
                    val entity = SubscriptionEntity(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        type = normType,
                        publicUrl = obj.optString("publicUrl").takeIf { it.isNotBlank() },
                        lastSyncedTime = obj.optLong("lastSyncedTime", 0L),
                        autoAddToPlaylist = obj.optBoolean("autoAddToPlaylist", true),
                        sinceTimestamp = sinceTs,
                        isPlaylist = obj.optBoolean("isPlaylist", false),
                        targetPlaylistGroupId = obj.optString("targetPlaylistGroupId", "default").ifBlank { "default" }
                    )
                    database.subscriptionDao().insert(entity)
                    restoredCount++
                }
            }


            // 2. 還原 Groups
            val restoredGroupIds = mutableSetOf<String>()
            val groupsArray = rootJson.optJSONArray("playlist_groups")
            if (groupsArray != null) {
                for (i in 0 until groupsArray.length()) {
                    val obj = groupsArray.getJSONObject(i)
                    val gid = obj.getString("id")
                    val entity = PlaylistGroupEntity(
                        id = gid,
                        name = obj.getString("name"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                    database.playlistGroupDao().insert(entity)
                    restoredGroupIds.add(gid)
                }
            }
            if (!restoredGroupIds.contains("default")) {
                database.playlistGroupDao().insert(PlaylistGroupEntity("default", "預設清單"))
                restoredGroupIds.add("default")
            }

            // 3. 還原 Playlist Items
            val itemsArray = rootJson.optJSONArray("playlist_items")
            if (itemsArray != null) {
                val items = mutableListOf<PlaylistItemEntity>()
                for (i in 0 until itemsArray.length()) {
                    val obj = itemsArray.getJSONObject(i)
                    val gid = obj.optString("playlistGroupId", "default").ifBlank { "default" }
                    if (!restoredGroupIds.contains(gid)) {
                        database.playlistGroupDao().insert(PlaylistGroupEntity(gid, "播放清單 #$gid"))
                        restoredGroupIds.add(gid)
                    }
                    items.add(
                        PlaylistItemEntity(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            artist = obj.getString("artist"),
                            durationMs = obj.optLong("durationMs", 0L),
                            mediaUri = obj.getString("mediaUri"),
                            sourceType = obj.optString("sourceType", "LOCAL"),
                            artworkUri = obj.optString("artworkUri").takeIf { it.isNotBlank() },
                            driveFolderId = obj.optString("driveFolderId").takeIf { it.isNotBlank() },
                            ytChannelId = obj.optString("ytChannelId").takeIf { it.isNotBlank() },
                            sortOrder = obj.optInt("sortOrder", i),
                            addedTime = obj.optLong("addedTime", System.currentTimeMillis()),
                            playlistGroupId = gid
                        )
                    )
                }
                if (items.isNotEmpty()) {
                    database.playlistDao().insertAll(items)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        restoredCount
    }
}
