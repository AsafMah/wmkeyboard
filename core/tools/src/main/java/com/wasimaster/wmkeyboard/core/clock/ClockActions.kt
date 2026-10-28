package com.wasimaster.wmkeyboard.core.clock

import java.util.TimeZone

/**
 * Every change the clock tool makes to its [ClockData], as pure functions.
 *
 * The keyboard panel, the settings app, the notification buttons and the ring
 * screen all change the same data, and each used to be a place a rule could be
 * forgotten (a dismissed one-shot left on, a snooze not logged). Here the rules
 * are written once, and [ClockStore.update] applies them.
 */
object ClockActions {

    /** The longest history kept, whatever the retention setting says. */
    const val HISTORY_CAP = 500

    // ---- Alarms --------------------------------------------------------

    fun addAlarm(data: ClockData, template: Alarm): Pair<Alarm, ClockData> {
        val (id, d) = data.nextId()
        val alarm = template.copy(id = id, enabled = true, snoozedUntil = 0, skipUntil = 0)
        return alarm to d.copy(alarms = (d.alarms + alarm).sortedWith(AlarmOrder))
    }

    fun putAlarm(data: ClockData, alarm: Alarm): ClockData =
        data.copy(alarms = data.alarms.map { if (it.id == alarm.id) alarm else it }.sortedWith(AlarmOrder))

    fun removeAlarm(data: ClockData, id: Long): ClockData =
        data.copy(alarms = data.alarms.filterNot { it.id == id }, ringing = data.ringing.filterNot {
            it.kind == RingKind.ALARM && it.ref == id
        })

    fun setAlarmEnabled(data: ClockData, id: Long, on: Boolean): ClockData {
        val alarm = data.alarm(id) ?: return data
        return putAlarm(data, alarm.copy(enabled = on, snoozedUntil = 0, snoozeCount = 0, skipUntil = 0))
    }

    fun toggleSkip(data: ClockData, id: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): ClockData {
        val alarm = data.alarm(id) ?: return data
        val next = if (AlarmMath.isSkipping(alarm, now)) alarm.copy(skipUntil = 0) else AlarmMath.skipNext(alarm, now, zone)
        return putAlarm(data, next)
    }

    /** Earliest first, then by id, so the list does not shuffle as the day goes by. */
    val AlarmOrder: Comparator<Alarm> = compareBy<Alarm>({ it.hour * 60 + it.minute }, { it.id })

    // ---- Ringing -------------------------------------------------------

    /** The alarms due at [at] start ringing. Returns the data and what started. */
    fun fireAlarms(data: ClockData, at: Long, zone: TimeZone = TimeZone.getDefault()): Pair<ClockData, List<Ringing>> {
        val due = AlarmMath.dueAt(data.alarms, at, zone)
            .filterNot { a -> data.ringing.any { it.kind == RingKind.ALARM && it.ref == a.id } }
        if (due.isEmpty()) return data to emptyList()
        val started = due.map { Ringing(RingKind.ALARM, it.id, at, it.label) }
        // A ringing one-shot is spent: it must not be scheduled again while it rings.
        val alarms = data.alarms.map { a ->
            if (due.any { it.id == a.id }) {
                a.copy(enabled = a.repeats, snoozedUntil = 0, skipUntil = 0)
            } else {
                a
            }
        }
        return data.copy(alarms = alarms, ringing = data.ringing + started) to started
    }

    /** The timers that have run out by [at] start ringing and leave the running list. */
    fun fireTimers(data: ClockData, at: Long, plan: PomodoroPlan): Pair<ClockData, List<Ringing>> {
        val done = data.timers.filter { !it.paused && it.endsAt <= at + TIMER_SLACK_MS }
        if (done.isEmpty()) return data to emptyList()
        var d = data.copy(timers = data.timers - done.toSet())
        val started = mutableListOf<Ringing>()
        for (t in done) {
            started += Ringing(RingKind.TIMER, t.id, at, t.label)
            d = log(
                d,
                if (t.pomodoro != null) HistoryKind.POMODORO else HistoryKind.TIMER,
                at, t.label, t.totalMs, HistoryOutcome.DONE,
            )
            // A Pomodoro phase hands straight over to the next one, so the user
            // only has to dismiss the ring, not start the break by hand.
            t.pomodoro?.let { run ->
                val next = plan.next(run)
                val ms = plan.minutesOf(next.phase) * 60_000L
                val (id, d2) = d.nextId()
                d = d2.copy(
                    timers = d2.timers + RunningTimer(
                        id = id, label = t.label, totalMs = ms, endsAt = at + ms,
                        startedAt = at, pomodoro = next,
                    ),
                )
            }
        }
        return d.copy(ringing = d.ringing + started) to started
    }

    private const val TIMER_SLACK_MS = 500L

    /** Stops one ring. An alarm logs DONE and settles; a timer is simply over. */
    fun dismiss(data: ClockData, ring: Ringing, now: Long): ClockData {
        var d = data.copy(ringing = data.ringing.filterNot { it.kind == ring.kind && it.ref == ring.ref })
        if (ring.kind == RingKind.ALARM) {
            val alarm = d.alarm(ring.ref)
            if (alarm != null) d = putAlarm(d, AlarmMath.afterDismiss(alarm))
            d = log(d, HistoryKind.ALARM, now, ring.label, now - ring.since, HistoryOutcome.DONE)
        }
        return d
    }

    /**
     * Snoozes one ring for [minutes]. A timer snoozed is a timer restarted for
     * that long, which is what "snooze" means to someone staring at an oven.
     */
    fun snooze(data: ClockData, ring: Ringing, now: Long, minutes: Int): ClockData {
        var d = data.copy(ringing = data.ringing.filterNot { it.kind == ring.kind && it.ref == ring.ref })
        when (ring.kind) {
            RingKind.ALARM -> {
                val alarm = d.alarm(ring.ref) ?: return d
                d = putAlarm(d, AlarmMath.snoozed(alarm.copy(enabled = true), now, minutes))
                d = log(d, HistoryKind.ALARM, now, ring.label, now - ring.since, HistoryOutcome.SNOOZED)
            }
            RingKind.TIMER -> d = startTimer(d, ring.label, minutes * 60_000L, now).second
        }
        return d
    }

    /**
     * Ends the snooze of alarm [id] from the shade (#970): the alarm settles the
     * way a dismissed one does, a one-shot off, a repeating one on its days.
     */
    fun endSnooze(data: ClockData, id: Long): ClockData {
        val alarm = data.alarm(id) ?: return data
        if (alarm.snoozedUntil == 0L) return data
        return putAlarm(data, AlarmMath.afterDismiss(alarm))
    }

    /**
     * Skips the coming ring of alarm [id], from the notice that warns of it
     * (#970): a repeating alarm skips that one ring, a one-shot turns off.
     */
    fun skipComing(data: ClockData, id: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): ClockData {
        val alarm = data.alarm(id) ?: return data
        return if (alarm.repeats) putAlarm(data, AlarmMath.skipNext(alarm, now, zone)) else setAlarmEnabled(data, id, false)
    }

    /**
     * A ring nobody answered, for the time it was let ring: an alarm with
     * [autoSnoozes] left to spend snoozes for [snoozeMinutes] (#970), and
     * anything else is [missed].
     */
    fun unanswered(data: ClockData, ring: Ringing, now: Long, autoSnoozes: Int, snoozeMinutes: Int): ClockData {
        val alarm = if (ring.kind == RingKind.ALARM) data.alarm(ring.ref) else null
        return if (alarm != null && alarm.snoozeCount < autoSnoozes) {
            snooze(data, ring, now, snoozeMinutes)
        } else {
            missed(data, ring, now)
        }
    }

    /** A ring nobody answered. */
    fun missed(data: ClockData, ring: Ringing, now: Long): ClockData {
        var d = data.copy(ringing = data.ringing.filterNot { it.kind == ring.kind && it.ref == ring.ref })
        if (ring.kind == RingKind.ALARM) {
            d.alarm(ring.ref)?.let { d = putAlarm(d, AlarmMath.afterDismiss(it)) }
            d = log(d, HistoryKind.ALARM, now, ring.label, now - ring.since, HistoryOutcome.MISSED)
        }
        return d
    }

    // ---- Timers --------------------------------------------------------

    fun startTimer(
        data: ClockData,
        label: String,
        durationMs: Long,
        now: Long,
        pomodoro: PomodoroRun? = null,
    ): Pair<RunningTimer, ClockData> {
        val (id, d) = data.nextId()
        val timer = RunningTimer(
            id = id, label = label, totalMs = durationMs, endsAt = now + durationMs,
            startedAt = now, pomodoro = pomodoro,
        )
        return timer to d.copy(timers = d.timers + timer)
    }

    fun startPomodoro(data: ClockData, plan: PomodoroPlan, label: String, now: Long): ClockData =
        startTimer(
            data, label, plan.focusMinutes * 60_000L, now,
            PomodoroRun(PomodoroPhase.FOCUS, 0),
        ).second

    fun pauseTimer(data: ClockData, id: Long, now: Long): ClockData = mapTimer(data, id) {
        if (it.paused) it else it.copy(pausedRemainingMs = it.remainingAt(now))
    }

    fun resumeTimer(data: ClockData, id: Long, now: Long): ClockData = mapTimer(data, id) {
        if (!it.paused) it else it.copy(endsAt = now + it.pausedRemainingMs, pausedRemainingMs = -1)
    }

    /** Adds [ms] to a timer, running or paused; the total grows with it so progress stays honest. */
    fun extendTimer(data: ClockData, id: Long, ms: Long): ClockData = mapTimer(data, id) {
        if (it.paused) {
            it.copy(pausedRemainingMs = it.pausedRemainingMs + ms, totalMs = it.totalMs + ms)
        } else {
            it.copy(endsAt = it.endsAt + ms, totalMs = it.totalMs + ms)
        }
    }

    /** Back to the full length, paused, the way a kitchen timer's reset works. */
    fun resetTimer(data: ClockData, id: Long): ClockData = mapTimer(data, id) {
        it.copy(pausedRemainingMs = it.totalMs)
    }

    fun cancelTimer(data: ClockData, id: Long, now: Long): ClockData {
        val t = data.timer(id) ?: return data
        val d = data.copy(timers = data.timers.filterNot { it.id == id })
        val ran = t.totalMs - t.remainingAt(now)
        // A timer cancelled within seconds of starting was a mis-tap, not history.
        return if (ran < MIN_LOGGED_MS) {
            d
        } else {
            log(
                d,
                if (t.pomodoro != null) HistoryKind.POMODORO else HistoryKind.TIMER,
                now, t.label, ran, HistoryOutcome.CANCELLED,
            )
        }
    }

    private fun mapTimer(data: ClockData, id: Long, f: (RunningTimer) -> RunningTimer): ClockData =
        data.copy(timers = data.timers.map { if (it.id == id) f(it) else it })

    private const val MIN_LOGGED_MS = 5_000L

    fun savePreset(data: ClockData, label: String, durationMs: Long): ClockData {
        if (durationMs <= 0 || data.presets.any { it.durationMs == durationMs && it.label == label }) return data
        val (id, d) = data.nextId()
        return d.copy(presets = (d.presets + TimerPreset(id, label, durationMs)).sortedBy { it.durationMs })
    }

    fun removePreset(data: ClockData, id: Long): ClockData =
        data.copy(presets = data.presets.filterNot { it.id == id })

    // ---- Stopwatch -----------------------------------------------------

    fun stopwatchStart(data: ClockData, now: Long): ClockData {
        val s = data.stopwatch
        return if (s.running) data else data.copy(stopwatch = s.copy(running = true, startedAt = now))
    }

    fun stopwatchPause(data: ClockData, now: Long): ClockData {
        val s = data.stopwatch
        return if (!s.running) data else data.copy(stopwatch = s.copy(running = false, accumulatedMs = s.elapsedAt(now)))
    }

    fun stopwatchLap(data: ClockData, now: Long): ClockData {
        val s = data.stopwatch
        if (s.isReset) return data
        return data.copy(stopwatch = s.copy(laps = (s.laps + s.elapsedAt(now)).takeLast(MAX_LAPS)))
    }

    /** Clears the stopwatch, logging the run when it measured anything. */
    fun stopwatchReset(data: ClockData, now: Long): ClockData {
        val s = data.stopwatch
        val elapsed = s.elapsedAt(now)
        val d = data.copy(stopwatch = Stopwatch())
        return if (elapsed < MIN_LOGGED_MS && s.laps.isEmpty()) {
            d
        } else {
            log(d, HistoryKind.STOPWATCH, now, "", elapsed, HistoryOutcome.DONE, s.laps)
        }
    }

    private const val MAX_LAPS = 999

    // ---- Countdowns ----------------------------------------------------

    fun addCountdown(data: ClockData, title: String, target: Long, allDay: Boolean): ClockData {
        val (id, d) = data.nextId()
        return d.copy(countdowns = (d.countdowns + Countdown(id, title, target, allDay)).sortedBy { it.target })
    }

    fun removeCountdown(data: ClockData, id: Long): ClockData =
        data.copy(countdowns = data.countdowns.filterNot { it.id == id })

    // ---- History -------------------------------------------------------

    @Suppress("detekt.LongParameterList")
    fun log(
        data: ClockData,
        kind: HistoryKind,
        at: Long,
        label: String,
        durationMs: Long,
        outcome: HistoryOutcome,
        laps: List<Long> = emptyList(),
    ): ClockData {
        val (id, d) = data.nextId()
        val entry = HistoryEntry(id, kind, at, label, durationMs.coerceAtLeast(0), outcome, laps)
        return d.copy(history = (listOf(entry) + d.history).take(HISTORY_CAP))
    }

    /** Drops history older than [days] days; 0 keeps everything. */
    fun pruneHistory(data: ClockData, now: Long, days: Int): ClockData {
        if (days <= 0) return data
        val cutoff = now - days * 86_400_000L
        val kept = data.history.filter { it.at >= cutoff }
        return if (kept.size == data.history.size) data else data.copy(history = kept)
    }

    fun removeHistory(data: ClockData, ids: Set<Long>): ClockData =
        data.copy(history = data.history.filterNot { it.id in ids })
}
