package com.bnyro.recorder

import com.bnyro.recorder.util.FileRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class FileRepositoryTest {

    @Test
    fun testDefaultNamingPatternFormatting() {
        val result = FileRepository.formatFileName(
            pattern = "%d_%t",
            date = "2026-09-07",
            time = "12-30-00",
            epochMillis = 1788784200000L
        )
        assertEquals("2026-09-07_12-30-00", result)
    }

    @Test
    fun testCustomNamingPatternWithEpoch() {
        val result = FileRepository.formatFileName(
            pattern = "rec_%s_%m",
            date = "2026-09-07",
            time = "12-30-00",
            epochMillis = 1788784200000L
        )
        assertEquals("rec_1788784200_1788784200000", result)
    }

    @Test
    fun testStaticNamingPattern() {
        val result = FileRepository.formatFileName(
            pattern = "custom_recording",
            date = "2026-09-07",
            time = "12-30-00",
            epochMillis = 1788784200000L
        )
        assertEquals("custom_recording", result)
    }

    @Test
    fun testMixedPattern() {
        val result = FileRepository.formatFileName(
            pattern = "AUDIO_%d_part_%s",
            date = "2026-01-01",
            time = "00-00-00",
            epochMillis = 1767225600000L
        )
        assertEquals("AUDIO_2026-01-01_part_1767225600", result)
    }
}
