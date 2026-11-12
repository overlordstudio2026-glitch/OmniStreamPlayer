package com.overlord.omnistream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.overlord.omnistream.core.model.MediaSourceType
import com.overlord.omnistream.ui.theme.AmberAccent
import com.overlord.omnistream.ui.theme.CyanAccent
import com.overlord.omnistream.ui.theme.RedAccent
import com.overlord.omnistream.ui.theme.SurfaceDark

/**
 * 效能最佳化的縮圖組件：
 * - 透過 size(sizePx) 強制降採樣以降低記憶體與 GC 卡頓
 * - 自動降落為對應來源圖示（本機 / Google 雲端 / YouTube）
 */
@Composable
fun TrackThumbnail(
    artworkUrl: String?,
    sourceType: MediaSourceType? = null,
    modifier: Modifier = Modifier,
    sizePx: Int = 160,
    cornerRadius: Dp = 8.dp,
    isCircular: Boolean = false,
    iconSize: Dp = 20.dp
) {
    val context = LocalContext.current
    val shape = if (isCircular) CircleShape else RoundedCornerShape(cornerRadius)

    val (fallbackIcon, fallbackTint) = when (sourceType) {
        MediaSourceType.YOUTUBE -> Icons.Default.PlayArrow to RedAccent.copy(alpha = 0.85f)
        MediaSourceType.GDRIVE -> Icons.Default.Cloud to CyanAccent.copy(alpha = 0.85f)
        MediaSourceType.LOCAL -> Icons.Default.MusicNote to AmberAccent.copy(alpha = 0.85f)
        null -> Icons.Default.MusicNote to CyanAccent.copy(alpha = 0.85f)
    }

    val imageRequest = remember(artworkUrl, sizePx) {
        if (artworkUrl.isNullOrBlank()) null
        else ImageRequest.Builder(context)
            .data(artworkUrl)
            .size(sizePx)
            .crossfade(120)
            .build()
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceDark)
            .border(1.dp, CyanAccent.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = fallbackIcon,
            contentDescription = null,
            tint = fallbackTint,
            modifier = Modifier.size(iconSize)
        )

        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = "曲目封面縮圖",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
