package com.wasimaster.wmkeyboard.core.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnterTraceTest {
    @Before
    fun clearLog() {
        DebugLog.clear()
    }

    @After
    fun reset() {
        DebugLog.setEnterTracing(false)
        DebugLog.clear()
    }

    @Test
    fun `tracing starts disabled and ignores events`() {
        assertFalse(DebugLog.enterTraceEnabled.value)
        EnterTraceEvent.entries.forEach(DebugLog::traceEnter)
        assertTrue(DebugLog.snapshot().isEmpty())
        assertTrue(DebugLog.exportText().contains("Enter trace: disabled"))
    }

    @Test
    fun `export keeps event order and timestamps after tracing stops`() {
        val before = System.currentTimeMillis()
        DebugLog.setEnterTracing(true)
        assertTrue(DebugLog.enterTraceEnabled.value)
        assertTrue(DebugLog.exportText().contains("Enter trace: enabled"))
        DebugLog.traceEnter(EnterTraceEvent.ENTER_TAP)
        DebugLog.traceEnter(EnterTraceEvent.PERFORM_EDITOR_ACTION)
        DebugLog.traceEnter(EnterTraceEvent.WINDOW_HIDDEN)
        DebugLog.setEnterTracing(false)
        assertFalse(DebugLog.enterTraceEnabled.value)
        DebugLog.traceEnter(EnterTraceEvent.COMMIT_TEXT)
        val after = System.currentTimeMillis()

        val names = listOf("trace.enabled", "onKey.Enter", "InputConnection.performEditorAction", "onWindowHidden", "trace.disabled")
        val entries = DebugLog.snapshot()
        assertEquals(names, entries.map { it.message })
        assertTrue(entries.all { it.tag == "EnterTrace" && it.timeMillis in before..after })
        val report = DebugLog.exportText()
        val exportedNames = report.lineSequence()
            .filter { "EnterTrace: " in it }
            .map { it.substringAfter("EnterTrace: ") }
            .toList()
        assertEquals(names, exportedNames)
        assertTrue(report.contains("Enter trace: disabled"))
        assertTrue(report.contains("last 500 session entries"))
    }

    @Test
    fun `trace shares the bounded ring and clear removes it`() {
        DebugLog.setEnterTracing(true)
        DebugLog.traceEnter(EnterTraceEvent.ENTER_TAP)
        repeat(600) { DebugLog.traceEnter(EnterTraceEvent.GET_TEXT_BEFORE_CURSOR) }
        val entries = DebugLog.snapshot()
        assertEquals(500, entries.size)
        assertTrue(entries.all { it.message == "InputConnection.getTextBeforeCursor" })
        DebugLog.clear()
        assertTrue(DebugLog.snapshot().isEmpty())
    }
}
