package com.bnyro.recorder

import com.bnyro.recorder.enums.AudioChannels
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChannelsTest {
    @Test
    fun testValidChannelResolution() {
        assertEquals(AudioChannels.MONO, AudioChannels.fromInt(1))
        assertEquals(AudioChannels.STEREO, AudioChannels.fromInt(2))
    }

    @Test
    fun testInvalidChannelFallback() {
        assertEquals(AudioChannels.STEREO, AudioChannels.fromInt(-1))
        assertEquals(AudioChannels.STEREO, AudioChannels.fromInt(99))
    }
}
