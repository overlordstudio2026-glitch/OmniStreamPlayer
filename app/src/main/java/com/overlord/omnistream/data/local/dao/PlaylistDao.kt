package com.overlord.omnistream.data.local.dao

import androidx.room.*
import com.overlord.omnistream.data.local.entity.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist_items WHERE playlistGroupId = :groupId ORDER BY sortOrder ASC")
    fun getByGroupFlow(groupId: String): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistGroupId = :groupId ORDER BY sortOrder ASC")
    suspend fun getByGroup(groupId: String): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlist_items ORDER BY sortOrder ASC")
    fun getAllFlow(): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items ORDER BY sortOrder ASC")
    suspend fun getAll(): List<PlaylistItemEntity>

    @Query("SELECT * FROM playlist_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PlaylistItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PlaylistItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM playlist_items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM playlist_items WHERE playlistGroupId = :groupId")
    suspend fun clearGroup(groupId: String)

    @Query("SELECT MAX(sortOrder) FROM playlist_items WHERE playlistGroupId = :groupId")
    suspend fun getMaxSortOrder(groupId: String): Int?

    @Query("UPDATE playlist_items SET mediaUri = :mediaUri WHERE id = :id")
    suspend fun updateMediaUri(id: String, mediaUri: String)

    @Query("UPDATE playlist_items SET title = :title, artist = :artist, mediaUri = :mediaUri WHERE id = :id")
    suspend fun updateItemDetails(id: String, title: String, artist: String, mediaUri: String)

    @Query("UPDATE playlist_items SET playlistGroupId = :newGroupId WHERE id = :id")
    suspend fun updateItemGroupId(id: String, newGroupId: String)

    @Query("UPDATE playlist_items SET playlistGroupId = :newGroupId WHERE id IN (:ids)")
    suspend fun updateItemsGroupId(ids: List<String>, newGroupId: String)

    @Query("UPDATE playlist_items SET lastPlayedTime = :timestamp WHERE id = :id")
    suspend fun updateLastPlayedTime(id: String, timestamp: Long)

    @Query("UPDATE playlist_items SET durationMs = :durationMs WHERE id = :id AND durationMs <= 0")
    suspend fun updateDuration(id: String, durationMs: Long)
}
