package com.overlord.omnistream.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import com.overlord.omnistream.ui.theme.CyanAccent
import com.overlord.omnistream.ui.theme.TextPrimary
import com.overlord.omnistream.ui.theme.TextSecondary

/**
 * Apple Music 風格動態同步滾動歌詞視圖
 * 支援歌詞時間軸自動跟隨、當前句平滑高亮縮放、點擊歌詞即刻 Seek 跳轉播放
 */
@Composable
fun LyricsView(
    lyrics: List<LyricLine>,
    currentPositionMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (lyrics.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "♫ 無同步歌詞 / 享受純音訊時光",
                color = TextSecondary.copy(alpha = 0.6f),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    val activeIndex = remember(currentPositionMs, lyrics) {
        val idx = lyrics.indexOfLast { currentPositionMs >= it.timestampMs }
        if (idx == -1) 0 else idx
    }

    val listState = rememberLazyListState()

    // 當目前演唱行變更時，平滑自動滾動使當前行置中
    LaunchedEffect(activeIndex) {
        if (lyrics.isNotEmpty() && activeIndex in lyrics.indices) {
            val targetScroll = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(targetScroll)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 40.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        itemsIndexed(lyrics, key = { index, item -> "${item.timestampMs}_$index" }) { index, line ->
            val isActive = (index == activeIndex)

            val textColor by animateColorAsState(
                targetValue = if (isActive) CyanAccent else TextSecondary.copy(alpha = 0.45f),
                label = "LyricTextColor"
            )
            val textScale by animateFloatAsState(
                targetValue = if (isActive) 1.08f else 1.0f,
                label = "LyricTextScale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSeekTo(line.timestampMs) }
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
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
