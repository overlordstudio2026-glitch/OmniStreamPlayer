package com.overlord.omnistream.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import com.overlord.omnistream.playback.PlaybackController
import com.overlord.omnistream.ui.components.LyricsView
import com.overlord.omnistream.ui.components.TrackThumbnail
import com.overlord.omnistream.ui.components.WavySeekBar
import com.overlord.omnistream.ui.theme.*
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

    // 當前曲目切換或取得總時長時，自動非同步抓取動態同步歌詞（支援雲端/本機 .lrc/.srt/.txt 與 LRCLIB）
    LaunchedEffect(currentMediaItem?.mediaId, durationMs > 0L) {
        val item = currentMediaItem
        val targetId = item?.mediaId
        if (item != null && !targetId.isNullOrBlank()) {
            isLoadingLyrics = true
            try {
                val durSec = if (durationMs > 0L) (durationMs / 1000L) else 0L
                val t = title.ifEmpty { item.mediaMetadata.title?.toString() ?: "" }
                val a = artist.ifEmpty { item.mediaMetadata.artist?.toString() ?: "" }
                val dedicatedLyricsUri = item.mediaMetadata.extras?.getString("LYRICS_URI")
                val accessToken = app.repository.gdriveService.currentAccessToken
                val fetched = app.lyricsManager.fetchLyrics(
                    cacheKey = targetId,
                    trackTitle = t,
                    artistName = a,
                    durationSec = durSec,
                    lyricsUri = dedicatedLyricsUri,
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
            mediaId = currentMediaItem?.mediaId,
            artworkUri = currentMediaItem?.mediaMetadata?.artworkUri?.toString(),
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
                                lyrics = lyricsList,
                                currentPositionMs = currentPosMs,
                                onSeekTo = { controller.seekTo(it) },
                                modifier = Modifier.fillMaxSize()
                            )
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
