package com.bnyro.recorder.ui.components

import android.os.Build
import android.view.SoundEffectConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.obj.RecordingItemData
import com.bnyro.recorder.ui.common.ClickableIcon
import com.bnyro.recorder.ui.common.DialogButton
import com.bnyro.recorder.ui.common.FullscreenDialog
import com.bnyro.recorder.ui.dialogs.ConfirmationDialog
import com.bnyro.recorder.ui.models.PlayerModel
import com.bnyro.recorder.ui.views.VideoView
import com.bnyro.recorder.util.IntentHelper
import com.bnyro.recorder.util.findActivity

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingItem(
    recordingItem: RecordingItemData,
    isSelected: Boolean,
    playerModel: PlayerModel = run {
        val activity = LocalContext.current.findActivity()
        if (activity != null) {
            viewModel(viewModelStoreOwner = activity, factory = PlayerModel.Factory)
        } else {
            viewModel(factory = PlayerModel.Factory)
        }
    },
    onClick: (wasLongClick: Boolean) -> Unit,
    onEdit: () -> Unit,
    startPlayingAudio: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current

    var showRenameDialog by remember {
        mutableStateOf(false)
    }
    var showDeleteDialog by remember {
        mutableStateOf(false)
    }
    var showDropDown by remember {
        mutableStateOf(false)
    }
    var showPlayer by remember {
        mutableStateOf(false)
    }
    with(recordingItem) {
        val cardColor =
            if (!isSelected) {
                MaterialTheme.colorScheme.surfaceColorAtElevation(
                    5.dp
                )
            } else {
                MaterialTheme.colorScheme.primary
            }
        ElevatedCard(
            modifier = Modifier
                .padding(vertical = 5.dp)
                .clip(CardDefaults.shape)
                .combinedClickable(
                    onClick = {
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        onClick.invoke(false)
                    },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick.invoke(true)
                    }
                ),
            colors = CardDefaults.cardColors(
                containerColor = cardColor,
                contentColor = contentColorFor(cardColor)
            )
        ) {
            Column() {
                if (isVideo) {
                    thumbnail?.let { thumbnail ->
                        val ratio = if (thumbnail.height > 0 && thumbnail.width > 0) {
                            thumbnail.width.toFloat() / thumbnail.height.toFloat()
                        } else {
                            16f / 9f
                        }
                        Image(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .aspectRatio(ratio)
                                .clip(RoundedCornerShape(8.dp)),
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.Center
                        )
                    }
                } else if (isAudio) {
                    AudioWaveformPreview(
                        amplitudes = waveform,
                        seed = name.hashCode(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 10.dp)
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = name
                    )
                    if (isAudio) {
                        val isReady = waveform != null && !com.bnyro.recorder.util.AudioWaveformExtractor.isFlatWaveform(waveform)
                        if (isReady) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = stringResource(R.string.waveform_ready),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .size(16.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .padding(horizontal = 6.dp)
                                    .size(14.dp),
                                strokeWidth = 1.5.dp
                            )
                        }
                    }
                    val isPlayingThis = isAudio && (playerModel.currentlyPlayingFile?.uri == recordingFile.uri) && playerModel.isAudioPlaying
                    ClickableIcon(
                        imageVector = if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (isPlayingThis) R.string.pause else R.string.play)
                    ) {
                        if (isVideo) {
                            showPlayer = true
                        } else {
                            playerModel.playFile(recordingFile)
                            startPlayingAudio.invoke()
                        }
                    }
                    Box {
                        ClickableIcon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.options)
                        ) {
                            showDropDown = true
                        }

                        DropdownMenu(
                            expanded = showDropDown,
                            onDismissRequest = { showDropDown = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.open))
                                },
                                onClick = {
                                    playerModel.stopPlaying()
                                    IntentHelper.openFile(context, recordingFile)
                                    showDropDown = false
                                }
                            )
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.trim))
                                    },
                                    onClick = {
                                        onEdit.invoke()
                                        showDropDown = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.share))
                                },
                                onClick = {
                                    playerModel.stopPlaying()
                                    IntentHelper.shareFile(context, recordingFile)
                                    showDropDown = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.rename))
                                },
                                onClick = {
                                    playerModel.stopPlaying()
                                    showRenameDialog = true
                                    showDropDown = false
                                }
                            )
                            if (isAudio) {
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.reset_waveform_cache))
                                    },
                                    onClick = {
                                        playerModel.resetWaveform(recordingItem)
                                        showDropDown = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.delete))
                                },
                                onClick = {
                                    playerModel.stopPlaying()
                                    showDeleteDialog = true
                                    showDropDown = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showRenameDialog) {
            var fileName by remember {
                mutableStateOf(name)
            }

            AlertDialog(
                onDismissRequest = {
                    showRenameDialog = false
                },
                title = {
                    Text(stringResource(R.string.rename))
                },
                text = {
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = {
                            fileName = it
                        },
                        label = {
                            Text(stringResource(R.string.file_name))
                        }
                    )
                },
                confirmButton = {
                    DialogButton(stringResource(R.string.okay)) {
                        recordingFile.renameTo(fileName)
                        playerModel.loadFiles()
                        showRenameDialog = false
                    }
                },
                dismissButton = {
                    DialogButton(stringResource(R.string.cancel)) {
                        showRenameDialog = false
                    }
                }
            )
        }

        if (showDeleteDialog) {
            ConfirmationDialog(
                title = R.string.delete,
                onDismissRequest = { showDeleteDialog = false }
            ) {
                playerModel.deleteFile(recordingItem)
            }
        }

        if (showPlayer) {
            FullscreenDialog(
                title = name.substringBeforeLast("."),
                onDismissRequest = {
                    showPlayer = false
                }
            ) {
                VideoView(videoUri = recordingFile.uri)
            }
        }
    }
}
