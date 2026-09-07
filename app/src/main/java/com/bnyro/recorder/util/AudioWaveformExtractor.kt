package com.bnyro.recorder.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

object AudioWaveformExtractor {
    fun extractWaveform(context: Context, uri: Uri, targetBars: Int = 50): List<Float>? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val path = uri.path.orEmpty().lowercase()
                if (path.endsWith(".wav")) {
                    extractWavWaveform(pfd, targetBars) ?: extractMediaWaveform(pfd, targetBars) ?: fallbackWaveform(pfd, targetBars)
                } else {
                    extractMediaWaveform(pfd, targetBars) ?: fallbackWaveform(pfd, targetBars)
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
        return raw.map { (it / maxGlobal).coerceIn(0.08f, 1f) }
    }

    private fun extractMediaWaveform(pfd: ParcelFileDescriptor, targetBars: Int): List<Float>? {
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
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) return null

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
            val maxBuffersToDecode = 3000

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
                            val timeUs = bufferInfo.presentationTimeUs
                            val barIdx = ((timeUs * targetBars) / durationUs).toInt().coerceIn(0, targetBars - 1)
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
                    raw[i] = ((prev + next) / 2f).coerceAtLeast(maxGlobal * 0.08f)
                }
            }

            return raw.map { (it / maxGlobal).coerceIn(0.08f, 1f) }
        } catch (e: Exception) {
            return null
        } finally {
            kotlin.runCatching { extractor.release() }
        }
    }

    private fun fallbackWaveform(pfd: ParcelFileDescriptor, targetBars: Int): List<Float> {
        val channel = FileInputStream(pfd.fileDescriptor).channel
        val size = channel.size()
        if (size <= 0L) return List(targetBars) { 0.15f }
        val step = (size / targetBars).coerceAtLeast(1L)
        val chunkSize = 512.coerceAtMost(step.toInt().coerceAtLeast(64))
        val buf = ByteBuffer.allocate(chunkSize)
        val raw = FloatArray(targetBars)
        var maxVal = 1f
        var minVal = Float.MAX_VALUE
        for (i in 0 until targetBars) {
            val pos = (i * step).coerceAtMost(size - chunkSize)
            channel.position(pos)
            buf.clear()
            val read = channel.read(buf)
            if (read > 0) {
                buf.flip()
                var sum = 0.0
                var sumSq = 0.0
                var count = 0
                while (buf.hasRemaining()) {
                    val b = buf.get().toInt() and 0xFF
                    sum += b
                    sumSq += b * b
                    count++
                }
                val mean = sum / count
                val variance = (sumSq / count) - (mean * mean)
                val energy = sqrt(variance.coerceAtLeast(0.0)).toFloat()
                raw[i] = energy
                if (energy > maxVal) maxVal = energy
                if (energy < minVal) minVal = energy
            }
        }
        val diff = (maxVal - minVal).coerceAtLeast(1f)
        return raw.map { ((it - minVal) / diff).coerceIn(0.12f, 1f) }
    }
}
