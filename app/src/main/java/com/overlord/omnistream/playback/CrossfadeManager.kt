package com.overlord.omnistream.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*

/**
 * DJ 級平滑交叉淡入淡出管理器 (Crossfade Manager)
 * 監控曲目結尾與開頭，透過時間加權音量曲線達成歌曲之間的無縫過渡
 */
class CrossfadeManager(
    private val exoPlayer: ExoPlayer,
    private val scope: CoroutineScope
) {
    var enabled: Boolean = true
    var crossfadeDurationMs: Long = 3500L // 預設 3.5 秒淡化

    private var crossfadeJob: Job? = null
    private var isFadingOut = false

    /**
     * 定期由進度追蹤器調用，檢查是否接近歌曲結尾並啟動淡出
     */
    fun onPlaybackProgress(currentPositionMs: Long, durationMs: Long) {
        if (!enabled || durationMs <= crossfadeDurationMs * 2 || !exoPlayer.isPlaying) {
            return
        }

        val remainingMs = durationMs - currentPositionMs
        if (remainingMs in 1..crossfadeDurationMs && !isFadingOut) {
            startFadeOut(remainingMs)
        }
    }

    /**
     * 當曲目切換時調用，啟動新曲目的平滑淡入
     */
    fun onTrackTransition() {
        if (!enabled) {
            exoPlayer.volume = 1.0f
            return
        }
        startFadeIn()
    }

    private fun startFadeOut(remainingMs: Long) {
        isFadingOut = true
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch(Dispatchers.Main) {
            val steps = 20
            val interval = (remainingMs / steps).coerceAtLeast(50L)
            for (i in steps downTo 0) {
                if (!isActive || !isFadingOut) break
                val vol = (i.toFloat() / steps).coerceIn(0.05f, 1.0f)
                exoPlayer.volume = vol
                delay(interval)
            }
        }
    }

    private fun startFadeIn() {
        isFadingOut = false
        crossfadeJob?.cancel()
        crossfadeJob = scope.launch(Dispatchers.Main) {
            exoPlayer.volume = 0.05f
            val steps = 20
            val interval = (crossfadeDurationMs / steps).coerceAtLeast(50L)
            for (i in 1..steps) {
                if (!isActive) break
                val vol = (i.toFloat() / steps).coerceIn(0.05f, 1.0f)
                exoPlayer.volume = vol
                delay(interval)
            }
            exoPlayer.volume = 1.0f
        }
    }

    fun release() {
        crossfadeJob?.cancel()
        exoPlayer.volume = 1.0f
    }
}
