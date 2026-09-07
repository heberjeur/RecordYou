package com.bnyro.recorder.ui.models

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bnyro.recorder.App
import com.bnyro.recorder.enums.SortOrder
import com.bnyro.recorder.obj.RecordingItemData
import com.bnyro.recorder.util.FileRepository
import com.bnyro.recorder.util.sortedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerModel(context: Context, private val fileRepository: FileRepository) : ViewModel() {
    @UnstableApi
    var player = ExoPlayer.Builder(context)
        .setUsePlatformDiagnostics(false)
        .build()

    var selectedFiles by mutableStateOf(listOf<RecordingItemData>())

    private var sortOrder = SortOrder.MODIFIED

    var audioRecordingItems by mutableStateOf(fileRepository.cachedAudio(sortOrder))
    var screenRecordingItems by mutableStateOf(fileRepository.cachedVideos(sortOrder))

    private var loadJob: Job? = null

    init {
        loadFiles()
    }

    fun loadFiles() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val audio = fileRepository.getAudioRecordingItems(sortOrder)
            val video = fileRepository.getVideoRecordingItems(sortOrder)
            withContext(Dispatchers.Main) {
                audioRecordingItems = audio
                screenRecordingItems = video
            }

            video.forEach { item ->
                if (item.thumbnail == null) {
                    val thumb = fileRepository.loadVideoThumbnail(item.recordingFile)
                    if (thumb != null) {
                        withContext(Dispatchers.Main) {
                            screenRecordingItems = screenRecordingItems.map {
                                if (it.recordingFile.uri == item.recordingFile.uri) {
                                    it.copy(thumbnail = thumb)
                                } else {
                                    it
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun sortItems(newSort: SortOrder) {
        if (newSort == sortOrder) return
        sortOrder = newSort
        audioRecordingItems = audioRecordingItems.sortedBy(sortOrder)
        screenRecordingItems = screenRecordingItems.sortedBy(sortOrder)
    }

    fun deleteFiles() {
        viewModelScope.launch {
            if (selectedFiles.isEmpty()) {
                fileRepository.deleteAllFiles()
                audioRecordingItems = emptyList()
                screenRecordingItems = emptyList()
                return@launch
            }
            val toDelete = selectedFiles
            selectedFiles = emptyList()
            fileRepository.deleteFiles(toDelete.map { it.recordingFile })
            val uris = toDelete.map { it.recordingFile.uri }.toSet()
            audioRecordingItems = audioRecordingItems.filterNot { uris.contains(it.recordingFile.uri) }
            screenRecordingItems = screenRecordingItems.filterNot { uris.contains(it.recordingFile.uri) }
        }
    }

    fun stopPlaying() {
        player.stop()
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as App)
                PlayerModel(application, application.fileRepository)
            }
        }
    }
}
