package com.overlord.omnistream.core.model

data class PlaylistItem(
    val id: String,                         // 唯一 ID（如本機 uri、drive fileId、yt videoId）
    val title: String,                      // 曲目標題
    val artist: String = "未知來源",         // 演出者 / YouTuber / 資料夾
    val durationMs: Long = 0L,              // 長度 (毫秒)
    val mediaUri: String,                   // 實體播放 URI 或串流連結
    val sourceType: MediaSourceType,        // 來源類別
    val artworkUri: String? = null,         // 封面縮圖 URI
    val driveFolderId: String? = null,      // 若為 GDrive 則記錄所屬資料夾
    val ytChannelId: String? = null,        // 若為 YT 則記錄所屬頻道
    val addedTime: Long = System.currentTimeMillis(), // 加入時間戳
    val lastPlayedTime: Long = 0L           // 最後播放時間戳（0 代表尚未播放過）
)
