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

    @Test
    fun testSegmentSplitting() {
        val initialSeg = com.bnyro.recorder.obj.MediaSegment(0L, 10000L)
        val splitPos = 4000L
        val left = initialSeg.copy(endMs = splitPos)
        val right = initialSeg.copy(startMs = splitPos)
        org.junit.Assert.assertEquals(4000L, left.durationMs)
        org.junit.Assert.assertEquals(6000L, right.durationMs)
        org.junit.Assert.assertEquals(initialSeg.durationMs, left.durationMs + right.durationMs)
    }

    @Test
    fun testSegmentReordering() {
        val seg1 = com.bnyro.recorder.obj.MediaSegment(0L, 2000L)
        val seg2 = com.bnyro.recorder.obj.MediaSegment(4000L, 7000L)
        val list = mutableListOf(seg1, seg2)
        val moved = list.removeAt(1)
        list.add(0, moved)
        org.junit.Assert.assertEquals(seg2, list[0])
        org.junit.Assert.assertEquals(seg1, list[1])
    }

    @Test
    fun testSegmentClipboard() {
        val seg = com.bnyro.recorder.obj.MediaSegment(1000L, 3000L, speed = 1.5f)
        val copied = seg.copy(id = 9999L)
        org.junit.Assert.assertEquals(seg.startMs, copied.startMs)
        org.junit.Assert.assertEquals(seg.endMs, copied.endMs)
        org.junit.Assert.assertEquals(seg.speed, copied.speed, 0.01f)
        org.junit.Assert.assertNotEquals(seg.id, copied.id)

        val list = mutableListOf(seg)
        list.add(copied)
        org.junit.Assert.assertEquals(2, list.size)
    }

    @Test
    fun testZoomBounds() {
        var zoom = 1.0f
        zoom = (zoom * 1.35f).coerceAtMost(20.0f)
        org.junit.Assert.assertEquals(1.35f, zoom, 0.01f)
        zoom = (zoom / 1.35f).coerceAtLeast(1.0f)
        org.junit.Assert.assertEquals(1.0f, zoom, 0.01f)
    }
}
