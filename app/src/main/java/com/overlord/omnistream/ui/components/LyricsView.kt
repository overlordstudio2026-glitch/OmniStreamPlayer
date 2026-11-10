package com.overlord.omnistream.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overlord.omnistream.data.lyrics.LyricLine
import com.overlord.omnistream.ui.theme.AmberAccent
import com.overlord.omnistream.ui.theme.CyanAccent
import com.overlord.omnistream.ui.theme.TextSecondary
import java.util.Locale

/**
 * Apple Music 風格動態同步滾動歌詞視圖（支援時間偏移微調、自由閱讀切換與打軸校準模式）
 */
@Composable
fun LyricsView(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    offsetMs: Long = 0L,
    autoScrollEnabled: Boolean = true,
    isTapSyncMode: Boolean = false,
    tapSyncTargetIndex: Int = 0,
    onSelectTapSyncLine: (Int) -> Unit = {}
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "♫ 無同步歌詞 / 可點下方「換詞」搜尋其他版本",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        return
    }

    val effectivePosMs = (currentPositionMs + offsetMs).coerceAtLeast(0L)
    val activeIndex = remember(effectivePosMs, lyrics, isTapSyncMode, tapSyncTargetIndex) {
        if (isTapSyncMode) {
            tapSyncTargetIndex.coerceIn(0, lyrics.lastIndex)
        } else {
            val idx = lyrics.indexOfLast { effectivePosMs >= it.timestampMs }
            if (idx == -1) 0 else idx
        }
    }

    val listState = rememberLazyListState()

    // 當目前演唱行（或打軸目標行）變更且啟用自動跟隨時，平滑自動滾動置中
    LaunchedEffect(activeIndex, autoScrollEnabled, isTapSyncMode) {
        if ((autoScrollEnabled || isTapSyncMode) && lyrics.isNotEmpty() && activeIndex in lyrics.indices) {
            val targetScroll = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(targetScroll)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 36.dp, bottom = 72.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lyrics, key = { index, item -> "${index}_${item.text}" }) { index, line ->
            val isActive = (index == activeIndex)
            val highlightColor = if (isTapSyncMode) AmberAccent else CyanAccent

            val textColor by animateColorAsState(
                targetValue = when {
                    isActive -> highlightColor
                    isTapSyncMode && index < activeIndex -> TextSecondary.copy(alpha = 0.75f)
                    else -> TextSecondary.copy(alpha = 0.45f)
                },
                label = "LyricTextColor"
            )
            val textScale by animateFloatAsState(
                targetValue = if (isActive) 1.07f else 1.0f,
                label = "LyricTextScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (isTapSyncMode && isActive) {
                            Modifier
                                .background(AmberAccent.copy(alpha = 0.14f))
                                .border(1.dp, AmberAccent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        } else Modifier
                    )
                    .clickable {
                        if (isTapSyncMode) {
                            onSelectTapSyncLine(index)
                        } else {
                            onSeekTo((line.timestampMs - offsetMs).coerceAtLeast(0L))
                        }
                    }
                    .padding(vertical = 6.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isTapSyncMode) {
                        val totalSec = line.timestampMs / 1000L
                        val min = totalSec / 60L
                        val sec = totalSec % 60L
                        Text(
                            text = if (isActive) "▶ 等待打點 (${String.format(Locale.US, "%02d:%02d", min, sec)})"
                            else String.format(Locale.US, "%02d:%02d", min, sec),
                            color = if (isActive) AmberAccent else TextSecondary.copy(alpha = 0.4f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = line.text,
                        color = textColor,
                        fontSize = if (isActive) 18.sp else 15.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.scale(textScale)
                    )
                }
            }
        }
    }
}
