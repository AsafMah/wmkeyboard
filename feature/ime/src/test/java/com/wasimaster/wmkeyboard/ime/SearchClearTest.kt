package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The ✕ in the emoji search box empties the query and keeps the keys on it (#907). */
@RunWith(RobolectricTestRunner::class)
class SearchClearTest {

    @Test
    fun `the emoji search clear empties the query and keeps searching`() {
        val editor = RecordingEditor()
        val service = GlideKeyboard(editor)
        seedState(
            service,
            glideReadyState(
                glideReady = false,
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
            ).copy(panel = PanelMode.EMOJI, emojiQuery = "cat", emojiSearchActive = false),
        )
        service.onEmojiQueryClear()
        assertEquals("", service.uiState.value.emojiQuery)
        assertTrue(service.uiState.value.emojiSearchActive)
        assertEquals("", editor.text.toString())
    }
}
