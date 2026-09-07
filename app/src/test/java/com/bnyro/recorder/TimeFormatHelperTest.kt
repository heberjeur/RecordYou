package com.bnyro.recorder

import com.bnyro.recorder.util.TimeFormatHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeFormatHelperTest {

    @Test
    fun formatsZeroSeconds() {
        assertEquals("00:00", TimeFormatHelper.formatDuration(0))
    }

    @Test
    fun formatsNegativeSeconds() {
        assertEquals("00:00", TimeFormatHelper.formatDuration(-5))
    }

    @Test
    fun formatsMinutesAndSeconds() {
        assertEquals("01:25", TimeFormatHelper.formatDuration(85))
        assertEquals("09:05", TimeFormatHelper.formatDuration(545))
    }

    @Test
    fun formatsHoursMinutesAndSeconds() {
        assertEquals("1:02:03", TimeFormatHelper.formatDuration(3723))
        assertEquals("10:00:00", TimeFormatHelper.formatDuration(36000))
    }

    @Test
    fun outputsStrictAsciiDigits() {
        val formatted = TimeFormatHelper.formatDuration(12345)
        assertTrue(formatted.all { it in '0'..'9' || it == ':' })
    }
}
