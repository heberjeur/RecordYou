package com.bnyro.recorder.util

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.enums.SortOrder
import com.bnyro.recorder.obj.RecordingItemData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar

interface FileRepository {
    suspend fun getVideoRecordingItems(sortOrder: SortOrder): List<RecordingItemData>
    suspend fun getAudioRecordingItems(sortOrder: SortOrder): List<RecordingItemData>
    suspend fun deleteFiles(files: List<DocumentFile>)
    suspend fun deleteAllFiles()
    fun getTempOutputFile(extension: String): java.io.File
    fun commitOutputFile(tempFile: java.io.File, extension: String, prefix: String = ""): DocumentFile?
    fun getOutputFile(extension: String, prefix: String = ""): DocumentFile?
    fun getOutputDir(): DocumentFile
    fun getOutputDirs(): List<DocumentFile>
}

class FileRepositoryImpl(val context: Context) : FileRepository {
    private val commonAudioExtensions = listOf(
        ".mp3",
        ".aac",
        ".ogg",
        ".wma",
        ".3gp",
        ".wav",
        ".m4a"
    )

    private val commonVideoExtensions = listOf(
        ".mp4",
        ".mov",
        ".avi",
        ".mkv",
        ".webm",
        ".mpg"
    )

    fun getMimeType(extension: String): String = when (extension.lowercase()) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "3gp" -> "video/3gpp"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> if (commonVideoExtensions.contains(".$extension")) "video/*" else "audio/*"
    }

    private fun getVideoFiles(): List<DocumentFile> =
        getOutputDirs().flatMap {
            it.listFiles().filter { file ->
                file.isFile && commonVideoExtensions.any { file.name?.endsWith(it) ?: false }
            }
        }

    private fun getAudioFiles(): List<DocumentFile> =
        getOutputDirs().flatMap {
            it.listFiles().filter { file ->
                file.isFile && commonAudioExtensions.any { file.name?.endsWith(it) ?: false }
            }
        }

    override suspend fun getVideoRecordingItems(sortOrder: SortOrder): List<RecordingItemData> {
        return withContext(Dispatchers.IO) {
            getVideoFiles().sortedBy(sortOrder).map {
                val thumbnail = kotlin.runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, it.uri)
                        retriever.frameAtTime
                    } finally {
                        retriever.release()
                    }
                }.getOrNull()
                RecordingItemData(it, RecorderType.VIDEO, thumbnail)
            }
        }
    }

    override suspend fun getAudioRecordingItems(sortOrder: SortOrder): List<RecordingItemData> {
        return withContext(Dispatchers.IO) {
            getAudioFiles().sortedBy(sortOrder).map { RecordingItemData(it, RecorderType.AUDIO) }
        }
    }

    override suspend fun deleteFiles(files: List<DocumentFile>) {
        withContext(Dispatchers.IO) {
            files.forEach {
                if (it.exists()) it.delete()
            }
        }
    }

    override suspend fun deleteAllFiles() {
        withContext(Dispatchers.IO) {
            getOutputDirs().map { files ->
                files.listFiles().forEach {
                    if (it.isFile) it.delete()
                }
            }
        }
    }

    override fun getTempOutputFile(extension: String): java.io.File {
        val tempDir = java.io.File(context.cacheDir, "recordings").apply { mkdirs() }
        return java.io.File.createTempFile("rec_", ".$extension", tempDir)
    }

    override fun commitOutputFile(tempFile: java.io.File, extension: String, prefix: String): DocumentFile? {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            tempFile.delete()
            return null
        }
        val destFile = getOutputFile(extension, prefix) ?: return null
        return try {
            context.contentResolver.openOutputStream(destFile.uri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                outputStream.flush()
            }
            tempFile.delete()
            destFile
        } catch (e: Exception) {
            Log.e("FileRepository", "Failed to commit temp recording to destination", e)
            tempFile.delete()
            null
        }
    }

    override fun getOutputFile(extension: String, prefix: String): DocumentFile? {
        val currentTimeMillis = Calendar.getInstance().time
        val currentDateTime = dateTimeFormat.format(currentTimeMillis)
        val currentDate = currentDateTime.split("_").first()
        val currentTime = currentDateTime.split("_").last()

        val fileName = Preferences.getString(
            Preferences.namingPatternKey,
            DEFAULT_NAMING_PATTERN
        )
            .replace("%d", currentDate)
            .replace("%t", currentTime)
            .replace("%m", currentTimeMillis.time.toString())
            .replace("%s", currentTimeMillis.time.div(1000).toString())

        val outputDir = getOutputDir()
        if (!outputDir.exists() || !outputDir.canRead() || !outputDir.canWrite()) return null

        val fullFileName = "$prefix$fileName.$extension"
        val existingFile = outputDir.findFile(fullFileName)

        return existingFile ?: outputDir.createFile(getMimeType(extension), fullFileName)
    }

    override fun getOutputDir(): DocumentFile = getOutputDirs().last()

    override fun getOutputDirs(): List<DocumentFile> {
        val prefDir = Preferences.prefs.getString(Preferences.targetFolderKey, "")
        val externalFilesDir = run {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            DocumentFile.fromFile(dir)
        }
        if (prefDir.isNullOrBlank()) {
            return listOf(externalFilesDir)
        }
        val customDir = runCatching {
            DocumentFile.fromTreeUri(context, prefDir.toUri())
        }.getOrNull()
        return if (customDir != null && customDir.exists()) {
            listOf(externalFilesDir, customDir)
        } else {
            listOf(externalFilesDir)
        }
    }

    companion object {
        @SuppressLint("SimpleDateFormat")
        private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
        const val DEFAULT_NAMING_PATTERN = "%d_%t"
    }
}

fun List<DocumentFile>.sortedBy(sortOrder: SortOrder): List<DocumentFile> {
    return when (sortOrder) {
        SortOrder.MODIFIED -> sortedBy { it.lastModified() }
        SortOrder.MODIFIED_REV -> sortedByDescending { it.lastModified() }
        SortOrder.ALPHABETIC -> sortedBy { it.name }
        SortOrder.ALPHABETIC_REV -> sortedByDescending { it.name }
        SortOrder.SIZE_REV -> sortedBy { it.length() }
        SortOrder.SIZE -> sortedByDescending { it.length() }
    }
}
