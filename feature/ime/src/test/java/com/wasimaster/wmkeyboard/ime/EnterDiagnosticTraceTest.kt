package com.wasimaster.wmkeyboard.ime

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.debug.EnterTraceEvent
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
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
class EnterDiagnosticTraceTest {
    @Before
    fun reset() {
        DebugLog.setEnterTracing(false)
        DebugLog.clear()
        ShadowLog.setLoggable(TracingInputConnection.TAG, Log.INFO)
    }

    @After
    fun stop() {
        DebugLog.setEnterTracing(false)
        DebugLog.clear()
    }

    private class EnterKeyboard(
        private val editor: InputConnection,
    ) : WMKeyboardService() {
        init { attachBaseContext(RuntimeEnvironment.getApplication()) }
        override fun getCurrentInputConnection(): InputConnection = editor
        override fun getCurrentInputEditorInfo(): EditorInfo = EditorInfo().also {
            it.inputType = InputType.TYPE_CLASS_TEXT
            it.imeOptions = EditorInfo.IME_ACTION_SEND
        }
    }

    @Test
    fun `phone switch wraps an existing connection and unwraps it when disabled`() {
        val service = WMKeyboardService()
        val editor = RecordingEditor()
        val traced = WMKeyboardService::class.java.getDeclaredMethod("traced", InputConnection::class.java)
            .apply { isAccessible = true }
        assertSame(editor, traced.invoke(service, editor))
        DebugLog.setEnterTracing(true)
        val wrapper = traced.invoke(service, editor)
        assertTrue(wrapper is TracingInputConnection)
        assertSame(wrapper, traced.invoke(service, editor))
        DebugLog.setEnterTracing(false)
        assertSame(editor, traced.invoke(service, editor))
        // Even a caller still holding the old wrapper must stop recording.
        DebugLog.clear()
        (wrapper as InputConnection).finishComposingText()
        assertTrue(DebugLog.snapshot().isEmpty())
    }

    @Test
    fun `Enter dispatch precedes the editor call and its hide callback`() {
        val editor = object : RecordingEditor() {
            override fun performEditorAction(editorAction: Int): Boolean {
                assertEquals("InputConnection.performEditorAction", DebugLog.snapshot().last().message)
                DebugLog.traceEnter(EnterTraceEvent.WINDOW_HIDDEN)
                return super.performEditorAction(editorAction)
            }
        }
        val service = EnterKeyboard(TracingInputConnection(editor))
        plantPersonalStores(service)
        seedState(
            service,
            glideReadyState(
                settings = KeyboardSettings(
                    learnFromTyping = false,
                    haptics = HapticSettings(enabled = false),
                ),
            ),
        )
        DebugLog.setEnterTracing(true)
        service.onKey(Key(label = "\n", action = KeyAction.Enter))
        val names = DebugLog.snapshot().filter { it.tag == "EnterTrace" }.map { it.message }
        val expected = listOf("onKey.Enter", "onEnter", "InputConnection.performEditorAction", "onWindowHidden")
        assertEquals(expected, names.filter { it in expected })
        assertEquals(listOf(EditorInfo.IME_ACTION_SEND), editor.editorActions)
        assertTrue(editor.commits.isEmpty())
    }

    @Test
    fun `all traced calls forward unchanged and log only names never arguments or results`() {
        val content = "editor-content-must-not-leak"
        val extracted = ExtractedText().apply { text = content }
        val forwarded = mutableListOf<String>()
        val editor = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            forwarded += method.name
            when (method.name) {
                "commitText", "setComposingText" -> {
                    assertSame(content, args!![0])
                    false
                }
                "performPrivateCommand" -> {
                    assertEquals(content, args!![0])
                    assertEquals(content, (args[1] as Bundle).getString("payload"))
                    false
                }
                "getTextBeforeCursor", "getTextAfterCursor", "getSelectedText" -> content
                "getExtractedText" -> extracted
                else -> false
            }
        } as InputConnection
        val ic = TracingInputConnection(editor)
        DebugLog.setEnterTracing(true)
        DebugLog.clear()
        assertFalse(ic.beginBatchEdit())
        assertFalse(ic.commitText(content, 1))
        assertFalse(ic.setComposingText(content, 1))
        assertFalse(ic.setComposingRegion(2, 4))
        assertFalse(ic.finishComposingText())
        assertFalse(ic.setSelection(2, 4))
        assertFalse(ic.deleteSurroundingText(2, 4))
        assertFalse(ic.deleteSurroundingTextInCodePoints(2, 4))
        assertFalse(ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
        assertFalse(ic.performEditorAction(EditorInfo.IME_ACTION_SEND))
        assertFalse(ic.performContextMenuAction(android.R.id.copy))
        assertFalse(ic.performPrivateCommand(content, Bundle().apply { putString("payload", content) }))
        assertFalse(ic.requestCursorUpdates(1))
        assertSame(content, ic.getTextBeforeCursor(10, 0))
        assertSame(content, ic.getTextAfterCursor(10, 0))
        assertSame(content, ic.getSelectedText(0))
        assertSame(extracted, ic.getExtractedText(ExtractedTextRequest(), 0))
        assertFalse(ic.endBatchEdit())

        val names = DebugLog.snapshot().map { it.message }
        assertEquals(forwarded.map { "InputConnection.$it" }, names)
        assertEquals(18, names.size)
        val report = DebugLog.exportText()
        assertFalse(report.contains(content))
        assertTrue(DebugLog.snapshot().all { entry ->
            EnterTraceEvent.entries.any { it.methodName == entry.message }
        })
        assertTrue(ShadowLog.getLogsForTag(TracingInputConnection.TAG).isEmpty())
    }
}
