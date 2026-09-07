package com.bnyro.recorder.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.TimeFormatHelper

@Composable
fun AudioVisualizer(
    modifier: Modifier = Modifier
) {
    val viewModel: RecorderModel = viewModel(LocalContext.current as ComponentActivity)
    val showTimestamps = remember {
        Preferences.prefs.getBoolean(
            Preferences.showVisualizerTimestamps,
            false
        )
    }

    val maxAmplitude = 12000f
    val cyanColor = Color(0xFF00E5C0)
    val gridColor = Color(0x33627282)
    val centerGridColor = Color(0x557A8B9E)
    val bgDark = Color(0xFF0F1115)
    val amplitudes = viewModel.recordedAmplitudes
    val measurer = rememberTextMeasurer()

    Box(
        modifier = modifier
            .padding(vertical = 30.dp, horizontal = 12.dp)
            .fillMaxWidth()
            .height(300.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgDark)
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(300.dp)) {
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
                strokeWidth = 1f
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, h * 0.75f),
                end = Offset(w, h * 0.75f),
                strokeWidth = 1f
            )

            val stepX = 8f
            val count = amplitudes.size
            for (index in 0 until count) {
                val amplitude = amplitudes[index]
                val ampPercent = (amplitude / maxAmplitude).coerceIn(0.03f, 1f)
                val halfSpike = (h * 0.44f) * ampPercent
                val reverseIndex = index - count
                val x = w + (reverseIndex * stepX)
                if (x in -stepX..w) {
                    drawLine(
                        color = cyanColor,
                        start = Offset(x, centerY - halfSpike),
                        end = Offset(x, centerY + halfSpike),
                        strokeWidth = 5f
                    )
                    if (showTimestamps) {
                        viewModel.recordedTime?.let {
                            val timeStamp = it + reverseIndex
                            if (timeStamp.mod(10) == 0) {
                                drawLine(
                                    color = gridColor,
                                    start = Offset(x, 0f),
                                    end = Offset(x, 24f),
                                    strokeWidth = 2f
                                )
                                drawLine(
                                    color = gridColor,
                                    start = Offset(x, h - 24f),
                                    end = Offset(x, h),
                                    strokeWidth = 2f
                                )
                                drawText(
                                    measurer,
                                    TimeFormatHelper.formatDuration(timeStamp / 10),
                                    topLeft = Offset(x - 24f, 6f),
                                    style = TextStyle(color = cyanColor.copy(alpha = 0.7f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
