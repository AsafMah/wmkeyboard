package com.wasimaster.wmkeyboard.core.settings

/**
 * A pause on the scheduled backup or sync: [AutoBackupSettings.pausedUntilMs]
 * and [SyncSettings.pausedUntilMs].
 *
 * A pause is not the switch going off. Locations, schedule, passphrase and
 * rotation all stay as they are, and so does the periodic job: rescheduling
 * one restarts its period (see [AutoBackupScheduler.sync]), so a pause that
 * cancelled the job and an end that put it back would cost a whole interval
 * every time. The job keeps firing and the run returns at once while paused.
 * "Back up now" and "Sync now" ignore it, as they ignore the interval.
 *
 * Stored as a wall-clock time rather than a flag, so a timed pause ends by
 * itself with nothing having to run at the moment it ends.
 */
object BackupPause {

    /** Stored for "until I resume". */
    const val FOREVER = Long.MAX_VALUE

    private const val HOUR_MS = 60L * 60L * 1000L

    /** The longest timed pause, a week. Anything longer is until resumed. */
    const val MAX_HOURS = 24 * 7

    /** What a pause can be set for, in hours; null is until resumed. */
    val CHOICES: List<Int?> = listOf(1, 24, MAX_HOURS, null)

    /** A timed pause further off than this is a clock that moved. */
    private const val LONGEST_MS = MAX_HOURS * HOUR_MS

    /**
     * The stored value for a pause of [hours] from [nowMs]. Null, less than
     * one hour, or more than [MAX_HOURS] is until resumed.
     */
    fun until(nowMs: Long, hours: Int?): Long =
        if (hours == null || hours <= 0 || hours > MAX_HOURS) FOREVER else nowMs + hours * HOUR_MS

    /**
     * Whether [untilMs] still holds at [nowMs].
     *
     * A timed pause that ends further away than the longest choice can only
     * mean the clock went back since it was set. Honoured, it could stop
     * backups for months with the screen saying an hour; ignored, the worst
     * case is one backup the user meant to skip.
     */
    fun isPaused(untilMs: Long, nowMs: Long): Boolean = when {
        untilMs == FOREVER -> true
        untilMs <= nowMs -> false
        else -> untilMs - nowMs <= LONGEST_MS
    }
}

/** Whether scheduled backups are paused at [nowMs]. */
fun AutoBackupSettings.paused(nowMs: Long = System.currentTimeMillis()): Boolean =
    BackupPause.isPaused(pausedUntilMs, nowMs)

/** Whether scheduled sync passes are paused at [nowMs]. */
fun SyncSettings.paused(nowMs: Long = System.currentTimeMillis()): Boolean =
    BackupPause.isPaused(pausedUntilMs, nowMs)
