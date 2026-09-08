package com.bnyro.recorder.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnyro.recorder.obj.MediaSegment
import com.bnyro.recorder.util.AudioWaveformExtractor
import com.bnyro.recorder.util.TimeFormatHelper
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

@Composable
fun RangeWaveformTimeline(
    totalDurationMs: Long,
    segments: List<MediaSegment>,
    selectedSegmentIndex: Int,
    currentSequencePositionMs: Long,
    waveform: List<Float>?,
    filmstrip: List<Bitmap> = emptyList(),
    zoomFactor: Float = 1.0f,
    onZoomChange: (Float) -> Unit = {},
    onResetZoom: () -> Unit = {},
    onZoomIn: () -> Unit = {},
    onZoomOut: () -> Unit = {},
    onSelectSegment: (Int) -> Unit,
    onSwapSegments: (Int, Int) -> Unit,
    onStartChanged: (Long) -> Unit,
    onEndChanged: (Long) -> Unit,
    onSeekSequence: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val sourceDuration = totalDurationMs.coerceAtLeast(1L)
    val totalSeqDuration = segments.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }.coerceAtLeast(1L)

    val cyanColor = Color(0xFF00E5C0)
    val cyanGlow = Color(0x3800E5C0)
    val otherClipColor = Color(0xFF38BDF8)
    val otherClipBg = Color(0x1F38BDF8)
    val bgDark = Color(0xFF0D1117)
    val dividerColor = Color(0x99FFFFFF)
    val playheadColor = Color.White
    val handleColor = Color(0xFF00E5C0)

    val bars = if (waveform != null && !AudioWaveformExtractor.isFlatWaveform(waveform)) {
        waveform
    } else {
        remember { AudioWaveformExtractor.createSyntheticWaveform(12345, 120) }
    }

    var scrollOffsetX by remember { mutableFloatStateOf(0f) }

    val updatedSourceDuration by rememberUpdatedState(sourceDuration)
    val updatedSeqDuration by rememberUpdatedState(totalSeqDuration)
    val updatedSegments by rememberUpdatedState(segments)
    val updatedSelectedIndex by rememberUpdatedState(selectedSegmentIndex)
    val updatedZoom by rememberUpdatedState(zoomFactor)
    val updatedOnZoomChange by rememberUpdatedState(onZoomChange)
    val updatedOnSelectSegment by rememberUpdatedState(onSelectSegment)
    val updatedOnSwapSegments by rememberUpdatedState(onSwapSegments)
    val updatedOnStartChanged by rememberUpdatedState(onStartChanged)
    val updatedOnEndChanged by rememberUpdatedState(onEndChanged)
    val updatedOnSeekSequence by rememberUpdatedState(onSeekSequence)

    LaunchedEffect(currentSequencePositionMs, zoomFactor) {
        if (zoomFactor > 1.0f) {
            val progress = currentSequencePositionMs.toFloat() / totalSeqDuration
            val estimatedVirtualX = progress * (1000f * zoomFactor)
            val minVisible = scrollOffsetX
            val maxVisible = scrollOffsetX + 1000f
            if (estimatedVirtualX > maxVisible - 120f) {
                scrollOffsetX = (estimatedVirtualX - 880f).coerceAtLeast(0f)
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
            .height(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgDark)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat()
                    val totalVirtualW = w * updatedZoom
                    val handleTolerance = 40.dp.toPx()

                    var dragMode = 0
                    var initialDistance = 0f

                    val segBounds = mutableListOf<Pair<Float, Float>>()
                    var runningX = 0f
                    for (seg in updatedSegments) {
                        val dur = (seg.endMs - seg.startMs).coerceAtLeast(0L)
                        val segW = (dur.toFloat() / updatedSeqDuration) * totalVirtualW
                        segBounds.add(runningX to (runningX + segW))
                        runningX += segW
                    }

                    val selectedBounds = segBounds.getOrNull(updatedSelectedIndex)
                    val startX = (selectedBounds?.first ?: 0f) - scrollOffsetX
                    val endX = (selectedBounds?.second ?: totalVirtualW) - scrollOffsetX
                    val touchX = down.position.x
                    val touchY = down.position.y
                    val touchVirtualX = (touchX + scrollOffsetX).coerceIn(0f, totalVirtualW)

                    var dragRefVirtualX = touchVirtualX

                    if (abs(touchX - startX) <= handleTolerance) {
                        dragMode = 1
                    } else if (abs(touchX - endX) <= handleTolerance) {
                        dragMode = 2
                    } else if (touchY <= 24.dp.toPx()) {
                        val seqMs = ((touchVirtualX / totalVirtualW) * updatedSeqDuration).toLong()
                        updatedOnSeekSequence(seqMs)
                        dragMode = 3
                    } else {
                        val hitIndex = segBounds.indexOfFirst { touchVirtualX in it.first..it.second }
                        if (hitIndex >= 0) {
                            if (hitIndex != updatedSelectedIndex) {
                                updatedOnSelectSegment(hitIndex)
                            }
                            dragMode = 4
                        } else {
                            val seqMs = ((touchVirtualX / totalVirtualW) * updatedSeqDuration).toLong()
                            updatedOnSeekSequence(seqMs)
                            dragMode = 3
                        }
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

                            val curSeg = updatedSegments.getOrNull(updatedSelectedIndex)
                            val segStart = curSeg?.startMs ?: 0L
                            val segEnd = curSeg?.endMs ?: updatedSourceDuration

                            when (dragMode) {
                                1 -> {
                                    val deltaVirtualX = curVirtualX - (segBounds.getOrNull(updatedSelectedIndex)?.first ?: 0f)
                                    val deltaMs = ((deltaVirtualX / totalVirtualW) * updatedSeqDuration).toLong()
                                    val newStart = (segStart + deltaMs).coerceIn(0L, segEnd - 150L)
                                    updatedOnStartChanged(newStart)
                                }
                                2 -> {
                                    val deltaVirtualX = curVirtualX - (segBounds.getOrNull(updatedSelectedIndex)?.second ?: totalVirtualW)
                                    val deltaMs = ((deltaVirtualX / totalVirtualW) * updatedSeqDuration).toLong()
                                    val newEnd = (segEnd + deltaMs).coerceIn(segStart + 150L, updatedSourceDuration)
                                    updatedOnEndChanged(newEnd)
                                }
                                3 -> {
                                    val seqMs = ((curVirtualX / totalVirtualW) * updatedSeqDuration).toLong()
                                    updatedOnSeekSequence(seqMs)
                                }
                                4 -> {
                                    val currentSegIndex = updatedSelectedIndex
                                    if (currentSegIndex in segBounds.indices) {
                                        val curCenter = (segBounds[currentSegIndex].first + segBounds[currentSegIndex].second) / 2f
                                        val deltaX = curVirtualX - dragRefVirtualX

                                        if (currentSegIndex > 0) {
                                            val prevCenter = (segBounds[currentSegIndex - 1].first + segBounds[currentSegIndex - 1].second) / 2f
                                            if (curCenter + deltaX < prevCenter) {
                                                updatedOnSwapSegments(currentSegIndex, currentSegIndex - 1)
                                                dragRefVirtualX = curVirtualX
                                            }
                                        }
                                        if (currentSegIndex < segBounds.size - 1) {
                                            val nextCenter = (segBounds[currentSegIndex + 1].first + segBounds[currentSegIndex + 1].second) / 2f
                                            if (curCenter + deltaX > nextCenter) {
                                                updatedOnSwapSegments(currentSegIndex, currentSegIndex + 1)
                                                dragRefVirtualX = curVirtualX
                                            }
                                        }
                                    }
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

            var runningVirtualX = 0f
            segments.forEachIndexed { index, seg ->
                val dur = (seg.endMs - seg.startMs).coerceAtLeast(0L)
                val segWidth = (dur.toFloat() / totalSeqDuration) * totalVirtualW
                val startScreenX = runningVirtualX - currentScroll
                val endScreenX = (runningVirtualX + segWidth) - currentScroll
                val isSelected = (index == selectedSegmentIndex)

                if (endScreenX >= 0f && startScreenX <= w) {
                    val visibleStart = startScreenX.coerceAtLeast(0f)
                    val visibleEnd = endScreenX.coerceAtMost(w)
                    val fillWidth = (visibleEnd - visibleStart).coerceAtLeast(0f)

                    drawRect(
                        color = if (isSelected) cyanGlow else otherClipBg,
                        topLeft = Offset(visibleStart, 0f),
                        size = Size(fillWidth, h)
                    )

                    if (filmstrip.isNotEmpty() && sourceDuration > 0L) {
                        val startFraction = seg.startMs.toFloat() / sourceDuration
                        val endFraction = seg.endMs.toFloat() / sourceDuration
                        val startFrameIdx = (startFraction * filmstrip.size).toInt().coerceIn(0, filmstrip.size - 1)
                        val endFrameIdx = (endFraction * filmstrip.size).toInt().coerceIn(startFrameIdx, filmstrip.size - 1)
                        val frameCount = (endFrameIdx - startFrameIdx + 1).coerceAtLeast(1)
                        val framePixelW = segWidth / frameCount.toFloat()

                        for (f in startFrameIdx..endFrameIdx) {
                            val fScreenX = startScreenX + (f - startFrameIdx) * framePixelW
                            if (fScreenX + framePixelW >= 0f && fScreenX <= w) {
                                val bmp = filmstrip[f].asImageBitmap()
                                drawImage(
                                    image = bmp,
                                    dstOffset = IntOffset(fScreenX.toInt(), 0),
                                    dstSize = IntSize((framePixelW + 1).toInt(), h.toInt())
                                )
                            }
                        }
                        drawRect(
                            color = Color(0x60000000),
                            topLeft = Offset(visibleStart, 0f),
                            size = Size(fillWidth, h)
                        )
                    }

                    val segBarCount = ((dur.toFloat() / sourceDuration) * bars.size).toInt().coerceAtLeast(3)
                    val startBarIdx = ((seg.startMs.toFloat() / sourceDuration) * bars.size).toInt().coerceIn(0, bars.size - 1)
                    val barSpacing = segWidth / segBarCount.toFloat()
                    val centerY = h / 2f

                    for (b in 0 until segBarCount) {
                        val barScreenX = startScreenX + b * barSpacing
                        if (barScreenX < -5f || barScreenX > w + 5f) continue
                        val barIdx = (startBarIdx + b).coerceIn(0, bars.size - 1)
                        val amp = bars[barIdx].coerceIn(0.06f, 0.95f)
                        val barH = amp * (h * 0.65f)

                        drawRoundRect(
                            color = if (isSelected) cyanColor else otherClipColor,
                            topLeft = Offset(barScreenX, centerY - barH / 2f),
                            size = Size((barSpacing * 0.70f).coerceAtLeast(1.5f), barH),
                            cornerRadius = CornerRadius(2f, 2f)
                        )
                    }

                    if (isSelected) {
                        drawLine(
                            color = cyanColor,
                            start = Offset(startScreenX, 2f),
                            end = Offset(endScreenX, 2f),
                            strokeWidth = 3f
                        )
                        drawLine(
                            color = cyanColor,
                            start = Offset(startScreenX, h - 2f),
                            end = Offset(endScreenX, h - 2f),
                            strokeWidth = 3f
                        )

                        val handleWidth = 14f
                        drawRoundRect(
                            color = handleColor,
                            topLeft = Offset(startScreenX - handleWidth / 2f, 0f),
                            size = Size(handleWidth, h),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 6f,
                            center = Offset(startScreenX, h / 2f)
                        )

                        drawRoundRect(
                            color = handleColor,
                            topLeft = Offset(endScreenX - handleWidth / 2f, 0f),
                            size = Size(handleWidth, h),
                            cornerRadius = CornerRadius(4f, 4f)
                        )
                        drawCircle(
                            color = Color.White,
                            radius = 6f,
                            center = Offset(endScreenX, h / 2f)
                        )
                    } else {
                        drawLine(
                            color = otherClipColor,
                            start = Offset(startScreenX, 0f),
                            end = Offset(endScreenX, 0f),
                            strokeWidth = 1.5f
                        )
                        drawLine(
                            color = otherClipColor,
                            start = Offset(startScreenX, h),
                            end = Offset(endScreenX, h),
                            strokeWidth = 1.5f
                        )
                    }

                    if (index < segments.size - 1) {
                        drawLine(
                            color = dividerColor,
                            start = Offset(endScreenX, 0f),
                            end = Offset(endScreenX, h),
                            strokeWidth = 2f
                        )
                        drawCircle(
                            color = dividerColor,
                            radius = 4f,
                            center = Offset(endScreenX, h / 2f)
                        )
                    }
                }
                runningVirtualX += segWidth
            }

            val playheadScreenX = (currentSequencePositionMs.toFloat() / totalSeqDuration) * totalVirtualW - currentScroll
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

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onZoomOut,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.width(2.dp))

                Text(
                    text = String.format(Locale.US, "%.1fx", zoomFactor),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onResetZoom() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )

                Spacer(modifier = Modifier.width(2.dp))

                IconButton(
                    onClick = onZoomIn,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
