package com.bnyro.recorder.ui.models

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.bnyro.recorder.App
import com.bnyro.recorder.enums.TrimmerState
import com.bnyro.recorder.obj.MediaSegment
import com.bnyro.recorder.util.ExportFormat
import com.bnyro.recorder.util.MediaTrimmer
import com.bnyro.recorder.util.TrimOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TrimmerModel(context: Context) : ViewModel() {

    @UnstableApi
    val player = ExoPlayer.Builder(context)
        .setUsePlatformDiagnostics(false)
        .build()

    var totalDurationMs by mutableLongStateOf(0L)
    var startTimeStamp by mutableLongStateOf(0L)
    var endTimeStamp by mutableStateOf<Long?>(null)
    var currentPositionMs by mutableLongStateOf(0L)

    var segments by mutableStateOf<List<MediaSegment>>(emptyList())
    val undoStack = ArrayDeque<List<MediaSegment>>()
    val redoStack = ArrayDeque<List<MediaSegment>>()

    var waveform by mutableStateOf<List<Float>?>(null)
    var filmstrip by mutableStateOf<List<Bitmap>>(emptyList())

    var exportFormat by mutableStateOf(ExportFormat.ORIGINAL)
    var enableFade by mutableStateOf(false)
    var replaceOriginal by mutableStateOf(false)
    var selectedSpeed by mutableFloatStateOf(1.0f)

    var isPreviewingSelection by mutableStateOf(false)
    var lastExportedFile by mutableStateOf<DocumentFile?>(null)
    var snapshotUri by mutableStateOf<Uri?>(null)
    var detectedSilencesCount by mutableStateOf(0)

    var trimmerState: TrimmerState by mutableStateOf(TrimmerState.NoJob)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && totalDurationMs == 0L) {
                val dur = player.duration.coerceAtLeast(0L)
                totalDurationMs = dur
                if (endTimeStamp == null || endTimeStamp == 0L) {
                    endTimeStamp = dur
                }
                if (segments.isEmpty() && dur > 0L) {
                    segments = listOf(MediaSegment(0L, dur))
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && isPreviewingSelection) {
                isPreviewingSelection = false
            }
        }
    }

    init {
        player.addListener(playerListener)
    }

    fun initFile(context: Context, inputFile: DocumentFile) {
        val mediaItem = MediaItem.Builder().setUri(inputFile.uri).build()
        player.setMediaItem(mediaItem)
        player.prepare()

        startTimeStamp = 0L
        endTimeStamp = null
        totalDurationMs = 0L
        segments = emptyList()
        undoStack.clear()
        redoStack.clear()
        waveform = null
        filmstrip = emptyList()
        lastExportedFile = null
        snapshotUri = null
        detectedSilencesCount = 0

        viewModelScope.launch {
            val app = context.applicationContext as App
            val wave = withContext(Dispatchers.IO) {
                app.fileRepository.loadAudioWaveform(inputFile)
            }
            waveform = wave

            val isVideo = inputFile.type?.startsWith("video") == true
            if (isVideo) {
                val dur = withContext(Dispatchers.IO) {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, inputFile.uri)
                        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } catch (e: Exception) {
                        0L
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
                if (dur > 0L) {
                    val frames = withContext(Dispatchers.IO) {
                        MediaTrimmer().extractFilmstrip(context, inputFile, dur, 10)
                    }
                    filmstrip = frames
                }
            }
        }
    }

    fun updatePosition(pos: Long) {
        currentPositionMs = pos
        if (isPreviewingSelection) {
            val endT = endTimeStamp ?: totalDurationMs
            if (pos >= endT) {
                player.pause()
                isPreviewingSelection = false
            }
        }
    }

    fun previewSelection() {
        val endT = endTimeStamp ?: totalDurationMs
        if (endT <= startTimeStamp) return
        isPreviewingSelection = true
        player.seekTo(startTimeStamp)
        player.play()
    }

    fun adjustStart(deltaMs: Long) {
        val newStart = (startTimeStamp + deltaMs).coerceIn(0L, (endTimeStamp ?: totalDurationMs) - 100L)
        startTimeStamp = newStart
        player.seekTo(newStart)
    }

    fun adjustEnd(deltaMs: Long) {
        val maxDur = if (totalDurationMs > 0L) totalDurationMs else Long.MAX_VALUE
        val curEnd = endTimeStamp ?: maxDur
        val newEnd = (curEnd + deltaMs).coerceIn(startTimeStamp + 100L, maxDur)
        endTimeStamp = newEnd
        player.seekTo(newEnd)
    }

    private fun pushUndoState() {
        undoStack.addLast(segments.toList())
        redoStack.clear()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.addLast(segments.toList())
            segments = undoStack.removeLast()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.addLast(segments.toList())
            segments = redoStack.removeLast()
        }
    }

    fun trimToSelection() {
        val end = endTimeStamp ?: totalDurationMs
        if (end <= startTimeStamp) return
        pushUndoState()
        segments = listOf(MediaSegment(startTimeStamp, end, selectedSpeed))
    }

    fun deleteSelection() {
        val end = endTimeStamp ?: totalDurationMs
        if (end <= startTimeStamp || segments.isEmpty()) return
        pushUndoState()

        val newSegments = mutableListOf<MediaSegment>()
        for (seg in segments) {
            if (seg.endMs <= startTimeStamp || seg.startMs >= end) {
                newSegments.add(seg)
            } else if (seg.startMs < startTimeStamp && seg.endMs > end) {
                newSegments.add(seg.copy(endMs = startTimeStamp))
                newSegments.add(seg.copy(startMs = end))
            } else if (seg.startMs < startTimeStamp) {
                newSegments.add(seg.copy(endMs = startTimeStamp))
            } else if (seg.endMs > end) {
                newSegments.add(seg.copy(startMs = end))
            }
        }
        segments = newSegments.filter { it.endMs > it.startMs }
    }

    fun moveSelectionToStart() {
        val end = endTimeStamp ?: totalDurationMs
        if (end <= startTimeStamp) return
        pushUndoState()
        val selectedSeg = MediaSegment(startTimeStamp, end, selectedSpeed)
        val remaining = mutableListOf<MediaSegment>()
        for (seg in segments) {
            if (seg.endMs <= startTimeStamp || seg.startMs >= end) {
                remaining.add(seg)
            } else if (seg.startMs < startTimeStamp && seg.endMs > end) {
                remaining.add(seg.copy(endMs = startTimeStamp))
                remaining.add(seg.copy(startMs = end))
            } else if (seg.startMs < startTimeStamp) {
                remaining.add(seg.copy(endMs = startTimeStamp))
            } else if (seg.endMs > end) {
                remaining.add(seg.copy(startMs = end))
            }
        }
        segments = listOf(selectedSeg) + remaining.filter { it.endMs > it.startMs }
    }

    fun moveSelectionToEnd() {
        val end = endTimeStamp ?: totalDurationMs
        if (end <= startTimeStamp) return
        pushUndoState()
        val selectedSeg = MediaSegment(startTimeStamp, end, selectedSpeed)
        val remaining = mutableListOf<MediaSegment>()
        for (seg in segments) {
            if (seg.endMs <= startTimeStamp || seg.startMs >= end) {
                remaining.add(seg)
            } else if (seg.startMs < startTimeStamp && seg.endMs > end) {
                remaining.add(seg.copy(endMs = startTimeStamp))
                remaining.add(seg.copy(startMs = end))
            } else if (seg.startMs < startTimeStamp) {
                remaining.add(seg.copy(endMs = startTimeStamp))
            } else if (seg.endMs > end) {
                remaining.add(seg.copy(startMs = end))
            }
        }
        segments = remaining.filter { it.endMs > it.startMs } + listOf(selectedSeg)
    }

    fun toggleMuteSelection() {
        val end = endTimeStamp ?: totalDurationMs
        if (end <= startTimeStamp) return
        pushUndoState()
        segments = segments.map {
            if (it.startMs >= startTimeStamp && it.endMs <= end) {
                it.copy(isMuted = !it.isMuted)
            } else it
        }
    }

    fun applySpeedToSelection(speed: Float) {
        selectedSpeed = speed
        player.playbackParameters = PlaybackParameters(speed)
        val end = endTimeStamp ?: totalDurationMs
        if (end <= startTimeStamp) return
        pushUndoState()
        segments = segments.map {
            if (it.startMs >= startTimeStamp && it.endMs <= end) {
                it.copy(speed = speed)
            } else it
        }
    }

    fun autoCutSilences() {
        val wave = waveform ?: return
        val silences = MediaTrimmer.detectSilences(wave, totalDurationMs)
        if (silences.isEmpty()) return
        pushUndoState()
        detectedSilencesCount = silences.size

        var currentList = segments.ifEmpty { listOf(MediaSegment(0L, totalDurationMs)) }
        for ((silenceStart, silenceEnd) in silences) {
            val updated = mutableListOf<MediaSegment>()
            for (seg in currentList) {
                if (seg.endMs <= silenceStart || seg.startMs >= silenceEnd) {
                    updated.add(seg)
                } else if (seg.startMs < silenceStart && seg.endMs > silenceEnd) {
                    updated.add(seg.copy(endMs = silenceStart))
                    updated.add(seg.copy(startMs = silenceEnd))
                } else if (seg.startMs < silenceStart) {
                    updated.add(seg.copy(endMs = silenceStart))
                } else if (seg.endMs > silenceEnd) {
                    updated.add(seg.copy(startMs = silenceEnd))
                }
            }
            currentList = updated.filter { it.endMs > it.startMs }
        }
        segments = currentList
    }

    fun captureSnapshot(context: Context, inputFile: DocumentFile) {
        viewModelScope.launch {
            val uri = MediaTrimmer().captureVideoFrame(context, inputFile, player.currentPosition)
            snapshotUri = uri
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startExport(context: Context, inputFile: DocumentFile) {
        val segs = if (segments.isNotEmpty()) segments else {
            val end = endTimeStamp ?: totalDurationMs
            listOf(MediaSegment(startTimeStamp, end, selectedSpeed))
        }
        viewModelScope.launch {
            trimmerState = TrimmerState.Running
            val trimmer = MediaTrimmer()
            val options = TrimOptions(
                exportFormat = exportFormat,
                enableFade = enableFade,
                replaceOriginal = replaceOriginal
            )
            val result = trimmer.processMedia(context, inputFile, segs, options)
            lastExportedFile = result
            trimmerState = if (result != null) TrimmerState.Success else TrimmerState.Failed
        }
    }

    override fun onCleared() {
        player.removeListener(playerListener)
        player.release()
        super.onCleared()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as App)
                TrimmerModel(application)
            }
        }
    }
}
