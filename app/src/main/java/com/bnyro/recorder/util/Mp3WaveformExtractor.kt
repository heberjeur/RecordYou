package com.bnyro.recorder.util

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.pow

object Mp3WaveformExtractor {
    private val MPEG1_BITRATES = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
    )
    private val MPEG2_BITRATES = intArrayOf(
        0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0
    )
    private val MPEG1_SAMPLE_RATES = intArrayOf(44100, 48000, 32000, 0)
    private val MPEG2_SAMPLE_RATES = intArrayOf(22050, 24000, 16000, 0)
    private val MPEG25_SAMPLE_RATES = intArrayOf(11025, 12000, 8000, 0)

    fun extract(pfd: ParcelFileDescriptor, targetBars: Int = 160): List<Float>? {
        return runCatching {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val fileSize = channel.size()
            if (fileSize <= 1024L) return null

            var audioStartOffset = 0L
            val id3Header = ByteBuffer.allocate(10)
            channel.position(0L)
            if (channel.read(id3Header) == 10) {
                id3Header.flip()
                if (id3Header.get() == 0x49.toByte() &&
                    id3Header.get() == 0x44.toByte() &&
                    id3Header.get() == 0x33.toByte()
                ) {
                    id3Header.get()
                    id3Header.get()
                    id3Header.get()
                    val b6 = id3Header.get().toInt() and 0x7F
                    val b7 = id3Header.get().toInt() and 0x7F
                    val b8 = id3Header.get().toInt() and 0x7F
                    val b9 = id3Header.get().toInt() and 0x7F
                    val tagSize = (b6 shl 21) or (b7 shl 14) or (b8 shl 7) or b9
                    audioStartOffset = (10L + tagSize).coerceAtMost(fileSize - 100L)
                }
            }

            val audioDataSize = fileSize - audioStartOffset
            if (audioDataSize <= 1024L) return null

            val step = (audioDataSize / targetBars).coerceAtLeast(64L)
            val scanBuffer = ByteBuffer.allocate(4096)
            val rawBars = FloatArray(targetBars)
            var validFrameCount = 0
            var maxGlobal = 0f

            for (i in 0 until targetBars) {
                val targetPos = audioStartOffset + i * step
                if (targetPos >= fileSize - 40L) break

                channel.position(targetPos)
                scanBuffer.clear()
                val bytesRead = channel.read(scanBuffer)
                if (bytesRead < 40) continue
                scanBuffer.flip()

                val bytes = ByteArray(bytesRead)
                scanBuffer.get(bytes)

                var syncFound = false
                var offset = 0
                while (offset <= bytesRead - 40) {
                    val b0 = bytes[offset].toInt() and 0xFF
                    val b1 = bytes[offset + 1].toInt() and 0xFF
                    if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                        val frameInfo = parseFrameAt(bytes, offset)
                        if (frameInfo != null) {
                            val linearAmp = if (frameInfo.gain > 0) {
                                2.0.pow((frameInfo.gain - 100).toDouble() / 16.0).toFloat()
                            } else 0f
                            rawBars[i] = linearAmp
                            if (linearAmp > maxGlobal) {
                                maxGlobal = linearAmp
                            }
                            validFrameCount++
                            syncFound = true
                            break
                        }
                    }
                    offset++
                }

                if (!syncFound && i > 0) {
                    rawBars[i] = rawBars[i - 1]
                }
            }

            if (validFrameCount < targetBars / 6 || maxGlobal <= 0.0001f) {
                return null
            }

            for (i in 0 until targetBars) {
                if (rawBars[i] <= 0f) {
                    val prev = if (i > 0) rawBars[i - 1] else 0f
                    val next = if (i < targetBars - 1) rawBars[i + 1] else 0f
                    rawBars[i] = ((prev + next) / 2f).coerceAtLeast(0f)
                }
            }

            val list = rawBars.map {
                val norm = (it / maxGlobal).coerceIn(0f, 1f)
                norm.pow(0.75f).coerceIn(0.02f, 1f)
            }

            if (!AudioWaveformExtractor.isFlatWaveform(list)) list else null
        }.getOrNull()
    }

    private class FrameInfo(val frameLength: Int, val gain: Int)

    private fun parseFrameAt(bytes: ByteArray, offset: Int): FrameInfo? {
        if (offset + 40 > bytes.size) return null

        val b0 = bytes[offset].toInt() and 0xFF
        val b1 = bytes[offset + 1].toInt() and 0xFF
        val b2 = bytes[offset + 2].toInt() and 0xFF
        val b3 = bytes[offset + 3].toInt() and 0xFF

        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null

        val mpegVer = (b1 ushr 3) and 3
        if (mpegVer == 1) return null

        val layer = (b1 ushr 1) and 3
        if (layer != 1) return null

        val protectionBit = b1 and 1
        val bitrateIdx = (b2 ushr 4) and 0x0F
        val sampleRateIdx = (b2 ushr 2) and 0x03
        val paddingBit = (b2 ushr 1) and 0x01
        val channelMode = (b3 ushr 6) and 0x03

        val isMpeg1 = mpegVer == 3
        val isMpeg2 = mpegVer == 2
        val isMpeg25 = mpegVer == 0

        val sampleRate = when {
            isMpeg1 -> MPEG1_SAMPLE_RATES.getOrElse(sampleRateIdx) { 0 }
            isMpeg2 -> MPEG2_SAMPLE_RATES.getOrElse(sampleRateIdx) { 0 }
            isMpeg25 -> MPEG25_SAMPLE_RATES.getOrElse(sampleRateIdx) { 0 }
            else -> 0
        }
        if (sampleRate <= 0) return null

        val bitrateKbps = if (isMpeg1) {
            MPEG1_BITRATES.getOrElse(bitrateIdx) { 0 }
        } else {
            MPEG2_BITRATES.getOrElse(bitrateIdx) { 0 }
        }
        if (bitrateKbps <= 0) return null

        val frameLength = if (isMpeg1) {
            ((144 * bitrateKbps * 1000) / sampleRate) + paddingBit
        } else {
            ((72 * bitrateKbps * 1000) / sampleRate) + paddingBit
        }
        if (frameLength < 24 || frameLength > 2880) return null

        val crcBytes = if (protectionBit == 0) 2 else 0
        val sideInfoOffset = offset + 4 + crcBytes
        val sideInfoLength = if (isMpeg1) {
            if (channelMode == 3) 17 else 32
        } else {
            if (channelMode == 3) 9 else 17
        }

        if (sideInfoOffset + sideInfoLength > bytes.size) return null

        val sideInfo = ByteArray(sideInfoLength)
        System.arraycopy(bytes, sideInfoOffset, sideInfo, 0, sideInfoLength)

        val gain = if (isMpeg1) {
            if (channelMode == 3) {
                maxOf(readBits(sideInfo, 39, 8), readBits(sideInfo, 91, 8))
            } else {
                maxOf(
                    readBits(sideInfo, 41, 8),
                    readBits(sideInfo, 93, 8),
                    readBits(sideInfo, 145, 8),
                    readBits(sideInfo, 197, 8)
                )
            }
        } else {
            if (channelMode == 3) {
                readBits(sideInfo, 30, 8)
            } else {
                maxOf(readBits(sideInfo, 39, 8), readBits(sideInfo, 87, 8))
            }
        }

        return FrameInfo(frameLength, gain)
    }

    private fun readBits(bytes: ByteArray, startBit: Int, length: Int): Int {
        var result = 0
        for (i in 0 until length) {
            val bitIndex = startBit + i
            val byteIndex = bitIndex / 8
            val bitOffset = 7 - (bitIndex % 8)
            if (byteIndex < bytes.size) {
                val bit = (bytes[byteIndex].toInt() ushr bitOffset) and 1
                result = (result shl 1) or bit
            }
        }
        return result
    }
}
