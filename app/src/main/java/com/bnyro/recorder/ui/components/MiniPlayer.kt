package com.bnyro.recorder.ui.components

import android.view.SoundEffectConstants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.ui.models.PlayerModel
import com.bnyro.recorder.util.TimeFormatHelper
import com.bnyro.recorder.util.findActivity

@Composable
fun MiniPlayer(
    inputFile: DocumentFile,
    playerModel: PlayerModel = run {
        val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
        if (activity != null) {
            viewModel(viewModelStoreOwner = activity, factory = PlayerModel.Factory)
        } else {
            viewModel(factory = PlayerModel.Factory)
        }
    },
    onClose: (() -> Unit)? = null
) {
    val view = LocalView.current
    LaunchedEffect(inputFile) {
        if (playerModel.currentlyPlayingFile?.uri != inputFile.uri) {
            playerModel.playFile(inputFile)
        }
    }
    val item = playerModel.audioRecordingItems.firstOrNull { it.recordingFile.uri == inputFile.uri }
    val posAndDur by playerModel.player.positionAndDurationState()
    val progress = if ((posAndDur.second ?: 0L) > 0L) {
        posAndDur.first.toFloat() / posAndDur.second!!.toFloat()
    } else 0f

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onClose != null) {
                    IconButton(
                        onClick = {
                            view.playSoundEffect(SoundEffectConstants.CLICK)
                            onClose()
                        }
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(id = com.bnyro.recorder.R.string.close)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                val fileName = inputFile.name.orEmpty()
                Text(
                    modifier = Modifier.weight(1f),
                    text = fileName.substringBeforeLast(".").takeIf {
                        it.isNotBlank()
                    } ?: fileName
                )
                FloatingActionButton(
                    onClick = {
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        playerModel.playFile(inputFile)
                    }
                ) {
                    if (playerModel.isAudioPlaying) {
                        Icon(
                            Icons.Default.Pause,
                            contentDescription = stringResource(id = com.bnyro.recorder.R.string.pause)
                        )
                    } else {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(id = com.bnyro.recorder.R.string.play)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AudioWaveformPreview(
                amplitudes = item?.waveform,
                seed = inputFile.name.hashCode(),
                progress = progress,
                onSeek = { fraction ->
                    val dur = posAndDur.second ?: playerModel.player.duration.takeIf { it > 0 } ?: 0L
                    if (dur > 0L) {
                        playerModel.player.seekTo((dur * fraction).toLong())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = TimeFormatHelper.formatDuration(posAndDur.first / 1000),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = posAndDur.second?.let { TimeFormatHelper.formatDuration(it / 1000) } ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
