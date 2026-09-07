package com.bnyro.recorder

import com.bnyro.recorder.util.PcmConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PcmConverterTest {
    @Test
    fun testPcmToWavConversionHeader() {
        val sampleRate = 44100L
        val channels = 2
        val bitsPerSample = 16
        val converter = PcmConverter(sampleRate, channels, bitsPerSample)

        val dummyPcmData = ByteArray(1024) { (it % 128).toByte() }
        val inputStream = ByteArrayInputStream(dummyPcmData)
        val outputStream = ByteArrayOutputStream()

        converter.convertToWave(inputStream, outputStream, 512)
        val result = outputStream.toByteArray()

        assertTrue(result.size >= 44 + dummyPcmData.size)
        assertEquals('R'.code.toByte(), result[0])
        assertEquals('I'.code.toByte(), result[1])
        assertEquals('F'.code.toByte(), result[2])
        assertEquals('F'.code.toByte(), result[3])
        assertEquals('W'.code.toByte(), result[8])
        assertEquals('A'.code.toByte(), result[9])
        assertEquals('V'.code.toByte(), result[10])
        assertEquals('E'.code.toByte(), result[11])
    }

    @Test
    fun testEmptyPcmStreamConversion() {
        val converter = PcmConverter(48000L, 1, 16)
        val inputStream = ByteArrayInputStream(ByteArray(0))
        val outputStream = ByteArrayOutputStream()

        converter.convertToWave(inputStream, outputStream, 256)
        val result = outputStream.toByteArray()

        assertEquals(44, result.size)
        assertEquals('d'.code.toByte(), result[36])
        assertEquals('a'.code.toByte(), result[37])
        assertEquals('t'.code.toByte(), result[38])
        assertEquals('a'.code.toByte(), result[39])
    }

    @Test
    fun testMp3Encoding() {
        val raw = java.io.File.createTempFile("test_audio", ".wav")
        val mp3 = java.io.File.createTempFile("test_audio", ".mp3")
        try {
            val sampleCount = 44100 * 2 * 2
            val dummy = ByteArray(sampleCount + 44) { (it % 64).toByte() }
            raw.writeBytes(dummy)

            val converter = PcmConverter(44100L, 2, 16)
            converter.writeHeader(raw)

            val args = arrayOf(
                "-b", "128",
                "-s", "44.1",
                "-f",
                "-q", "7",
                "--silent",
                raw.absolutePath,
                mp3.absolutePath
            )
            de.sciss.jump3r.Main().run(args)
            assertTrue(mp3.exists())
            assertTrue(mp3.length() > 0L)
        } finally {
            raw.delete()
            mp3.delete()
        }
    }
}
