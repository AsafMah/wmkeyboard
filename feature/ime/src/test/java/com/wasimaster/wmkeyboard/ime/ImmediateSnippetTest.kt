package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.prediction.SuggestionEngine
import com.wasimaster.wmkeyboard.core.prediction.Trie
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SuggestionStripSettings
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Snippets that expand on the keystroke that finishes their trigger, with no
 * space after it, and the two ways of keeping the trigger as typed: the escape
 * character in front of it, and one backspace after it.
 *
 * Most of these type into the harness's default field, which composes nothing,
 * so every letter lands through the plain commit; one runs the composing
 * buffer, which is the other place a letter can land.
 */
@RunWith(RobolectricTestRunner::class)
class ImmediateSnippetTest {

    private val space = Key(" ", action = KeyAction.Space)
    private val backspace = Key("", action = KeyAction.Delete)

    private fun keyboard(
        settings: KeyboardSettings = KeyboardSettings(learnFromTyping = false),
        secureField: Boolean = false,
        snippets: List<Snippet>,
    ): Pair<GlideKeyboard, RecordingEditor> {
        val editor = RecordingEditor()
        val (service, _, _) = glideKeyboard(
            state = glideReadyState(settings = settings, secureField = secureField),
            editor = editor,
        )
        val store = service.snippets()
        snippets.forEach { store.add(it) }
        return service to editor
    }

    private fun WMKeyboardService.snippets(): SnippetStore {
        val field = WMKeyboardService::class.java.getDeclaredField("snippetStore")
        field.isAccessible = true
        return field.get(this) as SnippetStore
    }

    private fun WMKeyboardService.type(text: String) =
        text.forEach { if (it == ' ') onKey(space) else onText(it.toString()) }

    private fun btw(insideWords: Boolean = false) = Snippet(
        id = 0,
        label = "BTW",
        text = "by the way",
        trigger = "btw",
        immediate = true,
        insideWords = insideWords,
    )

    @Test
    fun `fires on the trigger's last letter with nothing after it`() {
        val (service, editor) = keyboard(snippets = listOf(btw()))
        service.type("ok btw")
        assertEquals("ok by the way", editor.text.toString())
    }

    @Test
    fun `does not fire in the middle of a word`() {
        val (service, editor) = keyboard(snippets = listOf(btw()))
        service.type("abtw")
        assertEquals("abtw", editor.text.toString())
    }

    @Test
    fun `fires in the middle of a word when the snippet allows it`() {
        val (service, editor) = keyboard(snippets = listOf(btw(insideWords = true)))
        service.type("abtw")
        assertEquals("aby the way", editor.text.toString())
    }

    @Test
    fun `a trigger with a lead-in fires on its last letter`() {
        val addr = Snippet(id = 0, label = "Address", text = "1 Main St", trigger = ";;addr", immediate = true)
        val (service, editor) = keyboard(snippets = listOf(addr))
        service.type(";;addr")
        assertEquals("1 Main St", editor.text.toString())
    }

    @Test
    fun `the escape character keeps the trigger and goes itself`() {
        val (service, editor) = keyboard(snippets = listOf(btw()))
        service.type("\\btw")
        assertEquals("btw", editor.text.toString())
        // Still standing when the word ends: the space does not expand it.
        service.onKey(space)
        assertEquals("btw ", editor.text.toString())
    }

    @Test
    fun `with the escape off the character in front is just text`() {
        val settings = KeyboardSettings(
            learnFromTyping = false,
            suggestionStrip = SuggestionStripSettings(snippetEscape = ""),
        )
        val (service, editor) = keyboard(settings, snippets = listOf(btw()))
        service.type("\\btw")
        assertEquals("\\by the way", editor.text.toString())
    }

    @Test
    fun `one backspace puts the trigger back, and the space leaves it alone`() {
        val (service, editor) = keyboard(snippets = listOf(btw()))
        service.type("btw")
        assertEquals("by the way", editor.text.toString())
        service.onKey(backspace)
        assertEquals("btw", editor.text.toString())
        service.onKey(space)
        assertEquals("btw ", editor.text.toString())
    }

    @Test
    fun `an ordinary trigger still waits for the space`() {
        val omw = Snippet(id = 0, label = "OMW", text = "on my way", trigger = "omw")
        val (service, editor) = keyboard(snippets = listOf(omw))
        service.type("omw")
        assertEquals("omw", editor.text.toString())
        service.onKey(space)
        assertEquals("on my way ", editor.text.toString())
    }

    @Test
    fun `stays out of a password field unless snippets are allowed there`() {
        val (refused, refusedField) = keyboard(secureField = true, snippets = listOf(btw()))
        refused.type("btw")
        assertEquals("btw", refusedField.text.toString())

        val allowed = KeyboardSettings(
            learnFromTyping = false,
            suggestionStrip = SuggestionStripSettings(snippetsInSecureFields = true),
        )
        val (service, editor) = keyboard(allowed, secureField = true, snippets = listOf(btw()))
        service.type("btw")
        assertEquals("by the way", editor.text.toString())
    }

    @Test
    fun `fires out of the composing buffer and the next letter starts afresh`() {
        val editor = RecordingEditor()
        val service = GlideKeyboard(editor)
        plantPersonalStores(service)
        val engineField = WMKeyboardService::class.java.getDeclaredField("suggestionEngine")
        engineField.isAccessible = true
        engineField.set(
            service,
            SuggestionEngine(
                Trie().apply { insert("the", 9000) },
                BengaliPhoneticIndex(emptyList()),
                UserLexicon(null),
            ),
        )
        seedState(service, glideReadyState(fieldNoSuggestions = false))
        service.snippets().add(btw())

        service.type("btw")
        assertEquals("by the way", editor.text.toString())
        service.type("x")
        assertEquals("by the wayx", editor.text.toString())
    }
}
