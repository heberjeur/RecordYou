package com.bnyro.recorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnyro.recorder.R
import com.bnyro.recorder.util.AudioWaveformExtractor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AudioWaveformPreview(
    amplitudes: List<Float>?,
    seed: Int = 0,
    progress: Float? = null,
    onSeek: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cyanColor = Color(0xFF00E5C0)
    val cyanContourColor = Color(0xFF14FFE0)
    val gridColor = Color(0x33627282)
    val centerGridColor = Color(0x557A8B9E)
    val bgDark = Color(0xFF0F1216)
    val playedOverlayColor = Color(0x40000000)

    val isReady = amplitudes != null && !AudioWaveformExtractor.isFlatWaveform(amplitudes)

    val seekModifier = if (onSeek != null) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                onSeek((down.position.x / size.width).coerceIn(0f, 1f))
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                    change.consume()
                }
            }
        }
    } else Modifier

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgDark)
            .then(seekModifier)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerY = h / 2f

            val verticalDivisions = 8
            val colWidth = w / verticalDivisions
            for (c in 1 until verticalDivisions) {
                val x = c * colWidth
                drawLine(
                    color = gridColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
            }

            drawLine(
                color = gridColor,
                start = Offset(0f, h * 0.25f),
                end = Offset(w, h * 0.25f),
                strokeWidth = 1f
            )
            drawLine(
                color = centerGridColor,
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 1.2f
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, h * 0.75f),
                end = Offset(w, h * 0.75f),
                strokeWidth = 1f
            )

            val rawData = if (isReady) {
                amplitudes
            } else {
                AudioWaveformExtractor.createSyntheticWaveform(seed)
            }

            val numPoints = (w / 1.5f).toInt().coerceIn(120, 600)
            val dataSize = rawData.size
            val halfSpikes = FloatArray(numPoints + 1)
            val maxSpikeHeight = h * 0.38f

            for (p in 0..numPoints) {
                val u = (p.toFloat() / numPoints) * (dataSize - 1)
                val idx = u.toInt().coerceIn(0, dataSize - 1)
                val nextIdx = (idx + 1).coerceAtMost(dataSize - 1)
                val frac = u - idx
                val baseAmp = rawData[idx] * (1f - frac) + rawData[nextIdx] * frac
                val microNoise = (abs(sin(p * 12.9898) * cos(p * 4.1415)) * 0.12f).toFloat()
                val amp = (baseAmp * (0.90f + microNoise)).coerceIn(0.015f, 0.95f)
                halfSpikes[p] = (maxSpikeHeight * amp).coerceAtLeast(1.2f)
            }

            val solidPath = Path()
            solidPath.moveTo(0f, centerY)
            for (p in 0..numPoints) {
                val x = p * (w / numPoints)
                solidPath.lineTo(x, centerY - halfSpikes[p])
            }
            solidPath.lineTo(w, centerY)
            for (p in numPoints downTo 0) {
                val x = p * (w / numPoints)
                solidPath.lineTo(x, centerY + halfSpikes[p])
            }
            solidPath.close()

            val fillAlpha = if (isReady) 0.95f else 0.45f
            val contourAlpha = if (isReady) 1.0f else 0.55f

            drawPath(solidPath, color = cyanColor.copy(alpha = fillAlpha), style = Fill)

            val topPath = Path()
            topPath.moveTo(0f, centerY - halfSpikes[0])
            for (p in 1..numPoints) {
                topPath.lineTo(p * (w / numPoints), centerY - halfSpikes[p])
            }
            drawPath(topPath, color = cyanContourColor.copy(alpha = contourAlpha), style = Stroke(width = 1f))

            val bottomPath = Path()
            bottomPath.moveTo(0f, centerY + halfSpikes[0])
            for (p in 1..numPoints) {
                bottomPath.lineTo(p * (w / numPoints), centerY + halfSpikes[p])
            }
            drawPath(bottomPath, color = cyanContourColor.copy(alpha = contourAlpha), style = Stroke(width = 1f))

            if (progress != null && progress > 0f) {
                val playX = (w * progress.coerceIn(0f, 1f))
                drawRect(
                    color = playedOverlayColor,
                    topLeft = Offset.Zero,
                    size = Size(playX, h)
                )
                drawLine(
                    color = Color.White,
                    start = Offset(playX, 0f),
                    end = Offset(playX, h),
                    strokeWidth = 2f
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xCC0D1117))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isReady) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = cyanColor,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = stringResource(R.string.waveform_ready),
                    color = cyanColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(9.dp),
                    strokeWidth = 1.2.dp,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = stringResource(R.string.waveform_generating),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp
                )
            }
        }
    }
}

