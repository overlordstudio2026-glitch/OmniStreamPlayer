package com.overlord.omnistream

import android.app.Application
import android.util.Log
import com.overlord.omnistream.core.CrashHandler
import com.overlord.omnistream.core.cache.MediaCacheManager
import com.overlord.omnistream.data.local.AppDatabase
import com.overlord.omnistream.data.repository.PlayerRepository

class OmniStreamApp : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val repository: PlayerRepository by lazy {
        PlayerRepository(this, database)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 0. 安裝全域崩潰攔截器 (防止靜默閃退，提供可視化診斷介面)
        CrashHandler.init(this)

        // 1. 安全預熱快取與資料庫，若發生未預期異常立即喚起 CrashActivity 呈現真實根因
        try {
            database
            repository
            MediaCacheManager.init(this)
        } catch (t: Throwable) {
            Log.e("OmniStreamApp", "Fatal initialization error during startup", t)
            CrashHandler.showCrash(this, t)
        }
    }

    companion object {
        lateinit var instance: OmniStreamApp
            private set
    }
}


