package com.bnyro.recorder.obj

import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bnyro.recorder.enums.RecorderType

data class RecordingItemData(
    val recordingFile: DocumentFile,
    val recorderType: RecorderType,
    val name: String = "",
    val lastModified: Long = 0L,
    val size: Long = 0L,
    val thumbnail: Bitmap? = null
) {
    val uri: Uri get() = recordingFile.uri
    val isAudio get() = recorderType == RecorderType.AUDIO
    val isVideo get() = recorderType == RecorderType.VIDEO
}
