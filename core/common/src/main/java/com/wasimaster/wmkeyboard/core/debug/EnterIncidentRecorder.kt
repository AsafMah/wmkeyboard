package com.wasimaster.wmkeyboard.core.debug

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The closed set of things the Enter incident recorder can say happened. */
enum class IncidentEvent {
    PROCESS_START,
    PROCESS_EXIT,
    RECORDER_ON,
    RECORDER_OFF,
    SERVICE_CREATE,
    SERVICE_DESTROY,
    START_INPUT,
    START_INPUT_VIEW,
    FINISH_INPUT,
    FINISH_INPUT_VIEW,
    SHOW_REQUESTED,
    WINDOW_SHOWN,
    WINDOW_HIDDEN,
    HIDE_WINDOW,
    REQUEST_HIDE_SELF,
    INSETS,
    WINDOW_VIEWS,
    TOUCH,
    SYSTEM_KEY,
    ENTER_KEY,
    ENTER_ROUTE,
    IC,
    BACK_CALLBACK,
    GAP,
}

/**
 * An opt-in, on-disk trace of what happens around the Enter key, kept so an
 * intermittent "Enter closed the keyboard" can be saved *after* it happens.
 *
 * What makes it different from [DebugLog]'s ring:
 *  * the switch and the trace live on disk, so an IME crash, a low-memory kill
 *    or a service restart neither turns it off nor loses what led up to it;
 *  * every line carries the process, a per-process session id, a sequence
 *    number and both clocks, so a stale section cannot pass for a fresh one;
 *  * the recorder stops by itself [ENABLED_FOR_MILLIS] after it was switched on.
 *
 * Privacy is by construction: events are a closed enum and field values are
 * numbers, booleans or short tokens squeezed to `[A-Za-z0-9._:-]`. Call sites
 * pass geometry, flags, result codes and package names, never text.
 */
object EnterIncidentRecorder {

    const val LOG_TAG = "WMKB-Enter"

    /** Each ring segment's cap; two segments are kept, so the trace never tops ~2x this. */
    internal const val SEGMENT_BYTES = 192 * 1024L

    internal val ENABLED_FOR_MILLIS = TimeUnit.DAYS.toMillis(7)

    /** Saved incidents kept on the phone; older ones are deleted as new ones land. */
    const val INCIDENTS_KEPT = 5

    private const val DIR = "enter-incident"
    private const val STATE_FILE = "state.properties"
    private const val STATE_PENDING = "state.properties.new"
    private const val RING = "ring-0.log"
    private const val RING_OLD = "ring-1.log"
    private const val INCIDENT_DIR = "incidents"
    private const val TOKEN_MAX = 64

    /** Lines waiting for the disk; past this a line is dropped and counted rather than queued. */
    internal const val QUEUE_MAX = 512

    // Bounded: a stalled disk must not turn the recorder into the heap growth
    // it may be there to explain. A dropped line still went to logcat, still
    // took its seq number (so the gap shows), and is reported in a GAP line.
    private val writer = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE_MAX),
        { r -> Thread(r, "enter-incident").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private val lostPending = AtomicInteger()
    private val lostTotal = AtomicInteger()
    private val writeFailures = AtomicInteger()

    private val errorFlow = MutableStateFlow<String?>(null)

    /** The latest storage failure, for the settings screen; null while everything has reached the disk. */
    val error: StateFlow<String?> = errorFlow.asStateFlow()

    /** Throws in place of a disk operation, named by [op]. Tests only. */
    @Volatile
    internal var faultForTest: ((op: String) -> Unit)? = null

    @Volatile
    private var dir: File? = null

    @Volatile
    private var appContext: Context? = null

    private val enabledFlow = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()

    @Volatile
    private var enabledUntil = 0L

    @Volatile
    private var enabledSince = 0L

    /** Random per process start, so two processes with a reused pid stay apart. */
    val session: String = java.lang.Long.toHexString(java.util.Random().nextLong() and 0xffffffL)

    private var seq = 0L

    /**
     * Points the recorder at device-protected storage and reads its switch.
     * Idempotent; the keyboard service and the settings app both call it. The
     * first call in a recording process writes the process-start line and any
     * process exits Android recorded since the last one seen.
     */
    @Synchronized
    fun attach(context: Context) {
        if (dir != null) return
        appContext = context.applicationContext ?: context
        val target = try {
            File(DirectBoot.deviceContext(context).filesDir, DIR).apply {
                if (!isDirectory && !mkdirs()) throw java.io.IOException("could not create the recorder directory")
            }
        } catch (e: Exception) {
            fail("attach", e)
            return
        }
        dir = target
        val state = readState(target)
        enabledSince = state.getProperty("since")?.toLongOrNull() ?: 0L
        enabledUntil = state.getProperty("until")?.toLongOrNull() ?: 0L
        enabledFlow.value = enabledUntil > System.currentTimeMillis()
        if (enabledFlow.value) recordProcessStart(context, state)
    }

    /**
     * Switches recording on for [ENABLED_FOR_MILLIS], or off; persisted across
     * restarts. False when the switch could not be saved: it then holds only
     * until this process ends, and [error] says why.
     */
    @Synchronized
    fun setEnabled(context: Context, on: Boolean): Boolean {
        attach(context)
        val target = dir ?: return fail("setEnabled", IllegalStateException("no storage directory")).let { false }
        val now = System.currentTimeMillis()
        return if (on) {
            enabledSince = now
            enabledUntil = now + ENABLED_FOR_MILLIS
            enabledFlow.value = true
            val saved = writeState(target) {
                setProperty("since", enabledSince.toString())
                setProperty("until", enabledUntil.toString())
            }
            record(IncidentEvent.RECORDER_ON, "until" to timestamp(enabledUntil), "persisted" to saved)
            recordProcessStart(context, readState(target))
            saved
        } else {
            if (enabledFlow.value) record(IncidentEvent.RECORDER_OFF, "reason" to "user")
            enabledFlow.value = false
            enabledUntil = 0L
            writeState(target) { setProperty("until", "0") }
        }
    }

    /** Whether a line written now would be kept; cheap enough for every touch. */
    fun isRecording(): Boolean {
        if (!enabledFlow.value) return false
        if (System.currentTimeMillis() < enabledUntil) return true
        expire()
        return false
    }

    @Synchronized
    private fun expire() {
        if (!enabledFlow.value) return
        record(IncidentEvent.RECORDER_OFF, "reason" to "expired", force = true)
        enabledFlow.value = false
        dir?.let { target -> writeState(target) { setProperty("until", "0") } }
    }

    fun record(event: IncidentEvent, vararg fields: Pair<String, Any?>) = record(event, *fields, force = false)

    @Synchronized
    private fun record(event: IncidentEvent, vararg fields: Pair<String, Any?>, force: Boolean) {
        if (!force && !isRecording()) return
        val target = dir ?: return
        val now = System.currentTimeMillis()
        val lost = lostPending.getAndSet(0)
        val gap = if (lost > 0) line(++seq, now, IncidentEvent.GAP, arrayOf("lost" to lost, "reason" to "queueFull")) else null
        val line = line(++seq, now, event, fields)
        runCatching { Log.i(LOG_TAG, line) }
        try {
            writer.execute { append(target, if (gap != null) "$gap\n$line" else line) }
        } catch (_: RejectedExecutionException) {
            // The unwritten gap marker gives its number back, so holes in seq count only lost lines.
            if (gap != null) seq--
            lostPending.addAndGet(lost + 1)
            lostTotal.incrementAndGet()
        }
    }

    private fun line(seq: Long, now: Long, event: IncidentEvent, fields: Array<out Pair<String, Any?>>) = buildString {
        append("seq=").append(seq)
        append(" t=").append(timestamp(now))
        append(" up=").append(SystemClock.uptimeMillis())
        append(" pid=").append(Process.myPid())
        append(" s=").append(session)
        append(' ').append(event.name)
        for ((key, value) in fields) {
            if (value == null) continue
            append(' ').append(sanitize(key)).append('=').append(format(value))
        }
    }

    private fun append(target: File, text: String) {
        try {
            faultForTest?.invoke("append")
            val ring = File(target, RING)
            if (ring.length() > SEGMENT_BYTES) {
                val old = File(target, RING_OLD)
                old.delete()
                if (!ring.renameTo(old)) throw java.io.IOException("could not rotate the trace")
            }
            ring.appendText(text + "\n")
        } catch (e: Exception) {
            writeFailures.incrementAndGet()
            fail("write", e)
        }
    }

    private fun fail(op: String, e: Exception) {
        val message = "$op: ${e.javaClass.simpleName}${e.message?.let { ": " + it.take(160) } ?: ""}"
        errorFlow.value = message
        runCatching { Log.w(LOG_TAG, "recorder storage failure, $message") }
    }

    /**
     * Waits for the lines already queued to reach the disk. False when they
     * did not within [timeoutMillis]: what is on disk then misses the newest.
     */
    fun flush(timeoutMillis: Long = 2_000): Boolean = try {
        writer.submit {}.get(timeoutMillis, TimeUnit.MILLISECONDS)
        true
    } catch (_: Exception) {
        false
    }

    /** Every line still kept, oldest first, and whether the queue drained before they were read. */
    fun snapshot(): Pair<List<String>, Boolean> {
        val complete = flush()
        return readKept() to complete
    }

    /** What is on disk now, without waiting for the queue. */
    private fun readKept(): List<String> {
        val target = dir ?: return emptyList()
        return listOf(RING_OLD, RING).flatMap { name ->
            val file = File(target, name)
            if (!file.exists()) {
                emptyList()
            } else {
                try {
                    faultForTest?.invoke("read")
                    file.readLines()
                } catch (e: Exception) {
                    fail("read", e)
                    emptyList()
                }
            }
        }.filter { it.isNotBlank() }
    }

    /** Every line still kept, oldest first. */
    fun lines(): List<String> = snapshot().first

    /** Deletes the trace and every incident saved on the phone; the switch is left as it is. */
    @Synchronized
    fun clear(): Boolean {
        val target = dir ?: return false
        // record() shares this lock, so once the queue drains nothing new can land until the files are gone.
        // Undrained, a queued line would recreate the trace right after it was reported deleted.
        if (!flush()) {
            fail("clear", java.io.IOException("the write queue did not drain; nothing was deleted"))
            return false
        }
        val deleted = listOf(File(target, RING), File(target, RING_OLD)).all { !it.exists() || it.delete() } and
            File(target, INCIDENT_DIR).let { !it.exists() || it.deleteRecursively() }
        val stateSaved = writeState(target) { remove("lastExit") }
        if (deleted && stateSaved) {
            writeFailures.set(0)
            lostTotal.set(0)
            errorFlow.value = null
        } else if (!deleted) {
            fail("clear", java.io.IOException("some recorder files could not be deleted"))
        }
        return deleted && stateSaved
    }

    /** Saved incidents on the phone, newest first. */
    fun savedIncidents(): List<File> {
        val target = dir ?: return emptyList()
        return File(target, INCIDENT_DIR).listFiles()?.sortedByDescending { it.name }.orEmpty()
    }

    data class Status(
        val enabled: Boolean,
        val since: Long,
        val until: Long,
        val lineCount: Int,
        val bytes: Long,
        val incidents: Int,
        val lostLines: Int,
        val writeFailures: Int,
        val error: String?,
    )

    fun status(): Status {
        val target = dir
        // Not waiting for the writer: this is read on the main thread, also when the disk stalls.
        val lines = readKept()
        return Status(
            enabled = isRecording(),
            since = enabledSince,
            until = enabledUntil,
            lineCount = lines.size,
            bytes = target?.let { File(it, RING).length() + File(it, RING_OLD).length() } ?: 0L,
            incidents = target?.let { File(it, INCIDENT_DIR).listFiles()?.size } ?: 0,
            lostLines = lostTotal.get(),
            writeFailures = writeFailures.get(),
            error = errorFlow.value,
        )
    }

    /** A saved incident: the file kept on the phone (null if writing it failed), its text, and why it failed. */
    data class Saved(val file: File?, val text: String, val error: String?)

    /**
     * Freezes the trace into a saved incident and returns its text and file.
     * The header says when it was saved, from which process, and how old the
     * newest line is, so a capture taken after a restart says so itself.
     */
    @Synchronized
    fun saveIncident(context: Context, trigger: String): Saved {
        attach(context)
        val now = System.currentTimeMillis()
        val (lines, complete) = snapshot()
        val text = buildString {
            appendLine("WM Keyboard Enter incident")
            appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${DebugLog.flavorTag} ${BuildConfig.BUILD_TYPE}")
            appendLine("package: ${context.packageName}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(
                "saved: ${timestamp(now)} epochMs=$now up=${SystemClock.uptimeMillis()} pid=${Process.myPid()} " +
                    "s=$session trigger=${sanitize(trigger)}",
            )
            appendLine("boot count: ${bootCount(context)}")
            appendLine(
                "recorder: ${if (isRecording()) "on" else "off"}, since ${stamp(enabledSince)}, " +
                    "stops ${stamp(enabledUntil)}",
            )
            val newest = lines.lastOrNull()?.let(::lineTime)
            appendLine("lines: ${lines.size}, oldest ${lines.firstOrNull()?.let(::lineTime) ?: "-"}, newest ${newest ?: "-"}")
            appendLine(
                "trace: " + if (complete) {
                    "complete (every queued line reached the disk before this was read)"
                } else {
                    "INCOMPLETE (queued lines had not reached the disk in time; the newest may be missing)"
                },
            )
            appendLine(
                "lost this process: ${lostTotal.get()} lines dropped on a full queue, " +
                    "${writeFailures.get()} failed writes, last storage error: ${errorFlow.value ?: "none"}",
            )
            appendLine(
                "Lines carry seq (per process; a skipped number is a lost line), t (wall clock with UTC offset), " +
                    "up (uptime ms, the clock of touch and key event times), pid and s (random per process start). " +
                    "A different s means a different process.",
            )
            appendLine()
            appendLine("== process exits Android recorded (newest first) ==")
            exitReasons(context, 8).forEach { appendLine(it) }
            appendLine()
            appendLine("== trace ==")
            lines.forEach { appendLine(it) }
        }
        val target = dir ?: return Saved(null, text, errorFlow.value ?: "no storage directory")
        return try {
            faultForTest?.invoke("save")
            val incidents = File(target, INCIDENT_DIR).apply { mkdirs() }
            val name = "enter-incident-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".txt"
            val file = File(incidents, name)
            file.writeText(text)
            incidents.listFiles()
                ?.sortedByDescending { it.name }
                ?.drop(INCIDENTS_KEPT)
                ?.forEach { it.delete() }
            Saved(file, text, null)
        } catch (e: Exception) {
            fail("save", e)
            Saved(null, text, errorFlow.value)
        }
    }

    private fun recordProcessStart(context: Context, state: Properties) {
        record(
            IncidentEvent.PROCESS_START,
            "process" to DebugLog.processName().substringAfter(':', "main"),
            "version" to BuildConfig.VERSION_CODE,
            "boot" to bootCount(context),
            "upSinceBoot" to SystemClock.elapsedRealtime(),
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val lastSeen = state.getProperty("lastExit")?.toLongOrNull() ?: 0L
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 10)
        }.getOrDefault(emptyList())
        val fresh = exits.filter { it.timestamp > lastSeen }.sortedBy { it.timestamp }
        fresh.forEach { exit -> record(IncidentEvent.PROCESS_EXIT, *exitFields(exit).toTypedArray()) }
        val newest = exits.maxOfOrNull { it.timestamp } ?: return
        if (newest > lastSeen) dir?.let { target -> writeState(target) { setProperty("lastExit", newest.toString()) } }
    }

    private fun exitReasons(context: Context, max: Int): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return listOf("(needs Android 11)")
        val exits = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, max)
        }.getOrDefault(emptyList())
        if (exits.isEmpty()) return listOf("(none reported)")
        return exits.map { exit -> exitFields(exit).joinToString(" ") { (k, v) -> "$k=${v?.let(::format) ?: "-"}" } }
    }

    private fun exitFields(exit: ApplicationExitInfo): List<Pair<String, Any?>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return listOf(
            "at" to timestamp(exit.timestamp),
            "exitPid" to exit.pid,
            "process" to exit.processName.substringAfter(':', "main"),
            "reason" to exitReasonName(exit.reason),
            "status" to exit.status,
            "importance" to exit.importance,
            "pssKb" to exit.pss,
            "rssKb" to exit.rss,
        )
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        else -> "R$reason"
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /**
     * The saved switch. A missing file is a recorder never switched on; one
     * that cannot be read is reported, not taken silently for "off".
     */
    private fun readState(target: File): Properties = Properties().apply {
        val live = File(target, STATE_FILE)
        val pending = File(target, STATE_PENDING)
        val source = when {
            // A pending file beside a live one is a write that never committed.
            live.exists() -> live.also { pending.delete() }
            // Committed and synced, but killed between removing the old file and the rename.
            pending.exists() -> pending
            else -> return@apply
        }
        try {
            source.inputStream().use(::load)
        } catch (e: Exception) {
            fail("stateRead", e)
        }
    }

    /**
     * Writes the switch so a process killed mid-write leaves the previous one,
     * never a truncated file: the new state is written and synced aside, then
     * renamed over the live file.
     */
    private fun writeState(target: File, change: Properties.() -> Unit): Boolean {
        val live = File(target, STATE_FILE)
        val pending = File(target, STATE_PENDING)
        return try {
            val state = readState(target).apply(change)
            java.io.FileOutputStream(pending).use { out ->
                state.store(out, null)
                faultForTest?.invoke("state")
                out.fd.sync()
            }
            // Android's rename replaces atomically; a host that refuses to replace gets delete-then-rename,
            // whose gap readState covers by reading the synced pending file.
            if (!pending.renameTo(live) && !(live.delete() && pending.renameTo(live))) {
                throw java.io.IOException("could not commit the switch")
            }
            true
        } catch (e: Exception) {
            pending.delete()
            fail("state", e)
            false
        }
    }

    /** Only what a token can be: a package name, an enum name, a short code, a timestamp. */
    internal fun sanitize(value: String): String =
        value.take(TOKEN_MAX).map { if (it.isLetterOrDigit() && it.code < 128 || it in "._:+-") it else '_' }.joinToString("")

    private fun format(value: Any): String = when (value) {
        is Boolean, is Int, is Long, is Short, is Byte -> value.toString()
        is Float -> String.format(Locale.US, "%.1f", value)
        is Double -> String.format(Locale.US, "%.1f", value)
        is Enum<*> -> value.name
        else -> sanitize(value.toString())
    }

    private fun lineTime(line: String): String? = Regex("""\bt=(\S+)""").find(line)?.groupValues?.get(1)

    private fun stamp(millis: Long): String = if (millis <= 0L) "-" else timestamp(millis)

    /** Wall clock with date, year and UTC offset, so a capture read in another timezone still lines up. */
    private fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(millis))

    /** Forgets the in-memory state, as a process restart would. Tests only. */
    @Synchronized
    internal fun resetForTest() {
        flush()
        faultForTest = null
        dir = null
        appContext = null
        enabledFlow.value = false
        errorFlow.value = null
        lostPending.set(0)
        lostTotal.set(0)
        writeFailures.set(0)
        enabledUntil = 0L
        enabledSince = 0L
        seq = 0L
    }
}
