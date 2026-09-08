package com.bnyro.recorder.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.TrimmerState
import com.bnyro.recorder.ui.components.RangeWaveformTimeline
import com.bnyro.recorder.ui.components.TrimmerControlBar
import com.bnyro.recorder.ui.models.TrimmerModel
import com.bnyro.recorder.util.ExportFormat
import com.bnyro.recorder.util.IntentHelper
import com.bnyro.recorder.util.TimeFormatHelper
import kotlinx.coroutines.delay

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationGraphicsApi::class)
@Composable
fun TrimmerScreen(onDismissRequest: () -> Unit, inputFile: DocumentFile) {
    val trimmerModel: TrimmerModel = viewModel(factory = TrimmerModel.Factory)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val isVideo = inputFile.type?.startsWith("video") == true

    LaunchedEffect(inputFile.uri) {
        trimmerModel.initFile(context, inputFile)
    }

    DisposableEffect(Unit) {
        onDispose {
            trimmerModel.player.stop()
        }
    }

    var isPlayerPlaying by remember { mutableStateOf(false) }
    DisposableEffect(trimmerModel.player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                isPlayerPlaying = isPlaying
            }
        }
        trimmerModel.player.addListener(listener)
        onDispose {
            trimmerModel.player.removeListener(listener)
        }
    }

    LaunchedEffect(isPlayerPlaying) {
        while (isPlayerPlaying) {
            trimmerModel.updatePosition(trimmerModel.player.currentPosition)
            delay(30L)
        }
    }

    LaunchedEffect(trimmerModel.snapshotUri) {
        val uri = trimmerModel.snapshotUri
        if (uri != null) {
            Toast.makeText(context, context.getString(R.string.snapshot_saved), Toast.LENGTH_SHORT).show()
            trimmerModel.snapshotUri = null
        }
    }

    ModalBottomSheet(
        onDismissRequest = { onDismissRequest() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.select_trim_range)) },
                navigationIcon = {
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = null)
                    }
                }
            )

            if (isVideo) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = trimmerModel.player
                                useController = false
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = inputFile.name.orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = TimeFormatHelper.formatDuration(trimmerModel.totalDurationMs / 1000),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            RangeWaveformTimeline(
                totalDurationMs = trimmerModel.totalDurationMs,
                segments = trimmerModel.segments,
                selectedSegmentIndex = trimmerModel.selectedSegmentIndex,
                currentPositionMs = trimmerModel.currentPositionMs,
                waveform = trimmerModel.waveform,
                filmstrip = trimmerModel.filmstrip,
                zoomFactor = trimmerModel.zoomFactor,
                onZoomChange = { trimmerModel.zoomFactor = it },
                onResetZoom = { trimmerModel.resetZoom() },
                onZoomIn = { trimmerModel.zoomIn() },
                onZoomOut = { trimmerModel.zoomOut() },
                onSelectSegment = { trimmerModel.selectSegment(it) },
                onStartChanged = { trimmerModel.updateSelectedSegmentStart(it) },
                onEndChanged = { trimmerModel.updateSelectedSegmentEnd(it) },
                onSlideSegment = { trimmerModel.slideSelectedSegment(it) },
                onDragStart = { trimmerModel.beginSegmentEdit() },
                onSeek = {
                    trimmerModel.player.seekTo(it)
                    trimmerModel.currentPositionMs = it
                }
            )

            val curPosFormatted = TimeFormatHelper.formatDuration(trimmerModel.currentPositionMs / 1000)
            val selectedDur = trimmerModel.selectedSegment?.durationMs ?: 0L
            val selDurFormatted = TimeFormatHelper.formatDuration(selectedDur / 1000)
            val totalDurFormatted = TimeFormatHelper.formatDuration(trimmerModel.totalDurationMs / 1000)
            val segIndex = (trimmerModel.selectedSegmentIndex + 1).coerceAtLeast(1)
            val segTotal = trimmerModel.segments.size.coerceAtLeast(1)

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = curPosFormatted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = "${stringResource(R.string.segment_label, segIndex, segTotal)} ($selDurFormatted)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = totalDurFormatted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val curSeg = trimmerModel.selectedSegment
            val canSplit = curSeg != null &&
                    trimmerModel.currentPositionMs > curSeg.startMs + 150L &&
                    trimmerModel.currentPositionMs < curSeg.endMs - 150L
            val canCut = curSeg != null
            val canCopy = curSeg != null
            val canPaste = trimmerModel.clipboardSegment != null

            TrimmerControlBar(
                canSplit = canSplit,
                canCut = canCut,
                canCopy = canCopy,
                canPaste = canPaste,
                canDelete = trimmerModel.segments.size > 1,
                canSelectAll = trimmerModel.totalDurationMs > 0L,
                canMoveLeft = trimmerModel.selectedSegmentIndex > 0,
                canMoveRight = trimmerModel.selectedSegmentIndex < trimmerModel.segments.size - 1,
                canUndo = trimmerModel.undoStack.isNotEmpty(),
                canRedo = trimmerModel.redoStack.isNotEmpty(),
                canZoomIn = trimmerModel.zoomFactor < 20.0f,
                canZoomOut = trimmerModel.zoomFactor > 1.0f,
                isVideo = isVideo,
                isSegmentMuted = curSeg?.isMuted == true,
                selectedSpeed = trimmerModel.selectedSpeed,
                onSplit = { trimmerModel.splitAtCurrentPosition() },
                onCut = {
                    if (trimmerModel.cutSelectedSegment()) {
                        Toast.makeText(context, context.getString(R.string.segment_cut), Toast.LENGTH_SHORT).show()
                    }
                },
                onCopy = {
                    if (trimmerModel.copySelectedSegment()) {
                        Toast.makeText(context, context.getString(R.string.segment_copied), Toast.LENGTH_SHORT).show()
                    }
                },
                onPaste = { trimmerModel.pasteSegment() },
                onDelete = { trimmerModel.deleteSelectedSegment() },
                onSelectPart = { trimmerModel.selectPartAtCurrentPosition() },
                onSelectAll = { trimmerModel.selectAllOrReset() },
                onMoveLeft = { trimmerModel.moveSelectedSegmentLeft() },
                onMoveRight = { trimmerModel.moveSelectedSegmentRight() },
                onUndo = { trimmerModel.undo() },
                onRedo = { trimmerModel.redo() },
                onZoomIn = { trimmerModel.zoomIn() },
                onZoomOut = { trimmerModel.zoomOut() },
                onToggleMute = { trimmerModel.toggleMuteSelection() },
                onCycleSpeed = { trimmerModel.cycleSpeed() },
                onAutoCutSilences = { trimmerModel.autoCutSilences() },
                onSnapshot = { trimmerModel.captureSnapshot(context, inputFile) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { trimmerModel.previousSegment() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = null, modifier = Modifier.size(24.dp))
                }

                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp)
                ) {
                    IconButton(
                        onClick = {
                            if (isPlayerPlaying) trimmerModel.player.pause() else trimmerModel.player.play()
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Icon(
                            imageVector = if (isPlayerPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { trimmerModel.nextSegment() },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(24.dp))
                }

                OutlinedButton(
                    onClick = { trimmerModel.previewSelection() }
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.preview_selection),
                        modifier = Modifier.padding(start = 6.dp),
                        fontSize = 12.sp
                    )
                }
            }

            if (isVideo) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = trimmerModel.exportFormat == ExportFormat.ORIGINAL,
                        onClick = { trimmerModel.exportFormat = ExportFormat.ORIGINAL },
                        label = { Text(stringResource(R.string.export_video), fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = trimmerModel.exportFormat == ExportFormat.AUDIO_MP3,
                        onClick = { trimmerModel.exportFormat = ExportFormat.AUDIO_MP3 },
                        label = { Text(stringResource(R.string.export_as_mp3), fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = trimmerModel.exportFormat == ExportFormat.AUDIO_M4A,
                        onClick = { trimmerModel.exportFormat = ExportFormat.AUDIO_M4A },
                        label = { Text(stringResource(R.string.export_as_m4a), fontSize = 12.sp) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(
                        checked = trimmerModel.enableFade,
                        onCheckedChange = { trimmerModel.enableFade = it }
                    )
                    Text(stringResource(R.string.audio_fade), fontSize = 12.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(
                        checked = trimmerModel.replaceOriginal,
                        onCheckedChange = { trimmerModel.replaceOriginal = it }
                    )
                    Text(stringResource(R.string.replace_original), fontSize = 12.sp)
                }
            }

            Button(
                onClick = { trimmerModel.startExport(context, inputFile) },
                enabled = trimmerModel.trimmerState != TrimmerState.Running,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                if (trimmerModel.trimmerState == TrimmerState.Running) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.trimming), fontSize = 14.sp)
                } else {
                    Text(stringResource(R.string.start_trimming), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    if (trimmerModel.trimmerState == TrimmerState.Success && trimmerModel.lastExportedFile != null) {
        val exportedFile = trimmerModel.lastExportedFile!!
        AlertDialog(
            onDismissRequest = { trimmerModel.trimmerState = TrimmerState.NoJob },
            title = { Text(stringResource(R.string.trim_successful)) },
            text = { Text(exportedFile.name.orEmpty()) },
            confirmButton = {
                Button(
                    onClick = {
                        IntentHelper.openFile(context, exportedFile)
                        trimmerModel.trimmerState = TrimmerState.NoJob
                        onDismissRequest()
                    }
                ) {
                    Text(stringResource(R.string.play_result))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        IntentHelper.shareFile(context, exportedFile)
                    }
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.share))
                }
            }
        )
    }

    if (trimmerModel.trimmerState == TrimmerState.Failed) {
        AlertDialog(
            onDismissRequest = { trimmerModel.trimmerState = TrimmerState.NoJob },
            title = { Text(stringResource(R.string.trim_failed)) },
            text = { Text(stringResource(R.string.cant_access_selected_folder)) },
            confirmButton = {
                Button(onClick = { trimmerModel.trimmerState = TrimmerState.NoJob }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}
