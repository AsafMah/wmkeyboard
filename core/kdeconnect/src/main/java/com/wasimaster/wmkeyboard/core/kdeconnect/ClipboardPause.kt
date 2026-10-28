package com.wasimaster.wmkeyboard.core.kdeconnect

/**
 * Clipboard sharing put on hold for a while (#918), with one computer or with
 * all of them, without unpairing and without touching the clipboard switches.
 *
 * A pause is an end time, wall clock, keyed by device id or by [ALL]. Nothing
 * runs when it ends: every check compares against the time now, so a timed
 * pause lapses by itself, across a process restart too, since the map lives in
 * settings. [FOREVER] is "until I turn it back on".
 *
 * While a device is paused nothing of the clipboard goes to it and nothing it
 * sends is applied. The user's explicit "send this" ([ClipboardPlugin.push])
 * still goes, the way it does for a clip that looks like a password.
 */
object KdeClipboardPause {

    /** The key that pauses every computer at once. Not a valid KDE Connect device id. */
    const val ALL = "*"

    /** An end time that never comes. */
    const val FOREVER = Long.MAX_VALUE

    /** The lengths offered, in minutes; 0 is until turned back on. */
    val PRESET_MINUTES = listOf(15, 60, 0)

    /**
     * When sharing with [deviceId] comes back, or 0 if it is not paused. The
     * later of the device's own pause and the pause on all of them.
     */
    fun pausedUntil(pauses: Map<String, Long>, deviceId: String, nowMs: Long): Long {
        val until = maxOf(pauses[deviceId] ?: 0L, pauses[ALL] ?: 0L)
        return if (until > nowMs) until else 0L
    }

    fun isPaused(pauses: Map<String, Long>, deviceId: String, nowMs: Long): Boolean =
        pausedUntil(pauses, deviceId, nowMs) > 0L

    /** When the pause on all computers ends, or 0 if there is none. */
    fun allPausedUntil(pauses: Map<String, Long>, nowMs: Long): Long =
        (pauses[ALL] ?: 0L).takeIf { it > nowMs } ?: 0L

    /** The end time for a pause of [minutes] from [nowMs]; 0 or less is [FOREVER]. */
    fun endFor(minutes: Int, nowMs: Long): Long = if (minutes <= 0) FOREVER else nowMs + minutes * 60_000L

    /**
     * [pauses] with [deviceId] (null: all computers) paused until [untilMs].
     * Pausing all of them leaves each device's own pause in place, so one
     * paused for longer stays paused once the shorter pause on all ends.
     */
    fun pause(pauses: Map<String, Long>, deviceId: String?, untilMs: Long, nowMs: Long): Map<String, Long> =
        live(pauses, nowMs) + ((deviceId ?: ALL) to untilMs)

    /**
     * [pauses] with sharing back on for [deviceId], or for everything when
     * null. A device cannot be resumed out of a pause on all of them: that
     * pause is the one to end.
     */
    fun resume(pauses: Map<String, Long>, deviceId: String?, nowMs: Long): Map<String, Long> =
        if (deviceId == null) emptyMap() else live(pauses, nowMs) - deviceId

    /** The pauses still running; ended ones are dropped as the map is next written. */
    fun live(pauses: Map<String, Long>, nowMs: Long): Map<String, Long> = pauses.filterValues { it > nowMs }

    /** The next moment one of [pauses] ends, for a screen that redraws then; null if none is timed. */
    fun nextEnd(pauses: Map<String, Long>, nowMs: Long): Long? =
        pauses.values.filter { it in (nowMs + 1) until FOREVER }.minOrNull()
}
