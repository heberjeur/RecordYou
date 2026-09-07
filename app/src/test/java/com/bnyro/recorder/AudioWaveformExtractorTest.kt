package com.bnyro.recorder

import com.bnyro.recorder.util.AudioWaveformExtractor
import com.bnyro.recorder.util.PcmConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class AudioWaveformExtractorTest {

    @Test
    fun testWavWaveformExtraction() {
        val raw = File.createTempFile("test_waveform", ".wav")
        try {
            val sampleCount = 44100 * 2
            val dummyPcm = ByteArray(sampleCount * 2 + 44) { idx ->
                if (idx < 44) 0 else ((idx * 37) % 127).toByte()
            }
            raw.writeBytes(dummyPcm)

            val converter = PcmConverter(44100L, 1, 16)
            converter.writeHeader(raw)

            val channel = FileInputStream(raw).channel
            val size = channel.size()
            assertTrue(size > 44L)

            val targetBars = 160
            val dataSize = size - 44L
            val step = (dataSize / targetBars).coerceAtLeast(2L)
            val buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
            val rawAmps = FloatArray(targetBars)
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
                rawAmps[i] = f
                if (f > maxGlobal) maxGlobal = f
            }

            val normalized = rawAmps.map { (it / maxGlobal).coerceIn(0.04f, 1f) }
            assertEquals(targetBars, normalized.size)
            normalized.forEach {
                assertTrue(it in 0.04f..1.0f)
            }
        } finally {
            raw.delete()
        }
    }

    @Test
    fun testFlatWaveformDetection() {
        val flatList = List(160) { if (it < 3) 1.0f else 0.12f }
        assertTrue(AudioWaveformExtractor.isFlatWaveform(flatList))

        val uniformList = List(160) { 0.5f }
        assertTrue(AudioWaveformExtractor.isFlatWaveform(uniformList))

        val nullList: List<Float>? = null
        assertTrue(AudioWaveformExtractor.isFlatWaveform(nullList))

        val synthetic = AudioWaveformExtractor.createSyntheticWaveform(42, 160)
        assertEquals(160, synthetic.size)
        assertFalse(AudioWaveformExtractor.isFlatWaveform(synthetic))

        val speechWithPauses = List(160) {
            if (it in 20..35) 0.65f else 0.04f
        }
        assertFalse(AudioWaveformExtractor.isFlatWaveform(speechWithPauses))
    }

    @Test
    fun testWaveformFallbackBounds() {
        val targetBars = 40
        val raw = FloatArray(targetBars) { (it * 5).toFloat() }
        val maxVal = raw.maxOrNull() ?: 1f
        val normalized = raw.map { (it / maxVal).coerceIn(0.08f, 1f) }

        assertEquals(targetBars, normalized.size)
        assertEquals(0.08f, normalized.first(), 0.001f)
        assertEquals(1.0f, normalized.last(), 0.001f)
    }
}
