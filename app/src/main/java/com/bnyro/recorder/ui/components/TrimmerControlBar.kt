@file:Suppress("DEPRECATION")

package com.bnyro.recorder.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnyro.recorder.R
import com.bnyro.recorder.util.TimeFormatHelper

@Composable
fun TrimmerControlBar(
    startMs: Long,
    endMs: Long,
    totalDurationMs: Long,
    canUndo: Boolean,
    canRedo: Boolean,
    isVideo: Boolean,
    selectedSpeed: Float,
    onAdjustStart: (Long) -> Unit,
    onAdjustEnd: (Long) -> Unit,
    onTrim: () -> Unit,
    onDelete: () -> Unit,
    onMoveStart: () -> Unit,
    onMoveEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onCycleSpeed: () -> Unit,
    onAutoCutSilences: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSnapshot: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedDurMs = (endMs - startMs).coerceAtLeast(0L)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onAdjustStart(-100L) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("-0.1s", fontSize = 11.sp)
                }
                Text(
                    text = TimeFormatHelper.formatDuration(startMs / 1000),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = { onAdjustStart(100L) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("+0.1s", fontSize = 11.sp)
                }
            }

            Text(
                text = "${TimeFormatHelper.formatDuration(selectedDurMs / 1000)} / ${TimeFormatHelper.formatDuration(totalDurationMs / 1000)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onAdjustEnd(-100L) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("-0.1s", fontSize = 11.sp)
                }
                Text(
                    text = TimeFormatHelper.formatDuration(endMs / 1000),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = { onAdjustEnd(100L) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("+0.1s", fontSize = 11.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.undo), modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = stringResource(R.string.redo), modifier = Modifier.size(18.dp))
            }

            FilledTonalButton(
                onClick = onTrim,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.trim_mode_keep), fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
            }

            AssistChip(
                onClick = onDelete,
                label = { Text(stringResource(R.string.trim_mode_delete), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp)) },
                colors = AssistChipDefaults.assistChipColors()
            )

            AssistChip(
                onClick = onMoveStart,
                label = { Text(stringResource(R.string.move_to_start), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.FirstPage, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )

            AssistChip(
                onClick = onMoveEnd,
                label = { Text(stringResource(R.string.move_to_end), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.LastPage, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )

            AssistChip(
                onClick = onToggleMute,
                label = { Text(stringResource(R.string.trim_mode_mute), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.VolumeMute, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )

            AssistChip(
                onClick = onCycleSpeed,
                label = { Text("${selectedSpeed}x", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )

            AssistChip(
                onClick = onAutoCutSilences,
                label = { Text(stringResource(R.string.remove_silences), fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp)) }
            )

            if (isVideo) {
                AssistChip(
                    onClick = onSnapshot,
                    label = { Text(stringResource(R.string.capture_snapshot), fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }
        }
    }
}
