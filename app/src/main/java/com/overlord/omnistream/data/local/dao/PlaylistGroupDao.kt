package com.overlord.omnistream.data.local.dao

import androidx.room.*
import com.overlord.omnistream.data.local.entity.PlaylistGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistGroupDao {
    @Query("SELECT * FROM playlist_groups ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<PlaylistGroupEntity>>

    @Query("SELECT * FROM playlist_groups ORDER BY createdAt ASC")
    suspend fun getAll(): List<PlaylistGroupEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: PlaylistGroupEntity)

    @Query("UPDATE playlist_groups SET name = :newName WHERE id = :id")
    suspend fun updateName(id: String, newName: String)

    @Query("DELETE FROM playlist_groups WHERE id = :id")
    suspend fun deleteById(id: String)
}
