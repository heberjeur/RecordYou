package com.bnyro.recorder.ui.models

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
    var selectedSegmentIndex by mutableIntStateOf(0)
    var zoomFactor by mutableFloatStateOf(1.0f)

    val undoStack = ArrayDeque<List<MediaSegment>>()
    val redoStack = ArrayDeque<List<MediaSegment>>()

    var waveform by mutableStateOf<List<Float>?>(null)
    var filmstrip by mutableStateOf<List<Bitmap>>(emptyList())

    var exportFormat by mutableStateOf(ExportFormat.ORIGINAL)
    var enableFade by mutableStateOf(false)
    var replaceOriginal by mutableStateOf(false)
    var selectedSpeed by mutableFloatStateOf(1.0f)

    var isPreviewingSelection by mutableStateOf(false)
    var clipboardSegment by mutableStateOf<MediaSegment?>(null)
    var lastExportedFile by mutableStateOf<DocumentFile?>(null)
    var snapshotUri by mutableStateOf<Uri?>(null)
    var detectedSilencesCount by mutableStateOf(0)

    var trimmerState: TrimmerState by mutableStateOf(TrimmerState.NoJob)

    val selectedSegment: MediaSegment?
        get() = segments.getOrNull(selectedSegmentIndex)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && totalDurationMs == 0L) {
                val dur = player.duration.coerceAtLeast(0L)
                totalDurationMs = dur
                if (endTimeStamp == null || endTimeStamp == 0L) {
                    endTimeStamp = dur
                }
                if (segments.isEmpty() && dur > 0L) {
                    segments = listOf(MediaSegment(startMs = 0L, endMs = dur))
                    selectedSegmentIndex = 0
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
        selectedSegmentIndex = 0
        zoomFactor = 1.0f
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
            val seg = selectedSegment
            val targetEnd = seg?.endMs ?: (endTimeStamp ?: totalDurationMs)
            if (pos >= targetEnd) {
                player.pause()
                isPreviewingSelection = false
                val restartPos = seg?.startMs ?: startTimeStamp
                player.seekTo(restartPos)
                currentPositionMs = restartPos
            }
            return
        }

        if (segments.isNotEmpty() && player.isPlaying) {
            val inSegment = segments.any { pos in it.startMs until it.endMs }
            if (!inSegment) {
                val nextSeg = segments.firstOrNull { it.startMs > pos }
                if (nextSeg != null) {
                    player.seekTo(nextSeg.startMs)
                    currentPositionMs = nextSeg.startMs
                } else {
                    player.pause()
                    val firstSeg = segments.firstOrNull()
                    if (firstSeg != null) {
                        player.seekTo(firstSeg.startMs)
                        currentPositionMs = firstSeg.startMs
                    }
                }
            }
        }
    }

    fun previewSelection() {
        val seg = selectedSegment ?: return
        if (seg.endMs <= seg.startMs) return
        isPreviewingSelection = true
        player.seekTo(seg.startMs)
        player.play()
    }

    fun selectSegment(index: Int) {
        if (index in segments.indices) {
            selectedSegmentIndex = index
            val seg = segments[index]
            startTimeStamp = seg.startMs
            endTimeStamp = seg.endMs
            if (currentPositionMs < seg.startMs || currentPositionMs > seg.endMs) {
                player.seekTo(seg.startMs)
                currentPositionMs = seg.startMs
            }
        }
    }

    fun resetZoom() {
        zoomFactor = 1.0f
    }

    fun zoomIn() {
        zoomFactor = (zoomFactor * 1.35f).coerceAtMost(20.0f)
    }

    fun zoomOut() {
        zoomFactor = (zoomFactor / 1.35f).coerceAtLeast(1.0f)
    }

    fun copySelectedSegment(): Boolean {
        val seg = selectedSegment ?: return false
        clipboardSegment = seg.copy(id = System.nanoTime())
        return true
    }

    fun cutSelectedSegment(): Boolean {
        val seg = selectedSegment ?: return false
        clipboardSegment = seg.copy(id = System.nanoTime())
        deleteSelectedSegment()
        return true
    }

    fun pasteSegment(): Boolean {
        val clip = clipboardSegment ?: return false
        pushUndoState()
        val newSeg = clip.copy(id = System.nanoTime())
        val index = (selectedSegmentIndex + 1).coerceIn(0, segments.size)
        val updated = segments.toMutableList()
        updated.add(index, newSeg)
        segments = updated
        selectedSegmentIndex = index
        syncSelectionTimes()
        return true
    }

    fun selectAllOrReset() {
        if (totalDurationMs <= 0L) return
        pushUndoState()
        segments = listOf(MediaSegment(startMs = 0L, endMs = totalDurationMs))
        selectedSegmentIndex = 0
        syncSelectionTimes()
        player.seekTo(0L)
        currentPositionMs = 0L
    }

    fun updateSelectedSegmentStart(newStartMs: Long) {
        val index = selectedSegmentIndex
        if (index !in segments.indices) return
        val seg = segments[index]
        val prevSegEnd = if (index > 0) segments[index - 1].endMs else 0L
        val safeStart = newStartMs.coerceIn(prevSegEnd, seg.endMs - 100L)
        val updated = segments.toMutableList()
        updated[index] = seg.copy(startMs = safeStart)
        segments = updated
        startTimeStamp = safeStart
    }

    fun updateSelectedSegmentEnd(newEndMs: Long) {
        val index = selectedSegmentIndex
        if (index !in segments.indices) return
        val seg = segments[index]
        val nextSegStart = if (index < segments.size - 1) segments[index + 1].startMs else totalDurationMs
        val safeEnd = newEndMs.coerceIn(seg.startMs + 100L, nextSegStart)
        val updated = segments.toMutableList()
        updated[index] = seg.copy(endMs = safeEnd)
        segments = updated
        endTimeStamp = safeEnd
    }

    private fun pushUndoState() {
        undoStack.addLast(segments.toList())
        redoStack.clear()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            redoStack.addLast(segments.toList())
            segments = undoStack.removeLast()
            selectedSegmentIndex = selectedSegmentIndex.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
            syncSelectionTimes()
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            undoStack.addLast(segments.toList())
            segments = redoStack.removeLast()
            selectedSegmentIndex = selectedSegmentIndex.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
            syncSelectionTimes()
        }
    }

    private fun syncSelectionTimes() {
        val seg = selectedSegment
        if (seg != null) {
            startTimeStamp = seg.startMs
            endTimeStamp = seg.endMs
        }
    }

    fun splitAtCurrentPosition() {
        val pos = currentPositionMs
        val index = segments.indexOfFirst { pos > it.startMs + 150L && pos < it.endMs - 150L }
        if (index < 0) return
        pushUndoState()
        val seg = segments[index]
        val left = seg.copy(id = System.nanoTime(), endMs = pos)
        val right = seg.copy(id = System.nanoTime() + 1, startMs = pos)
        val updated = segments.toMutableList()
        updated[index] = left
        updated.add(index + 1, right)
        segments = updated
        selectedSegmentIndex = index + 1
        syncSelectionTimes()
    }

    fun deleteSelectedSegment() {
        if (segments.size <= 1) return
        pushUndoState()
        val index = selectedSegmentIndex
        if (index !in segments.indices) return
        val updated = segments.toMutableList()
        updated.removeAt(index)
        segments = updated
        selectedSegmentIndex = index.coerceAtMost(segments.size - 1)
        syncSelectionTimes()
        selectedSegment?.let {
            player.seekTo(it.startMs)
            currentPositionMs = it.startMs
        }
    }

    fun moveSelectedSegmentLeft() {
        val index = selectedSegmentIndex
        if (index <= 0 || index >= segments.size) return
        pushUndoState()
        val updated = segments.toMutableList()
        val item = updated.removeAt(index)
        updated.add(index - 1, item)
        segments = updated
        selectedSegmentIndex = index - 1
        syncSelectionTimes()
    }

    fun moveSelectedSegmentRight() {
        val index = selectedSegmentIndex
        if (index < 0 || index >= segments.size - 1) return
        pushUndoState()
        val updated = segments.toMutableList()
        val item = updated.removeAt(index)
        updated.add(index + 1, item)
        segments = updated
        selectedSegmentIndex = index + 1
        syncSelectionTimes()
    }

    fun previousSegment() {
        if (selectedSegmentIndex > 0) {
            selectSegment(selectedSegmentIndex - 1)
        } else {
            selectedSegment?.let {
                player.seekTo(it.startMs)
                currentPositionMs = it.startMs
            }
        }
    }

    fun nextSegment() {
        if (selectedSegmentIndex < segments.size - 1) {
            selectSegment(selectedSegmentIndex + 1)
        }
    }

    fun toggleMuteSelection() {
        val seg = selectedSegment ?: return
        pushUndoState()
        val updated = segments.toMutableList()
        updated[selectedSegmentIndex] = seg.copy(isMuted = !seg.isMuted)
        segments = updated
    }

    fun cycleSpeed() {
        val nextSpeed = when (selectedSpeed) {
            0.5f -> 1.0f
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 0.5f
        }
        selectedSpeed = nextSpeed
        player.playbackParameters = PlaybackParameters(nextSpeed)
        val seg = selectedSegment ?: return
        pushUndoState()
        val updated = segments.toMutableList()
        updated[selectedSegmentIndex] = seg.copy(speed = nextSpeed)
        segments = updated
    }

    fun autoCutSilences() {
        val wave = waveform ?: return
        val silences = MediaTrimmer.detectSilences(wave, totalDurationMs)
        if (silences.isEmpty()) return
        pushUndoState()
        detectedSilencesCount = silences.size

        var currentList = segments.ifEmpty { listOf(MediaSegment(startMs = 0L, endMs = totalDurationMs)) }
        for ((silenceStart, silenceEnd) in silences) {
            val updated = mutableListOf<MediaSegment>()
            for (seg in currentList) {
                if (seg.endMs <= silenceStart || seg.startMs >= silenceEnd) {
                    updated.add(seg)
                } else if (seg.startMs < silenceStart && seg.endMs > silenceEnd) {
                    updated.add(seg.copy(id = System.nanoTime(), endMs = silenceStart))
                    updated.add(seg.copy(id = System.nanoTime() + 1, startMs = silenceEnd))
                } else if (seg.startMs < silenceStart) {
                    updated.add(seg.copy(endMs = silenceStart))
                } else if (seg.endMs > silenceEnd) {
                    updated.add(seg.copy(startMs = silenceEnd))
                }
            }
            currentList = updated.filter { it.endMs > it.startMs }
        }
        segments = currentList
        selectedSegmentIndex = 0
        syncSelectionTimes()
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
            listOf(MediaSegment(startMs = startTimeStamp, endMs = end, speed = selectedSpeed))
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
