package com.wasimaster.wmkeyboard.core.clock

import java.util.Calendar
import java.util.TimeZone

/** A calendar date with no time and no zone. [month] is 1 to 12. */
data class LocalDay(val year: Int, val month: Int, val day: Int) : Comparable<LocalDay> {

    /** Days since 1970-01-01, the proleptic Gregorian count. */
    val epochDay: Long get() = civilToDays(year, month, day)

    override fun compareTo(other: LocalDay): Int = epochDay.compareTo(other.epochDay)

    fun plusDays(n: Long): LocalDay = ofEpochDay(epochDay + n)

    /**
     * [n] months later. The day is clamped to the month's length, so 31
     * January plus one month is 28 (or 29) February, the way people count.
     */
    fun plusMonths(n: Long): LocalDay {
        val total = year * 12L + (month - 1) + n
        val y = Math.floorDiv(total, 12L).toInt()
        val m = Math.floorMod(total, 12L).toInt() + 1
        return LocalDay(y, m, day.coerceAtMost(lengthOfMonth(y, m)))
    }

    fun plusYears(n: Long): LocalDay = plusMonths(n * 12)

    /** 0 for Monday to 6 for Sunday. */
    val isoDayOfWeek: Int get() = Math.floorMod(epochDay + 3, 7L).toInt()

    /** ISO-8601 week number, 1 to 53. */
    val isoWeek: Int
        get() {
            // The week's Thursday decides which year the week belongs to.
            val thursday = plusDays((3 - isoDayOfWeek).toLong())
            val jan1 = LocalDay(thursday.year, 1, 1)
            return ((thursday.epochDay - jan1.epochDay) / 7 + 1).toInt()
        }

    val dayOfYear: Int get() = (epochDay - LocalDay(year, 1, 1).epochDay + 1).toInt()

    /** Midnight at the start of this day in [zone]. */
    fun startMillis(zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, 0, 0, 0)
    }.timeInMillis

    companion object {
        /**
         * A date typed by hand: `17/5/2004`, `17-05-2004`, `17.5.04` or
         * `2004-05-17`. Four digits first is year, month, day; otherwise the
         * day comes first unless [monthFirst] (as the US writes it), and the
         * other order is tried when that one is no date. A two-digit year is
         * this century's up to [twoDigitPivot], the last century's above it.
         * Null for anything that is not a real calendar day.
         */
        fun parse(text: String, monthFirst: Boolean = false, twoDigitPivot: Int = 50): LocalDay? {
            val parts = text.trim().split('/', '-', '.', ' ').filter { it.isNotEmpty() }
            if (parts.size != 3 || parts.any { p -> p.any { !it.isDigit() } || p.length > 4 }) return null
            val n = parts.map { it.toInt() }
            fun year(i: Int): Int = if (parts[i].length <= 2) {
                n[i] + if (n[i] <= twoDigitPivot) 2000 else 1900
            } else {
                n[i]
            }
            fun valid(y: Int, m: Int, d: Int): LocalDay? =
                LocalDay(y, m, d).takeIf { m in 1..12 && d in 1..31 && ofEpochDay(civilToDays(y, m, d)) == it }
            if (parts[0].length == 4) return valid(n[0], n[1], n[2])
            val y = year(2)
            return if (monthFirst) {
                valid(y, n[0], n[1]) ?: valid(y, n[1], n[0])
            } else {
                valid(y, n[1], n[0]) ?: valid(y, n[0], n[1])
            }
        }

        fun of(at: Long, zone: TimeZone): LocalDay {
            val c = Calendar.getInstance(zone).apply { timeInMillis = at }
            return LocalDay(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        }

        fun ofEpochDay(days: Long): LocalDay {
            // Howard Hinnant's civil_from_days.
            val z = days + 719_468
            val era = Math.floorDiv(z, 146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return LocalDay(y, m, d)
        }

        fun civilToDays(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = Math.floorDiv(y, 400L)
            val yoe = y - era * 400
            val mp = ((month + 9) % 12).toLong()
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (isLeap(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

/** The distance between two dates, every way people ask for it. */
data class DayDistance(
    /** Signed: negative when the end is before the start. */
    val days: Long,
    /** Monday to Friday only, end date excluded. */
    val workdays: Long,
    val years: Int,
    val months: Int,
    /** Days left over after whole years and months. */
    val restDays: Int,
) {
    val weeks: Long get() = days / 7
    val weekRest: Long get() = days % 7
}

object DateMath {

    fun between(from: LocalDay, to: LocalDay): DayDistance {
        val days = to.epochDay - from.epochDay
        val (a, b) = if (days >= 0) from to to else to to from
        var years = b.year - a.year
        var months = b.month - a.month
        var rest = b.day - a.day
        if (rest < 0) {
            months -= 1
            // Borrow the length of the month before b.
            val prev = b.plusMonths(-1)
            rest += LocalDay.lengthOfMonth(prev.year, prev.month)
        }
        if (months < 0) {
            years -= 1
            months += 12
        }
        val sign = if (days < 0) -1 else 1
        return DayDistance(
            days = days,
            workdays = sign * workdays(a, b),
            years = years,
            months = months,
            restDays = rest,
        )
    }

    /** Monday-to-Friday days in [from, to), [from] before [to]. */
    fun workdays(from: LocalDay, to: LocalDay): Long {
        val total = to.epochDay - from.epochDay
        if (total <= 0) return 0
        val fullWeeks = total / 7
        var count = fullWeeks * 5
        val startDow = from.isoDayOfWeek
        for (i in 0 until (total % 7)) {
            if ((startDow + i) % 7 < 5) count++
        }
        return count
    }

    /** Units a date can be moved by in the Dates tab. */
    enum class Unit { DAYS, WEEKS, MONTHS, YEARS, WORKDAYS }

    fun add(day: LocalDay, amount: Long, unit: Unit): LocalDay = when (unit) {
        Unit.DAYS -> day.plusDays(amount)
        Unit.WEEKS -> day.plusDays(amount * 7)
        Unit.MONTHS -> day.plusMonths(amount)
        Unit.YEARS -> day.plusYears(amount)
        Unit.WORKDAYS -> addWorkdays(day, amount)
    }

    /** [n] working days later (or earlier, negative), skipping weekends. */
    fun addWorkdays(day: LocalDay, n: Long): LocalDay {
        var d = day
        var left = kotlin.math.abs(n)
        val step = if (n >= 0) 1L else -1L
        while (left > 0) {
            d = d.plusDays(step)
            if (d.isoDayOfWeek < 5) left--
        }
        return d
    }
}
