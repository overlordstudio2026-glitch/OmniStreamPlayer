package com.overlord.omnistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overlord.omnistream.core.model.MediaSourceType
import com.overlord.omnistream.core.model.PlaylistItem
import com.overlord.omnistream.data.local.entity.PlaylistGroupEntity
import com.overlord.omnistream.ui.theme.*

@Composable
fun PlaylistScreen(
    groups: List<PlaylistGroupEntity>,
    selectedGroupId: String,
    currentPlayingId: String? = null,
    isPlayerPlaying: Boolean = false,
    onSelectGroup: (String) -> Unit,
    onCreateGroup: (name: String) -> Unit,
    onRenameGroup: (id: String, newName: String) -> Unit = { _, _ -> },
    onDeleteGroup: (id: String) -> Unit = {},
    onClearGroup: (id: String) -> Unit = {},
    items: List<PlaylistItem>,
    onItemClick: (Int) -> Unit,
    onDeleteItem: (String) -> Unit,
    onBatchDeleteItems: (List<String>) -> Unit = {},
    onMoveItemToGroup: (itemId: String, newGroupId: String) -> Unit = { _, _ -> },
    onBatchMoveItemsToGroup: (itemIds: List<String>, newGroupId: String) -> Unit = { _, _ -> },
    onScanLocalAudio: () -> Unit,
    isSyncing: Boolean = false,
    onSyncCloud: () -> Unit = {},
    onRestoreBackup: () -> Unit = {}
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var targetRenameGroupId by remember { mutableStateOf("") }
    var targetRenameGroupName by remember { mutableStateOf("") }
    var newGroupName by remember { mutableStateOf("") }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    // 批量管理狀態
    var isBatchMode by remember { mutableStateOf(false) }
    val selectedItemIds = remember { mutableStateListOf<String>() }

    // 清空與刪除清單確認 Dialog
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var targetDeleteGroupId by remember { mutableStateOf("") }
    var targetDeleteGroupName by remember { mutableStateOf("") }
    var showClearGroupDialog by remember { mutableStateOf(false) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // 移動曲目至其他清單 Dialog 狀態
    var showMoveItemDialog by remember { mutableStateOf(false) }
    var targetMoveItemId by remember { mutableStateOf("") }
    var targetMoveItemTitle by remember { mutableStateOf("") }
    var showBatchMoveDialog by remember { mutableStateOf(false) }

    val currentGroupName = groups.find { it.id == selectedGroupId }?.name ?: "預設清單"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        // 頂部清單切換與管理列
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Button(
                    onClick = { isDropdownExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = CardDark),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "📁 $currentGroupName",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = "切換清單",
                        tint = CyanAccent
                    )
                }

                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false },
                    modifier = Modifier.background(CardDark)
                ) {
                    groups.forEach { group ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = group.name,
                                        color = if (group.id == selectedGroupId) CyanAccent else TextPrimary,
                                        fontWeight = if (group.id == selectedGroupId) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            isDropdownExpanded = false
                                            targetRenameGroupId = group.id
                                            targetRenameGroupName = group.name
                                            showRenameDialog = true
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "修改名稱",
                                            tint = CyanAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    if (groups.size > 1) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        IconButton(
                                            onClick = {
                                                isDropdownExpanded = false
                                                targetDeleteGroupId = group.id
                                                targetDeleteGroupName = group.name
                                                showDeleteGroupDialog = true
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "刪除清單",
                                                tint = RedAccent.copy(alpha = 0.8f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                onSelectGroup(group.id)
                                isDropdownExpanded = false
                            }
                        )
                    }
                    HorizontalDivider(color = SurfaceDark)
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = CyanAccent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("建立新播放清單", color = CyanAccent)
                            }
                        },
                        onClick = {
                            isDropdownExpanded = false
                            showCreateDialog = true
                        }
                    )
                    HorizontalDivider(color = SurfaceDark)
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ClearAll, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("清空目前清單曲目", color = AmberAccent)
                            }
                        },
                        onClick = {
                            isDropdownExpanded = false
                            showClearGroupDialog = true
                        }
                    )
                    if (groups.size > 1) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = RedAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("刪除目前播放清單", color = RedAccent)
                                }
                            },
                            onClick = {
                                isDropdownExpanded = false
                                targetDeleteGroupId = selectedGroupId
                                targetDeleteGroupName = currentGroupName
                                showDeleteGroupDialog = true
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 修改當前清單名稱快捷按鈕
            IconButton(
                onClick = {
                    targetRenameGroupId = selectedGroupId
                    targetRenameGroupName = currentGroupName
                    showRenameDialog = true
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardDark)
            ) {
                Icon(Icons.Default.Edit, contentDescription = "修改目前清單名稱", tint = CyanAccent)
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 批量多選模式切換按鈕
            IconButton(
                onClick = {
                    isBatchMode = !isBatchMode
                    if (!isBatchMode) selectedItemIds.clear()
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isBatchMode) CyanAccent.copy(alpha = 0.25f) else CardDark)
            ) {
                Icon(
                    Icons.Default.Checklist,
                    contentDescription = if (isBatchMode) "退出批量管理" else "批量管理曲目",
                    tint = if (isBatchMode) CyanAccent else TextSecondary
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 同步雲端有聲書按鈕
            IconButton(
                onClick = onSyncCloud,
                enabled = !isSyncing,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardDark)
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = CyanAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Sync,
                        contentDescription = "同步雲端有聲書",
                        tint = CyanAccent
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 掃描本機按鈕
            IconButton(
                onClick = onScanLocalAudio,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardDark)
            ) {
                Icon(Icons.Default.LibraryMusic, contentDescription = "掃描本機音樂", tint = AmberAccent)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 批量操作橫列（當開啟批量模式時呈現）
        if (isBatchMode && items.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selectedItemIds.size == items.size && items.isNotEmpty(),
                            onCheckedChange = { checked ->
                                selectedItemIds.clear()
                                if (checked) {
                                    selectedItemIds.addAll(items.map { it.id })
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = AmberAccent)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "已選 ${selectedItemIds.size} / ${items.size} 首",
                            color = AmberAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showBatchMoveDialog = true },
                            enabled = selectedItemIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.QueueMusic, contentDescription = null, modifier = Modifier.size(14.dp), tint = BgDark)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("移至清單 (${selectedItemIds.size})", fontSize = 12.sp, color = BgDark, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { showBatchDeleteDialog = true },
                            enabled = selectedItemIds.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = RedAccent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp), tint = TextPrimary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("刪除 (${selectedItemIds.size})", fontSize = 12.sp, color = TextPrimary)
                        }
                        OutlinedButton(
                            onClick = {
                                isBatchMode = false
                                selectedItemIds.clear()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("退出", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                }
            }
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Text(
                        text = "目前播放清單「$currentGroupName」為空",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "若您先前曾有使用紀錄，請點擊下方直接載入！\n（備份檔存放於手機【下載 (Download)】資料夾中的 omnistream_backup.json）",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onRestoreBackup,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = BgDark, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("一鍵載入先前保存資料", color = BgDark, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "或點擊右上角掃描本機音樂，或至「YouTube / 雲端」分頁新增內容",
                        color = TextSecondary.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    val isCurrent = (item.id == currentPlayingId)
                    val isSelected = item.id in selectedItemIds
                    PlaylistItemRow(
                        item = item,
                        index = index + 1,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlayerPlaying,
                        isBatchMode = isBatchMode,
                        isSelected = isSelected,
                        onToggleSelect = {
                            if (isSelected) selectedItemIds.remove(item.id)
                            else selectedItemIds.add(item.id)
                        },
                        onClick = {
                            if (isBatchMode) {
                                if (isSelected) selectedItemIds.remove(item.id)
                                else selectedItemIds.add(item.id)
                            } else {
                                onItemClick(index)
                            }
                        },
                        onMove = {
                            targetMoveItemId = item.id
                            targetMoveItemTitle = item.title
                            showMoveItemDialog = true
                        },
                        onDelete = { onDeleteItem(item.id) }
                    )
                }
            }
        }
    }

    // 建立新播放清單 Dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("新增播放清單", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = newGroupName,
                    onValueChange = { newGroupName = it },
                    label = { Text("清單名稱") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newGroupName.isNotBlank()) {
                            onCreateGroup(newGroupName.trim())
                            newGroupName = ""
                            showCreateDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent)
                ) {
                    Text("建立", color = BgDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }

    // 修改播放清單名稱 Dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("修改播放清單名稱", color = TextPrimary) },
            text = {
                OutlinedTextField(
                    value = targetRenameGroupName,
                    onValueChange = { targetRenameGroupName = it },
                    label = { Text("新名稱") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (targetRenameGroupName.isNotBlank()) {
                            onRenameGroup(targetRenameGroupId, targetRenameGroupName.trim())
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

    // 清空目前清單曲目確認 Dialog
    if (showClearGroupDialog) {
        AlertDialog(
            onDismissRequest = { showClearGroupDialog = false },
            title = { Text("清空播放清單", color = TextPrimary) },
            text = {
                Text("確定要清空播放清單「$currentGroupName」內的所有曲目嗎？\n清單本身仍會保留。", color = TextSecondary)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearGroup(selectedGroupId)
                        showClearGroupDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedAccent)
                ) {
                    Text("確定清空", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearGroupDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }

    // 刪除整份播放清單確認 Dialog
    if (showDeleteGroupDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteGroupDialog = false },
            title = { Text("刪除播放清單", color = TextPrimary) },
            text = {
                Text("確定要徹底刪除播放清單「$targetDeleteGroupName」及其內部的所有曲目嗎？", color = TextSecondary)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGroup(targetDeleteGroupId)
                        showDeleteGroupDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedAccent)
                ) {
                    Text("確定刪除", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroupDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }

    // 批量刪除曲目確認 Dialog
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDeleteDialog = false },
            title = { Text("批量刪除曲目", color = TextPrimary) },
            text = {
                Text("確定要從「$currentGroupName」中移除選取的 ${selectedItemIds.size} 首曲目嗎？", color = TextSecondary)
            },
            confirmButton = {
                Button(
                    onClick = {
                        onBatchDeleteItems(selectedItemIds.toList())
                        selectedItemIds.clear()
                        isBatchMode = false
                        showBatchDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedAccent)
                ) {
                    Text("確定刪除", color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }

    // 單曲移動至其他清單 Dialog
    if (showMoveItemDialog) {
        val otherGroups = groups.filter { it.id != selectedGroupId }
        AlertDialog(
            onDismissRequest = { showMoveItemDialog = false },
            title = { Text("移動曲目至其他清單", color = TextPrimary) },
            text = {
                Column {
                    Text("曲目：$targetMoveItemTitle", color = TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(10.dp))
                    if (otherGroups.isEmpty()) {
                        Text("目前沒有其他播放清單，請先至頂部建立新清單！", color = TextSecondary, fontSize = 12.sp)
                    } else {
                        otherGroups.forEach { g ->
                            TextButton(
                                onClick = {
                                    onMoveItemToGroup(targetMoveItemId, g.id)
                                    showMoveItemDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📁 " + g.name, color = CyanAccent, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMoveItemDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }

    // 批量移動至其他清單 Dialog
    if (showBatchMoveDialog) {
        val otherGroups = groups.filter { it.id != selectedGroupId }
        AlertDialog(
            onDismissRequest = { showBatchMoveDialog = false },
            title = { Text("批量移動 ${selectedItemIds.size} 首曲目", color = TextPrimary) },
            text = {
                Column {
                    Text("選擇目標播放清單：", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    if (otherGroups.isEmpty()) {
                        Text("目前沒有其他播放清單，請先至頂部建立新清單！", color = TextSecondary, fontSize = 12.sp)
                    } else {
                        otherGroups.forEach { g ->
                            TextButton(
                                onClick = {
                                    onBatchMoveItemsToGroup(selectedItemIds.toList(), g.id)
                                    selectedItemIds.clear()
                                    isBatchMode = false
                                    showBatchMoveDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("📁 " + g.name, color = CyanAccent, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBatchMoveDialog = false }) {
                    Text("取消", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }
}

@Composable
fun PlaylistItemRow(
    item: PlaylistItem,
    index: Int,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    isBatchMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit,
    onMove: () -> Unit = {},
    onDelete: () -> Unit
) {
    val hasPlayed = item.lastPlayedTime > 0L
    val titleColor = when {
        isCurrent && !isBatchMode -> CyanAccent
        isSelected -> AmberAccent
        hasPlayed -> TextSecondary.copy(alpha = 0.85f)
        else -> TextPrimary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isSelected) Modifier.border(1.dp, AmberAccent, RoundedCornerShape(8.dp))
                else if (isCurrent && !isBatchMode) Modifier.border(1.dp, CyanAccent, RoundedCornerShape(8.dp))
                else Modifier
            )
            .background(if (isSelected) SurfaceDark.copy(alpha = 0.9f) else if (isCurrent && !isBatchMode) SurfaceDark else CardDark)
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isBatchMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(checkedColor = AmberAccent),
                modifier = Modifier.padding(end = 4.dp)
            )
        } else if (isCurrent) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.VolumeUp else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "播放中" else "已暫停",
                tint = CyanAccent,
                modifier = Modifier
                    .width(28.dp)
                    .size(20.dp)
            )
        } else {
            Text(
                text = "$index",
                color = if (hasPlayed) TextSecondary.copy(alpha = 0.6f) else TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.width(28.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = titleColor,
                fontSize = 15.sp,
                fontWeight = if (isCurrent || isSelected) FontWeight.Bold else if (hasPlayed) FontWeight.Normal else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent && !isBatchMode) {
                    Text(
                        text = if (isPlaying) "[播放中]" else "[已暫停]",
                        color = CyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                } else if (hasPlayed) {
                    Text(
                        text = "[已播過]",
                        color = TextSecondary.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                val (badgeColor, badgeText) = when (item.sourceType) {
                    MediaSourceType.LOCAL -> AmberAccent to "本機"
                    MediaSourceType.GDRIVE -> CyanAccent to "雲端"
                    MediaSourceType.YOUTUBE -> RedAccent to "YouTube"
                }
                Text(
                    text = "[$badgeText]",
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = item.artist,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 總時長標籤
        Text(
            text = if (item.durationMs > 0L) formatPlayerTime(item.durationMs) else "--:--",
            color = if (hasPlayed) TextSecondary.copy(alpha = 0.5f) else TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 6.dp)
        )

        if (!isBatchMode) {
            IconButton(onClick = onMove) {
                Icon(
                    imageVector = Icons.Default.QueueMusic,
                    contentDescription = "移動清單",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "移除",
                    tint = TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
