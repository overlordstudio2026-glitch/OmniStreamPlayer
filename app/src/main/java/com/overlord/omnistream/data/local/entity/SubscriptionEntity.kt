package com.overlord.omnistream.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 追蹤的 Google 雲端資料夾或 YouTube 頻道
 */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,                 // 資料夾 ID 或 頻道 ID
    val name: String,                           // 資料夾名稱或頻道名稱
    val type: String,                           // "GDRIVE" 或 "YOUTUBE"
    val publicUrl: String? = null,              // 若為公開共用連結則記錄
    val lastSyncedTime: Long = 0L,              // 上次同步時間
    val autoAddToPlaylist: Boolean = true,      // 是否自動將新內容加入播放清單
    val sinceTimestamp: Long? = null,           // 時間因子：只收此時間之後的新影片
    val isPlaylist: Boolean = false,            // 標記是否為 YouTube 播放清單
    val targetPlaylistGroupId: String = "default" // 綁定指定的播放清單群組 ID
)
