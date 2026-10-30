package com.overlord.omnistream.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 多播放清單群組實體 (支援自訂不同播放清單)
 */
@Entity(tableName = "playlist_groups")
data class PlaylistGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)
