package com.bnyro.recorder.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.obj.RecordingItemData
import com.bnyro.recorder.ui.models.PlayerModel
import com.bnyro.recorder.ui.screens.TrimmerScreen

import com.bnyro.recorder.util.findActivity

@Composable
fun RecordingItemList(
    items: List<RecordingItemData>,
    isVideoList: Boolean,
    playerModel: PlayerModel = run {
        val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
        if (activity != null) {
            viewModel(viewModelStoreOwner = activity, factory = PlayerModel.Factory)
        } else {
            viewModel(factory = PlayerModel.Factory)
        }
    }
) {
    val icon = if (isVideoList) Icons.Default.VideoFile else Icons.Default.AudioFile
    var chosenFile by remember { mutableStateOf<DocumentFile?>(null) }
    var showTrimmer by remember { mutableStateOf(false) }
    var showMiniPlayer by remember { mutableStateOf(false) }
    if (items.isNotEmpty()) {
        Column {
            LazyColumn(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .weight(1f)
            ) {
                items(items, key = { it.recordingFile.uri }) {
                    RecordingItem(
                        it,
                        isSelected = playerModel.selectedFiles.contains(it),
                        playerModel = playerModel,
                        onClick = { wasLongPress ->
                            when {
                                wasLongPress -> playerModel.selectedFiles += it
                                playerModel.selectedFiles.isNotEmpty() -> {
                                    if (playerModel.selectedFiles.contains(it)) {
                                        playerModel.selectedFiles -= it
                                    } else {
                                        playerModel.selectedFiles += it
                                    }
                                }
                            }
                        },
                        onEdit = {
                            chosenFile = it.recordingFile
                            showTrimmer = true
                        }
                    ) {
                        chosenFile = it.recordingFile
                        showMiniPlayer = true
                    }
                }
            }
            val activeFile = playerModel.currentlyPlayingFile ?: chosenFile
            val isMiniPlayerVisible = (showMiniPlayer || playerModel.currentlyPlayingFile != null) && activeFile != null
            if (!isVideoList && activeFile != null) {
                AnimatedVisibility(
                    modifier = Modifier
                        .padding(bottom = 10.dp),
                    visible = isMiniPlayerVisible
                ) {
                    MiniPlayer(
                        inputFile = activeFile,
                        playerModel = playerModel,
                        onClose = {
                            playerModel.stopPlaying()
                            showMiniPlayer = false
                            chosenFile = null
                        }
                    )
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    modifier = Modifier.size(120.dp),
                    imageVector = icon,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = stringResource(R.string.nothing_here))
            }
        }
    }
    if (showTrimmer && chosenFile != null && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)) {
        TrimmerScreen(onDismissRequest = { showTrimmer = false }, inputFile = chosenFile!!)
    }
}
