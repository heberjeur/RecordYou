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
import androidx.compose.ui.unit.dp
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
    val gridColor = Color(0x33627282)
    val centerGridColor = Color(0x557A8B9E)
    val bgDark = Color(0xFF0F1115)
    val playedRegionTint = Color(0x406B2626)

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

            if (progress != null && progress > 0f) {
                val playedWidth = w * progress.coerceIn(0f, 1f)
                drawRect(
                    color = playedRegionTint,
                    topLeft = Offset.Zero,
                    size = Size(playedWidth, h)
                )
            }

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
                strokeWidth = 1f
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, h * 0.75f),
                end = Offset(w, h * 0.75f),
                strokeWidth = 1f
            )

            val data = amplitudes
            if (data != null && data.isNotEmpty() &&
                ((data.maxOrNull() ?: 0f) - (data.minOrNull() ?: 0f) > 0.05f)
            ) {
                val count = data.size
                val stepX = w / count.coerceAtLeast(1)
                val strokeW = stepX.coerceAtLeast(1.4f)
                for (i in 0 until count) {
                    val x = i * stepX + stepX / 2f
                    val amp = data[i].coerceIn(0.04f, 1f)
                    val halfSpike = (h * 0.46f) * amp
                    drawLine(
                        color = cyanColor,
                        start = Offset(x, centerY - halfSpike),
                        end = Offset(x, centerY + halfSpike),
                        strokeWidth = strokeW
                    )
                }
            } else {
                val count = 160
                val stepX = w / count
                val strokeW = stepX.coerceAtLeast(1.4f)
                val seedOffset = abs(seed) % 100
                for (i in 0 until count) {
                    val x = i * stepX + stepX / 2f
                    val t = (i + seedOffset) * 0.18
                    val burst = abs(sin(t * 0.45) * cos(t * 0.85 + seedOffset * 0.05))
                    val microNoise = (abs(sin(i * 3.7)) * 0.12f).toFloat()
                    val wave = (0.05f + 0.75f * burst.toFloat() + microNoise).coerceIn(0.04f, 0.95f)
                    val halfSpike = (h * 0.46f) * wave
                    drawLine(
                        color = cyanColor.copy(alpha = 0.85f),
                        start = Offset(x, centerY - halfSpike),
                        end = Offset(x, centerY + halfSpike),
                        strokeWidth = strokeW
                    )
                }
            }
        }
    }
}
