package com.overlord.omnistream.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.overlord.omnistream.core.model.PlaylistItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 前端 UI 與 Media3 MediaSession 通訊的控制器 (保證於 Main Thread 調用以防止線程違規崩潰)
 */
@OptIn(UnstableApi::class)
class PlaybackController(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    var mediaController: MediaController? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    val currentMediaItem = _currentMediaItem.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val progressRunnable = object : Runnable {
        override fun run() {
            val controller = mediaController
            if (controller != null) {
                _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                val dur = controller.duration
                if (dur > 0L && dur != C.TIME_UNSET) {
                    _durationMs.value = dur
                }
                if (controller.isPlaying) {
                    mainHandler.postDelayed(this, 250L)
                }
            }
        }
    }

    private fun startProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable)
        mainHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable)
        mediaController?.let { controller ->
            _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
            val dur = controller.duration
            if (dur > 0L && dur != C.TIME_UNSET) {
                _durationMs.value = dur
            }
        }
    }

    fun connect(onConnected: () -> Unit = {}) {
        try {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, OmniMediaSessionService::class.java)
            )
            controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
            controllerFuture?.addListener({
                try {
                    mediaController = controllerFuture?.get()
                    setupPlayerListener()
                    onConnected()
                } catch (e: Throwable) {
                    Log.e("PlaybackController", "Failed to connect to MediaSession", e)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Throwable) {
            Log.e("PlaybackController", "Error initializing SessionToken or Builder", e)
        }
    }

    private fun setupPlayerListener() {
        val controller = mediaController ?: return
        _isPlaying.value = controller.isPlaying
        _currentMediaItem.value = controller.currentMediaItem
        val dur = controller.duration
        if (dur > 0L && dur != C.TIME_UNSET) {
            _durationMs.value = dur
        }
        _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
        if (controller.isPlaying) {
            startProgressUpdates()
        }

        controller.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startProgressUpdates()
                } else {
                    stopProgressUpdates()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _currentMediaItem.value = mediaItem
                val dur = controller.duration
                _durationMs.value = if (dur > 0L && dur != C.TIME_UNSET) dur else 0L
                _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val dur = controller.duration
                if (dur > 0L && dur != C.TIME_UNSET) {
                    _durationMs.value = dur
                }
                _currentPositionMs.value = controller.currentPosition.coerceAtLeast(0L)
                if (playbackState == Player.STATE_READY && controller.isPlaying) {
                    startProgressUpdates()
                }
            }
        })
    }

    private fun runOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    fun play() = runOnMainThread { mediaController?.play() }
    fun pause() = runOnMainThread { mediaController?.pause() }

    fun seekTo(positionMs: Long) = runOnMainThread {
        val controller = mediaController ?: return@runOnMainThread
        val dur = controller.duration
        val target = if (dur > 0L && dur != C.TIME_UNSET) {
            positionMs.coerceIn(0L, dur)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        _currentPositionMs.value = target
        controller.seekTo(target)
    }

    fun seekRelative(offsetMs: Long) = runOnMainThread {
        val controller = mediaController ?: return@runOnMainThread
        val current = controller.currentPosition.coerceAtLeast(0L)
        val dur = controller.duration
        val target = if (dur > 0L && dur != C.TIME_UNSET) {
            (current + offsetMs).coerceIn(0L, dur)
        } else {
            (current + offsetMs).coerceAtLeast(0L)
        }
        _currentPositionMs.value = target
        controller.seekTo(target)
    }

    fun skipToNext() = runOnMainThread { mediaController?.seekToNextMediaItem() }
    fun skipToPrevious() = runOnMainThread { mediaController?.seekToPreviousMediaItem() }

    fun toggleOrPlayItemAtIndex(items: List<PlaylistItem>, index: Int) {
        runOnMainThread {
            val controller = mediaController ?: return@runOnMainThread
            if (index !in items.indices) return@runOnMainThread
            val target = items[index]
            val currentMedia = controller.currentMediaItem

            // 若點擊的是當前正在播/停的同一首曲目 -> 執行 Toggle (播放/暫停切換)
            if (currentMedia != null && currentMedia.mediaId == target.id) {
                if (controller.isPlaying) {
                    controller.pause()
                } else {
                    controller.play()
                }
                return@runOnMainThread
            }

            // 若為不同曲目 -> 切換曲目播放
            playItemAtIndex(items, index)
        }
    }

    fun playItemAtIndex(items: List<PlaylistItem>, index: Int) {
        runOnMainThread {
            val controller = mediaController ?: return@runOnMainThread
            if (index !in items.indices) return@runOnMainThread
            val target = items[index]

            // 1. 若當前 MediaController 佇列與傳入 items 大小相同且該位置 mediaId 一致，直接 seekToDefaultPosition 播放
            if (controller.mediaItemCount == items.size) {
                val current = controller.getMediaItemAt(index)
                if (current.mediaId == target.id) {
                    controller.seekToDefaultPosition(index)
                    controller.play()
                    return@runOnMainThread
                }
            }

            // 2. 檢查佇列中是否已有該 target.id（例如清單大小不完全吻合但佇列中存在此曲）
            for (i in 0 until controller.mediaItemCount) {
                if (controller.getMediaItemAt(i).mediaId == target.id) {
                    controller.seekToDefaultPosition(i)
                    controller.play()
                    return@runOnMainThread
                }
            }

            // 3. 佇列中沒有此曲或清單變更，重建佇列並指定 startIndex 播放
            setPlaylistAndPlay(items, startIndex = index)
        }
    }

    fun setPlaylistAndPlay(items: List<PlaylistItem>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        runOnMainThread {
            val controller = mediaController ?: return@runOnMainThread
            val mediaItems = items.map { item ->
                MediaItem.Builder()
                    .setMediaId(item.id)
                    .setUri(item.mediaUri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.artist)
                            .build()
                    )
                    .build()
            }
            _currentPositionMs.value = startPositionMs
            controller.setMediaItems(mediaItems, startIndex, startPositionMs)
            controller.prepare()
            controller.play()
        }
    }

    fun release() {
        runOnMainThread {
            stopProgressUpdates()
            controllerFuture?.let { MediaController.releaseFuture(it) }
        }
    }
}

