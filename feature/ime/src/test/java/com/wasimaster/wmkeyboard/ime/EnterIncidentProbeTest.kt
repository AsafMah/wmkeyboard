package com.wasimaster.wmkeyboard.ime

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.debug.EnterIncidentRecorder
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.settings.HapticSettings
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What the persistent Enter recorder notes when the on-screen Enter reaches
 * the service: the key, the route it took, and each editor call around it,
 * before and after, with no text.
 */
@RunWith(RobolectricTestRunner::class)
class EnterIncidentProbeTest {

    private lateinit var context: Context

    private class EnterKeyboard(
        private val editor: InputConnection,
        private val info: EditorInfo,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = info
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EnterIncidentRecorder.setEnabled(context, false)
        EnterIncidentRecorder.clear()
    }

    @After
    fun tearDown() {
        EnterIncidentRecorder.setEnabled(context, false)
        EnterIncidentRecorder.clear()
    }

    private fun pressEnter(editor: InputConnection, info: EditorInfo, action: KeyAction = KeyAction.Enter) {
        val service = EnterKeyboard(editor, info)
        plantPersonalStores(service)
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(learnFromTyping = false, haptics = HapticSettings(enabled = false)),
            ),
        )
        service.onKey(Key(label = "\n", action = action))
    }

    private fun info(inputType: Int, imeOptions: Int) = EditorInfo().also {
        it.inputType = inputType
        it.imeOptions = imeOptions
    }

    /** The event and its fields after the fixed seq/t/up/pid/s prefix. */
    private fun recorded(): List<String> = EnterIncidentRecorder.lines()
        .map { it.split(' ').drop(5).joinToString(" ") }
        .filter { it.startsWith("ENTER_") || it.startsWith("IC ") }

    @Test
    fun `an action field notes the key, the route and the editor action around the call`() {
        var duringCall: List<String> = emptyList()
        val editor = object : RecordingEditor() {
            override fun performEditorAction(editorAction: Int): Boolean {
                // A field that hides the keyboard here must already find the before line.
                duringCall = recorded()
                return super.performEditorAction(editorAction)
            }
        }
        EnterIncidentRecorder.setEnabled(context, true)
        pressEnter(editor, info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))

        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), editor.editorActions)
        val lines = recorded()
        assertTrue(lines.toString(), lines.first() == "ENTER_KEY action=Enter deferred=false")
        assertTrue(lines.toString(), lines.any { it.startsWith("ENTER_ROUTE ") })
        val before = "IC op=performEditorAction phase=before actionId=${EditorInfo.IME_ACTION_SEND}"
        assertEquals(before, duringCall.last())
        val i = lines.indexOf(before)
        assertTrue(lines.toString(), i > 0)
        assertTrue(lines.toString(), lines[i + 1].startsWith("IC op=performEditorAction phase=after result="))
    }

    @Test
    fun `a field without an action notes the Enter key events it is sent`() {
        val editor = RecordingEditor()
        EnterIncidentRecorder.setEnabled(context, true)
        pressEnter(editor, info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_NONE))

        val lines = recorded()
        assertTrue(lines.toString(), lines.contains("ENTER_ROUTE route=enterKeyEvent hadComposing=false"))
        val keys = lines.filter { it.startsWith("IC op=sendKeyEvent") }
        assertEquals(lines.toString(), 4, keys.size)
        assertEquals(
            listOf(
                "IC op=sendKeyEvent phase=before key=ENTER action=0 meta=0x0",
                "IC op=sendKeyEvent phase=after result=true",
                "IC op=sendKeyEvent phase=before key=ENTER action=1 meta=0x0",
                "IC op=sendKeyEvent phase=after result=true",
            ),
            keys,
        )
    }

    @Test
    fun `a committed line break is noted as a newline, never as its text`() {
        val editor = RecordingEditor()
        EnterIncidentRecorder.setEnabled(context, true)
        // Discord's message box (#214): the break is committed, not sent as a key.
        pressEnter(
            editor,
            info(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            ),
        )
        assertEquals(listOf("\n"), editor.commits)
        val lines = recorded()
        assertTrue(lines.toString(), lines.contains("IC op=commitText phase=before newline=true empty=false"))
        assertTrue(lines.toString(), lines.contains("IC op=commitText phase=after result=true"))
    }

    @Test
    fun `nothing is wrapped or noted while the recorder is off`() {
        val editor = RecordingEditor()
        assertSame(editor, EnterIncidentProbe.enterConnection(editor))
        pressEnter(editor, info(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEND))
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), editor.editorActions)
        assertTrue(EnterIncidentRecorder.lines().isEmpty())
    }

    @Test
    fun `turning the recorder off silences a connection already wrapped`() {
        val editor = RecordingEditor()
        EnterIncidentRecorder.setEnabled(context, true)
        val wrapped = EnterIncidentProbe.enterConnection(editor)
        wrapped.finishComposingText()
        EnterIncidentRecorder.setEnabled(context, false)
        wrapped.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        val ic = recorded().filter { it.startsWith("IC ") }
        assertEquals(
            listOf("IC op=finishComposingText phase=before", "IC op=finishComposingText phase=after result=true"),
            ic,
        )
    }

    @Test
    fun `other keys sent through the wrapper are not named`() {
        val editor = RecordingEditor()
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentProbe.enterConnection(editor).sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
        val line = recorded().first { it.startsWith("IC op=sendKeyEvent phase=before") }
        assertTrue(line, line.contains(" key=other "))
        assertFalse(line, line.contains("KEYCODE_A"))
    }

    @Test
    fun `touch zone covers only the bottom edge corners`() {
        // 1000 wide, view from y=1000 to y=2000: bottom 22% and outer 25% of width.
        assertTrue(EnterIncidentProbe.inZone(950f, 1900f, 1000, 1000, 2000))
        assertTrue(EnterIncidentProbe.inZone(50f, 1900f, 1000, 1000, 2000))
        assertFalse(EnterIncidentProbe.inZone(500f, 1900f, 1000, 1000, 2000))
        assertFalse(EnterIncidentProbe.inZone(950f, 1300f, 1000, 1000, 2000))
    }
}
