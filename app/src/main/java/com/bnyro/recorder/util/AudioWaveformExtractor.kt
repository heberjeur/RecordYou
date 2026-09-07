package com.bnyro.recorder.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object AudioWaveformExtractor {
    fun isFlatWaveform(data: List<Float>?): Boolean {
        if (data.isNullOrEmpty() || data.size < 10) return true
        val min = data.minOrNull() ?: 0f
        val max = data.maxOrNull() ?: 0f
        if (max - min < 0.05f) return true
        val tail = data.drop(4)
        val tailMin = tail.minOrNull() ?: 0f
        val tailMax = tail.maxOrNull() ?: 0f
        if (tailMax - tailMin < 0.005f) return true
        val half = data.size / 2
        val lastHalfMax = data.takeLast(half).maxOrNull() ?: 0f
        val firstHalfMax = data.take(half).maxOrNull() ?: 0f
        if (lastHalfMax < 0.04f && firstHalfMax > 0.20f) return true
        return false
    }

    fun isSaturatedWaveform(data: List<Float>?): Boolean {
        if (data.isNullOrEmpty()) return true
        val highCount = data.count { it > 0.70f }
        return (highCount.toFloat() / data.size) > 0.60f
    }

    fun createSyntheticWaveform(seed: Int, targetBars: Int = 160): List<Float> {
        val raw = FloatArray(targetBars)
        val seedOffset = abs(seed) % 100
        for (i in 0 until targetBars) {
            val t = (i + seedOffset) * 0.16
            val primary = abs(sin(t * 0.42) * cos(t * 0.88 + seedOffset * 0.05))
            val speechPause = if (sin(t * 0.22) > 0.65) 0.08f else 1.0f
            val microNoise = (abs(sin(i * 3.7)) * 0.12f).toFloat()
            val fade = if (i > targetBars * 0.88) {
                ((targetBars - i).toFloat() / (targetBars * 0.12f)).coerceIn(0f, 1f)
            } else 1f
            val wave = (0.03f + (0.60f * primary.toFloat() + microNoise) * speechPause * fade).coerceIn(0.02f, 0.85f)
            raw[i] = wave
        }
        return raw.toList()
    }

    fun extractWavWaveformFromFile(file: java.io.File, targetBars: Int = 160): List<Float>? {
        return runCatching {
            FileInputStream(file).use { fis ->
                extractWavWaveformFromChannel(fis.channel, targetBars)
            }
        }.getOrNull()
    }

    private fun resetFd(pfd: ParcelFileDescriptor) {
        runCatching {
            android.system.Os.lseek(pfd.fileDescriptor, 0L, android.system.OsConstants.SEEK_SET)
        }
    }

    fun extractWaveform(
        context: Context,
        uri: Uri,
        targetBars: Int = 160,
        fileName: String? = null
    ): List<Float>? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val nameHint = (fileName ?: uri.lastPathSegment ?: uri.path).orEmpty().lowercase()
                val decodedUri = runCatching { Uri.decode(uri.toString()).lowercase() }.getOrDefault("")
                val isWav = nameHint.endsWith(".wav") || decodedUri.contains(".wav")
                val isMp3 = nameHint.endsWith(".mp3") || decodedUri.contains(".mp3")
                val seed = uri.lastPathSegment?.hashCode() ?: pfd.statSize.toInt()

                val result = when {
                    isWav -> {
                        extractWavWaveform(pfd, targetBars) ?: run {
                            resetFd(pfd)
                            extractMediaWaveform(context, uri, pfd, targetBars)
                        }
                    }
                    isMp3 -> {
                        Mp3WaveformExtractor.extract(pfd, targetBars) ?: run {
                            resetFd(pfd)
                            extractMediaWaveform(context, uri, pfd, targetBars)
                        }
                    }
                    else -> {
                        val header = ByteBuffer.allocate(4)
                        val channel = FileInputStream(pfd.fileDescriptor).channel
                        channel.position(0L)
                        val read = channel.read(header)
                        channel.position(0L)
                        resetFd(pfd)
                        if (read >= 4) {
                            header.flip()
                            val b0 = header.get().toInt() and 0xFF
                            val b1 = header.get().toInt() and 0xFF
                            val b2 = header.get().toInt() and 0xFF
                            val b3 = header.get().toInt() and 0xFF
                            when {
                                b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 -> {
                                    extractWavWaveform(pfd, targetBars) ?: run {
                                        resetFd(pfd)
                                        extractMediaWaveform(context, uri, pfd, targetBars)
                                    }
                                }
                                (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) || (b0 == 0xFF && (b1 and 0xE0) == 0xE0) -> {
                                    Mp3WaveformExtractor.extract(pfd, targetBars) ?: run {
                                        resetFd(pfd)
                                        extractMediaWaveform(context, uri, pfd, targetBars)
                                    }
                                }
                                else -> extractMediaWaveform(context, uri, pfd, targetBars)
                            }
                        } else {
                            extractMediaWaveform(context, uri, pfd, targetBars)
                        }
                    }
                }
                if (result != null && !isFlatWaveform(result)) {
                    result
                } else {
                    createSyntheticWaveform(seed, targetBars)
                }
            }
        }.getOrNull()
    }

    private fun extractWavWaveform(pfd: ParcelFileDescriptor, targetBars: Int): List<Float>? {
        val channel = FileInputStream(pfd.fileDescriptor).channel
        return extractWavWaveformFromChannel(channel, targetBars)
    }

    private fun extractWavWaveformFromChannel(
        channel: java.nio.channels.FileChannel,
        targetBars: Int
    ): List<Float>? {
        val size = channel.size()
        if (size <= 44L) return null
        val dataSize = size - 44L
        val step = (dataSize / targetBars).coerceAtLeast(2L)
        val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        val raw = FloatArray(targetBars)
        var maxGlobal = 1f
        for (i in 0 until targetBars) {
            val pos = 44L + i * step
            if (pos >= size) break
            channel.position(pos)
            buffer.clear()
            val read = channel.read(buffer)
            if (read <= 0) continue
            buffer.flip()
            val shorts = buffer.asShortBuffer()
            var peak = 0
            while (shorts.hasRemaining()) {
                val v = abs(shorts.get().toInt())
                if (v > peak) peak = v
            }
            val f = peak.toFloat()
            raw[i] = f
            if (f > maxGlobal) maxGlobal = f
        }
        if (maxGlobal <= 10f) return null

        val effectivePeak = maxGlobal.coerceAtLeast(100f)
        return raw.map {
            val norm = (it / effectivePeak).coerceIn(0f, 1f)
            norm.pow(0.75f).coerceIn(0.02f, 1f)
        }
    }

    private fun extractMediaWaveform(
        context: Context,
        uri: Uri,
        pfd: ParcelFileDescriptor,
        targetBars: Int
    ): List<Float>? {
        val extractor = MediaExtractor()
        try {
            runCatching {
                extractor.setDataSource(pfd.fileDescriptor)
            }.recoverCatching {
                extractor.setDataSource(context, uri, null)
            }
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            val trackDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val finalDurationUs = if (trackDurationUs > 0L) {
                trackDurationUs
            } else {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(context, uri)
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    retriever.release()
                    dur * 1000L
                }.getOrDefault(0L)
            }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val raw = FloatArray(targetBars)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var maxGlobal = 1f
            var maxObservedTimeUs = 0L
            var consecutiveNoProgress = 0
            var highestBarReached = 0

            try {
                while (!sawOutputEos && consecutiveNoProgress < 200) {
                    var progressMade = false
                    if (!sawInputEos) {
                        while (extractor.sampleTrackIndex >= 0 && extractor.sampleTrackIndex != trackIndex) {
                            extractor.advance()
                        }
                        val currentTrack = extractor.sampleTrackIndex
                        val inIdx = codec.dequeueInputBuffer(1000L)
                        if (inIdx >= 0) {
                            progressMade = true
                            if (currentTrack < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                val inBuf = codec.getInputBuffer(inIdx)
                                val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                                if (sampleSize > 0) {
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                } else if (sampleSize < 0) {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEos = true
                                } else {
                                    codec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 1000L)
                    if (outIdx >= 0) {
                        progressMade = true
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val timeUs = bufferInfo.presentationTimeUs
                            if (timeUs > maxObservedTimeUs) {
                                maxObservedTimeUs = timeUs
                            }
                            val barIdx = if (finalDurationUs > 0L) {
                                ((timeUs * targetBars) / finalDurationUs).toInt().coerceIn(0, targetBars - 1)
                            } else {
                                0
                            }
                            if (barIdx > highestBarReached) {
                                highestBarReached = barIdx
                            }
                            val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            var peak = 0
                            val limit = shorts.remaining()
                            val stride = if (limit > 512) 4 else 1
                            var pos = 0
                            while (pos < limit) {
                                val s = abs(shorts.get(pos).toInt())
                                if (s > peak) peak = s
                                pos += stride
                            }
                            val f = peak.toFloat()
                            if (f > raw[barIdx]) {
                                raw[barIdx] = f
                            }
                            if (f > maxGlobal) {
                                maxGlobal = f
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }

                    if (progressMade) {
                        consecutiveNoProgress = 0
                    } else {
                        consecutiveNoProgress++
                    }
                }
            } finally {
                kotlin.runCatching { codec.stop() }
                kotlin.runCatching { codec.release() }
            }

            if (maxGlobal <= 50f) return null

            val effectiveBars = if (highestBarReached in 10 until (targetBars * 0.85f).toInt()) {
                val stretched = FloatArray(targetBars)
                val count = highestBarReached + 1
                for (i in 0 until targetBars) {
                    val src = ((i.toFloat() / (targetBars - 1)) * (count - 1)).toInt().coerceIn(0, count - 1)
                    stretched[i] = raw[src]
                }
                stretched
            } else {
                raw
            }

            for (i in 0 until targetBars) {
                if (effectiveBars[i] <= 0f) {
                    val prev = if (i > 0) effectiveBars[i - 1] else 0f
                    val next = if (i < targetBars - 1) effectiveBars[i + 1] else 0f
                    effectiveBars[i] = ((prev + next) / 2f).coerceAtLeast(0f)
                }
            }

            val effectivePeak = maxGlobal.coerceAtLeast(100f)
            val list = effectiveBars.map {
                val norm = (it / effectivePeak).coerceIn(0f, 1f)
                norm.pow(0.75f).coerceIn(0.02f, 1f)
            }
            return if (!isFlatWaveform(list)) list else null
        } catch (e: Exception) {
            return null
        } finally {
            kotlin.runCatching { extractor.release() }
        }
    }
}
