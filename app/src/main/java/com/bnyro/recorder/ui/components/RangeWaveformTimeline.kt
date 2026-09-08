package com.bnyro.recorder.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bnyro.recorder.util.AudioWaveformExtractor

@Composable
fun RangeWaveformTimeline(
    totalDurationMs: Long,
    startMs: Long,
    endMs: Long,
    currentPositionMs: Long,
    waveform: List<Float>?,
    filmstrip: List<Bitmap> = emptyList(),
    onStartChanged: (Long) -> Unit,
    onEndChanged: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = totalDurationMs.coerceAtLeast(1L)
    val safeEnd = endMs.coerceIn(startMs, duration)
    val safeStart = startMs.coerceIn(0L, safeEnd)

    val cyanColor = Color(0xFF00E5C0)
    val cyanGlow = Color(0x3314FFE0)
    val bgDark = Color(0xFF0F1216)
    val dimOverlay = Color(0x99000000)
    val playheadColor = Color.White
    val handleColor = Color(0xFF00E5C0)

    val bars = if (waveform != null && !AudioWaveformExtractor.isFlatWaveform(waveform)) {
        waveform
    } else {
        remember { AudioWaveformExtractor.createSyntheticWaveform(12345, 120) }
    }

    var activeDragHandle by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgDark)
            .pointerInput(totalDurationMs, startMs, endMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val touchX = down.position.x
                    val startX = (safeStart.toFloat() / duration) * w
                    val endX = (safeEnd.toFloat() / duration) * w
                    val handleTolerance = 36.dp.toPx()

                    activeDragHandle = when {
                        kotlin.math.abs(touchX - startX) <= handleTolerance -> 1
                        kotlin.math.abs(touchX - endX) <= handleTolerance -> 2
                        else -> {
                            val seekMs = ((touchX / w).coerceIn(0f, 1f) * duration).toLong()
                            onSeek(seekMs)
                            3
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break

                        val curX = change.position.x
                        val curFraction = (curX / w).coerceIn(0f, 1f)
                        val curMs = (curFraction * duration).toLong()

                        when (activeDragHandle) {
                            1 -> onStartChanged(curMs.coerceIn(0L, safeEnd - 100L))
                            2 -> onEndChanged(curMs.coerceIn(safeStart + 100L, duration))
                            3 -> onSeek(curMs)
                        }
                        change.consume()
                    }
                    activeDragHandle = 0
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (filmstrip.isNotEmpty()) {
                val frameW = w / filmstrip.size
                for (i in filmstrip.indices) {
                    val bmp = filmstrip[i].asImageBitmap()
                    drawImage(
                        image = bmp,
                        dstOffset = androidx.compose.ui.unit.IntOffset((i * frameW).toInt(), 0),
                        dstSize = androidx.compose.ui.unit.IntSize(frameW.toInt() + 1, h.toInt())
                    )
                }
                drawRect(
                    color = Color(0x66000000),
                    topLeft = Offset.Zero,
                    size = size
                )
            }

            val startX = (safeStart.toFloat() / duration) * w
            val endX = (safeEnd.toFloat() / duration) * w

            val barCount = bars.size
            val barW = (w / barCount.toFloat()).coerceAtLeast(1.5f)
            val centerY = h / 2f

            for (i in 0 until barCount) {
                val x = i * (w / barCount.toFloat())
                val amp = bars[i].coerceIn(0.04f, 0.95f)
                val barH = amp * (h * 0.75f)
                val inSelection = x in startX..endX
                val color = if (inSelection) cyanColor else Color(0x55607080)

                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, centerY - barH / 2f),
                    size = Size(barW * 0.75f, barH),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            if (startX > 0f) {
                drawRect(
                    color = dimOverlay,
                    topLeft = Offset.Zero,
                    size = Size(startX, h)
                )
            }
            if (endX < w) {
                drawRect(
                    color = dimOverlay,
                    topLeft = Offset(endX, 0f),
                    size = Size(w - endX, h)
                )
            }

            drawRect(
                color = cyanGlow,
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, h)
            )

            drawLine(
                color = cyanColor,
                start = Offset(startX, 1f),
                end = Offset(endX, 1f),
                strokeWidth = 3f
            )
            drawLine(
                color = cyanColor,
                start = Offset(startX, h - 1f),
                end = Offset(endX, h - 1f),
                strokeWidth = 3f
            )

            val handleBarWidth = 14f
            drawRoundRect(
                color = handleColor,
                topLeft = Offset(startX - handleBarWidth / 2f, 0f),
                size = Size(handleBarWidth, h),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawCircle(
                color = Color.White,
                radius = 7f,
                center = Offset(startX, h / 2f)
            )

            drawRoundRect(
                color = handleColor,
                topLeft = Offset(endX - handleBarWidth / 2f, 0f),
                size = Size(handleBarWidth, h),
                cornerRadius = CornerRadius(4f, 4f)
            )
            drawCircle(
                color = Color.White,
                radius = 7f,
                center = Offset(endX, h / 2f)
            )

            val playheadX = (currentPositionMs.toFloat() / duration) * w
            drawLine(
                color = playheadColor,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, h),
                strokeWidth = 4f
            )
            drawCircle(
                color = playheadColor,
                radius = 5f,
                center = Offset(playheadX, 5f)
            )
        }
    }
}
