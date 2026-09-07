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

import android.graphics.Bitmap
import android.os.Build

interface FileRepository {
    fun cachedVideos(sort: SortOrder): List<RecordingItemData>
    fun cachedAudio(sort: SortOrder): List<RecordingItemData>
    suspend fun getVideoRecordingItems(sortOrder: SortOrder): List<RecordingItemData>
    suspend fun getAudioRecordingItems(sortOrder: SortOrder): List<RecordingItemData>
    fun loadVideoThumbnail(file: DocumentFile): Bitmap?
    suspend fun deleteFiles(files: List<DocumentFile>)
    suspend fun deleteAllFiles()
    fun getTempOutputFile(extension: String): java.io.File
    fun commitOutputFile(tempFile: java.io.File, extension: String, prefix: String = ""): DocumentFile?
    fun getOutputFile(extension: String, prefix: String = ""): DocumentFile?
    fun getOutputDir(): DocumentFile
    fun getOutputDirs(): List<DocumentFile>
    fun getAudioOutputDir(): DocumentFile
    fun getVideoOutputDir(): DocumentFile
    fun getAudioOutputDirs(): List<DocumentFile>
    fun getVideoOutputDirs(): List<DocumentFile>

    companion object {
        const val DEFAULT_NAMING_PATTERN = "%d_%t"

        fun formatFileName(
            pattern: String,
            date: String,
            time: String,
            epochMillis: Long
        ): String {
            return pattern
                .replace("%d", date)
                .replace("%t", time)
                .replace("%m", epochMillis.toString())
                .replace("%s", (epochMillis / 1000).toString())
        }
    }
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
        getVideoOutputDirs().flatMap {
            it.listFiles().filter { file ->
                file.isFile && commonVideoExtensions.any { file.name?.endsWith(it) ?: false }
            }
        }.distinctBy { it.uri }

    private fun getAudioFiles(): List<DocumentFile> =
        getAudioOutputDirs().flatMap {
            it.listFiles().filter { file ->
                file.isFile && commonAudioExtensions.any { file.name?.endsWith(it) ?: false }
            }
        }.distinctBy { it.uri }

    private val videoThumbnailCache = android.util.LruCache<String, Bitmap>(50)
    private var cachedAudio: List<RecordingItemData>? = null
    private var cachedVideos: List<RecordingItemData>? = null
    private val cacheFile = java.io.File(context.cacheDir, "recordings.json")

    init {
        readCache()
    }

    private fun readCache() {
        if (!cacheFile.exists()) return
        try {
            val arr = org.json.JSONArray(cacheFile.readText())
            val audio = mutableListOf<RecordingItemData>()
            val video = mutableListOf<RecordingItemData>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uriStr = obj.optString("uri")
                val isVideo = obj.optBoolean("video")
                if (uriStr.isNotEmpty()) {
                    val uri = android.net.Uri.parse(uriStr)
                    val doc = if (uri.scheme == "file") {
                        val file = java.io.File(uri.path ?: "")
                        if (file.exists()) DocumentFile.fromFile(file) else null
                    } else {
                        DocumentFile.fromSingleUri(context, uri)
                    }
                    if (doc != null && doc.exists()) {
                        val item = RecordingItemData(
                            recordingFile = doc,
                            recorderType = if (isVideo) RecorderType.VIDEO else RecorderType.AUDIO,
                            thumbnail = if (isVideo) videoThumbnailCache.get(doc.uri.toString()) else null
                        )
                        if (isVideo) video.add(item) else audio.add(item)
                    }
                }
            }
            cachedAudio = audio
            cachedVideos = video
        } catch (e: Exception) {
            Log.e("FileRepository", "Cache read failed", e)
        }
    }

    private fun writeCache() {
        try {
            val arr = org.json.JSONArray()
            val all = (cachedAudio.orEmpty() + cachedVideos.orEmpty()).distinctBy { it.recordingFile.uri }
            for (item in all) {
                val obj = org.json.JSONObject()
                obj.put("uri", item.recordingFile.uri.toString())
                obj.put("video", item.isVideo)
                arr.put(obj)
            }
            cacheFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e("FileRepository", "Cache write failed", e)
        }
    }

    override fun cachedAudio(sort: SortOrder): List<RecordingItemData> =
        cachedAudio?.sortedBy(sort) ?: emptyList()

    override fun cachedVideos(sort: SortOrder): List<RecordingItemData> =
        cachedVideos?.sortedBy(sort) ?: emptyList()

    override fun loadVideoThumbnail(file: DocumentFile): Bitmap? {
        val uriStr = file.uri.toString()
        videoThumbnailCache.get(uriStr)?.let { return it }
        return kotlin.runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, file.uri)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 320)
                        ?: retriever.frameAtTime
                } else {
                    retriever.frameAtTime
                }
                if (bitmap != null) {
                    videoThumbnailCache.put(uriStr, bitmap)
                }
                bitmap
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    override suspend fun getVideoRecordingItems(sortOrder: SortOrder): List<RecordingItemData> {
        return withContext(Dispatchers.IO) {
            val items = getVideoFiles().map {
                RecordingItemData(it, RecorderType.VIDEO, videoThumbnailCache.get(it.uri.toString()))
            }
            cachedVideos = items
            writeCache()
            items.sortedBy(sortOrder)
        }
    }

    override suspend fun getAudioRecordingItems(sortOrder: SortOrder): List<RecordingItemData> {
        return withContext(Dispatchers.IO) {
            val items = getAudioFiles().map { RecordingItemData(it, RecorderType.AUDIO) }
            cachedAudio = items
            writeCache()
            items.sortedBy(sortOrder)
        }
    }

    override suspend fun deleteFiles(files: List<DocumentFile>) {
        withContext(Dispatchers.IO) {
            val uris = files.map { it.uri }.toSet()
            files.forEach {
                if (it.exists()) it.delete()
            }
            cachedAudio = cachedAudio?.filterNot { uris.contains(it.recordingFile.uri) }
            cachedVideos = cachedVideos?.filterNot { uris.contains(it.recordingFile.uri) }
            writeCache()
        }
    }

    override suspend fun deleteAllFiles() {
        withContext(Dispatchers.IO) {
            (getAudioOutputDirs() + getVideoOutputDirs() + getOutputDirs()).distinctBy { it.uri }.forEach { files ->
                files.listFiles().forEach {
                    if (it.isFile) it.delete()
                }
            }
            cachedAudio = emptyList()
            cachedVideos = emptyList()
            writeCache()
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
            val isAudio = commonAudioExtensions.contains(".$extension")
            if (isAudio) {
                cachedAudio = (listOf(RecordingItemData(destFile, RecorderType.AUDIO)) + cachedAudio.orEmpty()).distinctBy { it.recordingFile.uri }
            } else {
                cachedVideos = (listOf(RecordingItemData(destFile, RecorderType.VIDEO)) + cachedVideos.orEmpty()).distinctBy { it.recordingFile.uri }
            }
            writeCache()
            destFile
        } catch (e: java.io.IOException) {
            Log.e("FileRepository", "IO error committing temp recording", e)
            tempFile.delete()
            null
        } catch (e: SecurityException) {
            Log.e("FileRepository", "Security error committing temp recording", e)
            tempFile.delete()
            null
        }
    }

    override fun getOutputFile(extension: String, prefix: String): DocumentFile? {
        val currentTimeMillis = Calendar.getInstance().time
        val currentDateTime = dateTimeFormat.format(currentTimeMillis)
        val currentDate = currentDateTime.split("_").first()
        val currentTime = currentDateTime.split("_").last()

        val fileName = FileRepository.formatFileName(
            Preferences.getString(
                Preferences.namingPatternKey,
                FileRepository.DEFAULT_NAMING_PATTERN
            ),
            currentDate,
            currentTime,
            currentTimeMillis.time
        )

        val isAudio = commonAudioExtensions.contains(".$extension")
        val outputDir = if (isAudio) getAudioOutputDir() else getVideoOutputDir()
        if (!outputDir.exists() || !outputDir.canRead() || !outputDir.canWrite()) return null

        val fullFileName = "$prefix$fileName.$extension"
        val existingFile = outputDir.findFile(fullFileName)

        return existingFile ?: outputDir.createFile(getMimeType(extension), fullFileName)
    }

    private fun resolveDirs(prefKey: String): List<DocumentFile> {
        val prefDir = Preferences.prefs.getString(prefKey, "")
            .takeIf { !it.isNullOrBlank() }
            ?: Preferences.prefs.getString(Preferences.targetFolderKey, "")
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

    override fun getAudioOutputDir(): DocumentFile = getAudioOutputDirs().last()
    override fun getVideoOutputDir(): DocumentFile = getVideoOutputDirs().last()

    override fun getAudioOutputDirs(): List<DocumentFile> = resolveDirs(Preferences.audioTargetFolderKey)
    override fun getVideoOutputDirs(): List<DocumentFile> = resolveDirs(Preferences.videoTargetFolderKey)

    override fun getOutputDir(): DocumentFile = getOutputDirs().last()
    override fun getOutputDirs(): List<DocumentFile> = resolveDirs(Preferences.targetFolderKey)

    companion object {
        const val DEFAULT_NAMING_PATTERN = FileRepository.DEFAULT_NAMING_PATTERN
        @SuppressLint("SimpleDateFormat")
        private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")
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

@JvmName("sortItems")
fun List<RecordingItemData>.sortedBy(sortOrder: SortOrder): List<RecordingItemData> {
    return when (sortOrder) {
        SortOrder.MODIFIED -> sortedBy { it.recordingFile.lastModified() }
        SortOrder.MODIFIED_REV -> sortedByDescending { it.recordingFile.lastModified() }
        SortOrder.ALPHABETIC -> sortedBy { it.recordingFile.name.orEmpty().lowercase() }
        SortOrder.ALPHABETIC_REV -> sortedByDescending { it.recordingFile.name.orEmpty().lowercase() }
        SortOrder.SIZE_REV -> sortedBy { it.recordingFile.length() }
        SortOrder.SIZE -> sortedByDescending { it.recordingFile.length() }
    }
}

