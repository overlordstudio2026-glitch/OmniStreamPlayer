package com.overlord.omnistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QueueMusic
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

import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun GDriveScreen(
    subscriptions: List<SubscriptionEntity>,
    groups: List<PlaylistGroupEntity> = emptyList(),
    currentGroupId: String = "default",
    isSyncing: Boolean,
    onAddFolder: (folderIdOrUrl: String, name: String, targetGroupId: String, newGroupName: String?) -> Unit,
    onDeleteFolder: (id: String) -> Unit,
    onRenameFolder: ((id: String, newName: String) -> Unit)? = null,
    onChangeTargetGroup: ((id: String, newGroupId: String) -> Unit)? = null,
    onSyncFolder: ((id: String) -> Unit)? = null,
    onSyncNow: () -> Unit,
    onManualBackup: () -> Unit = {},
    onManualRestore: () -> Unit = {}
) {
    var folderInput by remember { mutableStateOf("") }
    var folderNameInput by remember { mutableStateOf("") }
    
    // 清單選擇模式: "__AUTO__" (以此資料夾名稱自建新清單), "__CUSTOM__" (手動輸入新清單名稱), 或現有的 groupId
    var selectedTargetOption by remember { mutableStateOf("__AUTO__") }
    var customNewGroupName by remember { mutableStateOf("") }
    var isTargetDropdownExpanded by remember { mutableStateOf(false) }

    // 是否展開新增表單（預設若無資料夾則展開，已有資料夾則收合以優先展示已監控項目）
    var isAddFormExpanded by rememberSaveable { mutableStateOf(subscriptions.isEmpty()) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTargetId by remember { mutableStateOf("") }
    var renameTargetName by remember { mutableStateOf("") }

    var showChangeGroupDialog by remember { mutableStateOf(false) }
    var changeGroupFolderId by remember { mutableStateOf("") }
    var changeGroupFolderName by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = "Google 雲端硬碟自動同步",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            // 新增資料夾 Card (可摺疊/展開)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isAddFormExpanded = !isAddFormExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isAddFormExpanded) Icons.Default.FolderOpen else Icons.Default.CreateNewFolder,
                                    contentDescription = null,
                                    tint = CyanAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isAddFormExpanded) "監控雲端資料夾 (公開連結/ID 皆可)" else "➕ 新增監控雲端資料夾 (點擊展開)",
                                    color = CyanAccent,
                                    fontSize = 15.sp,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                            }
                            IconButton(
                                onClick = { isAddFormExpanded = !isAddFormExpanded },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isAddFormExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isAddFormExpanded) "收合表單" else "展開表單",
                                    tint = CyanAccent
                                )
                            }
                        }

                        if (isAddFormExpanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "支援免登入自動抓取音訊，可指定收納至特定播放清單，分門別類不混雜！",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = folderNameInput,
                                onValueChange = { folderNameInput = it },
                                label = { Text("自訂名稱 (如: 每日新知 Podcast)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = folderInput,
                                onValueChange = { folderInput = it },
                                label = { Text("資料夾網址或 ID") },
                                placeholder = { Text("https://drive.google.com/drive/folders/...") },
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
                                    onClick = { isTargetDropdownExpanded = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark)
                                ) {
                                    val currentLabel = when (selectedTargetOption) {
                                        "__AUTO__" -> "✨ 自動以此資料夾名稱建立獨立清單 (推薦)"
                                        "__CUSTOM__" -> "➕ 自訂全新播放清單名稱"
                                        else -> "📁 " + (groups.find { it.id == selectedTargetOption }?.name ?: "現有清單")
                                    }
                                    Text(
                                        text = currentLabel,
                                        color = CyanAccent,
                                        fontSize = 13.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = CyanAccent)
                                }

                                DropdownMenu(
                                    expanded = isTargetDropdownExpanded,
                                    onDismissRequest = { isTargetDropdownExpanded = false },
                                    modifier = Modifier.background(CardDark)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("✨ 自動以此資料夾名稱建立獨立清單", color = CyanAccent) },
                                        onClick = {
                                            selectedTargetOption = "__AUTO__"
                                            isTargetDropdownExpanded = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("➕ 自訂全新播放清單名稱...", color = AmberAccent) },
                                        onClick = {
                                            selectedTargetOption = "__CUSTOM__"
                                            isTargetDropdownExpanded = false
                                        }
                                    )
                                    HorizontalDivider(color = SurfaceDark)
                                    groups.forEach { group ->
                                        DropdownMenuItem(
                                            text = { Text("📁 " + group.name, color = TextPrimary) },
                                            onClick = {
                                                selectedTargetOption = group.id
                                                isTargetDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }

                            if (selectedTargetOption == "__CUSTOM__") {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = customNewGroupName,
                                    onValueChange = { customNewGroupName = it },
                                    label = { Text("請輸入全新播放清單名稱") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    if (folderInput.isNotBlank()) {
                                        val finalNewName = if (selectedTargetOption == "__CUSTOM__") customNewGroupName.trim() else null
                                        onAddFolder(folderInput, folderNameInput.ifBlank { "雲端資料夾" }, selectedTargetOption, finalNewName)
                                        folderInput = ""
                                        folderNameInput = ""
                                        customNewGroupName = ""
                                        isAddFormExpanded = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("新增並載入至指定清單", color = BgDark, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // 監控狀態 Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已監控資料夾 (${subscriptions.size})",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )

                    Button(
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = CyanAccent,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("同步中...", color = CyanAccent)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = "同步", tint = CyanAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("同步全部", color = CyanAccent)
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
                        Text("💾 立即備份設定", color = CyanAccent, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onManualRestore,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🔄 從檔案還原", color = CyanAccent, fontSize = 12.sp)
                    }
                }
            }

            if (subscriptions.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "尚未加入任何雲端資料夾\n點擊上方「➕ 新增監控雲端資料夾」貼上連結即可連續播放資料夾音訊",
                            color = TextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            fontSize = 13.sp
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
                                    Text("找不到先前的雲端資料夾？", color = AmberAccent, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
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
                items(subscriptions, key = { it.id }) { sub ->
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
                            // 上半部：圖示 + 名稱 + 操作按鈕群
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = null,
                                    tint = CyanAccent,
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
                                        onClick = { onSyncFolder?.invoke(sub.id) },
                                        enabled = !isSyncing,
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sync,
                                            contentDescription = "同步此資料夾",
                                            tint = CyanAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            renameTargetId = sub.id
                                            renameTargetName = sub.name
                                            showRenameDialog = true
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Edit,
                                            contentDescription = "自訂加註/修改名稱",
                                            tint = CyanAccent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteFolder(sub.id) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "刪除",
                                            tint = RedAccent.copy(alpha = 0.85f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 下半部：收納播放清單徽章（滿寬整行呈現，保證在同一行絕對不落字）
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = SurfaceDark,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        changeGroupFolderId = sub.id
                                        changeGroupFolderName = sub.name
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
                                        tint = CyanAccent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "收納至: $targetGroupName (點擊變更)",
                                        color = CyanAccent,
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

        // 修改資料夾名稱 Dialog
        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("修改資料夾名稱 / 自訂加註", color = TextPrimary) },
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
                                onRenameFolder?.invoke(renameTargetId, renameTargetName.trim())
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

        // 變更資料夾綁定播放清單 Dialog
        if (showChangeGroupDialog) {
            AlertDialog(
                onDismissRequest = { showChangeGroupDialog = false },
                title = { Text("變更「$changeGroupFolderName」收納清單", color = TextPrimary) },
                text = {
                    Column {
                        Text("選擇後，後續同步的音訊將自動存入所選播放清單：", color = TextSecondary, fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        groups.forEach { group ->
                            TextButton(
                                onClick = {
                                    onChangeTargetGroup?.invoke(changeGroupFolderId, group.id)
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
}

