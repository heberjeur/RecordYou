package com.bnyro.recorder

import com.bnyro.recorder.obj.AudioFormat
import com.bnyro.recorder.util.LanguageHelper
import com.bnyro.recorder.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatTest {

    @Test
    fun testMp3FormatPresent() {
        val mp3 = AudioFormat.formats.find { it.name == "MP3" }
        assertNotNull(mp3)
        assertEquals("mp3", mp3?.extension)
    }

    @Test
    fun testLanguageHelperOptions() {
        val languages = LanguageHelper.languages
        assertTrue(languages.size >= 40)
        assertEquals("", languages.first().code)
        assertTrue(languages.any { it.code == "fr" && it.name == "Français" })
        assertTrue(languages.any { it.code == "en" && it.name == "English" })
    }

    @Test
    fun testSeparateFolderPreferenceConstants() {
        assertEquals("audioTargetFolder", Preferences.audioTargetFolderKey)
        assertEquals("videoTargetFolder", Preferences.videoTargetFolderKey)
        assertEquals("targetFolder", Preferences.targetFolderKey)
        assertEquals("language", Preferences.languageKey)
    }
}
