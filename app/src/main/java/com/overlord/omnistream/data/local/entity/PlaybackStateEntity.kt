package com.overlord.omnistream.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 保存全域播放狀態（用於跨啟動斷點續播記憶）
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = 1,                 // 單例記錄，固定為 1
    val currentItemId: String?,                 // 上次播放的曲目 ID
    val currentPositionMs: Long,                // 上次播放進度 (毫秒)
    val isPlaying: Boolean = false,              // 上次是否正在播放
    val lastUpdated: Long = System.currentTimeMillis()
)
