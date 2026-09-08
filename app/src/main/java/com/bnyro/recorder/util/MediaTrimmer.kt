package com.bnyro.recorder.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SparseIntArray
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.bnyro.recorder.App
import com.bnyro.recorder.obj.MediaSegment
import de.sciss.jump3r.Main as Jump3rMain
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ExportFormat {
    ORIGINAL,
    AUDIO_MP3,
    AUDIO_M4A
}

data class TrimOptions(
    val exportFormat: ExportFormat = ExportFormat.ORIGINAL,
    val enableFade: Boolean = false,
    val replaceOriginal: Boolean = false
)

class MediaTrimmer {

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun trimMedia(
        context: Context,
        inputFile: DocumentFile,
        startMs: Long,
        endMs: Long
    ): Boolean {
        if (!isValidTrimRange(startMs, endMs)) return false
        val segments = listOf(MediaSegment(startMs, endMs))
        val result = processMedia(context, inputFile, segments, TrimOptions())
        return result != null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun processMedia(
        context: Context,
        inputFile: DocumentFile,
        segments: List<MediaSegment>,
        options: TrimOptions
    ): DocumentFile? {
        if (segments.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            when (options.exportFormat) {
                ExportFormat.AUDIO_MP3 -> extractMp3(context, inputFile, segments, options)
                ExportFormat.AUDIO_M4A -> muxSegments(context, inputFile, segments, options, isAudioOnly = true, targetExt = "m4a")
                ExportFormat.ORIGINAL -> {
                    val isVideo = inputFile.type?.startsWith("video") == true
                    val ext = if (isVideo) {
                        inputFile.name?.split('.')?.lastOrNull() ?: "mp4"
                    } else {
                        var e = inputFile.name?.split('.')?.lastOrNull() ?: "m4a"
                        if (e == "aac" || (e == "ogg" && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)) e = "m4a"
                        e
                    }
                    muxSegments(context, inputFile, segments, options, isAudioOnly = !isVideo, targetExt = ext)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("WrongConstant")
    private suspend fun muxSegments(
        context: Context,
        inputFile: DocumentFile,
        segments: List<MediaSegment>,
        options: TrimOptions,
        isAudioOnly: Boolean,
        targetExt: String
    ): DocumentFile? {
        return withContext(Dispatchers.IO) {
            val app = context.applicationContext as App
            val isVideo = !isAudioOnly && inputFile.type?.startsWith("video") == true
            val tempFile = File(context.cacheDir, "trim_tmp_${System.currentTimeMillis()}.$targetExt")

            val inputPfd = context.contentResolver.openFileDescriptor(inputFile.uri, "r") ?: return@withContext null
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPfd.fileDescriptor)

            val outputMuxerFormat = when (targetExt.lowercase()) {
                "3gp" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                "ogg" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
                "webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            var muxer: MediaMuxer? = null
            var bufferSize = DEFAULT_BUFFER_SIZE
            val trackMap = SparseIntArray()
            var videoTrackIndex = -1
            var audioTrackIndex = -1

            val success = try {
                muxer = MediaMuxer(tempFile.absolutePath, outputMuxerFormat)
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val isAudio = mime.startsWith("audio/")
                    val isVid = mime.startsWith("video/")

                    if ((isAudioOnly && isAudio) || (!isAudioOnly && (isAudio || isVid))) {
                        extractor.selectTrack(i)
                        val dst = muxer.addTrack(format)
                        trackMap.put(i, dst)
                        if (isVid) videoTrackIndex = i
                        if (isAudio) audioTrackIndex = i

                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            val sz = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                            if (sz > bufferSize) bufferSize = sz
                        }
                    }
                }

                muxer.start()
                val buffer = ByteBuffer.allocate(bufferSize)
                val bufferInfo = MediaCodec.BufferInfo()

                var totalVideoTimeUs = 0L
                var totalAudioTimeUs = 0L

                for (seg in segments) {
                    if (seg.endMs <= seg.startMs) continue
                    val segStartUs = seg.startMs * 1000L
                    val segEndUs = seg.endMs * 1000L

                    var segFirstVideoPts = -1L
                    var segFirstAudioPts = -1L
                    var segLastVideoPts = -1L
                    var segLastAudioPts = -1L

                    extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    while (true) {
                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs > segEndUs) break

                        val track = extractor.sampleTrackIndex
                        val dstTrack = trackMap.get(track, -1)

                        if (dstTrack >= 0) {
                            val isAudioSample = (track == audioTrackIndex)
                            val isVideoSample = (track == videoTrackIndex)

                            if (sampleTimeUs >= segStartUs || isVideoSample) {
                                if (isAudioSample && seg.isMuted) {
                                    extractor.advance()
                                    continue
                                }

                                val adjustedPts: Long = if (isVideoSample) {
                                    if (segFirstVideoPts < 0) segFirstVideoPts = sampleTimeUs
                                    segLastVideoPts = sampleTimeUs
                                    totalVideoTimeUs + (sampleTimeUs - segFirstVideoPts)
                                } else {
                                    if (segFirstAudioPts < 0) segFirstAudioPts = sampleTimeUs
                                    segLastAudioPts = sampleTimeUs
                                    totalAudioTimeUs + (sampleTimeUs - segFirstAudioPts)
                                }

                                bufferInfo.presentationTimeUs = adjustedPts
                                bufferInfo.flags = extractor.sampleFlags
                                muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                            }
                        }
                        extractor.advance()
                    }

                    val segVideoDuration = if (segLastVideoPts > segFirstVideoPts && segFirstVideoPts >= 0) {
                        segLastVideoPts - segFirstVideoPts + 33_333L
                    } else (seg.endMs - seg.startMs) * 1000L

                    val segAudioDuration = if (segLastAudioPts > segFirstAudioPts && segFirstAudioPts >= 0) {
                        segLastAudioPts - segFirstAudioPts + 23_220L
                    } else (seg.endMs - seg.startMs) * 1000L

                    totalVideoTimeUs += segVideoDuration
                    totalAudioTimeUs += segAudioDuration
                }

                muxer.stop()
                true
            } catch (e: Exception) {
                Log.e("MediaTrimmer", "Error muxing segments: ${e.message}", e)
                false
            } finally {
                runCatching { muxer?.release() }
                runCatching { extractor.release() }
                runCatching { inputPfd.close() }
            }

            if (!success || !tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return@withContext null
            }

            val finalFile = if (options.replaceOriginal && inputFile.canWrite()) {
                context.contentResolver.openOutputStream(inputFile.uri, "wt")?.use { out ->
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(out)
                    }
                }
                tempFile.delete()
                inputFile
            } else {
                val prefix = if (isAudioOnly) "Audio_" else "Trim_"
                val targetDoc = app.fileRepository.getOutputFile(targetExt, prefix)
                if (targetDoc != null) {
                    context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                        FileInputStream(tempFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                    tempFile.delete()
                    app.fileRepository.addRecordedFile(targetDoc, isVideo = isVideo)
                    targetDoc
                } else {
                    tempFile.delete()
                    null
                }
            }
            finalFile
        }
    }

    private suspend fun extractMp3(
        context: Context,
        inputFile: DocumentFile,
        segments: List<MediaSegment>,
        options: TrimOptions
    ): DocumentFile? {
        return withContext(Dispatchers.IO) {
            val app = context.applicationContext as App
            val rawPcmFile = File(context.cacheDir, "raw_${System.currentTimeMillis()}.pcm")
            val wavFile = File(context.cacheDir, "audio_${System.currentTimeMillis()}.wav")
            val mp3File = File(context.cacheDir, "audio_${System.currentTimeMillis()}.mp3")

            val inputPfd = context.contentResolver.openFileDescriptor(inputFile.uri, "r") ?: return@withContext null
            val extractor = MediaExtractor()
            extractor.setDataSource(inputPfd.fileDescriptor)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                extractor.release()
                inputPfd.close()
                return@withContext null
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return@withContext null
            val sampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

            val decodeSuccess = try {
                val decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(audioFormat, null, null, 0)
                decoder.start()

                FileOutputStream(rawPcmFile).use { pcmOut ->
                    val bufferInfo = MediaCodec.BufferInfo()
                    for (seg in segments) {
                        if (seg.endMs <= seg.startMs) continue
                        val segStartUs = seg.startMs * 1000L
                        val segEndUs = seg.endMs * 1000L
                        extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                        var isSegDone = false
                        var consecutiveIdle = 0

                        while (!isSegDone && consecutiveIdle < 150) {
                            val inIdx = decoder.dequeueInputBuffer(2000L)
                            if (inIdx >= 0) {
                                val inBuf = decoder.getInputBuffer(inIdx)
                                val currentSampleTime = extractor.sampleTime
                                val track = extractor.sampleTrackIndex

                                if (track == audioTrackIndex && currentSampleTime <= segEndUs && inBuf != null) {
                                    val size = extractor.readSampleData(inBuf, 0)
                                    if (size > 0) {
                                        decoder.queueInputBuffer(inIdx, 0, size, currentSampleTime, 0)
                                        extractor.advance()
                                    } else {
                                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                                        isSegDone = true
                                    }
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                                    isSegDone = true
                                }
                            }

                            val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 2000L)
                            if (outIdx >= 0) {
                                val outBuf = decoder.getOutputBuffer(outIdx)
                                if (outBuf != null && bufferInfo.size > 0) {
                                    val chunk = ByteArray(bufferInfo.size)
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.get(chunk, 0, bufferInfo.size)

                                    if (seg.isMuted) {
                                        chunk.fill(0)
                                    }
                                    pcmOut.write(chunk)
                                }
                                decoder.releaseOutputBuffer(outIdx, false)
                                consecutiveIdle = 0
                            } else {
                                consecutiveIdle++
                            }
                        }
                    }
                }
                decoder.stop()
                decoder.release()
                true
            } catch (e: Exception) {
                Log.e("MediaTrimmer", "Error decoding audio for MP3: ${e.message}", e)
                false
            } finally {
                extractor.release()
                inputPfd.close()
            }

            if (!decodeSuccess || !rawPcmFile.exists() || rawPcmFile.length() <= 0L) {
                rawPcmFile.delete()
                return@withContext null
            }

            if (options.enableFade && rawPcmFile.length() > 4000L) {
                applyPcmFade(rawPcmFile, sampleRate, channelCount)
            }

            val pcmConv = PcmConverter(sampleRate.toLong(), channelCount, 16)
            FileOutputStream(wavFile).use { wavOut ->
                pcmConv.initHeader(wavOut)
                FileInputStream(rawPcmFile).use { it.copyTo(wavOut) }
            }
            pcmConv.writeHeader(wavFile)
            rawPcmFile.delete()

            val kbps = 192
            val args = arrayOf(
                "-b", kbps.toString(),
                "-s", (sampleRate / 1000.0).toString(),
                "-f",
                "-q", "7",
                "--silent",
                wavFile.absolutePath,
                mp3File.absolutePath
            )
            Jump3rMain().run(args)
            wavFile.delete()

            if (!mp3File.exists() || mp3File.length() <= 0L) {
                mp3File.delete()
                return@withContext null
            }

            val targetDoc = app.fileRepository.getOutputFile("mp3", "Audio_")
            if (targetDoc != null) {
                context.contentResolver.openOutputStream(targetDoc.uri)?.use { out ->
                    FileInputStream(mp3File).use { it.copyTo(out) }
                }
                mp3File.delete()
                app.fileRepository.addRecordedFile(targetDoc, isVideo = false)
                targetDoc
            } else {
                mp3File.delete()
                null
            }
        }
    }

    private fun applyPcmFade(file: File, sampleRate: Int, channels: Int) {
        val fadeSamples = (sampleRate * 0.3f).toInt()
        val bytesPerSample = 2 * channels
        val fadeBytes = fadeSamples * bytesPerSample
        val totalBytes = file.length()
        if (totalBytes < fadeBytes * 2) return

        java.io.RandomAccessFile(file, "rw").use { raf ->
            val buf = ByteArray(fadeBytes)
            raf.seek(0)
            raf.readFully(buf)
            val sBuf = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val totalShorts = sBuf.capacity()
            for (i in 0 until totalShorts) {
                val factor = i.toFloat() / totalShorts
                sBuf.put(i, (sBuf.get(i) * factor).toInt().toShort())
            }
            raf.seek(0)
            raf.write(buf)

            raf.seek(totalBytes - fadeBytes)
            raf.readFully(buf)
            val endBuf = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until totalShorts) {
                val factor = 1f - (i.toFloat() / totalShorts)
                endBuf.put(i, (endBuf.get(i) * factor).toInt().toShort())
            }
            raf.seek(totalBytes - fadeBytes)
            raf.write(buf)
        }
    }

    suspend fun captureVideoFrame(
        context: Context,
        inputFile: DocumentFile,
        timeMs: Long
    ): Uri? {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, inputFile.uri)
                val bitmap = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime ?: return@withContext null
                val fileName = "Snapshot_${System.currentTimeMillis()}.jpg"
                val fileRepo = (context.applicationContext as App).fileRepository
                val outputDir = fileRepo.getVideoOutputDir()
                val targetFile = outputDir.createFile("image/jpeg", fileName) ?: return@withContext null
                context.contentResolver.openOutputStream(targetFile.uri)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                targetFile.uri
            } catch (e: Exception) {
                Log.e("MediaTrimmer", "Failed to capture frame: ${e.message}")
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    suspend fun extractFilmstrip(
        context: Context,
        inputFile: DocumentFile,
        durationMs: Long,
        count: Int = 10
    ): List<Bitmap> {
        if (durationMs <= 0L) return emptyList()
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, inputFile.uri)
                val list = mutableListOf<Bitmap>()
                val interval = durationMs / count
                for (i in 0 until count) {
                    val timeUs = (i * interval + interval / 2).coerceAtMost(durationMs) * 1000L
                    val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 120, 80)
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    if (frame != null) {
                        list.add(frame)
                    }
                }
                list
            } catch (e: Exception) {
                emptyList()
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 1024 * 1024

        fun isValidTrimRange(startMs: Long, endMs: Long, totalDurationMs: Long = Long.MAX_VALUE): Boolean {
            return startMs >= 0 && endMs > startMs && endMs <= totalDurationMs
        }

        fun detectSilences(
            amplitudes: List<Float>?,
            totalDurationMs: Long,
            threshold: Float = 0.05f,
            minDurationMs: Long = 1000L
        ): List<Pair<Long, Long>> {
            if (amplitudes.isNullOrEmpty() || totalDurationMs <= 0L) return emptyList()
            val silences = mutableListOf<Pair<Long, Long>>()
            val barDurationMs = totalDurationMs.toFloat() / amplitudes.size
            var inSilence = false
            var silenceStartMs = 0L
            for (i in amplitudes.indices) {
                val amp = amplitudes[i]
                val timeMs = (i * barDurationMs).toLong()
                if (amp <= threshold) {
                    if (!inSilence) {
                        inSilence = true
                        silenceStartMs = timeMs
                    }
                } else {
                    if (inSilence) {
                        inSilence = false
                        val silenceDuration = timeMs - silenceStartMs
                        if (silenceDuration >= minDurationMs) {
                            silences.add(silenceStartMs to timeMs)
                        }
                    }
                }
            }
            if (inSilence && (totalDurationMs - silenceStartMs) >= minDurationMs) {
                silences.add(silenceStartMs to totalDurationMs)
            }
            return silences
        }
    }
}
