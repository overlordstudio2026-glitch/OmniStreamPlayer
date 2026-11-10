package com.overlord.omnistream.core.cache

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.overlord.omnistream.data.youtube.YouTubeAudioExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 縮圖與封面影像快取管理器
 * - 記憶體快取：上限 15% 可用記憶體，防止 OOM 與頻繁 GC 卡頓
 * - 磁碟快取：專屬目錄 50 MB LRU 硬性上限，超標自動淘汰最久未讀縮圖
 * - 定期清理：每 14 天於背景檢查並重置過期快取，兼顧流暢與儲存空間整潔
 */
object ImageCacheManager {

    private const val TAG = "ImageCacheManager"
    private const val CACHE_DIR_NAME = "omni_image_cache"
    private const val PREFS_NAME = "omnistream_cache_prefs"
    private const val KEY_LAST_PRUNE_TS = "last_image_prune_ts"

    private const val MAX_MEMORY_PERCENT = 0.15
    private const val MAX_DISK_CACHE_BYTES = 50L * 1024 * 1024 // 50 MB LRU
    private const val PRUNE_INTERVAL_MS = 14L * 24 * 60 * 60 * 1000 // 14 天定期清理

    @Volatile
    private var imageLoader: ImageLoader? = null

    fun getImageLoader(context: Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: buildImageLoader(context.applicationContext).also {
                imageLoader = it
            }
        }
    }

    private fun buildImageLoader(appContext: Context): ImageLoader {
        val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
        return ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(MAX_MEMORY_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir)
                    .maxSizeBytes(MAX_DISK_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false) // 忽略 YouTube CDN 短時效標頭，充分利用本機 50MB LRU
            .crossfade(120)
            .build()
    }

    /**
     * App 啟動時在背景執行：檢查距離上次清理是否已滿 14 天
     */
    suspend fun pruneIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastPrune = prefs.getLong(KEY_LAST_PRUNE_TS, 0L)
            if (lastPrune == 0L) {
                prefs.edit().putLong(KEY_LAST_PRUNE_TS, now).apply()
                return@withContext
            }
            if (now - lastPrune >= PRUNE_INTERVAL_MS) {
                val sizeMb = getCacheSizeBytes(context) / (1024.0 * 1024.0)
                Log.i(TAG, "14-day periodic image cache prune triggered (current size: ${String.format(Locale.US, "%.2f", sizeMb)} MB)")
                clearAllCache(context)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Periodic prune skipped due to error", e)
        }
    }

    /**
     * 計算目前磁碟縮圖快取總佔用大小 (Bytes)
     */
    fun getCacheSizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }

    /**
     * 取得易讀的快取容量字串 (例如 "12.4 MB")
     */
    fun getFormattedCacheSize(context: Context): String {
        val bytes = getCacheSizeBytes(context)
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 0.1 && bytes > 0L) {
            String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
        } else {
            String.format(Locale.getDefault(), "%.1f MB", mb)
        }
    }

    /**
     * 手動或定期徹底清理記憶體與磁碟縮圖快取
     * @return 釋放前的磁碟大小字串
     */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun clearAllCache(context: Context): String = withContext(Dispatchers.IO) {
        val freedLabel = getFormattedCacheSize(context)
        try {
            val loader = getImageLoader(context)
            withContext(Dispatchers.Main) {
                loader.memoryCache?.clear()
            }
            loader.diskCache?.clear()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_PRUNE_TS, System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Cleared image cache, freed $freedLabel")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to clear image cache cleanly", e)
        }
        freedLabel
    }

    /**
     * 智慧解析縮圖網址：
     * - 舊版資料庫若未紀錄 artworkUri 且為 yt_ 曲目，自動由 videoId 補全縮圖網址
     * - 清單小圖優先選用 mqdefault.jpg (320x180 輕量高速)，全螢幕大圖使用 hqdefault.jpg (480x360)
     */
    fun resolveArtworkUrl(
        mediaId: String?,
        artworkUri: String?,
        highQuality: Boolean = false
    ): String? {
        val targetVariant = if (highQuality) "hqdefault.jpg" else "mqdefault.jpg"

        if (!artworkUri.isNullOrBlank()) {
            return if (artworkUri.contains("img.youtube.com/vi/")) {
                artworkUri
                    .replace("maxresdefault.jpg", targetVariant)
                    .replace("sddefault.jpg", targetVariant)
                    .replace("hqdefault.jpg", targetVariant)
                    .replace("mqdefault.jpg", targetVariant)
            } else {
                artworkUri
            }
        }

        if (!mediaId.isNullOrBlank() && mediaId.startsWith("yt_")) {
            val vid = YouTubeAudioExtractor.extractVideoId(mediaId)
            if (vid.length == 11) {
                return "https://img.youtube.com/vi/$vid/$targetVariant"
            }
        }
        return null
    }
}
