package com.overlord.omnistream.playback

import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.overlord.omnistream.OmniStreamApp
import com.overlord.omnistream.core.cache.MediaCacheManager
import com.overlord.omnistream.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 支援關閉螢幕背景連續播放、耳機/藍芽中斷自動暫停、歷史播放記錄與時長自動記錄的核心 Media3 前台服務
 */
@OptIn(UnstableApi::class)
class OmniMediaSessionService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var crossfadeManager: CrossfadeManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var progressTrackerJob: Job? = null
    private var lastProgressSaveTime = 0L

    // 螢幕關閉時防止 CPU 深度休眠與 Wi-Fi 降頻的喚醒鎖
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioManager: AudioManager? = null

    // 1. 現代 AudioDeviceCallback 監聽（偵測 A2DP 藍芽耳機、BLE 耳機、Type-C / 3.5mm 耳機等拔除事件）
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesRemoved(removedDevices)
            if (removedDevices == null) return
            val isHeadphoneOrBluetoothRemoved = removedDevices.any { dev ->
                when (dev.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> true
                    else -> false
                }
            }
            if (isHeadphoneOrBluetoothRemoved) {
                android.util.Log.i("OmniSessionService", "Audio output device removed (AudioDeviceCallback), auto-pausing playback.")
                handleAutoPauseOnDeviceDisconnected()
            }
        }
    }

    // 2. 廣播監聽（備援監聽 ACTION_AUDIO_BECOMING_NOISY、藍芽 A2DP 狀態切換與 ACL 斷線）
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    android.util.Log.i("OmniSessionService", "ACTION_AUDIO_BECOMING_NOISY received, auto-pausing playback.")
                    handleAutoPauseOnDeviceDisconnected()
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (state == BluetoothProfile.STATE_DISCONNECTED) {
                        android.util.Log.i("OmniSessionService", "BluetoothA2dp disconnected, auto-pausing playback.")
                        handleAutoPauseOnDeviceDisconnected()
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    android.util.Log.i("OmniSessionService", "BluetoothDevice ACL disconnected, auto-pausing playback.")
                    handleAutoPauseOnDeviceDisconnected()
                }
            }
        }
    }

    /**
     * 當偵測到耳機或藍芽斷開時，只要播放器有播放意圖或正在播放，一律強制暫停
     */
    private fun handleAutoPauseOnDeviceDisconnected() {
        if (::exoPlayer.isInitialized && (exoPlayer.playWhenReady || exoPlayer.isPlaying)) {
            android.util.Log.i("OmniSessionService", "Enforcing auto-pause on device disconnection.")
            exoPlayer.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as OmniStreamApp
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // 0. 初始化硬體 WakeLock 與 WifiLock
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OmniStream:PlaybackWakeLock")
            wakeLock?.setReferenceCounted(false)

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "OmniStream:PlaybackWifiLock")
            wifiLock?.setReferenceCounted(false)
        } catch (e: Throwable) {
            android.util.Log.w("OmniSessionService", "Failed to acquire power/wifi lock managers", e)
        }

        // 註冊硬體音訊設備移除監聽
        try {
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: Throwable) {
            android.util.Log.w("OmniSessionService", "Failed to register AudioDeviceCallback", e)
        }

        // 註冊廣播監聽耳機斷線
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(becomingNoisyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(becomingNoisyReceiver, filter)
        }

        // 1. 配置相容 User-Agent 與快取 DataSource
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.android.youtube/21.26.364 (Linux; U; Android 11)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)

        val upstreamFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val cacheDataSourceFactory = MediaCacheManager.createCacheDataSourceFactory(upstreamFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(cacheDataSourceFactory)

        // 2. 建立 ExoPlayer 實例並設置音訊屬性
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true // handleAudioFocus: 自動處理音訊焦點（來電暫停、通話結束續播）
            )
            .setHandleAudioBecomingNoisy(true) // 耳機/藍芽斷開時原生自動暫停
            .setWakeMode(C.WAKE_MODE_NETWORK) // 鎖屏時保持網路與 CPU 運作
            .build()

        crossfadeManager = CrossfadeManager(exoPlayer, serviceScope)

        // 3. 監聽播放進度以支援跨啟動斷點續播、已播過記錄、時長同步與關閉螢幕連續播放
        exoPlayer.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && exoPlayer.mediaItemCount > 0) {
                    val count = exoPlayer.mediaItemCount
                    val currentIndex = exoPlayer.currentMediaItemIndex
                    exoPlayer.setShuffleOrder(BetterShuffleOrder(count, currentIndex))
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                crossfadeManager.onTrackTransition()
                saveCurrentPlaybackProgress()
                updateLocks()
                if (mediaItem == null) return

                val currentId = mediaItem.mediaId
                val currentUri = mediaItem.localConfiguration?.uri?.toString() ?: ""

                // 1. 記錄曲目已播放過
                serviceScope.launch(Dispatchers.IO) {
                    app.repository.recordTrackPlayed(currentId)
                }

                // 2. 獲取 YouTube SponsorBlock 業配片段
                if (currentId.startsWith("yt_")) {
                    val vid = com.overlord.omnistream.data.youtube.YouTubeAudioExtractor.extractVideoId(currentId)
                    serviceScope.launch(Dispatchers.IO) {
                        app.sponsorBlockManager.fetchSegments(vid)
                    }
                }

                // 2. 檢查 YouTube 直鏈是否為網頁網址、空白、或已過期
                if (currentId.startsWith("yt_") && isYouTubeUrlExpiredOrUnresolved(currentUri)) {
                    android.util.Log.d("OmniSessionService", "onMediaItemTransition: Current item $currentId needs resolving on screen-off")
                    // 鎖定 CPU 喚醒保證後台網路請求不受休眠凍結
                    wakeLock?.acquire(60 * 1000L)
                    serviceScope.launch(Dispatchers.IO) {
                        val vid = com.overlord.omnistream.data.youtube.YouTubeAudioExtractor.extractVideoId(currentId)
                        val mediaInfo = app.repository.ytAudioExtractor.extractMediaInfo(vid)
                        if (mediaInfo != null && mediaInfo.audioUrl.isNotBlank()) {
                            app.repository.updateItemMediaUri(currentId, mediaInfo.audioUrl)
                            if (mediaInfo.durationMs > 0L) {
                                app.repository.updateItemDuration(currentId, mediaInfo.durationMs)
                            }
                            withContext(Dispatchers.Main) {
                                if (exoPlayer.currentMediaItem?.mediaId == currentId) {
                                    val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                                    val updatedMediaItem = mediaItem.buildUpon()
                                        .setUri(mediaInfo.audioUrl)
                                        .build()
                                    val currentIndex = exoPlayer.currentMediaItemIndex
                                    exoPlayer.replaceMediaItem(currentIndex, updatedMediaItem)
                                    if (currentPos > 0) {
                                        exoPlayer.seekTo(currentIndex, currentPos)
                                    }
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                    android.util.Log.d("OmniSessionService", "Successfully updated & resumed $currentId at $currentPos ms")
                                }
                            }
                        } else {
                            // 解析失敗或影片失效時，自動跳轉至下一首，防止鎖屏下播放停滯
                            withContext(Dispatchers.Main) {
                                android.util.Log.w("OmniSessionService", "Failed to resolve $currentId, auto-skipping to next item...")
                                if (exoPlayer.hasNextMediaItem()) {
                                    exoPlayer.seekToNextMediaItem()
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                }
                            }
                        }
                    }
                }

                // 提前預解析下一首及後續 YouTube 曲目 (Pre-fetch Next)，達成完全無感零延遲連續播放
                prefetchUpcomingYouTubeItems()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // 使用者手動 Seek 時，重設淡化狀態與音量避免卡死
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    crossfadeManager.cancelFadeAndResetVolume(1.0f)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveCurrentPlaybackProgress()
                updateLocks()
                if (isPlaying) {
                    startProgressTracker()
                    prefetchUpcomingYouTubeItems()
                } else {
                    crossfadeManager.cancelFadeAndResetVolume(1.0f)
                    progressTrackerJob?.cancel()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateLocks()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateLocks()
                if (playbackState == Player.STATE_READY) {
                    val currentItem = exoPlayer.currentMediaItem
                    if (currentItem != null) {
                        val dur = exoPlayer.duration
                        if (dur > 0L && dur != C.TIME_UNSET) {
                            serviceScope.launch(Dispatchers.IO) {
                                app.repository.updateItemDuration(currentItem.mediaId, dur)
                            }
                        }
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    // 若有下一首曲目，主動引導至下一首播放
                    if (exoPlayer.hasNextMediaItem()) {
                        exoPlayer.seekToNextMediaItem()
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w("OmniSessionService", "Player error: ${error.errorCodeName}, attempting recovery or skip...", error)
                crossfadeManager.cancelFadeAndResetVolume(1.0f)
                val currentItem = exoPlayer.currentMediaItem ?: return
                val currentId = currentItem.mediaId

                if (currentId.startsWith("yt_")) {
                    wakeLock?.acquire(60 * 1000L)
                    serviceScope.launch(Dispatchers.IO) {
                        val vid = com.overlord.omnistream.data.youtube.YouTubeAudioExtractor.extractVideoId(currentId)
                        val freshInfo = app.repository.ytAudioExtractor.extractMediaInfo(vid)
                        if (freshInfo != null && freshInfo.audioUrl.isNotBlank()) {
                            app.repository.updateItemMediaUri(currentId, freshInfo.audioUrl)
                            if (freshInfo.durationMs > 0L) {
                                app.repository.updateItemDuration(currentId, freshInfo.durationMs)
                            }
                            withContext(Dispatchers.Main) {
                                val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                                val updatedMediaItem = currentItem.buildUpon()
                                    .setUri(freshInfo.audioUrl)
                                    .build()
                                val currentIndex = exoPlayer.currentMediaItemIndex
                                exoPlayer.replaceMediaItem(currentIndex, updatedMediaItem)
                                exoPlayer.seekTo(currentIndex, currentPos)
                                exoPlayer.prepare()
                                exoPlayer.play()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                android.util.Log.w("OmniSessionService", "Cannot recover $currentId, auto skipping to next media item.")
                                if (exoPlayer.hasNextMediaItem()) {
                                    exoPlayer.seekToNextMediaItem()
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                }
                            }
                        }
                    }
                } else {
                    if (exoPlayer.hasNextMediaItem()) {
                        serviceScope.launch(Dispatchers.Main) {
                            delay(1000)
                            exoPlayer.seekToNextMediaItem()
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                    }
                }
            }
        })

        // 4. 點擊通知欄時跳轉回 MainActivity
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(pendingIntent)
            .build()
    }

    /**
     * 檢查網址是否為 YouTube 網頁網址、空白、或即將過期的 googlevideo 直鏈
     */
    private fun isYouTubeUrlExpiredOrUnresolved(uri: String): Boolean {
        if (uri.isBlank() || uri.contains("youtube.com/watch")) return true
        if (uri.contains("googlevideo.com") && uri.contains("expire=")) {
            val expireMatch = "expire=(\\d+)".toRegex().find(uri)
            if (expireMatch != null) {
                val expireSec = expireMatch.groupValues[1].toLongOrNull() ?: 0L
                val currentSec = System.currentTimeMillis() / 1000L
                if (expireSec <= currentSec + 300) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * 控制喚醒鎖：只要使用者播放意圖為真 (playWhenReady == true) 且播放器未停止，鎖屏時必須持續持有喚醒鎖，
     * 避免曲目結束至下一首開始時因短暫緩衝而被系統深度休眠凍結網路
     */
    private fun updateLocks() {
        try {
            val shouldHold = ::exoPlayer.isInitialized && exoPlayer.playWhenReady && (exoPlayer.playbackState != Player.STATE_IDLE)
            if (shouldHold) {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(3600 * 1000L) // 最長維持 1 小時喚醒
                }
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w("OmniSessionService", "Error toggling locks", e)
        }
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = serviceScope.launch {
            while (isActive) {
                delay(500)
                val currentItem = exoPlayer.currentMediaItem
                if (currentItem != null && exoPlayer.isPlaying) {
                    val currentPos = exoPlayer.currentPosition
                    val dur = exoPlayer.duration

                    // 1. SponsorBlock 自動跳過業配
                    if (currentItem.mediaId.startsWith("yt_")) {
                        val vid = com.overlord.omnistream.data.youtube.YouTubeAudioExtractor.extractVideoId(currentItem.mediaId)
                        val skipTarget = (applicationContext as OmniStreamApp).sponsorBlockManager.checkSkipTarget(vid, currentPos)
                        if (skipTarget != null) {
                            android.util.Log.i("OmniSessionService", "SponsorBlock skipping from $currentPos to $skipTarget")
                            exoPlayer.seekTo(skipTarget)
                        }
                    }

                    // 2. DJ 級淡入淡出進度跟隨
                    crossfadeManager.onPlaybackProgress(currentPos, dur)
                }

                val now = System.currentTimeMillis()
                if (now - lastProgressSaveTime >= 3000L) {
                    lastProgressSaveTime = now
                    saveCurrentPlaybackProgress()
                }
            }
        }
    }

    private fun saveCurrentPlaybackProgress() {
        val currentItem = exoPlayer.currentMediaItem ?: return
        val currentPosition = exoPlayer.currentPosition
        val isPlaying = exoPlayer.isPlaying
        val app = applicationContext as OmniStreamApp
        serviceScope.launch(Dispatchers.IO) {
            app.repository.savePlaybackState(currentItem.mediaId, currentPosition, isPlaying)
        }
    }

    /**
     * 提前預解析佇列中的後續 2 首 YouTube 音訊直鏈 (Pre-fetch Upcoming Items)
     */
    private fun prefetchUpcomingYouTubeItems() {
        val currentIndex = exoPlayer.currentMediaItemIndex
        val maxIndex = (currentIndex + 2).coerceAtMost(exoPlayer.mediaItemCount - 1)
        val app = applicationContext as OmniStreamApp

        for (targetIndex in (currentIndex + 1)..maxIndex) {
            val item = exoPlayer.getMediaItemAt(targetIndex)
            val id = item.mediaId
            val uri = item.localConfiguration?.uri?.toString() ?: ""

            if (id.startsWith("yt_") && isYouTubeUrlExpiredOrUnresolved(uri)) {
                serviceScope.launch(Dispatchers.IO) {
                    val vid = com.overlord.omnistream.data.youtube.YouTubeAudioExtractor.extractVideoId(id)
                    val info = app.repository.ytAudioExtractor.extractMediaInfo(vid)
                    if (info != null && info.audioUrl.isNotBlank()) {
                        app.repository.updateItemMediaUri(id, info.audioUrl)
                        if (info.durationMs > 0L) {
                            app.repository.updateItemDuration(id, info.durationMs)
                        }
                        withContext(Dispatchers.Main) {
                            if (targetIndex < exoPlayer.mediaItemCount && exoPlayer.getMediaItemAt(targetIndex).mediaId == id) {
                                val updated = item.buildUpon()
                                    .setUri(info.audioUrl)
                                    .build()
                                exoPlayer.replaceMediaItem(targetIndex, updated)
                                android.util.Log.d("OmniSessionService", "Successfully pre-fetched YouTube audio for queue index $targetIndex ($id)")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        try {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (e: Exception) {
            // ignore
        }
        try {
            unregisterReceiver(becomingNoisyReceiver)
        } catch (e: Exception) {
            // ignore
        }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        saveCurrentPlaybackProgress()
        progressTrackerJob?.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
