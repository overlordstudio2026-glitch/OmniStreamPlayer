package com.overlord.omnistream.data.repository

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.overlord.omnistream.core.model.MediaSourceType
import com.overlord.omnistream.core.model.PlaylistItem
import com.overlord.omnistream.data.gdrive.GoogleDriveService
import com.overlord.omnistream.data.local.AppDatabase
import com.overlord.omnistream.data.local.entity.PlaybackStateEntity
import com.overlord.omnistream.data.local.entity.PlaylistGroupEntity
import com.overlord.omnistream.data.local.entity.PlaylistItemEntity
import com.overlord.omnistream.data.local.entity.SubscriptionEntity
import com.overlord.omnistream.data.youtube.YouTubeAudioExtractor
import com.overlord.omnistream.data.youtube.YouTubePlaylistParser
import com.overlord.omnistream.data.youtube.YouTubeRssParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlayerRepository(
    private val context: Context,
    private val database: AppDatabase
) {

    val gdriveService by lazy { GoogleDriveService() }
    val ytRssParser by lazy { YouTubeRssParser() }
    val ytPlaylistParser by lazy { YouTubePlaylistParser() }
    val ytAudioExtractor by lazy { YouTubeAudioExtractor() }

    val playlistDao by lazy { database.playlistDao() }
    val groupDao by lazy { database.playlistGroupDao() }
    val playbackStateDao by lazy { database.playbackStateDao() }
    val subscriptionDao by lazy { database.subscriptionDao() }
    val backupManager by lazy { com.overlord.omnistream.data.backup.ConfigBackupManager(context, database) }


    // 播放清單群組
    fun getPlaylistGroupsFlow(): Flow<List<PlaylistGroupEntity>> = groupDao.getAllFlow()

    suspend fun createPlaylistGroup(id: String, name: String) {
        groupDao.insert(PlaylistGroupEntity(id = id, name = name))
        backupManager.createBackup()
    }

    suspend fun renamePlaylistGroup(id: String, newName: String) {
        groupDao.updateName(id, newName)
        backupManager.createBackup()
    }

    suspend fun deletePlaylistGroup(id: String) {
        groupDao.deleteById(id)
        playlistDao.clearGroup(id)
        backupManager.createBackup()
    }

    // 取得指定群組的播放清單
    fun getPlaylistFlow(groupId: String = "default"): Flow<List<PlaylistItem>> {
        return playlistDao.getByGroupFlow(groupId).map { list -> list.map { it.toDomain() } }
    }

    suspend fun getPlaylistItems(groupId: String = "default"): List<PlaylistItem> {
        return playlistDao.getByGroup(groupId).map { it.toDomain() }
    }

    suspend fun addItemsToPlaylist(items: List<PlaylistItem>, groupId: String = "default") {
        val currentMax = playlistDao.getMaxSortOrder(groupId) ?: 0
        val entities = items.mapIndexed { index, item ->
            PlaylistItemEntity.fromDomain(item, currentMax + index + 1, groupId)
        }
        playlistDao.insertAll(entities)
        backupManager.createBackup()
    }

    /**
     * 深度同步並合併 Google Drive 資料夾項目：
     * 1. 抓取雲端最新檔案（包含持續新增的集數）
     * 2. 與群組內既有項目合併（補充新檔案）
     * 3. 依自然排序重新整理整個群組中所有項目的 sortOrder
     * 4. 即時觸發備份
     * @return 新增的檔案數量
     */
    suspend fun syncAndMergeFolderItems(
        groupId: String,
        folderId: String,
        folderName: String
    ): Int = withContext(Dispatchers.IO) {
        val remoteFiles = gdriveService.fetchFolderAudioFiles(folderId, folderName)
        if (remoteFiles.isEmpty()) return@withContext 0

        val existingEntities = playlistDao.getByGroup(groupId)
        val existingMap = existingEntities.associateBy { it.id }.toMutableMap()
        var newCount = 0

        // 檢查並加入新檔案
        for (file in remoteFiles) {
            if (!existingMap.containsKey(file.id)) {
                val newEntity = PlaylistItemEntity.fromDomain(file, 0, groupId)
                existingMap[file.id] = newEntity
                newCount++
            }
        }

        // 對現有群組內所有項目（包含既有的其他項目與新加入的項目）重新進行自然排序
        val allItems = existingMap.values.toList()
        val sortedList = allItems.sortedWith { a, b ->
            GoogleDriveService.naturalCompare(a.title, b.title)
        }

        val reorderedEntities = sortedList.mapIndexed { index, entity ->
            entity.copy(sortOrder = index + 1)
        }

        playlistDao.insertAll(reorderedEntities)
        backupManager.createBackup()
        return@withContext newCount
    }

    suspend fun removeItemFromPlaylist(id: String) {
        playlistDao.deleteById(id)
        backupManager.createBackup()
    }

    suspend fun removeItemsFromPlaylist(ids: List<String>) {
        if (ids.isNotEmpty()) {
            playlistDao.deleteByIds(ids)
            backupManager.createBackup()
        }
    }

    // --- 永久持久化訂閱鏡像 (防資料庫被抹除核心機制) ---
    private val persistentPrefs by lazy { context.getSharedPreferences("omnistream_persistent_meta", Context.MODE_PRIVATE) }
    private val persistentFile by lazy { File(context.filesDir, "subscriptions_persistent.json") }

    suspend fun saveSubscription(entity: SubscriptionEntity) {
        subscriptionDao.insert(entity)
        savePersistentSubscriptionsMirror(subscriptionDao.getAll())
        backupManager.createBackup()
    }

    suspend fun savePersistentSubscriptionsMirror(subs: List<SubscriptionEntity>) = withContext(Dispatchers.IO) {
        if (subs.isEmpty()) return@withContext
        try {
            val array = JSONArray()
            for (s in subs) {
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("type", s.type)
                    put("publicUrl", s.publicUrl)
                    put("lastSyncedTime", s.lastSyncedTime)
                    put("autoAddToPlaylist", s.autoAddToPlaylist)
                    put("sinceTimestamp", s.sinceTimestamp ?: -1L)
                    put("isPlaylist", s.isPlaylist)
                    put("targetPlaylistGroupId", s.targetPlaylistGroupId)
                }
                array.put(obj)
            }
            val json = array.toString(2)
            persistentFile.writeText(json, Charsets.UTF_8)
            persistentPrefs.edit().putString("subscriptions_mirror", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun restoreSubscriptionsFromAnySource(): Int = withContext(Dispatchers.IO) {
        var restored = 0
        // 來源 1: 內部私有檔案 subscriptions_persistent.json (永久不受外在環境影響)
        if (persistentFile.exists() && persistentFile.length() > 10) {
            try {
                val json = persistentFile.readText(Charsets.UTF_8)
                restored = restoreSubscriptionsFromJsonArrayString(json)
                if (restored > 0) return@withContext restored
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 來源 2: SharedPreferences 鏡像
        val prefJson = persistentPrefs.getString("subscriptions_mirror", null)
        if (!prefJson.isNullOrBlank() && prefJson.length > 10) {
            try {
                restored = restoreSubscriptionsFromJsonArrayString(prefJson)
                if (restored > 0) return@withContext restored
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 來源 3: ConfigBackupManager (Downloads 目錄與 MediaStore 備份)
        restored = backupManager.restoreBackupIfAvailable()
        if (restored > 0) {
            savePersistentSubscriptionsMirror(subscriptionDao.getAll())
        }
        restored
    }

    private suspend fun restoreSubscriptionsFromJsonArrayString(jsonString: String): Int {
        var count = 0
        try {
            val array = JSONArray(jsonString)
            val list = mutableListOf<SubscriptionEntity>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rawType = obj.optString("type", "GDRIVE").trim().uppercase()
                val normType = if (rawType.contains("YOUTUBE")) "YOUTUBE" else "GDRIVE"
                val sinceTs = obj.optLong("sinceTimestamp", -1L).takeIf { it > 0 }
                list.add(
                    SubscriptionEntity(
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
                )
                count++
            }
            if (list.isNotEmpty()) {
                subscriptionDao.insertAll(list)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return count
    }

    suspend fun clearPlaylist(groupId: String = "default") {
        playlistDao.clearGroup(groupId)
        backupManager.createBackup()
    }

    suspend fun updateSubscriptionName(id: String, name: String) {
        subscriptionDao.updateName(id, name)
        savePersistentSubscriptionsMirror(subscriptionDao.getAll())
        backupManager.createBackup()
    }

    suspend fun updateSubscriptionTargetGroup(id: String, targetGroupId: String) {
        subscriptionDao.updateTargetPlaylistGroup(id, targetGroupId)
        savePersistentSubscriptionsMirror(subscriptionDao.getAll())
        backupManager.createBackup()
    }

    suspend fun moveItemToGroup(itemId: String, newGroupId: String) {
        playlistDao.updateItemGroupId(itemId, newGroupId)
        backupManager.createBackup()
    }

    suspend fun moveItemsToGroup(itemIds: List<String>, newGroupId: String) {
        if (itemIds.isNotEmpty()) {
            playlistDao.updateItemsGroupId(itemIds, newGroupId)
            backupManager.createBackup()
        }
    }

    suspend fun deleteSubscription(id: String) {
        subscriptionDao.deleteById(id)
        val remaining = subscriptionDao.getAll()
        if (remaining.isNotEmpty()) {
            savePersistentSubscriptionsMirror(remaining)
        } else {
            persistentFile.delete()
            persistentPrefs.edit().remove("subscriptions_mirror").apply()
        }
        backupManager.createBackup()
    }


    suspend fun ensureDefaultGroup() {
        val existing = groupDao.getAll()
        if (existing.isEmpty()) {
            groupDao.insert(PlaylistGroupEntity(id = "default", name = "預設清單"))
        }
    }

    suspend fun updateItemMediaUri(id: String, mediaUri: String) {
        playlistDao.updateMediaUri(id, mediaUri)
    }

    suspend fun updateItemDetails(id: String, title: String, artist: String, mediaUri: String) {
        playlistDao.updateItemDetails(id, title, artist, mediaUri)
    }

    // 本機音訊掃描 (MediaStore)
    suspend fun scanLocalAudioFiles(): List<PlaylistItem> = withContext(Dispatchers.IO) {
        val list = mutableListOf<PlaylistItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val query = context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )
        query?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol) ?: "未知曲目"
                val artist = cursor.getString(artistCol) ?: "本機音訊"
                val duration = cursor.getLong(durationCol)
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                list.add(
                    PlaylistItem(
                        id = "local_$id",
                        title = title,
                        artist = artist,
                        durationMs = duration,
                        mediaUri = contentUri.toString(),
                        sourceType = MediaSourceType.LOCAL
                    )
                )
            }
        }
        return@withContext list
    }

    // 斷點續播狀態
    fun getPlaybackStateFlow(): Flow<PlaybackStateEntity?> = playbackStateDao.getStateFlow()
    suspend fun getPlaybackState(): PlaybackStateEntity? = playbackStateDao.getState()
    suspend fun savePlaybackState(currentId: String?, positionMs: Long, isPlaying: Boolean) {
        playbackStateDao.saveState(
            PlaybackStateEntity(
                currentItemId = currentId,
                currentPositionMs = positionMs,
                isPlaying = isPlaying
            )
        )
    }

    // 紀錄曲目已播放過
    suspend fun recordTrackPlayed(id: String, timestamp: Long = System.currentTimeMillis()) {
        playlistDao.updateLastPlayedTime(id, timestamp)
    }

    // 更新曲目時長 (毫秒)
    suspend fun updateItemDuration(id: String, durationMs: Long) {
        if (durationMs > 0L) {
            playlistDao.updateDuration(id, durationMs)
        }
    }
}
