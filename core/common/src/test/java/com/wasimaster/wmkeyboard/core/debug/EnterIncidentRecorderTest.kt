package com.wasimaster.wmkeyboard.core.debug

import android.content.Context
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EnterIncidentRecorderTest {

    private lateinit var context: Context
    private lateinit var dir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EnterIncidentRecorder.resetForTest()
        dir = File(DirectBoot.deviceContext(context).filesDir, "enter-incident")
        dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        EnterIncidentRecorder.resetForTest()
        dir.deleteRecursively()
    }

    private fun restart() {
        EnterIncidentRecorder.resetForTest()
        EnterIncidentRecorder.attach(context)
    }

    private fun events() = EnterIncidentRecorder.lines().map { it.split(' ')[5] }

    @Test
    fun `nothing is written while the recorder is off`() {
        EnterIncidentRecorder.attach(context)
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter")
        assertFalse(EnterIncidentRecorder.isRecording())
        assertTrue(EnterIncidentRecorder.lines().isEmpty())
        assertFalse(File(dir, "ring-0.log").exists())
    }

    @Test
    fun `the switch and the trace survive a process restart`() {
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter", "deferred" to false)
        restart()
        assertTrue(EnterIncidentRecorder.isRecording())
        EnterIncidentRecorder.record(IncidentEvent.ENTER_ROUTE, "route" to "editorAction")
        assertEquals(
            listOf("RECORDER_ON", "PROCESS_START", "ENTER_KEY", "PROCESS_START", "ENTER_ROUTE"),
            events(),
        )
        val lines = EnterIncidentRecorder.lines()
        // A new process numbers its lines from 1 again; s= tells the processes apart.
        assertTrue(lines[3], lines[3].startsWith("seq=1 "))
        val shape = Regex("""^seq=\d+ t=\S+ up=\d+ pid=\d+ s=[0-9a-f]+ [A-Z_]+( [A-Za-z0-9._:-]+=[A-Za-z0-9._:+-]+)*$""")
        lines.forEach { assertTrue(it, shape.matches(it)) }
        // Full date with UTC offset, so a capture read in another timezone still lines up.
        assertTrue(lines[0], Regex(""" t=\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}(Z|[+-]\d\d:\d\d) """).containsMatchIn(lines[0]))
    }

    @Test
    fun `turning it off is recorded and stops the trace, also after a restart`() {
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentRecorder.setEnabled(context, false)
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter")
        restart()
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter")
        assertEquals(listOf("RECORDER_ON", "PROCESS_START", "RECORDER_OFF"), events())
        assertTrue(EnterIncidentRecorder.lines().last().endsWith(" RECORDER_OFF reason=user"))
    }

    @Test
    fun `an expired switch reads as off after a restart`() {
        EnterIncidentRecorder.setEnabled(context, true)
        File(dir, "state.properties").writeText("since=1\nuntil=2\n")
        restart()
        assertFalse(EnterIncidentRecorder.isRecording())
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter")
        assertEquals(listOf("RECORDER_ON", "PROCESS_START"), events())
    }

    @Test
    fun `field values cannot carry text`() {
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentRecorder.record(
            IncidentEvent.IC,
            "op" to "hello world, שלום <b>",
            "pkg" to "com.whatsapp",
            "n" to 3,
            "x" to 12.25f,
            "skipped" to null,
        )
        val line = EnterIncidentRecorder.lines().last()
        assertTrue(line, line.endsWith(" IC op=hello_world________b_ pkg=com.whatsapp n=3 x=12.3"))
        assertEquals(64, EnterIncidentRecorder.sanitize("a".repeat(500)).length)
    }

    @Test
    fun `the trace stays bounded and keeps the newest lines`() {
        EnterIncidentRecorder.setEnabled(context, true)
        repeat(12_000) {
            EnterIncidentRecorder.record(IncidentEvent.TOUCH, "action" to "DOWN", "x" to it)
            // Paced like real input, so the bounded queue never overflows here.
            if (it % 256 == 0) EnterIncidentRecorder.flush(20_000)
        }
        EnterIncidentRecorder.flush(20_000)
        val bytes = File(dir, "ring-0.log").length() + File(dir, "ring-1.log").length()
        assertTrue("trace is $bytes bytes", bytes <= 2 * EnterIncidentRecorder.SEGMENT_BYTES + 512)
        assertTrue(File(dir, "ring-1.log").exists())
        assertTrue(EnterIncidentRecorder.lines().last().endsWith("x=11999"))
        assertEquals(0, EnterIncidentRecorder.status().lostLines)
    }

    @Test
    fun `a saved incident says what it is and how fresh it is`() {
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter")
        val saved = EnterIncidentRecorder.saveIncident(context, "test run")
        val file = saved.file
        val text = saved.text
        assertNotNull(file)
        assertEquals(null, saved.error)
        assertEquals(text, file!!.readText())
        assertTrue(text, text.startsWith("WM Keyboard Enter incident\nversion: "))
        assertTrue(text, text.contains("package: ${context.packageName}"))
        assertTrue(text, text.contains(" trigger=test_run"))
        assertTrue(text, Regex("""saved: \d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}(Z|[+-]\d\d:\d\d) epochMs=\d+ """).containsMatchIn(text))
        assertTrue(text, text.contains("trace: complete"))
        assertTrue(text, text.contains("lost this process: 0 lines dropped on a full queue, 0 failed writes, last storage error: none"))
        assertTrue(text, text.contains("recorder: on, since "))
        assertTrue(text, Regex("""lines: 3, oldest \S+, newest \S+""").containsMatchIn(text))
        assertTrue(text, text.contains("== process exits Android recorded (newest first) =="))
        assertTrue(text, text.substringAfter("== trace ==").contains(" ENTER_KEY action=enter"))
        assertEquals(listOf(file), EnterIncidentRecorder.savedIncidents())
        assertEquals(1, EnterIncidentRecorder.status().incidents)
    }

    @Test
    fun `clear deletes the trace and saved incidents but keeps the switch`() {
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter")
        EnterIncidentRecorder.saveIncident(context, "x")
        EnterIncidentRecorder.clear()
        assertTrue(EnterIncidentRecorder.lines().isEmpty())
        assertTrue(EnterIncidentRecorder.savedIncidents().isEmpty())
        assertTrue(EnterIncidentRecorder.isRecording())
    }

    @Test
    fun `a switch that cannot be saved says so`() {
        EnterIncidentRecorder.attach(context)
        EnterIncidentRecorder.faultForTest = { op -> if (op == "state") throw java.io.IOException("disk full") }
        assertFalse(EnterIncidentRecorder.setEnabled(context, true))
        assertEquals("state: IOException: disk full", EnterIncidentRecorder.error.value)
        EnterIncidentRecorder.flush()
        assertTrue(EnterIncidentRecorder.lines().first().contains(" RECORDER_ON ") )
        assertTrue(EnterIncidentRecorder.lines().first().endsWith(" persisted=false"))
    }

    @Test
    fun `failed trace writes are counted and surfaced, not swallowed`() {
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentRecorder.flush()
        EnterIncidentRecorder.faultForTest = { op -> if (op == "append") throw java.io.IOException("disk full") }
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter")
        EnterIncidentRecorder.flush()
        val status = EnterIncidentRecorder.status()
        assertEquals(1, status.writeFailures)
        assertEquals("write: IOException: disk full", status.error)
        EnterIncidentRecorder.faultForTest = null
        val text = EnterIncidentRecorder.saveIncident(context, "x").text
        assertTrue(text, text.contains("1 failed writes, last storage error: write: IOException: disk full"))
    }

    @Test
    fun `an incident that cannot be written reports why`() {
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentRecorder.faultForTest = { op -> if (op == "save") throw java.io.IOException("disk full") }
        val saved = EnterIncidentRecorder.saveIncident(context, "x")
        assertEquals(null, saved.file)
        assertEquals("save: IOException: disk full", saved.error)
        assertTrue(EnterIncidentRecorder.savedIncidents().isEmpty())
    }

    @Test
    fun `a stalled disk drops lines into a counted gap instead of queueing without bound`() {
        EnterIncidentRecorder.setEnabled(context, true)
        EnterIncidentRecorder.flush()
        val release = java.util.concurrent.CountDownLatch(1)
        EnterIncidentRecorder.faultForTest = { op -> if (op == "append") release.await() }
        val total = EnterIncidentRecorder.QUEUE_MAX + 100
        repeat(total) { EnterIncidentRecorder.record(IncidentEvent.TOUCH, "x" to it) }
        // The writer is stuck: a snapshot now must say it is incomplete.
        assertFalse(EnterIncidentRecorder.flush(200))
        val stalled = EnterIncidentRecorder.status().lostLines
        // One line is held by the stuck writer and QUEUE_MAX wait; roughly the rest are dropped.
        assertTrue("lost $stalled", stalled in 90..100)
        release.countDown()
        EnterIncidentRecorder.faultForTest = null
        // The queue is still full the moment it is released, so drain before asking.
        val deadline = System.currentTimeMillis() + 10_000
        while (!EnterIncidentRecorder.flush() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(EnterIncidentRecorder.flush())
        EnterIncidentRecorder.record(IncidentEvent.ENTER_KEY, "action" to "enter")
        val lines = EnterIncidentRecorder.lines()
        val gaps = lines.filter { it.contains(" GAP ") }
        gaps.forEach { assertTrue(it, Regex(""" GAP lost=\d+ reason=queueFull$""").containsMatchIn(it)) }
        assertEquals(stalled, gaps.sumOf { it.substringAfter("lost=").substringBefore(' ').toInt() })
        assertTrue(lines.last().endsWith(" ENTER_KEY action=enter"))
        // Every dropped line still took its seq number, so the hole shows.
        val seqs = lines.map { it.substringAfter("seq=").substringBefore(' ').toLong() }
        assertEquals(seqs.size + stalled, (seqs.last() - seqs.first() + 1).toInt())
    }
}