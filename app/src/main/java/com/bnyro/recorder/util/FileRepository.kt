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
    fun loadAudioWaveform(file: DocumentFile): List<Float>?
    fun resetWaveformCache()
    fun resetWaveform(file: DocumentFile)
    suspend fun deleteFiles(files: List<DocumentFile>)
    suspend fun deleteAllFiles()
    fun getTempOutputFile(extension: String): java.io.File
    fun commitOutputFile(tempFile: java.io.File, extension: String, prefix: String = "", waveform: List<Float>? = null): DocumentFile?
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
                val name = file.name
                name != null && commonVideoExtensions.any { ext -> name.endsWith(ext, ignoreCase = true) }
            }
        }.distinctBy { it.uri }

    private fun getAudioFiles(): List<DocumentFile> =
        getAudioOutputDirs().flatMap {
            it.listFiles().filter { file ->
                val name = file.name
                name != null && commonAudioExtensions.any { ext -> name.endsWith(ext, ignoreCase = true) }
            }
        }.distinctBy { it.uri }

    private val videoThumbnailCache = android.util.LruCache<String, Bitmap>(50)
    private val audioWaveformCache = android.util.LruCache<String, List<Float>>(100)
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
                val name = obj.optString("name")
                val modified = obj.optLong("modified", 0L)
                val size = obj.optLong("size", 0L)
                if (uriStr.isNotEmpty()) {
                    val uri = android.net.Uri.parse(uriStr)
                    val doc = if (uri.scheme == "file") {
                        val file = java.io.File(uri.path ?: "")
                        DocumentFile.fromFile(file)
                    } else {
                        DocumentFile.fromSingleUri(context, uri)
                    }
                    if (doc != null && doc.exists()) {
                        val waveArr = obj.optJSONArray("waveform")
                        val wave = if (waveArr != null && waveArr.length() > 0) {
                            val list = ArrayList<Float>(waveArr.length())
                            for (j in 0 until waveArr.length()) {
                                list.add(waveArr.optDouble(j, 0.1).toFloat())
                            }
                            if (!AudioWaveformExtractor.isFlatWaveform(list) && !AudioWaveformExtractor.isSaturatedWaveform(list)) list else null
                        } else null
                        if (wave != null) {
                            audioWaveformCache.put(doc.uri.toString(), wave)
                        }
                        val item = RecordingItemData(
                            recordingFile = doc,
                            recorderType = if (isVideo) RecorderType.VIDEO else RecorderType.AUDIO,
                            name = if (name.isNotEmpty()) name else (uri.lastPathSegment ?: ""),
                            lastModified = modified,
                            size = size,
                            thumbnail = if (isVideo) videoThumbnailCache.get(doc.uri.toString()) else null,
                            waveform = wave
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

    private val writeLock = Any()

    private fun writeCache() {
        synchronized(writeLock) {
            try {
                val arr = org.json.JSONArray()
                val all = (cachedAudio.orEmpty() + cachedVideos.orEmpty()).distinctBy { it.recordingFile.uri }
                for (item in all) {
                    val obj = org.json.JSONObject()
                    obj.put("uri", item.recordingFile.uri.toString())
                    obj.put("video", item.isVideo)
                    obj.put("name", item.name)
                    obj.put("modified", item.lastModified)
                    obj.put("size", item.size)
                    item.waveform?.let { wave ->
                        val jArr = org.json.JSONArray()
                        for (f in wave) {
                            jArr.put(f.toDouble())
                        }
                        obj.put("waveform", jArr)
                    }
                    arr.put(obj)
                }
                cacheFile.writeText(arr.toString())
            } catch (e: Exception) {
                Log.e("FileRepository", "Cache write failed", e)
            }
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
                    retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
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

    override fun loadAudioWaveform(file: DocumentFile): List<Float>? {
        val uriStr = file.uri.toString()
        val currentSize = file.length()
        val currentModified = file.lastModified()
        val existing = cachedAudio?.firstOrNull { it.recordingFile.uri == file.uri }
        val isModified = existing != null && (existing.size != currentSize || existing.lastModified != currentModified)
        if (isModified) {
            audioWaveformCache.remove(uriStr)
        } else {
            val cached = audioWaveformCache.get(uriStr)
            if (cached != null && !AudioWaveformExtractor.isFlatWaveform(cached) && !AudioWaveformExtractor.isSaturatedWaveform(cached)) {
                return cached
            }
        }
        val wave = AudioWaveformExtractor.extractWaveform(context, file.uri, fileName = file.name) ?: return null
        audioWaveformCache.put(uriStr, wave)
        cachedAudio = cachedAudio?.map {
            if (it.recordingFile.uri == file.uri) {
                it.copy(waveform = wave, size = currentSize, lastModified = currentModified)
            } else it
        }
        writeCache()
        return wave
    }

    override fun resetWaveformCache() {
        audioWaveformCache.evictAll()
        cachedAudio = cachedAudio?.map { it.copy(waveform = null) }
        writeCache()
    }

    override fun resetWaveform(file: DocumentFile) {
        val uriStr = file.uri.toString()
        audioWaveformCache.remove(uriStr)
        cachedAudio = cachedAudio?.map {
            if (it.recordingFile.uri == file.uri) it.copy(waveform = null) else it
        }
        writeCache()
    }

    override suspend fun getVideoRecordingItems(sortOrder: SortOrder): List<RecordingItemData> {
        return withContext(Dispatchers.IO) {
            val items = getVideoFiles().map {
                val uriStr = it.uri.toString()
                val currentSize = it.length()
                val currentModified = it.lastModified()
                val existing = cachedVideos?.firstOrNull { c -> c.recordingFile.uri == it.uri }
                val thumb = if (existing != null && existing.size == currentSize && existing.lastModified == currentModified) {
                    videoThumbnailCache.get(uriStr) ?: existing.thumbnail
                } else {
                    null
                }
                RecordingItemData(
                    recordingFile = it,
                    recorderType = RecorderType.VIDEO,
                    name = it.name.orEmpty(),
                    lastModified = currentModified,
                    size = currentSize,
                    thumbnail = thumb
                )
            }
            cachedVideos = items
            writeCache()
            items.sortedBy(sortOrder)
        }
    }

    override suspend fun getAudioRecordingItems(sortOrder: SortOrder): List<RecordingItemData> {
        return withContext(Dispatchers.IO) {
            val items = getAudioFiles().map {
                val uriStr = it.uri.toString()
                val currentSize = it.length()
                val currentModified = it.lastModified()
                val existing = cachedAudio?.firstOrNull { c -> c.recordingFile.uri == it.uri }
                val wave = if (existing != null && existing.size == currentSize && existing.lastModified == currentModified) {
                    val w = audioWaveformCache.get(uriStr) ?: existing.waveform
                    if (w != null && !AudioWaveformExtractor.isSaturatedWaveform(w)) w else null
                } else {
                    null
                }
                if (wave != null) {
                    audioWaveformCache.put(uriStr, wave)
                }
                RecordingItemData(
                    recordingFile = it,
                    recorderType = RecorderType.AUDIO,
                    name = it.name.orEmpty(),
                    lastModified = currentModified,
                    size = currentSize,
                    waveform = wave
                )
            }
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

    override fun commitOutputFile(
        tempFile: java.io.File,
        extension: String,
        prefix: String,
        waveform: List<Float>?
    ): DocumentFile? {
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
            val validWaveform = if (waveform != null && !AudioWaveformExtractor.isFlatWaveform(waveform) && !AudioWaveformExtractor.isSaturatedWaveform(waveform)) {
                waveform
            } else null
            if (isAudio && validWaveform != null) {
                audioWaveformCache.put(destFile.uri.toString(), validWaveform)
            }
            val newItem = RecordingItemData(
                recordingFile = destFile,
                recorderType = if (isAudio) RecorderType.AUDIO else RecorderType.VIDEO,
                name = destFile.name.orEmpty(),
                lastModified = destFile.lastModified(),
                size = destFile.length(),
                waveform = validWaveform
            )
            if (isAudio) {
                cachedAudio = (listOf(newItem) + cachedAudio.orEmpty()).distinctBy { it.recordingFile.uri }
            } else {
                cachedVideos = (listOf(newItem) + cachedVideos.orEmpty()).distinctBy { it.recordingFile.uri }
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
        SortOrder.MODIFIED -> sortedBy { it.lastModified }
        SortOrder.MODIFIED_REV -> sortedByDescending { it.lastModified }
        SortOrder.ALPHABETIC -> sortedBy { it.name.lowercase() }
        SortOrder.ALPHABETIC_REV -> sortedByDescending { it.name.lowercase() }
        SortOrder.SIZE_REV -> sortedBy { it.size }
        SortOrder.SIZE -> sortedByDescending { it.size }
    }
}

