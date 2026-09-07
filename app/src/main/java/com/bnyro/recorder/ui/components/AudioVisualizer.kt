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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val cyanContourColor = Color(0xFF14FFE0)
    val gridColor = Color(0x33627282)
    val centerGridColor = Color(0x557A8B9E)
    val bgDark = Color(0xFF0F1216)
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
                strokeWidth = 1.2f
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, h * 0.75f),
                end = Offset(w, h * 0.75f),
                strokeWidth = 1f
            )

            val stepX = 6f
            val count = amplitudes.size
            if (count > 1) {
                val visiblePoints = mutableListOf<Triple<Float, Float, Int>>()
                for (index in 0 until count) {
                    val amplitude = amplitudes[index]
                    val ampPercent = (amplitude / maxAmplitude).coerceIn(0.02f, 1f)
                    val halfSpike = (h * 0.45f) * ampPercent
                    val reverseIndex = index - count
                    val x = w + (reverseIndex * stepX)
                    if (x in -stepX..(w + stepX)) {
                        visiblePoints.add(Triple(x, halfSpike, reverseIndex))
                    }
                }

                if (visiblePoints.isNotEmpty()) {
                    val solidPath = Path()
                    val first = visiblePoints.first()
                    solidPath.moveTo(first.first, centerY)
                    for (pt in visiblePoints) {
                        solidPath.lineTo(pt.first, centerY - pt.second)
                    }
                    val last = visiblePoints.last()
                    solidPath.lineTo(last.first, centerY)
                    for (i in visiblePoints.indices.reversed()) {
                        val pt = visiblePoints[i]
                        solidPath.lineTo(pt.first, centerY + pt.second)
                    }
                    solidPath.close()

                    drawPath(solidPath, color = cyanColor, style = Fill)

                    val topPath = Path()
                    topPath.moveTo(first.first, centerY - first.second)
                    for (i in 1 until visiblePoints.size) {
                        topPath.lineTo(visiblePoints[i].first, centerY - visiblePoints[i].second)
                    }
                    drawPath(topPath, color = cyanContourColor, style = Stroke(width = 1f))

                    val bottomPath = Path()
                    bottomPath.moveTo(first.first, centerY + first.second)
                    for (i in 1 until visiblePoints.size) {
                        bottomPath.lineTo(visiblePoints[i].first, centerY + visiblePoints[i].second)
                    }
                    drawPath(bottomPath, color = cyanContourColor, style = Stroke(width = 1f))

                    if (showTimestamps) {
                        viewModel.recordedTime?.let { recTime ->
                            for (pt in visiblePoints) {
                                val timeStamp = recTime + pt.third
                                if (timeStamp.mod(10) == 0) {
                                    drawLine(
                                        color = gridColor,
                                        start = Offset(pt.first, 0f),
                                        end = Offset(pt.first, 24f),
                                        strokeWidth = 2f
                                    )
                                    drawLine(
                                        color = gridColor,
                                        start = Offset(pt.first, h - 24f),
                                        end = Offset(pt.first, h),
                                        strokeWidth = 2f
                                    )
                                    drawText(
                                        measurer,
                                        TimeFormatHelper.formatDuration(timeStamp / 10),
                                        topLeft = Offset(pt.first - 24f, 6f),
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
}
