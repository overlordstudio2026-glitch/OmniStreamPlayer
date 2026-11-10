package com.overlord.omnistream.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.overlord.omnistream.core.model.MediaSourceType
import com.overlord.omnistream.core.model.PlaylistItem

@Entity(tableName = "playlist_items")
data class PlaylistItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val mediaUri: String,
    val sourceType: String,
    val artworkUri: String?,
    val lyricsUri: String? = null,               // 專屬歌詞 URI（雲端或本機歌詞檔）
    val driveFolderId: String?,
    val ytChannelId: String?,
    val sortOrder: Int,
    val addedTime: Long,
    val playlistGroupId: String = "default",      // 歸屬的播放清單群組
    val lastPlayedTime: Long = 0L                // 最後播放時間戳（0 代表尚未播放）
) {
    fun toDomain(): PlaylistItem {
        val safeType = runCatching { MediaSourceType.valueOf(sourceType) }.getOrDefault(MediaSourceType.LOCAL)
        return PlaylistItem(
            id = id,
            title = title,
            artist = artist,
            durationMs = durationMs,
            mediaUri = mediaUri,
            sourceType = safeType,
            artworkUri = artworkUri,
            lyricsUri = lyricsUri,
            driveFolderId = driveFolderId,
            ytChannelId = ytChannelId,
            addedTime = addedTime,
            lastPlayedTime = lastPlayedTime
        )
    }

    companion object {
        fun fromDomain(item: PlaylistItem, sortOrder: Int, groupId: String = "default"): PlaylistItemEntity {
            return PlaylistItemEntity(
                id = item.id,
                title = item.title,
                artist = item.artist,
                durationMs = item.durationMs,
                mediaUri = item.mediaUri,
                sourceType = item.sourceType.name,
                artworkUri = item.artworkUri,
                lyricsUri = item.lyricsUri,
                driveFolderId = item.driveFolderId,
                ytChannelId = item.ytChannelId,
                sortOrder = sortOrder,
                addedTime = item.addedTime,
                playlistGroupId = groupId,
                lastPlayedTime = item.lastPlayedTime
            )
        }
    }
}
