package io.github.numq.kempo.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.max

/**
 * Interactive Studio Waveform visualizer displaying a mirrored amplitude envelope
 * with smooth gradient playback fills and interactive cursor seeking.
 */
@Composable
fun WaveformView(
    peaks: FloatArray,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = StudioColors.PrimaryCyan,
    inactiveColor: Color = StudioColors.WaveformInactive,
    cursorColor: Color = Color.White
) {
    Canvas(modifier = modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures { offset ->
            onSeek((offset.x / size.width).coerceIn(0f, 1f))
        }
    }.pointerInput(Unit) {
        detectDragGestures { change, _ ->
            change.consume()
            onSeek((change.position.x / size.width).coerceIn(0f, 1f))
        }
    }) {
        if (peaks.isEmpty()) return@Canvas

        val canvasWidth = size.width
        val canvasHeight = size.height
        val centerY = canvasHeight / 2f

        val barSpacing = canvasWidth / peaks.size
        val barWidth = max(1.5f, barSpacing * 0.72f)
        val progressX = canvasWidth * progress

        val activeBrush = Brush.verticalGradient(
            colors = listOf(activeColor, activeColor.copy(alpha = 0.65f), activeColor), startY = 0f, endY = canvasHeight
        )

        val cornerRadius = CornerRadius(2.5f, 2.5f)

        for (i in peaks.indices) {
            val barHeight = peaks[i] * (canvasHeight * 0.88f)
            val x = i * barSpacing
            val y = centerY - barHeight / 2f

            val isPlayed = (x + barWidth) <= progressX
            val topLeft = Offset(x, y)
            val barSize = Size(barWidth, barHeight)

            if (isPlayed) {
                drawRoundRect(
                    brush = activeBrush, topLeft = topLeft, size = barSize, cornerRadius = cornerRadius
                )
            } else {
                drawRoundRect(
                    color = inactiveColor, topLeft = topLeft, size = barSize, cornerRadius = cornerRadius
                )
            }
        }

        drawLine(
            color = cursorColor, start = Offset(progressX, 0f), end = Offset(progressX, canvasHeight), strokeWidth = 2f
        )
    }
}