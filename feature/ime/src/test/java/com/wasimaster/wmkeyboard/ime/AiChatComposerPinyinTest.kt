package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.input.composer.CjkConfig
import com.wasimaster.wmkeyboard.core.input.composer.CjkContext
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictionaries
import com.wasimaster.wmkeyboard.core.input.composer.CjkNgrams
import com.wasimaster.wmkeyboard.core.input.composer.ConversionDictionary
import com.wasimaster.wmkeyboard.core.input.composer.PinyinComposer
import com.wasimaster.wmkeyboard.core.input.composer.PinyinSyllables
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The AI chat composer converts pinyin from its first keystroke (#942). */
@RunWith(RobolectricTestRunner::class)
class AiChatComposerPinyinTest {

    @Before
    fun loadPinyinTables() {
        PinyinSyllables.valid = setOf("wo", "ta")
        CjkDictionaries.pinyin = ConversionDictionary.parse(sequenceOf("wo\t我\t100", "ta\t他\t100"))
        CjkDictionaries.pinyinNgrams = CjkNgrams.EMPTY
        CjkConfig.pinyinPrediction = true
        CjkContext.before = ""
    }

    @After
    fun resetPinyinTables() {
        PinyinSyllables.valid = emptySet()
        CjkDictionaries.pinyin = ConversionDictionary.EMPTY
        CjkContext.before = ""
    }

    private fun keyboard(): Pair<GlideKeyboard, RecordingEditor> {
        val editor = RecordingEditor()
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        seedState(
            service,
            glideReadyState(
                glideReady = false,
                fieldNoSuggestions = false,
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
            ).copy(composer = PinyinComposer, panel = PanelMode.AI, aiChat = AiChatUi(open = true)),
        )
        // onCreate starts it on a phone; the harness skips onCreate.
        WMKeyboardService::class.java.getDeclaredMethod("startCaptureSuggestionWatcher")
            .apply { isAccessible = true }
            .invoke(service)
        return service to editor
    }

    @Test
    fun `the first keystrokes after focusing the composer compose and convert`() {
        val (service, editor) = keyboard()
        service.onAiChatAction(AiChatAction.FocusComposer)
        for (c in "wo") service.onKey(Key(label = c.toString()))
        settle { "我" in service.uiState.value.captureSuggestions }
        assertEquals("", editor.text.toString())
        assertEquals("wo", service.uiState.value.aiChat.draft)
        assertTrue(service.uiState.value.captureSuggestions.toString(), "我" in service.uiState.value.captureSuggestions)
    }
}
