package com.bnyro.recorder.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.TrimmerState
import com.bnyro.recorder.ui.components.AudioWaveformPreview
import com.bnyro.recorder.ui.components.RangeWaveformTimeline
import com.bnyro.recorder.ui.components.TrimmerControlBar
import com.bnyro.recorder.ui.models.TrimmerModel
import com.bnyro.recorder.util.ExportFormat
import com.bnyro.recorder.util.IntentHelper
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
            delay(40L)
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
                        .height(200.dp),
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val progressFraction = if (trimmerModel.totalDurationMs > 0L) {
                        (trimmerModel.currentPositionMs.toFloat() / trimmerModel.totalDurationMs).coerceIn(0f, 1f)
                    } else null
                    AudioWaveformPreview(
                        amplitudes = trimmerModel.waveform,
                        seed = inputFile.name.hashCode(),
                        progress = progressFraction,
                        onSeek = { fraction ->
                            val seekMs = (fraction * trimmerModel.totalDurationMs).toLong()
                            trimmerModel.player.seekTo(seekMs)
                            trimmerModel.currentPositionMs = seekMs
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    )
                }
            }

            RangeWaveformTimeline(
                totalDurationMs = trimmerModel.totalDurationMs,
                startMs = trimmerModel.startTimeStamp,
                endMs = trimmerModel.endTimeStamp ?: trimmerModel.totalDurationMs,
                currentPositionMs = trimmerModel.currentPositionMs,
                waveform = trimmerModel.waveform,
                filmstrip = trimmerModel.filmstrip,
                onStartChanged = { trimmerModel.startTimeStamp = it },
                onEndChanged = { trimmerModel.endTimeStamp = it },
                onSeek = {
                    trimmerModel.player.seekTo(it)
                    trimmerModel.currentPositionMs = it
                }
            )

            TrimmerControlBar(
                startMs = trimmerModel.startTimeStamp,
                endMs = trimmerModel.endTimeStamp ?: trimmerModel.totalDurationMs,
                totalDurationMs = trimmerModel.totalDurationMs,
                canUndo = trimmerModel.undoStack.isNotEmpty(),
                canRedo = trimmerModel.redoStack.isNotEmpty(),
                isVideo = isVideo,
                selectedSpeed = trimmerModel.selectedSpeed,
                onAdjustStart = { trimmerModel.adjustStart(it) },
                onAdjustEnd = { trimmerModel.adjustEnd(it) },
                onTrim = { trimmerModel.trimToSelection() },
                onDelete = { trimmerModel.deleteSelection() },
                onMoveStart = { trimmerModel.moveSelectionToStart() },
                onMoveEnd = { trimmerModel.moveSelectionToEnd() },
                onToggleMute = { trimmerModel.toggleMuteSelection() },
                onCycleSpeed = {
                    val nextSpeed = when (trimmerModel.selectedSpeed) {
                        0.5f -> 1.0f
                        1.0f -> 1.5f
                        1.5f -> 2.0f
                        else -> 0.5f
                    }
                    trimmerModel.applySpeedToSelection(nextSpeed)
                },
                onAutoCutSilences = { trimmerModel.autoCutSilences() },
                onUndo = { trimmerModel.undo() },
                onRedo = { trimmerModel.redo() },
                onSnapshot = { trimmerModel.captureSnapshot(context, inputFile) }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    shape = CircleShape
                ) {
                    IconButton(
                        onClick = {
                            if (isPlayerPlaying) trimmerModel.player.pause() else trimmerModel.player.play()
                        }
                    ) {
                        Icon(
                            imageVector = if (isPlayerPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                    }
                }

                OutlinedButton(
                    onClick = { trimmerModel.previewSelection() }
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.preview_selection), modifier = Modifier.padding(start = 6.dp), fontSize = 13.sp)
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
                    modifier = Modifier.clickable { trimmerModel.enableFade = !trimmerModel.enableFade }
                ) {
                    Checkbox(
                        checked = trimmerModel.enableFade,
                        onCheckedChange = { trimmerModel.enableFade = it }
                    )
                    Text(stringResource(R.string.audio_fade), fontSize = 12.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { trimmerModel.replaceOriginal = !trimmerModel.replaceOriginal }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = trimmerModel.trimmerState != TrimmerState.Running
            ) {
                Text(stringResource(R.string.start_trimming), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (trimmerModel.trimmerState != TrimmerState.NoJob) {
        val notRunning = trimmerModel.trimmerState != TrimmerState.Running
        AlertDialog(
            onDismissRequest = {
                if (notRunning) trimmerModel.trimmerState = TrimmerState.NoJob
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (trimmerModel.trimmerState == TrimmerState.Success && trimmerModel.lastExportedFile != null) {
                        val file = trimmerModel.lastExportedFile!!
                        OutlinedButton(
                            onClick = {
                                IntentHelper.shareFile(context, file)
                            }
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.share), modifier = Modifier.padding(start = 4.dp))
                        }
                        Button(
                            onClick = {
                                IntentHelper.openFile(context, file)
                                trimmerModel.trimmerState = TrimmerState.NoJob
                                onDismissRequest()
                            }
                        ) {
                            Text(stringResource(R.string.play_result))
                        }
                    } else {
                        Button(
                            onClick = { trimmerModel.trimmerState = TrimmerState.NoJob },
                            enabled = notRunning
                        ) {
                            Text(stringResource(R.string.okay))
                        }
                    }
                }
            },
            title = {
                when (trimmerModel.trimmerState) {
                    TrimmerState.Failed -> Text(stringResource(R.string.trim_failed))
                    TrimmerState.Running -> Text(stringResource(R.string.trimming))
                    TrimmerState.Success -> Text(stringResource(R.string.trim_successful))
                    else -> {}
                }
            },
            text = {
                val image = AnimatedImageVector.animatedVectorResource(id = R.drawable.ic_trimmer)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.alpha(0.35f)
                ) {
                    Image(
                        modifier = Modifier.size(260.dp),
                        painter = painterResource(id = R.drawable.blob),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.secondaryContainer)
                    )
                    Image(
                        modifier = Modifier.size(180.dp),
                        painter = rememberAnimatedVectorPainter(
                            animatedImageVector = image,
                            atEnd = notRunning
                        ),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer),
                        contentDescription = null
                    )
                }
            }
        )
    }
}
