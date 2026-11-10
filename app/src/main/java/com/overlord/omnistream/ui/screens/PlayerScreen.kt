package com.overlord.omnistream.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.overlord.omnistream.OmniStreamApp
import com.overlord.omnistream.core.cache.ImageCacheManager
import com.overlord.omnistream.data.lyrics.LyricLine
import com.overlord.omnistream.data.lyrics.LyricsCandidate
import com.overlord.omnistream.playback.PlaybackController
import com.overlord.omnistream.ui.components.LyricsView
import com.overlord.omnistream.ui.components.TrackThumbnail
import com.overlord.omnistream.ui.components.WavySeekBar
import com.overlord.omnistream.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PlayerScreen(
    controller: PlaybackController,
    title: String,
    artist: String,
    isPlaying: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as OmniStreamApp

    val currentMediaItem by controller.currentMediaItem.collectAsState()
    val currentPosMs by controller.currentPositionMs.collectAsState()
    val durationMs by controller.durationMs.collectAsState()
    val shuffleModeEnabled by controller.shuffleModeEnabled.collectAsState()
    val repeatMode by controller.repeatMode.collectAsState()

    var showLyrics by remember { mutableStateOf(false) }
    var lyricsList by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var isLoadingLyrics by remember { mutableStateOf(false) }
    var lyricsReloadNonce by remember { mutableIntStateOf(0) }

    val coroutineScope = rememberCoroutineScope()
    val activeMediaId = currentMediaItem?.mediaId

    // 單曲時間偏移記憶與自由閱讀/打軸模式狀態
    var lyricsOffsetMs by remember(activeMediaId) {
        mutableLongStateOf(
            if (!activeMediaId.isNullOrBlank()) app.lyricsManager.getOffsetMs(context, activeMediaId) else 0L
        )
    }
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var isTapSyncMode by remember(activeMediaId) { mutableStateOf(false) }
    var tapSyncTargetIndex by remember(activeMediaId) { mutableIntStateOf(0) }
    var tapSyncDraft by remember(activeMediaId) { mutableStateOf<List<LyricLine>>(emptyList()) }

    // 候選版本歌詞搜尋彈窗狀態
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchingCandidates by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf<List<LyricsCandidate>>(emptyList()) }

    // 直接訂閱 Room 資料庫取得該曲目的即時最新 Metadata（包含剛同步完的雲端 lyricsUri 與 artworkUri）
    val dbItem by remember(activeMediaId) {
        if (!activeMediaId.isNullOrBlank()) {
            app.repository.getItemByIdFlow(activeMediaId)
        } else {
            kotlinx.coroutines.flow.flowOf(null)
        }
    }.collectAsState(initial = null)

    val effectiveLyricsUri = dbItem?.lyricsUri ?: currentMediaItem?.mediaMetadata?.extras?.getString("LYRICS_URI")

    // 當前曲目切換、取得總時長、或資料庫剛同步新歌詞 URI 時，立即自動非同步抓取動態同步歌詞
    LaunchedEffect(activeMediaId, effectiveLyricsUri, durationMs > 0L, lyricsReloadNonce) {
        val item = currentMediaItem
        val targetId = activeMediaId
        if (item != null && !targetId.isNullOrBlank()) {
            isLoadingLyrics = true
            try {
                val durSec = if (durationMs > 0L) (durationMs / 1000L) else (dbItem?.durationMs ?: 0L) / 1000L
                val t = title.ifEmpty { dbItem?.title ?: item.mediaMetadata.title?.toString() ?: "" }
                val a = artist.ifEmpty { dbItem?.artist ?: item.mediaMetadata.artist?.toString() ?: "" }
                val accessToken = app.repository.gdriveService.currentAccessToken
                val fetched = app.lyricsManager.fetchLyrics(
                    cacheKey = targetId,
                    trackTitle = t,
                    artistName = a,
                    durationSec = durSec,
                    lyricsUri = effectiveLyricsUri,
                    accessToken = accessToken,
                    context = context
                )
                // 嚴格比對：防止快速切歌時前一首的延遲請求覆蓋當前歌曲歌詞
                if (currentMediaItem?.mediaId == targetId) {
                    lyricsList = fetched
                }
            } catch (e: Throwable) {
                if (currentMediaItem?.mediaId == targetId) {
                    lyricsList = emptyList()
                }
            } finally {
                if (currentMediaItem?.mediaId == targetId) {
                    isLoadingLyrics = false
                }
            }
        } else {
            lyricsList = emptyList()
            isLoadingLyrics = false
        }
    }

    val currentRatio = if (durationMs > 0L) {
        (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 頂部導航列：下拉關閉與歌詞模式切換
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(5.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                    .background(TextSecondary.copy(alpha = 0.5f))
                    .clickable { onClose() }
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "收合播放器",
                        tint = CyanAccent,
                        modifier = Modifier.size(32.dp)
                    )
                }

                // 明確的「封面 | 動態歌詞」膠囊分段切換鈕
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(CardDark)
                        .border(1.dp, CyanAccent.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                        .padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (!showLyrics) CyanAccent else CardDark)
                            .clickable { showLyrics = false }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "封面",
                            color = if (!showLyrics) BgDark else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (showLyrics) CyanAccent else CardDark)
                            .clickable { showLyrics = true }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val lyricsLabel = when {
                            isLoadingLyrics -> "歌詞..."
                            lyricsList.isNotEmpty() -> "歌詞 (${lyricsList.size})"
                            else -> "歌詞"
                        }
                        Text(
                            text = lyricsLabel,
                            color = if (showLyrics) BgDark else if (lyricsList.isNotEmpty()) CyanAccent else TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.size(48.dp)) // 與左側收合鈕平衡置中
            }
        }

        // 中間內容區域：大封面唱盤視覺 或 動態滾動歌詞
        val artworkUrl = ImageCacheManager.resolveArtworkUrl(
            mediaId = activeMediaId,
            artworkUri = dbItem?.artworkUri ?: currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
            highQuality = true
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = showLyrics,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "PlayerCenterContent"
            ) { isLyricsMode ->
                if (isLyricsMode) {
                    // 歌詞模式：底層襯托暗色封面 + 深色漸層暗罩，絕不搶戲且大氣有層次
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(20.dp))
                            .background(CardDark.copy(alpha = 0.35f))
                            .border(1.dp, CyanAccent.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                    ) {
                        if (artworkUrl != null) {
                            AsyncImage(
                                model = remember(artworkUrl) {
                                    ImageRequest.Builder(context)
                                        .data(artworkUrl)
                                        .size(560)
                                        .crossfade(true)
                                        .build()
                                },
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.24f)
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                BgDark.copy(alpha = 0.85f),
                                                BgDark.copy(alpha = 0.65f),
                                                BgDark.copy(alpha = 0.88f)
                                            )
                                        )
                                    )
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                CyanAccent.copy(alpha = 0.08f),
                                                BgDark.copy(alpha = 0.92f)
                                            )
                                        )
                                    )
                            )
                        }

                        if (isLoadingLyrics) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = CyanAccent, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "正在搜尋雲端與網路同步歌詞...",
                                    color = TextSecondary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            LyricsView(
                                lyrics = if (isTapSyncMode) tapSyncDraft else lyricsList,
                                currentPositionMs = currentPosMs,
                                onSeekTo = { controller.seekTo(it) },
                                modifier = Modifier.fillMaxSize(),
                                offsetMs = lyricsOffsetMs,
                                autoScrollEnabled = autoScrollEnabled,
                                isTapSyncMode = isTapSyncMode,
                                tapSyncTargetIndex = tapSyncTargetIndex,
                                onSelectTapSyncLine = { tapSyncTargetIndex = it }
                            )

                            // 歌詞底部懸浮調校控制列（±0.5s 偏移記憶 + 跟隨鎖定 + 邊聽邊打軸 + 換詞搜尋）
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(BgDark.copy(alpha = 0.88f))
                                    .border(1.dp, CyanAccent.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                if (isTapSyncMode) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = {
                                                if (tapSyncTargetIndex in tapSyncDraft.indices) {
                                                    val updated = tapSyncDraft.toMutableList()
                                                    updated[tapSyncTargetIndex] = updated[tapSyncTargetIndex].copy(timestampMs = currentPosMs)
                                                    tapSyncDraft = updated
                                                    if (tapSyncTargetIndex < updated.lastIndex) {
                                                        tapSyncTargetIndex += 1
                                                    } else {
                                                        Toast.makeText(context, "已到最後一句，請點擊「儲存」完成校準！", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = AmberAccent),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.weight(1.4f).height(34.dp)
                                        ) {
                                            Text(
                                                text = "同步 #${tapSyncTargetIndex + 1}",
                                                color = BgDark,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                if (tapSyncTargetIndex > 0) tapSyncTargetIndex -= 1
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("上句", color = TextSecondary, fontSize = 11.sp)
                                        }
                                        Button(
                                            onClick = {
                                                val id = activeMediaId
                                                if (!id.isNullOrBlank() && tapSyncDraft.isNotEmpty()) {
                                                    app.lyricsManager.saveCustomSyncedLrc(context, id, tapSyncDraft)
                                                    app.lyricsManager.saveOffsetMs(context, id, 0L)
                                                    lyricsOffsetMs = 0L
                                                    lyricsList = tapSyncDraft.sortedBy { it.timestampMs }
                                                    isTapSyncMode = false
                                                    Toast.makeText(context, "✅ 已儲存本首自訂打軸歌詞！", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("儲存", color = BgDark, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                        TextButton(
                                            onClick = { isTapSyncMode = false },
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("取消", color = RedAccent, fontSize = 11.sp)
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 1. 偏移微調 (-0.5s / 偏移顯示 / +0.5s)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(CardDark)
                                                    .clickable {
                                                        val nextOffset = (lyricsOffsetMs - 500L).coerceIn(-30000L, 30000L)
                                                        lyricsOffsetMs = nextOffset
                                                        activeMediaId?.let { app.lyricsManager.saveOffsetMs(context, it, nextOffset) }
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("-0.5s", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                            }
                                            val offsetSec = lyricsOffsetMs / 1000.0
                                            Text(
                                                text = if (lyricsOffsetMs == 0L) "0.0s" else String.format(Locale.US, "%+.1fs", offsetSec),
                                                color = if (lyricsOffsetMs != 0L) CyanAccent else TextSecondary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .clickable {
                                                        lyricsOffsetMs = 0L
                                                        activeMediaId?.let { app.lyricsManager.saveOffsetMs(context, it, 0L) }
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(CardDark)
                                                    .clickable {
                                                        val nextOffset = (lyricsOffsetMs + 500L).coerceIn(-30000L, 30000L)
                                                        lyricsOffsetMs = nextOffset
                                                        activeMediaId?.let { app.lyricsManager.saveOffsetMs(context, it, nextOffset) }
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text("+0.5s", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                            }
                                        }

                                        // 2. 自動跟隨鎖定切換 + 打軸 + 換詞
                                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(if (autoScrollEnabled) CyanAccent.copy(alpha = 0.2f) else CardDark)
                                                    .clickable { autoScrollEnabled = !autoScrollEnabled }
                                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (autoScrollEnabled) "跟隨" else "自由",
                                                    color = if (autoScrollEnabled) CyanAccent else TextSecondary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            if (lyricsList.isNotEmpty()) {
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(AmberAccent.copy(alpha = 0.2f))
                                                        .clickable {
                                                            tapSyncDraft = lyricsList.toList()
                                                            val currentIdx = lyricsList.indexOfLast {
                                                                (currentPosMs + lyricsOffsetMs) >= it.timestampMs
                                                            }.coerceAtLeast(0)
                                                            tapSyncTargetIndex = currentIdx
                                                            isTapSyncMode = true
                                                            Toast.makeText(context, "打軸模式：聽到句子開唱即點按下方「同步」！", Toast.LENGTH_SHORT).show()
                                                        }
                                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                                ) {
                                                    Text("打軸", color = AmberAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(CardDark)
                                                    .clickable {
                                                        val defaultQuery = title.ifEmpty { dbItem?.title ?: currentMediaItem?.mediaMetadata?.title?.toString() ?: "" }
                                                        searchQuery = defaultQuery
                                                        showSearchDialog = true
                                                        isSearchingCandidates = true
                                                        coroutineScope.launch {
                                                            val durSec = if (durationMs > 0L) durationMs / 1000L else 0L
                                                            candidates = app.lyricsManager.searchLyricsCandidates(defaultQuery, artist, durSec)
                                                            isSearchingCandidates = false
                                                        }
                                                    }
                                                    .padding(horizontal = 7.dp, vertical = 4.dp)
                                            ) {
                                                Text("換詞", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(244.dp)
                                .clip(CircleShape)
                                .background(CardDark)
                                .border(2.dp, CyanAccent.copy(alpha = 0.35f), CircleShape)
                                .clickable { showLyrics = true },
                            contentAlignment = Alignment.Center
                        ) {
                            if (artworkUrl != null) {
                                TrackThumbnail(
                                    artworkUrl = artworkUrl,
                                    modifier = Modifier.size(224.dp),
                                    sizePx = 560,
                                    isCircular = true,
                                    iconSize = 64.dp
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(214.dp)
                                        .clip(CircleShape)
                                        .background(SurfaceDark),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "OMNI STREAM",
                                        color = CyanAccent,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        val hintText = when {
                            isLoadingLyrics -> "⏳ 正在比對動態歌詞..."
                            lyricsList.isNotEmpty() -> "✨ 已匹配同步歌詞 · 點擊封面或上方「歌詞」查看"
                            else -> "點擊「歌詞」切換動態歌詞模式（純音樂歌曲適用）"
                        }
                        Text(
                            text = hintText,
                            color = if (lyricsList.isNotEmpty()) CyanAccent.copy(alpha = 0.85f) else TextSecondary.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clickable { showLyrics = true }
                        )
                    }
                }
            }
        }

        // 歌曲標題與創作者資訊
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title.ifEmpty { "OmniStream 音訊播放器" },
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = artist.ifEmpty { "無縫跨來源播放" },
                color = CyanAccent,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 播放控制面板與靈動水波進度時間軸
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 靈動流體波浪水波進度條 (Cyberpunk Wavy SeekBar)
            WavySeekBar(
                progressRatio = currentRatio,
                isPlaying = isPlaying,
                onSeek = { ratio ->
                    if (durationMs > 0L) {
                        val targetMs = (ratio * durationMs).toLong()
                        controller.seekTo(targetMs)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
            )

            // 時間文字顯示：目前播放進度 vs 音訊總長度
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatPlayerTime(currentPosMs),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = formatPlayerTime(durationMs),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 控制按鈕群（智慧不重複隨機、上一首、快退10s、播放/暫停、快進10s、下一首、循環切換）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 智慧隨機播放按鈕
                IconButton(
                    onClick = { controller.toggleShuffle() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "不重複隨機播放",
                        tint = if (shuffleModeEnabled) CyanAccent else TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                IconButton(
                    onClick = { controller.skipToPrevious() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "上一首",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                IconButton(
                    onClick = { controller.seekRelative(-10000L) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "快退 10 秒",
                        tint = CyanAccent,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = { if (isPlaying) controller.pause() else controller.play() },
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(CyanAccent)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "播放/暫停",
                        tint = BgDark,
                        modifier = Modifier.size(34.dp)
                    )
                }

                IconButton(
                    onClick = { controller.seekRelative(10000L) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "快進 10 秒",
                        tint = CyanAccent,
                        modifier = Modifier.size(26.dp)
                    )
                }

                IconButton(
                    onClick = { controller.skipToNext() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "下一首",
                        tint = TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // 循環模式切換按鈕
                IconButton(
                    onClick = { controller.toggleRepeat() },
                    modifier = Modifier.size(36.dp)
                ) {
                    val repeatIcon = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat
                    val isRepeatActive = repeatMode != Player.REPEAT_MODE_OFF
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "循環播放",
                        tint = if (isRepeatActive) CyanAccent else TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }

    // 候選歌詞版本搜尋與還原對話框
    if (showSearchDialog) {
        val hasCustom = !activeMediaId.isNullOrBlank() && app.lyricsManager.hasCustomSyncedLrc(context, activeMediaId)
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("切換歌詞版本", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("搜尋關鍵字") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                isSearchingCandidates = true
                                coroutineScope.launch {
                                    val durSec = if (durationMs > 0L) durationMs / 1000L else 0L
                                    candidates = app.lyricsManager.searchLyricsCandidates(searchQuery, "", durSec)
                                    isSearchingCandidates = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CyanAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("搜尋", color = BgDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isSearchingCandidates) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = CyanAccent)
                        }
                    } else if (candidates.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("找不到相符歌詞版本，可嘗試精簡歌名關鍵字", color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(candidates, key = { it.id }) { cand ->
                                val durText = if (cand.durationSec > 0L) {
                                    String.format(Locale.US, "%02d:%02d", cand.durationSec / 60, cand.durationSec % 60)
                                } else "--:--"
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SurfaceDark)
                                        .clickable {
                                            val id = activeMediaId
                                            if (!id.isNullOrBlank()) {
                                                app.lyricsManager.saveCustomSyncedLrc(context, id, cand.lines)
                                                lyricsList = cand.lines
                                                showSearchDialog = false
                                                Toast.makeText(context, "✅ 已套用「${cand.trackName}」歌詞版本", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        .padding(10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (cand.isSynced) "[同步]" else "[純字]",
                                            color = if (cand.isSynced) CyanAccent else AmberAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = cand.trackName,
                                            color = TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(durText, color = TextSecondary, fontSize = 11.sp)
                                    }
                                    Text(
                                        text = listOf(cand.artistName, cand.albumName).filter { it.isNotBlank() }.joinToString(" · "),
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (hasCustom) {
                    TextButton(onClick = {
                        val id = activeMediaId
                        if (!id.isNullOrBlank()) {
                            app.lyricsManager.clearCustomSyncedLrc(context, id)
                            app.lyricsManager.saveOffsetMs(context, id, 0L)
                            lyricsOffsetMs = 0L
                            lyricsReloadNonce += 1
                            showSearchDialog = false
                            Toast.makeText(context, "已還原為雲端/預設原始歌詞", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("還原原版", color = AmberAccent)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false }) {
                    Text("關閉", color = TextSecondary)
                }
            },
            containerColor = CardDark
        )
    }
}

fun formatPlayerTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
