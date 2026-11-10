package com.overlord.omnistream.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.overlord.omnistream.ui.theme.CardDark
import com.overlord.omnistream.ui.theme.CyanAccent
import kotlin.math.PI
import kotlin.math.sin

/**
 * 靈動波浪水波進度條 (Cyberpunk Wavy SeekBar)
 * 播放時呈現如水波起伏的霓虹流體正弦波浪，暫停時優雅過渡撫平為精確直線
 */
@Composable
fun WavySeekBar(
    progressRatio: Float,
    isPlaying: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = CyanAccent,
    inactiveColor: Color = CardDark,
    thumbColor: Color = CyanAccent
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(progressRatio) }

    val displayedProgress = if (isDragging) dragProgress else progressRatio

    // 水波波動相位連續動畫
    val infiniteTransition = rememberInfiniteTransition(label = "WavyPhaseTransition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavyPhase"
    )

    // 當暫停時，波幅 (amplitude) 平滑回歸至 0（撫平成直線）；播放時平滑放大至 5dp
    val targetAmplitude = if (isPlaying && !isDragging) 10f else 0f
    val animatedAmplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "WavyAmplitude"
    )

    // 拖曳時游標微放大效果
    val thumbRadius by animateFloatAsState(
        targetValue = if (isDragging) 22f else 16f,
        animationSpec = tween(durationMillis = 200),
        label = "ThumbRadius"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        try {
                            val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                            dragProgress = ratio
                            isDragging = true
                            val success = tryAwaitRelease()
                            if (success) {
                                onSeek(dragProgress)
                            }
                        } finally {
                            isDragging = false
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragProgress = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragProgress = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        try {
                            onSeek(dragProgress)
                        } finally {
                            isDragging = false
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f
            val activeWidth = width * displayedProgress.coerceIn(0f, 1f)

            // 1. 繪製非啟用軌道 (Inactive track) - 筆直沉穩底色
            drawLine(
                color = inactiveColor,
                start = Offset(activeWidth, centerY),
                end = Offset(width, centerY),
                strokeWidth = 6.dp.toPx(),
                cap = StrokeCap.Round
            )

            // 2. 繪製啟用軌道 (Active track) - 靈動正弦波浪或直線
            if (activeWidth > 0f) {
                val wavePath = Path()
                wavePath.moveTo(0f, centerY)

                val wavelength = 60f // 每個波峰的波長
                var x = 0f
                val step = 4f

                while (x <= activeWidth) {
                    // 正弦波形 y = centerY + A * sin(kx + phase)
                    val y = if (animatedAmplitude > 0.5f) {
                        centerY + animatedAmplitude * sin((x / wavelength) * (2 * PI).toFloat() + phase)
                    } else {
                        centerY
                    }
                    wavePath.lineTo(x, y)
                    x += step
                }
                // 確保線條連接至游標目前 X 座標
                wavePath.lineTo(activeWidth, centerY)

                drawPath(
                    path = wavePath,
                    color = activeColor,
                    style = Stroke(
                        width = 5.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                )
            }

            // 3. 繪製外發光游標 (Thumb)
            drawCircle(
                color = activeColor.copy(alpha = 0.35f),
                radius = thumbRadius + 8f,
                center = Offset(activeWidth, centerY)
            )
            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(activeWidth, centerY)
            )
        }
    }
}
