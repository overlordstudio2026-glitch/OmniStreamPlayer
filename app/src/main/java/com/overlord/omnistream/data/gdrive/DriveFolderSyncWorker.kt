package com.overlord.omnistream.data.gdrive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.overlord.omnistream.OmniStreamApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager 背景定時任務：
 * 自動偵測 Google 雲端資料夾內容新增，並自動加入播放清單
 */
class DriveFolderSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as OmniStreamApp
        val repo = app.repository
        val subDao = app.database.subscriptionDao()

        val gdriveSubs = subDao.getAll().filter { it.type == "GDRIVE" && it.autoAddToPlaylist }
        if (gdriveSubs.isEmpty()) {
            return@withContext Result.success()
        }

        var newItemsCount = 0
        for (sub in gdriveSubs) {
            val targetGroupId = sub.targetPlaylistGroupId.ifBlank { "default" }
            val added = repo.syncAndMergeFolderItems(targetGroupId, sub.id, sub.name)
            newItemsCount += added
            subDao.updateLastSyncedTime(sub.id, System.currentTimeMillis())
        }

        Result.success()
    }
}
