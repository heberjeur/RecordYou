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
}
