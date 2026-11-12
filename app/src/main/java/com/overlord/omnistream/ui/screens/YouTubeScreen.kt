package com.overlord.omnistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overlord.omnistream.data.local.entity.PlaylistGroupEntity
import com.overlord.omnistream.data.local.entity.SubscriptionEntity
import com.overlord.omnistream.ui.theme.*

import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun YouTubeScreen(
    subscriptions: List<SubscriptionEntity>,
    groups: List<PlaylistGroupEntity> = emptyList(),
    currentGroupId: String = "default",
    onAddChannel: (channelIdOrUrl: String, name: String, onlyNew: Boolean, targetGroupId: String, newGroupName: String?) -> Unit,
    onDeleteSubscription: (id: String) -> Unit,
    onRenameSubscription: ((id: String, newName: String) -> Unit)? = null,
    onChangeTargetGroup: ((id: String, newGroupId: String) -> Unit)? = null,
    onImportPlaylist: (playlistUrlOrId: String, name: String, targetGroupId: String, newGroupName: String?) -> Unit,
    onSyncVideos: () -> Unit,
    onSyncSingleChannel: ((channelId: String) -> Unit)? = null,
    onSyncSinglePlaylist: ((playlistId: String) -> Unit)? = null,
    isSyncing: Boolean = false,
    onManualBackup: () -> Unit = {},
    onManualRestore: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: 頻道追蹤 (含時間因子), 1: 匯入播放清單
    var channelInput by remember { mutableStateOf("") }
    var channelNameInput by remember { mutableStateOf("") }
    var filterOnlyNew by remember { mutableStateOf(false) } // 預設關閉：追蹤時抓取最新影片供立即試聽
    
    // 頻道目標播放清單
    var channelTargetOption by remember { mutableStateOf("__AUTO__") }
    var channelCustomGroupName by remember { mutableStateOf("") }
    var isChannelTargetDropdownExpanded by remember { mutableStateOf(false) }

    // 匯入播放清單目標
    var playlistInput by remember { mutableStateOf("") }
    var playlistNameInput by remember { mutableStateOf("") }
    var playlistTargetOption by remember { mutableStateOf("__AUTO__") }
    var playlistCustomGroupName by remember { mutableStateOf("") }
    var isPlaylistTargetDropdownExpanded by remember { mutableStateOf(false) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetId by remember { mutableStateOf("") }
    var renameTargetName by remember { mutableStateOf("") }
    var renameDialogTitle by remember { mutableStateOf("") }

    var showChangeGroupDialog by remember { mutableStateOf(false) }
    var changeGroupSubId by remember { mutableStateOf("") }
    var changeGroupSubName by remember { mutableStateOf("") }

    val channels = remember(subscriptions) { subscriptions.filter { !it.isPlaylist } }
    val playlists = remember(subscriptions) { subscriptions.filter { it.isPlaylist } }

    var isAddChannelExpanded by rememberSaveable { mutableStateOf(channels.isEmpty()) }
    var isAddPlaylistExpanded by rememberSaveable { mutableStateOf(playlists.isEmpty()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Text(
            text = "YouTube 內容整合",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = CardDark,
            contentColor = RedAccent,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("YouTuber 頻道更新") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("匯入 YouTube 播放清單") }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectedTab == 0) {
                // 1. 頻道更新追蹤卡片 (可摺疊/展開)
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isAddChannelExpanded = !isAddChannelExpanded },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (isAddChannelExpanded) Icons.Default.Subscriptions else Icons.Default.AddCircleOutline,
                                        contentDescription = null,
                                        tint = RedAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isAddChannelExpanded) "追蹤頻道並設定時間過濾" else "➕ 追蹤新 YouTuber 頻道 (點擊展開)",
                                        color = RedAccent,
                                        fontSize = 15.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                }
                                IconButton(
                                    onClick = { isAddChannelExpanded = !isAddChannelExpanded },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isAddChannelExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (isAddChannelExpanded) "收合表單" else "展開表單",
                                        tint = RedAccent
                                    )
                                }
                            }

                            if (isAddChannelExpanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "免 API Key 自動提取純音訊，可指定存入特定播放清單，分類清楚不混雜！",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = channelNameInput,
                                    onValueChange = { channelNameInput = it },
                                    label = { Text("頻道名稱 (如: 科技導讀)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = channelInput,
                                    onValueChange = { channelInput = it },
                                    label = { Text("頻道網址、@Handle 或 ID (UC...)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                // 目標播放清單選擇
                                Text(
                                    text = "指定存入的播放清單：",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { isChannelTargetDropdownExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark)
                                    ) {
                                        val currentLabel = when (channelTargetOption) {
                                            "__AUTO__" -> "✨ 自動以此頻道名稱建立獨立清單 (推薦)"
                                            "__CUSTOM__" -> "➕ 自訂全新播放清單名稱"
                                            else -> "📁 " + (groups.find { it.id == channelTargetOption }?.name ?: "現有清單")
                                        }
                                        Text(
                                            text = currentLabel,
                                            color = RedAccent,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = RedAccent)
                                    }

                                    DropdownMenu(
                                        expanded = isChannelTargetDropdownExpanded,
                                        onDismissRequest = { isChannelTargetDropdownExpanded = false },
                                        modifier = Modifier.background(CardDark)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("✨ 自動以此頻道名稱建立獨立清單", color = RedAccent) },
                                            onClick = {
                                                channelTargetOption = "__AUTO__"
                                                isChannelTargetDropdownExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("➕ 自訂全新播放清單名稱...", color = AmberAccent) },
                                            onClick = {
                                                channelTargetOption = "__CUSTOM__"
                                                isChannelTargetDropdownExpanded = false
                                            }
                                        )
                                        HorizontalDivider(color = SurfaceDark)
                                        groups.forEach { group ->
                                            DropdownMenuItem(
                                                text = { Text("📁 " + group.name, color = TextPrimary) },
                                                onClick = {
                                                    channelTargetOption = group.id
                                                    isChannelTargetDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                if (channelTargetOption == "__CUSTOM__") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = channelCustomGroupName,
                                        onValueChange = { channelCustomGroupName = it },
                                        label = { Text("請輸入全新播放清單名稱") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // 時間因子選擇器
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = filterOnlyNew,
                                        onCheckedChange = { filterOnlyNew = it },
                                        colors = CheckboxDefaults.colors(checkedColor = RedAccent)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column {
                                        Text(
                                            text = "只加入「追蹤後」發布的新影片 (排除歷史舊片)",
                                            color = TextPrimary,
                                            fontSize = 13.sp
                                        )
                                        Text(
                                            text = if (filterOnlyNew) "開啟中：後續自動更新僅收錄新片 (首次追蹤仍保留最新5首)" else "已關閉：直接載入近期 15~25 首影片供立即聆聽",
                                            color = TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = {
                                        if (channelInput.isNotBlank()) {
                                            val finalNewName = if (channelTargetOption == "__CUSTOM__") channelCustomGroupName.trim() else null
                                            onAddChannel(channelInput, channelNameInput.ifBlank { "YouTuber" }, filterOnlyNew, channelTargetOption, finalNewName)
                                            channelInput = ""
                                            channelNameInput = ""
                                            channelCustomGroupName = ""
                                            isAddChannelExpanded = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("開始追蹤並載入至指定清單", color = TextPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 2. 已追蹤頻道清單區塊 Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已追蹤頻道 (${channels.size})",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )

                        Button(
                            onClick = onSyncVideos,
                            enabled = !isSyncing,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = RedAccent,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("檢查中...", color = RedAccent)
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = "同步", tint = RedAccent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("檢查全部更新", color = RedAccent)
                            }
                        }
                    }
                }

                // 備份與還原按鈕
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onManualBackup,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("💾 備份設定 (含YT/雲端)", color = RedAccent, fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = onManualRestore,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🔄 從檔案還原", color = RedAccent, fontSize = 11.sp)
                        }
                    }
                }

                if (channels.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "尚未追蹤任何 YouTuber 頻道。\n點擊上方「➕ 追蹤新 YouTuber 頻道」即可開始追蹤！",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("找不到先前的追蹤頻道？", color = AmberAccent, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        Text("若曾備份過，點擊此處立即從備份檔案快速還原", color = TextSecondary, fontSize = 11.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = onManualRestore,
                                        colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("📂 還原", color = BgDark, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(channels, key = { it.id }) { sub ->
                        val targetGroupName = groups.find { it.id == sub.targetPlaylistGroupId }?.name ?: "預設清單"
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                // 上半部：圖示 + 頻道名稱 + 操作按鈕群
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Subscriptions,
                                        contentDescription = "頻道",
                                        tint = RedAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = sub.name,
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { onSyncSingleChannel?.invoke(sub.id) },
                                            enabled = !isSyncing,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Sync,
                                                contentDescription = "檢查此頻道新影片",
                                                tint = RedAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                renameTargetId = sub.id
                                                renameTargetName = sub.name
                                                renameDialogTitle = "修改頻道名稱 / 自訂備註"
                                                showRenameDialog = true
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "修改名稱",
                                                tint = RedAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteSubscription(sub.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "刪除追蹤",
                                                tint = RedAccent.copy(alpha = 0.85f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // 中間：收納清單徽章（滿寬整行呈現，保證同一行絕不落字）
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SurfaceDark,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            changeGroupSubId = sub.id
                                            changeGroupSubName = sub.name
                                            showChangeGroupDialog = true
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QueueMusic,
                                            contentDescription = null,
                                            tint = RedAccent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "收納至: $targetGroupName (點擊變更)",
                                            color = RedAccent,
                                            fontSize = 11.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // 下方：時間過濾狀態
                                Text(
                                    text = if (sub.sinceTimestamp != null) "⚡ 時間過濾：開啟 (只收新片)" else "✦ 收錄全部近期影片",
                                    color = if (sub.sinceTimestamp != null) RedAccent else CyanAccent,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // selectedTab == 1 (YouTube 播放清單)
                // 1. 匯入 YouTube 既有播放清單卡片 (可摺疊/展開)
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isAddPlaylistExpanded = !isAddPlaylistExpanded },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (isAddPlaylistExpanded) Icons.Default.PlaylistPlay else Icons.Default.AddCircleOutline,
                                        contentDescription = null,
                                        tint = AmberAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isAddPlaylistExpanded) "匯入 YouTube 播放清單" else "➕ 匯入新 YouTube 播放清單 (點擊展開)",
                                        color = AmberAccent,
                                        fontSize = 15.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                    )
                                }
                                IconButton(
                                    onClick = { isAddPlaylistExpanded = !isAddPlaylistExpanded },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isAddPlaylistExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (isAddPlaylistExpanded) "收合表單" else "展開表單",
                                        tint = AmberAccent
                                    )
                                }
                            }

                            if (isAddPlaylistExpanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "貼入 YouTube 播放清單網址 (包含 list=PL...)，可指定存入專屬播放清單！",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = playlistNameInput,
                                    onValueChange = { playlistNameInput = it },
                                    label = { Text("清單名稱 (如: 專注工作音樂)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = playlistInput,
                                    onValueChange = { playlistInput = it },
                                    label = { Text("YouTube 播放清單網址或 ID") },
                                    placeholder = { Text("https://www.youtube.com/playlist?list=PL...") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                // 目標播放清單選擇
                                Text(
                                    text = "指定存入的播放清單：",
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { isPlaylistTargetDropdownExpanded = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark)
                                    ) {
                                        val currentLabel = when (playlistTargetOption) {
                                            "__AUTO__" -> "✨ 自動以此 YouTube 清單名稱建立獨立清單 (推薦)"
                                            "__CUSTOM__" -> "➕ 自訂全新播放清單名稱"
                                            else -> "📁 " + (groups.find { it.id == playlistTargetOption }?.name ?: "現有清單")
                                        }
                                        Text(
                                            text = currentLabel,
                                            color = AmberAccent,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AmberAccent)
                                    }

                                    DropdownMenu(
                                        expanded = isPlaylistTargetDropdownExpanded,
                                        onDismissRequest = { isPlaylistTargetDropdownExpanded = false },
                                        modifier = Modifier.background(CardDark)
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("✨ 自動以此 YouTube 清單名稱建立獨立清單", color = AmberAccent) },
                                            onClick = {
                                                playlistTargetOption = "__AUTO__"
                                                isPlaylistTargetDropdownExpanded = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("➕ 自訂全新播放清單名稱...", color = CyanAccent) },
                                            onClick = {
                                                playlistTargetOption = "__CUSTOM__"
                                                isPlaylistTargetDropdownExpanded = false
                                            }
                                        )
                                        HorizontalDivider(color = SurfaceDark)
                                        groups.forEach { group ->
                                            DropdownMenuItem(
                                                text = { Text("📁 " + group.name, color = TextPrimary) },
                                                onClick = {
                                                    playlistTargetOption = group.id
                                                    isPlaylistTargetDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                if (playlistTargetOption == "__CUSTOM__") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = playlistCustomGroupName,
                                        onValueChange = { playlistCustomGroupName = it },
                                        label = { Text("請輸入全新播放清單名稱") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = {
                                        if (playlistInput.isNotBlank()) {
                                            val finalNewName = if (playlistTargetOption == "__CUSTOM__") playlistCustomGroupName.trim() else null
                                            onImportPlaylist(playlistInput, playlistNameInput.ifBlank { "YouTube 清單" }, playlistTargetOption, finalNewName)
                                            playlistInput = ""
                                            playlistNameInput = ""
                                            playlistCustomGroupName = ""
                                            isAddPlaylistExpanded = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("解析並整批匯入指定清單", color = BgDark, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 2. 已匯入播放清單區塊 Header
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已加入的播放清單 (${playlists.size})",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )

                        if (playlists.isNotEmpty()) {
                            TextButton(
                                onClick = onSyncVideos,
                                enabled = !isSyncing
                            ) {
                                Icon(Icons.Default.Sync, contentDescription = "同步全部", tint = AmberAccent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isSyncing) "檢查中..." else "檢查清單新曲目", color = AmberAccent, fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (playlists.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "尚未加入任何 YouTube 播放清單。\n點擊上方「➕ 匯入新 YouTube 播放清單」即可匯入！",
                                color = TextSecondary,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("找不到先前的播放清單？", color = AmberAccent, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        Text("若曾備份過，點擊此處立即從備份檔案快速還原", color = TextSecondary, fontSize = 11.sp)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = onManualRestore,
                                        colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("📂 還原", color = BgDark, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    items(playlists, key = { it.id }) { pl ->
                        val targetGroupName = groups.find { it.id == pl.targetPlaylistGroupId }?.name ?: "預設清單"
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardDark),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                // 上半部：圖示 + 清單名稱 + 操作按鈕群
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlaylistPlay,
                                        contentDescription = "播放清單",
                                        tint = AmberAccent,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = pl.name,
                                        color = TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconButton(
                                            onClick = { onSyncSinglePlaylist?.invoke(pl.id) ?: onImportPlaylist(pl.id, pl.name, pl.targetPlaylistGroupId, null) },
                                            enabled = !isSyncing,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Sync,
                                                contentDescription = "檢查此清單新曲目",
                                                tint = AmberAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                renameTargetId = pl.id
                                                renameTargetName = pl.name
                                                renameDialogTitle = "修改清單名稱 / 自訂備註"
                                                showRenameDialog = true
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "修改名稱",
                                                tint = AmberAccent,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        IconButton(
                                            onClick = { onDeleteSubscription(pl.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "移除紀錄",
                                                tint = RedAccent.copy(alpha = 0.85f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // 下半部：收納播放清單徽章（滿寬整行呈現，保證同一行絕不落字）
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SurfaceDark,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            changeGroupSubId = pl.id
                                            changeGroupSubName = pl.name
                                            showChangeGroupDialog = true
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.QueueMusic,
                                            contentDescription = null,
                                            tint = AmberAccent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "收納至: $targetGroupName (點擊變更)",
                                            color = AmberAccent,
                                            fontSize = 11.sp,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(renameDialogTitle, color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = renameTargetName,
                    onValueChange = { renameTargetName = it },
                    label = { Text("自訂名稱或備註") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameTargetName.isNotBlank()) {
                            onRenameSubscription?.invoke(renameTargetId, renameTargetName.trim())
                            showRenameDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Text("儲存", color = BgDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }

    // 變更 YouTube 項目綁定播放清單 Dialog
    if (showChangeGroupDialog) {
        AlertDialog(
            onDismissRequest = { showChangeGroupDialog = false },
            title = { Text("變更「$changeGroupSubName」收納清單", color = TextPrimary) },
            text = {
                Column {
                    Text("選擇後，後續更新的音訊將自動存入所選播放清單：", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    groups.forEach { group ->
                        TextButton(
                            onClick = {
                                onChangeTargetGroup?.invoke(changeGroupSubId, group.id)
                                showChangeGroupDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("📁 " + group.name, color = CyanAccent, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showChangeGroupDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }
}
