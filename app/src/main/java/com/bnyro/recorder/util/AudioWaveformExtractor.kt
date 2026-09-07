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
        return raw.map { (it / maxGlobal).coerceIn(0.08f, 1f) }
    }

    private fun extractMediaWaveform(pfd: ParcelFileDescriptor, targetBars: Int): List<Float>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(pfd.fileDescriptor)
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
            var maxGlobal = 1f
            val stepUs = durationUs / targetBars
            val bufferInfo = MediaCodec.BufferInfo()

            try {
                for (i in 0 until targetBars) {
                    val targetUs = i * stepUs
                    extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    var peak = 0
                    var samplesRead = 0
                    while (samplesRead < 2) {
                        val inIdx = codec.dequeueInputBuffer(4000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            val sampleSize = if (inBuf != null) extractor.readSampleData(inBuf, 0) else -1
                            if (sampleSize > 0) {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            }
                        }
                        val outIdx = codec.dequeueOutputBuffer(bufferInfo, 4000L)
                        if (outIdx >= 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                val shorts = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                                while (shorts.hasRemaining()) {
                                    val s = abs(shorts.get().toInt())
                                    if (s > peak) peak = s
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            samplesRead++
                        } else {
                            samplesRead++
                        }
                    }
                    val f = peak.toFloat()
                    raw[i] = f
                    if (f > maxGlobal) maxGlobal = f
                }
            } finally {
                kotlin.runCatching { codec.stop() }
                kotlin.runCatching { codec.release() }
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
        val buf = ByteBuffer.allocate(64)
        val raw = FloatArray(targetBars)
        var maxVal = 1f
        for (i in 0 until targetBars) {
            val pos = (i * step).coerceAtMost(size - 1)
            channel.position(pos)
            buf.clear()
            val read = channel.read(buf)
            var sum = 0f
            if (read > 0) {
                buf.flip()
                while (buf.hasRemaining()) {
                    sum += abs(buf.get().toInt())
                }
                sum /= read
            }
            raw[i] = sum
            if (sum > maxVal) maxVal = sum
        }
        return raw.map { (it / maxVal).coerceIn(0.08f, 1f) }
    }
}
