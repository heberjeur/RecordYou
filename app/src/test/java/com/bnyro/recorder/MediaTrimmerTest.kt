package com.bnyro.recorder

import com.bnyro.recorder.util.MediaTrimmer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTrimmerTest {

    @Test
    fun testValidTrimRange() {
        assertTrue(MediaTrimmer.isValidTrimRange(0, 1000, 5000))
        assertTrue(MediaTrimmer.isValidTrimRange(1000, 2000, 2000))
        assertTrue(MediaTrimmer.isValidTrimRange(500, 600))
    }

    @Test
    fun testInvalidTrimRangeEqualStartEnd() {
        assertFalse(MediaTrimmer.isValidTrimRange(1000, 1000, 5000))
    }

    @Test
    fun testInvalidTrimRangeEndBeforeStart() {
        assertFalse(MediaTrimmer.isValidTrimRange(2000, 1000, 5000))
    }

    @Test
    fun testInvalidTrimRangeNegativeStart() {
        assertFalse(MediaTrimmer.isValidTrimRange(-1, 1000, 5000))
    }

    @Test
    fun testInvalidTrimRangeExceedsDuration() {
        assertFalse(MediaTrimmer.isValidTrimRange(0, 6000, 5000))
    }

    @Test
    fun testDetectSilencesWithLowAmplitudes() {
        val amplitudes = listOf(0.01f, 0.01f, 0.01f, 0.01f, 0.8f, 0.9f, 0.01f, 0.01f, 0.01f, 0.01f)
        val silences = MediaTrimmer.detectSilences(amplitudes, 10000L, threshold = 0.05f, minDurationMs = 2000L)
        assertTrue(silences.isNotEmpty())
    }

    @Test
    fun testDetectSilencesWithNoSilence() {
        val amplitudes = listOf(0.5f, 0.6f, 0.8f, 0.7f, 0.9f)
        val silences = MediaTrimmer.detectSilences(amplitudes, 5000L, threshold = 0.05f, minDurationMs = 1000L)
        assertTrue(silences.isEmpty())
    }

    @Test
    fun testMediaSegmentDuration() {
        val seg = com.bnyro.recorder.obj.MediaSegment(1000L, 5000L, speed = 2.0f)
        org.junit.Assert.assertEquals(2000L, seg.durationMs)
    }
}
