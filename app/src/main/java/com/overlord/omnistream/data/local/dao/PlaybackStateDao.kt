package com.overlord.omnistream.data.local.dao

import androidx.room.*
import com.overlord.omnistream.data.local.entity.PlaybackStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackStateDao {
    @Query("SELECT * FROM playback_state WHERE id = 1")
    fun getStateFlow(): Flow<PlaybackStateEntity?>

    @Query("SELECT * FROM playback_state WHERE id = 1")
    suspend fun getState(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: PlaybackStateEntity)
}
