package com.bnyro.recorder.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnyro.recorder.obj.MediaSegment
import com.bnyro.recorder.util.AudioWaveformExtractor
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

@Composable
fun RangeWaveformTimeline(
    totalDurationMs: Long,
    segments: List<MediaSegment>,
    selectedSegmentIndex: Int,
    currentPositionMs: Long,
    waveform: List<Float>?,
    filmstrip: List<Bitmap> = emptyList(),
    zoomFactor: Float = 1.0f,
    onZoomChange: (Float) -> Unit = {},
    onResetZoom: () -> Unit = {},
    onSelectSegment: (Int) -> Unit,
    onStartChanged: (Long) -> Unit,
    onEndChanged: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = totalDurationMs.coerceAtLeast(1L)
    val cyanColor = Color(0xFF00E5C0)
    val cyanGlow = Color(0x3300E5C0)
    val bgDark = Color(0xFF0F1216)
    val dimOverlay = Color(0xAA0A0D10)
    val otherSegmentColor = Color(0xFF267368)
    val playheadColor = Color.White
    val handleColor = Color(0xFF00E5C0)

    val bars = if (waveform != null && !AudioWaveformExtractor.isFlatWaveform(waveform)) {
        waveform
    } else {
        remember { AudioWaveformExtractor.createSyntheticWaveform(12345, 120) }
    }

    var scrollOffsetX by remember { mutableFloatStateOf(0f) }

    val updatedDuration by rememberUpdatedState(duration)
    val updatedSegments by rememberUpdatedState(segments)
    val updatedSelectedIndex by rememberUpdatedState(selectedSegmentIndex)
    val updatedZoom by rememberUpdatedState(zoomFactor)
    val updatedOnZoomChange by rememberUpdatedState(onZoomChange)
    val updatedOnSelectSegment by rememberUpdatedState(onSelectSegment)
    val updatedOnStartChanged by rememberUpdatedState(onStartChanged)
    val updatedOnEndChanged by rememberUpdatedState(onEndChanged)
    val updatedOnSeek by rememberUpdatedState(onSeek)

    LaunchedEffect(currentPositionMs, zoomFactor) {
        if (zoomFactor > 1.0f) {
            val progress = currentPositionMs.toFloat() / duration
            val estimatedVirtualX = progress * (1000f * zoomFactor)
            val minVisible = scrollOffsetX
            val maxVisible = scrollOffsetX + 1000f
            if (estimatedVirtualX > maxVisible - 100f) {
                scrollOffsetX = (estimatedVirtualX - 900f).coerceAtLeast(0f)
            } else if (estimatedVirtualX < minVisible) {
                scrollOffsetX = estimatedVirtualX.coerceAtLeast(0f)
            }
        } else {
            scrollOffsetX = 0f
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgDark)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val totalVirtualW = w * updatedZoom
                    val maxScroll = (totalVirtualW - w).coerceAtLeast(0f)
                    val handleTolerance = 44.dp.toPx()

                    var dragMode = 0
                    var initialDistance = 0f

                    val activeSeg = updatedSegments.getOrNull(updatedSelectedIndex)
                    val curSafeStart = activeSeg?.startMs ?: 0L
                    val curSafeEnd = activeSeg?.endMs ?: updatedDuration

                    val startX = (curSafeStart.toFloat() / updatedDuration) * totalVirtualW - scrollOffsetX
                    val endX = (curSafeEnd.toFloat() / updatedDuration) * totalVirtualW - scrollOffsetX
                    val touchX = down.position.x

                    if (abs(touchX - startX) <= handleTolerance) {
                        dragMode = 1
                    } else if (abs(touchX - endX) <= handleTolerance) {
                        dragMode = 2
                    } else {
                        val touchVirtualX = (touchX + scrollOffsetX).coerceIn(0f, totalVirtualW)
                        val touchMs = ((touchVirtualX / totalVirtualW) * updatedDuration).toLong()
                        val hitIndex = updatedSegments.indexOfFirst { touchMs in it.startMs..it.endMs }
                        if (hitIndex >= 0 && hitIndex != updatedSelectedIndex) {
                            updatedOnSelectSegment(hitIndex)
                        }
                        updatedOnSeek(touchMs)
                        dragMode = 3
                    }

                    while (true) {
                        val event = awaitPointerEvent()
                        val activePointers = event.changes.filter { it.pressed }
                        if (activePointers.isEmpty()) break

                        if (activePointers.size >= 2) {
                            val p1 = activePointers[0].position
                            val p2 = activePointers[1].position
                            val dx = p1.x - p2.x
                            val dy = p1.y - p2.y
                            val dist = sqrt(dx * dx + dy * dy)

                            if (initialDistance > 0f && dist > 10f) {
                                val scale = dist / initialDistance
                                val newZoom = (updatedZoom * scale).coerceIn(1.0f, 20.0f)
                                updatedOnZoomChange(newZoom)
                            }
                            initialDistance = dist
                            activePointers.forEach { it.consume() }
                        } else if (activePointers.size == 1) {
                            val change = activePointers[0]
                            val curX = change.position.x
                            val curVirtualX = (curX + scrollOffsetX).coerceIn(0f, totalVirtualW)
                            val curMs = ((curVirtualX / totalVirtualW) * updatedDuration).toLong()

                            val curSeg = updatedSegments.getOrNull(updatedSelectedIndex)
                            val segStart = curSeg?.startMs ?: 0L
                            val segEnd = curSeg?.endMs ?: updatedDuration

                            when (dragMode) {
                                1 -> {
                                    val prevSegEnd = if (updatedSelectedIndex > 0) updatedSegments[updatedSelectedIndex - 1].endMs else 0L
                                    val safeMin = prevSegEnd
                                    val safeMax = segEnd - 100L
                                    updatedOnStartChanged(curMs.coerceIn(safeMin, safeMax))
                                }
                                2 -> {
                                    val nextSegStart = if (updatedSelectedIndex < updatedSegments.size - 1) updatedSegments[updatedSelectedIndex + 1].startMs else updatedDuration
                                    val safeMin = segStart + 100L
                                    val safeMax = nextSegStart
                                    updatedOnEndChanged(curMs.coerceIn(safeMin, safeMax))
                                }
                                3 -> {
                                    updatedOnSeek(curMs.coerceIn(0L, updatedDuration))
                                }
                            }
                            change.consume()
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val totalVirtualW = w * zoomFactor
            val maxScroll = (totalVirtualW - w).coerceAtLeast(0f)
            val currentScroll = scrollOffsetX.coerceIn(0f, maxScroll)

            if (filmstrip.isNotEmpty()) {
                val frameW = totalVirtualW / filmstrip.size
                for (i in filmstrip.indices) {
                    val frameScreenX = i * frameW - currentScroll
                    if (frameScreenX + frameW >= 0 && frameScreenX <= w) {
                        val bmp = filmstrip[i].asImageBitmap()
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(frameScreenX.toInt(), 0),
                            dstSize = IntSize((frameW + 1).toInt(), h.toInt())
                        )
                    }
                }
                drawRect(
                    color = Color(0x55000000),
                    topLeft = Offset.Zero,
                    size = size
                )
            }

            val barCount = bars.size
            val barSpacing = totalVirtualW / barCount.toFloat()
            val centerY = h / 2f

            for (i in 0 until barCount) {
                val barVirtualX = i * barSpacing
                val barScreenX = barVirtualX - currentScroll
                if (barScreenX < -5f || barScreenX > w + 5f) continue

                val barMs = ((barVirtualX / totalVirtualW) * duration).toLong()
                val isSelected = segments.getOrNull(selectedSegmentIndex)?.let { barMs in it.startMs..it.endMs } == true
                val isInAnySegment = segments.any { barMs in it.startMs..it.endMs }

                val barColor = when {
                    isSelected -> cyanColor
                    isInAnySegment -> otherSegmentColor
                    else -> Color(0x30556677)
                }

                val amp = bars[i].coerceIn(0.05f, 0.95f)
                val barH = amp * (h * 0.72f)

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(barScreenX, centerY - barH / 2f),
                    size = Size((barSpacing * 0.75f).coerceAtLeast(1.5f), barH),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            var coveredUntilMs = 0L
            for (seg in segments) {
                if (seg.startMs > coveredUntilMs) {
                    val gapStartX = (coveredUntilMs.toFloat() / duration) * totalVirtualW - currentScroll
                    val gapEndX = (seg.startMs.toFloat() / duration) * totalVirtualW - currentScroll
                    val visibleGapStart = gapStartX.coerceAtLeast(0f)
                    val visibleGapEnd = gapEndX.coerceAtMost(w)
                    if (visibleGapEnd > visibleGapStart) {
                        drawRect(
                            color = dimOverlay,
                            topLeft = Offset(visibleGapStart, 0f),
                            size = Size(visibleGapEnd - visibleGapStart, h)
                        )
                    }
                }
                coveredUntilMs = seg.endMs
            }
            if (coveredUntilMs < duration) {
                val gapStartX = (coveredUntilMs.toFloat() / duration) * totalVirtualW - currentScroll
                val visibleGapStart = gapStartX.coerceAtLeast(0f)
                if (w > visibleGapStart) {
                    drawRect(
                        color = dimOverlay,
                        topLeft = Offset(visibleGapStart, 0f),
                        size = Size(w - visibleGapStart, h)
                    )
                }
            }

            segments.forEachIndexed { index, seg ->
                val segStartX = (seg.startMs.toFloat() / duration) * totalVirtualW - currentScroll
                val segEndX = (seg.endMs.toFloat() / duration) * totalVirtualW - currentScroll
                val isSelected = (index == selectedSegmentIndex)

                if (segEndX >= 0f && segStartX <= w) {
                    if (isSelected) {
                        drawRect(
                            color = cyanGlow,
                            topLeft = Offset(segStartX.coerceAtLeast(0f), 0f),
                            size = Size((segEndX - segStartX).coerceAtLeast(0f), h)
                        )
                        drawLine(
                            color = cyanColor,
                            start = Offset(segStartX, 1.5f),
                            end = Offset(segEndX, 1.5f),
                            strokeWidth = 3f
                        )
                        drawLine(
                            color = cyanColor,
                            start = Offset(segStartX, h - 1.5f),
                            end = Offset(segEndX, h - 1.5f),
                            strokeWidth = 3f
                        )

                        val handleWidth = 14f
                        drawRoundRect(
                            color = handleColor,
                            topLeft = Offset(segStartX - handleWidth / 2f, 0f),
                            size = Size(handleWidth, h),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 6f,
                            center = Offset(segStartX, h / 2f)
                        )

                        drawRoundRect(
                            color = handleColor,
                            topLeft = Offset(segEndX - handleWidth / 2f, 0f),
                            size = Size(handleWidth, h),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 6f,
                            center = Offset(segEndX, h / 2f)
                        )
                    } else {
                        drawLine(
                            color = Color(0x66FFFFFF),
                            start = Offset(segStartX, 0f),
                            end = Offset(segStartX, h),
                            strokeWidth = 2f
                        )
                        drawLine(
                            color = Color(0x66FFFFFF),
                            start = Offset(segEndX, 0f),
                            end = Offset(segEndX, h),
                            strokeWidth = 2f
                        )
                    }
                }
            }

            val playheadScreenX = (currentPositionMs.toFloat() / duration) * totalVirtualW - currentScroll
            if (playheadScreenX in 0f..w) {
                drawLine(
                    color = playheadColor,
                    start = Offset(playheadScreenX, 0f),
                    end = Offset(playheadScreenX, h),
                    strokeWidth = 4f
                )
                drawCircle(
                    color = playheadColor,
                    radius = 6f,
                    center = Offset(playheadScreenX, 6f)
                )
            }
        }

        if (zoomFactor > 1.05f) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onResetZoom() }
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1fx", zoomFactor),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                    Icon(
                        imageVector = Icons.Default.ZoomOutMap,
                        contentDescription = null,
                        modifier = Modifier.height(12.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
