package com.overlord.omnistream.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.overlord.omnistream.OmniStreamApp
import com.overlord.omnistream.data.gdrive.DriveFolderSyncWorker
import com.overlord.omnistream.data.local.entity.SubscriptionEntity
import com.overlord.omnistream.core.model.MediaSourceType
import com.overlord.omnistream.playback.PlaybackController
import com.overlord.omnistream.ui.components.MiniPlayerBar
import com.overlord.omnistream.ui.screens.*
import com.overlord.omnistream.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var playbackController: PlaybackController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as OmniStreamApp
        val repo = app.repository

        // 1. 初始化背景播放控制器並恢復斷點續播
        playbackController = PlaybackController(this)
        playbackController.connect {
            restorePreviousPlaybackState(repo)
        }

        // 1.5 檢查本機備份並自動無縫還原（避免重新安裝 App 後設定遺失）
        lifecycleScope.launch(Dispatchers.IO) {
            val subsCount = app.database.subscriptionDao().getAll().size
            val itemsCount = repo.getPlaylistItems("default").size
            if (subsCount == 0 && itemsCount == 0) {
                val restored = repo.backupManager.restoreBackupIfAvailable()
                if (restored > 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "✨ 已自動為您接回前次保留的設定與訂閱清單！", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // 2. 啟動 Google 雲端定時背景同步 (每 4 小時自動檢查)
        scheduleDriveBackgroundSync()

        // 3. Jetpack Compose UI
        setContent {
            OmniStreamTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                var showFullPlayer by remember { mutableStateOf(false) }
                val prefs = remember { getSharedPreferences("omnistream_prefs", Context.MODE_PRIVATE) }
                val initialGroupId = remember { prefs.getString("last_group_id", "default") ?: "default" }
                var currentGroupId by remember { mutableStateOf(initialGroupId) }
                var isSyncingGDrive by remember { mutableStateOf(false) }
                var isSyncingYouTube by remember { mutableStateOf(false) }

                val groups by repo.getPlaylistGroupsFlow().collectAsState(initial = emptyList())
                val playlist by repo.getPlaylistFlow(currentGroupId).collectAsState(initial = emptyList())
                val subscriptions by app.database.subscriptionDao().getByTypeFlow("GDRIVE").collectAsState(initial = emptyList())
                val ytSubscriptions by app.database.subscriptionDao().getByTypeFlow("YOUTUBE").collectAsState(initial = emptyList())
                val isPlaying by playbackController.isPlaying.collectAsState()
                val currentItem by playbackController.currentMediaItem.collectAsState()

                // 首次啟動自動確保預設群組存在，若當前資料庫無訂閱或無曲目則自動進行多軌防抹除復原
                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        repo.ensureDefaultGroup()
                        val currentSubs = app.database.subscriptionDao().getAll()
                        if (currentSubs.isEmpty()) {
                            val restoredSubs = repo.restoreSubscriptionsFromAnySource()
                            if (restoredSubs > 0) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "🎉 已自動恢復 $restoredSubs 個已監控雲端資料夾與追蹤頻道！", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            repo.savePersistentSubscriptionsMirror(currentSubs)
                        }

                        val currentItems = app.database.playlistDao().getAll().size
                        if (currentItems == 0) {
                            repo.backupManager.restoreBackupIfAvailable()
                        }
                    }
                }

                // 系統檔案選擇器 (SAF)：讓使用者直接從「下載」或任何資料夾挑選備份檔，受系統授權 100% 讀取
                val filePickerLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val inputStream = contentResolver.openInputStream(uri)
                                if (inputStream != null) {
                                    val count = repo.backupManager.restoreFromStream(inputStream)
                                    withContext(Dispatchers.Main) {
                                        if (count > 0) {
                                            Toast.makeText(this@MainActivity, "🎉 還原成功！已完整恢復 $count 筆訂閱（含 YouTube 與雲端）及清單！", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(this@MainActivity, "檔案解析完成，未發現新的資料或格式不符", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "無法開啟所選檔案", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "還原發生錯誤: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                // 當全螢幕播放器開啟時，按 Android 返回鍵收合播放器回到當前分頁，避免退出 App
                BackHandler(enabled = showFullPlayer) {
                    showFullPlayer = false
                }

                Scaffold(
                    bottomBar = {
                        Column {
                            if (!showFullPlayer) {
                                MiniPlayerBar(
                                    controller = playbackController,
                                    isPlaying = isPlaying,
                                    title = currentItem?.mediaMetadata?.title?.toString() ?: "",
                                    artist = currentItem?.mediaMetadata?.artist?.toString() ?: "",
                                    onClick = { showFullPlayer = true }
                                )
                            }

                            NavigationBar(containerColor = BgDark) {
                                NavigationBarItem(
                                    selected = selectedTab == 0 && !showFullPlayer,
                                    onClick = {
                                        selectedTab = 0
                                        showFullPlayer = false
                                    },
                                    icon = { Icon(Icons.Default.QueueMusic, contentDescription = "播放清單") },
                                    label = { Text("清單") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = CyanAccent,
                                        selectedTextColor = CyanAccent,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 1 && !showFullPlayer,
                                    onClick = {
                                        selectedTab = 1
                                        showFullPlayer = false
                                    },
                                    icon = { Icon(Icons.Default.Cloud, contentDescription = "雲端硬碟") },
                                    label = { Text("Google 雲端") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = CyanAccent,
                                        selectedTextColor = CyanAccent,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )
                                NavigationBarItem(
                                    selected = selectedTab == 2 && !showFullPlayer,
                                    onClick = {
                                        selectedTab = 2
                                        showFullPlayer = false
                                    },
                                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = "YouTube") },
                                    label = { Text("YouTube") },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = CyanAccent,
                                        selectedTextColor = CyanAccent,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (selectedTab) {
                            0 -> PlaylistScreen(
                                groups = groups,
                                selectedGroupId = currentGroupId,
                                currentPlayingId = currentItem?.mediaId,
                                isPlayerPlaying = isPlaying,
                                onSelectGroup = {
                                    currentGroupId = it
                                    prefs.edit().putString("last_group_id", it).apply()
                                },
                                onCreateGroup = { name ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val gid = "grp_" + UUID.randomUUID().toString().take(8)
                                        repo.createPlaylistGroup(gid, name)
                                        currentGroupId = gid
                                        prefs.edit().putString("last_group_id", gid).apply()
                                    }
                                },
                                onRenameGroup = { gid, newName ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.renamePlaylistGroup(gid, newName)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "播放清單已更名為：$newName", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDeleteGroup = { gid ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.deletePlaylistGroup(gid)
                                        val remaining = repo.groupDao.getAll()
                                        val nextId = remaining.firstOrNull()?.id ?: "default"
                                        repo.ensureDefaultGroup()
                                        currentGroupId = nextId
                                        prefs.edit().putString("last_group_id", nextId).apply()
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "播放清單已刪除", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onClearGroup = { gid ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.clearPlaylist(gid)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "播放清單曲目已清空", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onBatchDeleteItems = { ids ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.removeItemsFromPlaylist(ids)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已成功批量刪除 ${ids.size} 首曲目！", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onMoveItemToGroup = { itemId, newGid ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.moveItemToGroup(itemId, newGid)
                                        val destName = groups.find { it.id == newGid }?.name ?: "目標清單"
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "曲目已移至「$destName」", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onBatchMoveItemsToGroup = { itemIds, newGid ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.moveItemsToGroup(itemIds, newGid)
                                        val destName = groups.find { it.id == newGid }?.name ?: "目標清單"
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已成功將 ${itemIds.size} 首曲目移至「$destName」！", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                items = playlist,
                                onItemClick = { index ->
                                    val clicked = playlist[index]
                                    // 1. 若點擊的是當前正在播/停的同一首曲目，直接執行 Toggle (暫停/續播)，無需重新解析或緩衝
                                    if (currentItem?.mediaId == clicked.id) {
                                        playbackController.toggleOrPlayItemAtIndex(playlist, index)
                                        return@PlaylistScreen
                                    }

                                    // 2. 若為不同曲目，且為 YouTube 需動態更新音訊 URL
                                    if (clicked.mediaUri.contains("youtube.com/watch") || clicked.mediaUri.isBlank()) {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            val vid = com.overlord.omnistream.data.youtube.YouTubeAudioExtractor.extractVideoId(clicked.id.ifBlank { clicked.mediaUri })
                                            val mediaInfo = repo.ytAudioExtractor.extractMediaInfo(vid)
                                            val freshUrl = mediaInfo?.audioUrl
                                            if (freshUrl != null) {
                                                repo.updateItemMediaUri(clicked.id, freshUrl)
                                                val updatedList = playlist.toMutableList()
                                                updatedList[index] = clicked.copy(
                                                    mediaUri = freshUrl,
                                                    title = if (clicked.title.startsWith("YouTube 播放清單 #") || clicked.title.isBlank()) mediaInfo.title else clicked.title,
                                                    artist = if (clicked.artist == "YouTube 播放清單") mediaInfo.author else clicked.artist
                                                )
                                                withContext(Dispatchers.Main) {
                                                    playbackController.playItemAtIndex(updatedList, index)
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    playbackController.playItemAtIndex(playlist, index)
                                                }
                                            }
                                        }
                                    } else {
                                        playbackController.playItemAtIndex(playlist, index)
                                    }
                                },
                                onDeleteItem = { id ->
                                    lifecycleScope.launch(Dispatchers.IO) { repo.removeItemFromPlaylist(id) }
                                },
                                onScanLocalAudio = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val localFiles = repo.scanLocalAudioFiles()
                                        repo.addItemsToPlaylist(localFiles, currentGroupId)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已載入 ${localFiles.size} 首本機音訊", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                isSyncing = isSyncingGDrive,
                                onSyncCloud = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        isSyncingGDrive = true
                                        var totalNew = 0
                                        val subs = app.database.subscriptionDao().getAll().filter { it.type == "GDRIVE" }
                                        if (subs.isEmpty()) {
                                            isSyncingGDrive = false
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "尚未新增任何 Google 雲端資料夾，請至「Google 雲端」分頁新增", Toast.LENGTH_LONG).show()
                                            }
                                            return@launch
                                        }
                                        // 依各資料夾绑定的 targetPlaylistGroupId 進行同步
                                        for (sub in subs) {
                                            val targetGid = sub.targetPlaylistGroupId.ifBlank { "default" }
                                            val added = repo.syncAndMergeFolderItems(targetGid, sub.id, sub.name)
                                            totalNew += added
                                        }
                                        isSyncingGDrive = false
                                        withContext(Dispatchers.Main) {
                                            if (totalNew > 0) {
                                                Toast.makeText(this@MainActivity, "雲端同步完成！新增 $totalNew 首檔案並分類存入對應清單", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "已是最新狀態，無新增雲端檔案", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onRestoreBackup = {
                                    filePickerLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                                }
                            )
                            1 -> GDriveScreen(
                                subscriptions = subscriptions,
                                groups = groups,
                                currentGroupId = currentGroupId,
                                isSyncing = isSyncingGDrive,
                                onAddFolder = { folderInput, name, targetOption, customNewName ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val cleanFolderId = com.overlord.omnistream.data.gdrive.GoogleDriveService.extractFolderId(folderInput)
                                        val existing = app.database.subscriptionDao().getAll().firstOrNull { it.id == cleanFolderId }
                                        var finalName = name.trim()
                                        if (finalName.isBlank() || finalName == "雲端資料夾") {
                                            val autoName = repo.gdriveService.fetchFolderName(cleanFolderId)
                                            if (!autoName.isNullOrBlank()) {
                                                finalName = autoName
                                            } else {
                                                finalName = "雲端資料夾"
                                            }
                                        }

                                        // 計算目標 PlaylistGroupId
                                        val targetGid = when (targetOption) {
                                            "__AUTO__" -> {
                                                val newGid = "grp_" + UUID.randomUUID().toString().take(8)
                                                repo.createPlaylistGroup(newGid, finalName)
                                                currentGroupId = newGid
                                                prefs.edit().putString("last_group_id", newGid).apply()
                                                newGid
                                            }
                                            "__CUSTOM__" -> {
                                                val newGid = "grp_" + UUID.randomUUID().toString().take(8)
                                                val groupName = customNewName?.ifBlank { finalName } ?: finalName
                                                repo.createPlaylistGroup(newGid, groupName)
                                                currentGroupId = newGid
                                                prefs.edit().putString("last_group_id", newGid).apply()
                                                newGid
                                            }
                                            else -> targetOption
                                        }

                                        if (existing != null) {
                                            repo.updateSubscriptionTargetGroup(cleanFolderId, targetGid)
                                            val added = repo.syncAndMergeFolderItems(targetGid, cleanFolderId, existing.name)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "此雲端資料夾已在監控中！已更新目標清單並增量同步（新增 $added 首）", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            repo.saveSubscription(
                                                SubscriptionEntity(
                                                    id = cleanFolderId,
                                                    name = finalName,
                                                    type = "GDRIVE",
                                                    publicUrl = folderInput,
                                                    targetPlaylistGroupId = targetGid
                                                )
                                            )
                                            val added = repo.syncAndMergeFolderItems(targetGid, cleanFolderId, finalName)
                                            val targetGroupName = repo.groupDao.getAll().find { it.id == targetGid }?.name ?: "清單"
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "已加入「$finalName」並存入「$targetGroupName」（新增 $added 首）！", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                onDeleteFolder = { id ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.deleteSubscription(id)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已移除資料夾監控", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onRenameFolder = { id, newName ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.updateSubscriptionName(id, newName)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "資料夾名稱已更新為：$newName", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onChangeTargetGroup = { id, newGid ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.updateSubscriptionTargetGroup(id, newGid)
                                        val groupName = repo.groupDao.getAll().find { it.id == newGid }?.name ?: "指定清單"
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已將收納目標變更為「$groupName」", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onSyncFolder = { folderId ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val sub = app.database.subscriptionDao().getAll().firstOrNull { it.id == folderId }
                                        if (sub != null) {
                                            val targetGid = sub.targetPlaylistGroupId.ifBlank { "default" }
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "正在同步「${sub.name}」...", Toast.LENGTH_SHORT).show()
                                            }
                                            val added = repo.syncAndMergeFolderItems(targetGid, sub.id, sub.name)
                                            val groupName = repo.groupDao.getAll().find { it.id == targetGid }?.name ?: "指定清單"
                                            withContext(Dispatchers.Main) {
                                                if (added > 0) {
                                                    Toast.makeText(this@MainActivity, "「${sub.name}」同步完成！新增 $added 首至「$groupName」", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(this@MainActivity, "「${sub.name}」已是最新狀態，無新增曲目", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                onSyncNow = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        isSyncingGDrive = true
                                        var totalNew = 0
                                        val subs = app.database.subscriptionDao().getAll().filter { it.type == "GDRIVE" }
                                        for (sub in subs) {
                                            val targetGid = sub.targetPlaylistGroupId.ifBlank { "default" }
                                            val added = repo.syncAndMergeFolderItems(targetGid, sub.id, sub.name)
                                            totalNew += added
                                        }
                                        isSyncingGDrive = false
                                        withContext(Dispatchers.Main) {
                                            if (totalNew > 0) {
                                                Toast.makeText(this@MainActivity, "雲端同步完成！新增 $totalNew 首檔案並分類存入各清單", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "已是最新狀態，無新增雲端檔案", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onManualBackup = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val ok = repo.backupManager.createBackup()
                                        withContext(Dispatchers.Main) {
                                            if (ok) {
                                                Toast.makeText(this@MainActivity, "備份成功！已存至 Download/omnistream_backup.json", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "備份失敗，請檢視權限", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onManualRestore = {
                                    filePickerLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                                }
                            )
                            2 -> YouTubeScreen(
                                subscriptions = ytSubscriptions,
                                groups = groups,
                                currentGroupId = currentGroupId,
                                isSyncing = isSyncingYouTube,
                                onAddChannel = { channelInput, name, onlyNew, targetOption, customNewName ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "正在查詢 YouTuber 頻道資訊...", Toast.LENGTH_SHORT).show()
                                        }
                                        val channelInfo = repo.ytRssParser.resolveChannelInfo(channelInput)
                                        val resolvedId = channelInfo.channelId
                                        val finalName = if (name.isNotBlank() && name != "YouTuber") name.trim() else channelInfo.channelTitle
                                        val existing = app.database.subscriptionDao().getAll().firstOrNull { it.id == resolvedId }
                                        val sinceTs = if (onlyNew) System.currentTimeMillis() else null

                                        // 計算目標 PlaylistGroupId
                                        val targetGid = when (targetOption) {
                                            "__AUTO__" -> {
                                                val newGid = "grp_" + UUID.randomUUID().toString().take(8)
                                                repo.createPlaylistGroup(newGid, finalName)
                                                currentGroupId = newGid
                                                prefs.edit().putString("last_group_id", newGid).apply()
                                                newGid
                                            }
                                            "__CUSTOM__" -> {
                                                val newGid = "grp_" + UUID.randomUUID().toString().take(8)
                                                val groupName = customNewName?.ifBlank { finalName } ?: finalName
                                                repo.createPlaylistGroup(newGid, groupName)
                                                currentGroupId = newGid
                                                prefs.edit().putString("last_group_id", newGid).apply()
                                                newGid
                                            }
                                            else -> targetOption
                                        }

                                        if (existing != null) {
                                            repo.updateSubscriptionTargetGroup(resolvedId, targetGid)
                                            val videos = repo.ytRssParser.fetchChannelLatestVideos(resolvedId, existing.name, sinceTs)
                                            val currentItems = repo.getPlaylistItems(targetGid)
                                            val currentIds = currentItems.map { it.id }.toSet()
                                            val newVideos = videos.filter { it.id !in currentIds }
                                            if (newVideos.isNotEmpty()) {
                                                repo.addItemsToPlaylist(newVideos, targetGid)
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "「${existing.name}」已更新目標清單！增量加入 ${newVideos.size} 首新影片", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "「${existing.name}」已更新目標清單，目前曲目皆已收錄", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            repo.saveSubscription(
                                                SubscriptionEntity(
                                                    id = resolvedId,
                                                    name = finalName,
                                                    type = "YOUTUBE",
                                                    isPlaylist = false,
                                                    publicUrl = channelInput,
                                                    sinceTimestamp = sinceTs,
                                                    lastSyncedTime = System.currentTimeMillis(),
                                                    targetPlaylistGroupId = targetGid
                                                )
                                            )
                                            val videos = repo.ytRssParser.fetchChannelLatestVideos(resolvedId, finalName, sinceTs)
                                            repo.addItemsToPlaylist(videos, targetGid)
                                            val groupName = repo.groupDao.getAll().find { it.id == targetGid }?.name ?: "清單"
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "已成功追蹤「$finalName」！已載入 ${videos.size} 首影片至「$groupName」", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onDeleteSubscription = { id ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.deleteSubscription(id)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已移除追蹤紀錄", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onRenameSubscription = { id, newName ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.updateSubscriptionName(id, newName)
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "名稱已更新為：$newName", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onChangeTargetGroup = { id, newGid ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        repo.updateSubscriptionTargetGroup(id, newGid)
                                        val groupName = repo.groupDao.getAll().find { it.id == newGid }?.name ?: "指定清單"
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "已將收納目標變更為「$groupName」", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onImportPlaylist = { playlistInput, customName, targetOption, customNewName ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val cleanPid = com.overlord.omnistream.data.youtube.YouTubePlaylistParser.extractPlaylistId(playlistInput)
                                        val existing = app.database.subscriptionDao().getAll().firstOrNull { it.id == cleanPid }
                                        
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "正在解析播放清單與頻道資訊...", Toast.LENGTH_SHORT).show()
                                        }

                                        val info = repo.ytPlaylistParser.fetchPlaylistDetails(cleanPid, customName)
                                        if (info.items.isEmpty()) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "未能解析到曲目，請確認清單為公開或網址正確", Toast.LENGTH_LONG).show()
                                            }
                                            return@launch
                                        }

                                        val displayName = when {
                                            customName.isNotBlank() && customName != "YouTube 清單" -> {
                                                if (info.channelName.isNotBlank()) "[$customName] ${info.channelName}" else customName
                                            }
                                            info.channelName.isNotBlank() && info.title.isNotBlank() -> "[${info.channelName}] ${info.title}"
                                            info.title.isNotBlank() -> info.title
                                            info.channelName.isNotBlank() -> "[${info.channelName}] 播放清單"
                                            else -> "YouTube 播放清單 #$cleanPid"
                                        }

                                        // 計算目標 PlaylistGroupId
                                        val targetGid = when (targetOption) {
                                            "__AUTO__" -> {
                                                val newGid = "grp_" + UUID.randomUUID().toString().take(8)
                                                repo.createPlaylistGroup(newGid, displayName)
                                                currentGroupId = newGid
                                                prefs.edit().putString("last_group_id", newGid).apply()
                                                newGid
                                            }
                                            "__CUSTOM__" -> {
                                                val newGid = "grp_" + UUID.randomUUID().toString().take(8)
                                                val groupName = customNewName?.ifBlank { displayName } ?: displayName
                                                repo.createPlaylistGroup(newGid, groupName)
                                                currentGroupId = newGid
                                                prefs.edit().putString("last_group_id", newGid).apply()
                                                newGid
                                            }
                                            else -> targetOption
                                        }

                                        val currentItems = repo.getPlaylistItems(targetGid)
                                        val currentIds = currentItems.map { it.id }.toSet()
                                        val newVideos = info.items.filter { it.id !in currentIds }

                                        if (existing != null) {
                                            repo.updateSubscriptionTargetGroup(cleanPid, targetGid)
                                            if (newVideos.isNotEmpty()) {
                                                repo.addItemsToPlaylist(newVideos, targetGid)
                                                app.database.subscriptionDao().updateLastSyncedTime(cleanPid, System.currentTimeMillis())
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "清單目標已更新！增量掃描完成：新增 ${newVideos.size} 首新曲目", Toast.LENGTH_LONG).show()
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "此播放清單目標已更新，曲目已全部收錄。", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        } else {
                                            repo.saveSubscription(
                                                SubscriptionEntity(
                                                    id = cleanPid,
                                                    name = displayName,
                                                    type = "YOUTUBE",
                                                    isPlaylist = true,
                                                    publicUrl = playlistInput,
                                                    lastSyncedTime = System.currentTimeMillis(),
                                                    targetPlaylistGroupId = targetGid
                                                )
                                            )
                                            repo.addItemsToPlaylist(info.items, targetGid)
                                            val groupName = repo.groupDao.getAll().find { it.id == targetGid }?.name ?: "清單"
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "成功匯入「$displayName」！共 ${info.items.size} 首存入「$groupName」", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                },
                                onSyncSingleChannel = { channelId ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val sub = app.database.subscriptionDao().getAll().firstOrNull { it.id == channelId }
                                        if (sub != null) {
                                            val targetGid = sub.targetPlaylistGroupId.ifBlank { "default" }
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "正在檢查「${sub.name}」新影片...", Toast.LENGTH_SHORT).show()
                                            }
                                            val videos = repo.ytRssParser.fetchChannelLatestVideos(sub.id, sub.name, sub.sinceTimestamp)
                                            val currentIds = repo.getPlaylistItems(targetGid).map { it.id }.toSet()
                                            val newVideos = videos.filter { it.id !in currentIds }
                                            if (newVideos.isNotEmpty()) {
                                                repo.addItemsToPlaylist(newVideos, targetGid)
                                                app.database.subscriptionDao().updateLastSyncedTime(sub.id, System.currentTimeMillis())
                                                val groupName = repo.groupDao.getAll().find { it.id == targetGid }?.name ?: "指定清單"
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "「${sub.name}」同步完成！新增 ${newVideos.size} 首至「$groupName」", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@MainActivity, "「${sub.name}」已是最新狀態，無新增影片", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    }
                                },
                                onSyncSinglePlaylist = { playlistId ->
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val sub = app.database.subscriptionDao().getAll().firstOrNull { it.id == playlistId }
                                        val subName = sub?.name ?: playlistId
                                        val targetGid = sub?.targetPlaylistGroupId?.ifBlank { "default" } ?: "default"
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@MainActivity, "正在檢查「$subName」新曲目...", Toast.LENGTH_SHORT).show()
                                        }
                                        val info = repo.ytPlaylistParser.fetchPlaylistDetails(playlistId)
                                        val currentIds = repo.getPlaylistItems(targetGid).map { it.id }.toSet()
                                        val newVideos = info.items.filter { it.id !in currentIds }
                                        if (newVideos.isNotEmpty()) {
                                            repo.addItemsToPlaylist(newVideos, targetGid)
                                            app.database.subscriptionDao().updateLastSyncedTime(playlistId, System.currentTimeMillis())
                                            val groupName = repo.groupDao.getAll().find { it.id == targetGid }?.name ?: "指定清單"
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "「$subName」同步完成！新增 ${newVideos.size} 首至「$groupName」", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@MainActivity, "「$subName」已是最新狀態，無新增曲目", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onSyncVideos = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        isSyncingYouTube = true
                                        val allSubs = app.database.subscriptionDao().getAll().filter { it.type == "YOUTUBE" }
                                        val channels = allSubs.filter { !it.isPlaylist }
                                        val playlists = allSubs.filter { it.isPlaylist }
                                        var totalNew = 0

                                        // 檢查頻道更新 (依各自綁定的 targetPlaylistGroupId 存入)
                                        for (ch in channels) {
                                            val targetGid = ch.targetPlaylistGroupId.ifBlank { "default" }
                                            val currentIds = repo.getPlaylistItems(targetGid).map { it.id }.toSet()
                                            val videos = repo.ytRssParser.fetchChannelLatestVideos(ch.id, ch.name, ch.sinceTimestamp)
                                            val newVideos = videos.filter { it.id !in currentIds }
                                            if (newVideos.isNotEmpty()) {
                                                repo.addItemsToPlaylist(newVideos, targetGid)
                                                totalNew += newVideos.size
                                            }
                                            app.database.subscriptionDao().updateLastSyncedTime(ch.id, System.currentTimeMillis())
                                        }

                                        // 檢查播放清單更新 (依各自綁定的 targetPlaylistGroupId 存入)
                                        for (pl in playlists) {
                                            val targetGid = pl.targetPlaylistGroupId.ifBlank { "default" }
                                            val currentIds = repo.getPlaylistItems(targetGid).map { it.id }.toSet()
                                            val info = repo.ytPlaylistParser.fetchPlaylistDetails(pl.id)
                                            val newVideos = info.items.filter { it.id !in currentIds }
                                            if (newVideos.isNotEmpty()) {
                                                repo.addItemsToPlaylist(newVideos, targetGid)
                                                totalNew += newVideos.size
                                            }
                                            app.database.subscriptionDao().updateLastSyncedTime(pl.id, System.currentTimeMillis())
                                        }

                                        isSyncingYouTube = false
                                        withContext(Dispatchers.Main) {
                                            if (totalNew > 0) {
                                                Toast.makeText(this@MainActivity, "檢查完成！共新增 $totalNew 首最新曲目並分門別類存入清單", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "全部頻道與清單皆為最新狀態，無新增曲目", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onManualBackup = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        val ok = repo.backupManager.createBackup()
                                        withContext(Dispatchers.Main) {
                                            if (ok) {
                                                Toast.makeText(this@MainActivity, "備份成功！已存至 Download/omnistream_backup.json", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "備份失敗，請檢視權限", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onManualRestore = {
                                    filePickerLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                                }
                            )
                        }

                        AnimatedVisibility(
                            visible = showFullPlayer,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            PlayerScreen(
                                controller = playbackController,
                                title = currentItem?.mediaMetadata?.title?.toString() ?: "",
                                artist = currentItem?.mediaMetadata?.artist?.toString() ?: "",
                                isPlaying = isPlaying,
                                onClose = { showFullPlayer = false }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun restorePreviousPlaybackState(repo: com.overlord.omnistream.data.repository.PlayerRepository) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val lastState = repo.getPlaybackState()
                val playlist = repo.getPlaylistItems("default")

                if (lastState != null && playlist.isNotEmpty()) {
                    val index = playlist.indexOfFirst { it.id == lastState.currentItemId }.coerceAtLeast(0)
                    withContext(Dispatchers.Main) {
                        playbackController.setPlaylistAndPlay(
                            items = playlist,
                            startIndex = index,
                            startPositionMs = lastState.currentPositionMs
                        )
                        playbackController.pause()
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("MainActivity", "Failed to restore playback state", e)
            }
        }
    }

    private fun scheduleDriveBackgroundSync() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<DriveFolderSyncWorker>(
                4, TimeUnit.HOURS
            ).setConstraints(constraints).build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "OmniDriveFolderSync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Failed to schedule drive sync worker", e)
        }
    }


    override fun onDestroy() {
        playbackController.release()
        super.onDestroy()
    }
}
