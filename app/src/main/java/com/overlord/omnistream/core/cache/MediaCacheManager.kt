package com.overlord.omnistream.core.cache

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@OptIn(UnstableApi::class)
object MediaCacheManager {
    private var simpleCache: SimpleCache? = null
    private const val MAX_CACHE_SIZE: Long = 1024 * 1024 * 512 // 512 MB LRU 快取
    private var isInitialized = false

    @Synchronized
    fun init(context: Context) {
        if (isInitialized) return
        try {
            val cacheDir = File(context.cacheDir, "omni_media_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val evictor = LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE)
            val databaseProvider = StandaloneDatabaseProvider(context)
            simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
            isInitialized = true
        } catch (e: Throwable) {
            Log.e("MediaCacheManager", "MediaCacheManager init failed, fallback to direct streaming", e)
            simpleCache = null
            isInitialized = true
        }
    }

    fun getCache(): SimpleCache? = simpleCache

    /**
     * 建立支援邊播邊緩存的 DataSource.Factory (若快取不可用則安全回退至直接串流)
     */
    fun createCacheDataSourceFactory(
        upstreamFactory: DataSource.Factory
    ): DataSource.Factory {
        val cache = simpleCache ?: return upstreamFactory
        return try {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } catch (e: Throwable) {
            Log.e("MediaCacheManager", "CacheDataSource factory failed, using upstream direct", e)
            upstreamFactory
        }
    }
}

