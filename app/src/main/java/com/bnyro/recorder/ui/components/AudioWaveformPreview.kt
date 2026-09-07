package com.bnyro.recorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bnyro.recorder.util.AudioWaveformExtractor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AudioWaveformPreview(
    amplitudes: List<Float>?,
    seed: Int = 0,
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    val cyanColor = Color(0xFF00E5C0)
    val cyanContourColor = Color(0xFF14FFE0)
    val gridColor = Color(0x33627282)
    val centerGridColor = Color(0x557A8B9E)
    val bgDark = Color(0xFF0F1216)
    val playedOverlayColor = Color(0x40000000)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgDark)
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

            val rawData = if (!AudioWaveformExtractor.isFlatWaveform(amplitudes)) {
                amplitudes!!
            } else {
                AudioWaveformExtractor.createSyntheticWaveform(seed)
            }

            val numPoints = (w / 1.5f).toInt().coerceIn(120, 600)
            val dataSize = rawData.size
            val halfSpikes = FloatArray(numPoints + 1)

            for (p in 0..numPoints) {
                val u = (p.toFloat() / numPoints) * (dataSize - 1)
                val idx = u.toInt().coerceIn(0, dataSize - 1)
                val nextIdx = (idx + 1).coerceAtMost(dataSize - 1)
                val frac = u - idx
                val baseAmp = rawData[idx] * (1f - frac) + rawData[nextIdx] * frac
                val microNoise = abs(sin(p * 12.9898) * cos(p * 4.1415)).toFloat()
                val amp = (baseAmp * (0.80f + 0.38f * microNoise)).coerceIn(0.015f, 0.95f)
                halfSpikes[p] = (h * 0.46f) * amp
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

            drawPath(solidPath, color = cyanColor, style = Fill)

            val topPath = Path()
            topPath.moveTo(0f, centerY - halfSpikes[0])
            for (p in 1..numPoints) {
                topPath.lineTo(p * (w / numPoints), centerY - halfSpikes[p])
            }
            drawPath(topPath, color = cyanContourColor, style = Stroke(width = 1f))

            val bottomPath = Path()
            bottomPath.moveTo(0f, centerY + halfSpikes[0])
            for (p in 1..numPoints) {
                bottomPath.lineTo(p * (w / numPoints), centerY + halfSpikes[p])
            }
            drawPath(bottomPath, color = cyanContourColor, style = Stroke(width = 1f))

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
    }
}
