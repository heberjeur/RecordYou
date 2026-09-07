package com.bnyro.recorder.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AudioWaveformPreview(
    amplitudes: List<Float>?,
    seed: Int = 0,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val placeholderColor = primaryColor.copy(alpha = 0.28f)
    val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val h = size.height
            val w = size.width
            val totalBars = amplitudes?.size ?: 40
            val spacing = 2.dp.toPx()
            val barWidth = ((w - (totalBars - 1) * spacing) / totalBars).coerceAtLeast(2f)

            val hasRealData = amplitudes != null && amplitudes.isNotEmpty() &&
                ((amplitudes.maxOrNull() ?: 0f) - (amplitudes.minOrNull() ?: 0f) > 0.05f)

            if (hasRealData) {
                amplitudes.forEachIndexed { i, amp ->
                    val barHeight = (h * amp.coerceIn(0.08f, 1f)).coerceAtLeast(3.dp.toPx())
                    val top = (h - barHeight) / 2f
                    val left = i * (barWidth + spacing)
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            } else {
                val seedOffset = abs(seed) % 100
                for (i in 0 until totalBars) {
                    val t = (i + seedOffset) * 0.32
                    val wave = (0.15f + 0.55f * abs(sin(t) * cos(t * 0.65 + seedOffset * 0.1))).toFloat().coerceIn(0.12f, 0.9f)
                    val barHeight = (h * wave).coerceAtLeast(3.dp.toPx())
                    val top = (h - barHeight) / 2f
                    val left = i * (barWidth + spacing)
                    drawRoundRect(
                        color = placeholderColor,
                        topLeft = Offset(left, top),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }
        }
    }
}
