package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A delete swipe previews in the keyboard's own fields too, and dragging back gives it back (#880). */
@RunWith(RobolectricTestRunner::class)
class CaptureDeleteSwipeTest {

    private fun keyboard(draft: String): Pair<GlideKeyboard, RecordingEditor> {
        val editor = RecordingEditor(initial = "app text")
        val service = GlideKeyboard(editor)
        seedState(
            service,
            glideReadyState(
                glideReady = false,
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
            ).copy(panel = PanelMode.AI, aiChat = AiChatUi(open = true, composing = true, draft = draft)),
        )
        return service to editor
    }

    @Test
    fun `a word swipe selects the word and the release deletes it`() {
        val (service, editor) = keyboard("hello world")
        assertEquals(1, service.onDeleteSwipeSelect(1, byWord = true, forward = false))
        val shown = service.uiState.value.captureCaretText()!!
        assertTrue(shown.hasSelection)
        assertEquals("world", shown.selectedText)
        service.onDeleteSwipeCommit()
        assertEquals("hello ", service.uiState.value.aiChat.draft)
        assertEquals("app text", editor.text.toString())
    }

    @Test
    fun `dragging back to the start and letting go deletes nothing`() {
        val (service, _) = keyboard("hello world")
        service.onDeleteSwipeSelect(2, byWord = true, forward = false)
        service.onDeleteSwipeSelect(0, byWord = true, forward = false)
        service.onDeleteSwipeCancel()
        assertEquals("hello world", service.uiState.value.aiChat.draft)
        assertEquals(false, service.uiState.value.captureCaretText()!!.hasSelection)
    }
}
