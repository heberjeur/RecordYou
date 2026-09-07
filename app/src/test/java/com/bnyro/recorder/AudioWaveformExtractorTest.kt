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
            val dummyPcm = ByteArray(sampleCount * 2 + 44)
            val pcmBuf = ByteBuffer.wrap(dummyPcm).order(ByteOrder.LITTLE_ENDIAN)
            pcmBuf.position(44)
            for (i in 0 until sampleCount) {
                val envelope = if ((i / 10000) % 2 == 0) 1.0 else 0.08
                val amp = (kotlin.math.sin(i * 0.1) * 20000 * envelope).toInt().toShort()
                pcmBuf.putShort(amp)
            }
            raw.writeBytes(dummyPcm)

            val converter = PcmConverter(44100L, 1, 16)
            converter.writeHeader(raw)

            val waveform = AudioWaveformExtractor.extractWavWaveformFromFile(raw, 160)
            assertTrue(waveform != null)
            assertEquals(160, waveform?.size)
            assertFalse(AudioWaveformExtractor.isFlatWaveform(waveform))
            waveform?.forEach {
                assertTrue(it in 0.02f..1.0f)
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

    @Test
    fun testSaturatedWaveformDetection() {
        val saturated = List(100) { if (it < 75) 0.85f else 0.20f }
        assertTrue(AudioWaveformExtractor.isSaturatedWaveform(saturated))

        val normal = List(100) { if (it < 30) 0.85f else 0.20f }
        assertFalse(AudioWaveformExtractor.isSaturatedWaveform(normal))

        val empty: List<Float>? = emptyList()
        assertTrue(AudioWaveformExtractor.isSaturatedWaveform(empty))
    }
}
