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
        return false
    }

    fun createSyntheticWaveform(seed: Int, targetBars: Int = 160): List<Float> {
        val raw = FloatArray(targetBars)
        val seedOffset = abs(seed) % 100
        for (i in 0 until targetBars) {
            val t = (i + seedOffset) * 0.16
            val primary = abs(sin(t * 0.42) * cos(t * 0.88 + seedOffset * 0.05))
            val speechPause = if (sin(t * 0.22) > 0.65) 0.15f else 1.0f
            val microNoise = (abs(sin(i * 3.7)) * 0.18f).toFloat()
            val fade = if (i > targetBars * 0.88) {
                ((targetBars - i).toFloat() / (targetBars * 0.12f)).coerceIn(0f, 1f)
            } else 1f
            val wave = (0.05f + (0.78f * primary.toFloat() + microNoise) * speechPause * fade).coerceIn(0.03f, 0.95f)
            raw[i] = wave
        }
        return raw.toList()
    }

    fun extractWaveform(context: Context, uri: Uri, targetBars: Int = 160): List<Float>? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val path = uri.path.orEmpty().lowercase()
                val seed = uri.lastPathSegment?.hashCode() ?: pfd.statSize.toInt()
                val result = if (path.endsWith(".wav")) {
                    extractWavWaveform(pfd, targetBars)
                        ?: extractMediaWaveform(context, uri, pfd, targetBars)
                } else {
                    extractMediaWaveform(context, uri, pfd, targetBars)
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

        val sorted = raw.filter { it > 0f }.sorted()
        val effectivePeak = if (sorted.isNotEmpty()) {
            val p95 = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
            p95.coerceAtLeast(maxGlobal * 0.30f).coerceAtLeast(100f)
        } else maxGlobal.coerceAtLeast(100f)

        return raw.map {
            val norm = (it / effectivePeak).coerceIn(0f, 1.5f)
            sqrt(norm).coerceIn(0.02f, 1f)
        }
    }

    private fun extractMediaWaveform(
        context: Context,
        uri: Uri,
        pfd: ParcelFileDescriptor,
        targetBars: Int
    ): List<Float>? {
        val durationUs = runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            dur * 1000L
        }.getOrDefault(0L)

        val extractor = MediaExtractor()
        try {
            if (pfd.statSize > 0) {
                extractor.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
            } else {
                extractor.setDataSource(pfd.fileDescriptor)
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
            val finalDurationUs = if (durationUs > 0L) durationUs else trackDurationUs
            if (finalDurationUs <= 0L) return null

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val raw = FloatArray(targetBars)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var maxGlobal = 1f
            var totalBuffersDecoded = 0
            val maxBuffersToDecode = 8000

            try {
                while (!sawOutputEos && totalBuffersDecoded < maxBuffersToDecode) {
                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(8000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (sampleSize > 0) {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 8000L)
                    if (outIdx >= 0) {
                        totalBuffersDecoded++
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            val timeUs = bufferInfo.presentationTimeUs
                            val barIdx = ((timeUs * targetBars) / finalDurationUs).toInt().coerceIn(0, targetBars - 1)
                            val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            var peak = 0
                            while (shorts.hasRemaining()) {
                                val s = abs(shorts.get().toInt())
                                if (s > peak) peak = s
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
                }
            } finally {
                kotlin.runCatching { codec.stop() }
                kotlin.runCatching { codec.release() }
            }

            if (maxGlobal <= 50f) return null

            for (i in 0 until targetBars) {
                if (raw[i] <= 0f) {
                    val prev = if (i > 0) raw[i - 1] else 0f
                    val next = if (i < targetBars - 1) raw[i + 1] else 0f
                    raw[i] = ((prev + next) / 2f).coerceAtLeast(0f)
                }
            }

            val sorted = raw.filter { it > 0f }.sorted()
            val effectivePeak = if (sorted.isNotEmpty()) {
                val p95 = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
                p95.coerceAtLeast(maxGlobal * 0.30f).coerceAtLeast(100f)
            } else maxGlobal.coerceAtLeast(100f)

            val list = raw.map {
                val norm = (it / effectivePeak).coerceIn(0f, 1.5f)
                sqrt(norm).coerceIn(0.02f, 1f)
            }
            return if (!isFlatWaveform(list)) list else null
        } catch (e: Exception) {
            return null
        } finally {
            kotlin.runCatching { extractor.release() }
        }
    }
}
