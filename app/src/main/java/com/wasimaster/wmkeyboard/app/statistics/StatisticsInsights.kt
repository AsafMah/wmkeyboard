package com.wasimaster.wmkeyboard.app.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.chartEntrance
import com.wasimaster.wmkeyboard.app.rememberChartEntrance
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Backspace
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ShortText
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Abc
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoStories
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassBottom
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Straighten
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Swipe
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Today
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TouchApp
import com.wasimaster.wmkeyboard.core.tools.StatsPeriod
import com.wasimaster.wmkeyboard.core.tools.TypingInsights
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import com.wasimaster.wmkeyboard.core.tools.TypingStatsMath
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The third part of the Statistics screen (#761 and after): streaks with a
 * calendar of typing days, where the words come from, voice typing, personal
 * bests, the counts turned into things a person can picture, and the weekly
 * punch card. Every number is worked out by [TypingInsights] from what
 * [TypingStats] already keeps; nothing here is stored.
 */

/**
 * Three streak figures over a calendar of the last [CALENDAR_WEEKS] weeks, one
 * square a day, darker for busier days. The calendar is a picture only: the
 * figures and the line under it say what it shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StreakCard(entries: List<TypingStats.DayEntry>, today: Int) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val current = remember(entries, today) { TypingInsights.currentStreak(entries, today) }
    val longest = remember(entries) { TypingInsights.longestStreak(entries) }
    val active = remember(entries) { TypingInsights.activeDays(entries) }
    val firstDay = calendarStart(today)
    val shownDays = active.count { it in firstDay..today }
    val entrance = rememberChartEntrance(ready = true)
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    maxItemsInEachRow = 3,
                    modifier = Modifier.fillMaxWidth().chartEntrance(entrance),
                ) {
                    val days: @Composable (Long) -> String = { n ->
                        pluralStringResource(R.plurals.statistics_streak_days, n.toInt(), numbers.format(n))
                    }
                    StatTile(current.toLong(), stringResource(R.string.statistics_streak_current_label), entrance.seen, 0, days)
                    StatTile(longest.toLong(), stringResource(R.string.statistics_streak_longest_label), entrance.seen, 1, days)
                    StatTile(active.size.toLong(), stringResource(R.string.statistics_streak_active_label), entrance.seen, 2) {
                        numbers.format(it)
                    }
                }
                TypingCalendar(entries, today, modifier = Modifier.padding(top = 16.dp))
                Text(
                    stringResource(R.string.statistics_calendar_summary, numbers.format(shownDays)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** The Monday that starts the calendar's first column. */
private fun calendarStart(today: Int): Int =
    today - TypingStatsMath.weekdayOf(today) - (CALENDAR_WEEKS - 1) * DAYS_PER_WEEK

/**
 * The calendar itself: a column per week, Monday at the top, today in the
 * last column. One hue, light to dark, for how much was typed and said that
 * day; days still to come are left blank.
 */
@Composable
private fun TypingCalendar(entries: List<TypingStats.DayEntry>, today: Int, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val start = calendarStart(today)
    val counts = remember(entries, today) {
        entries.filter { it.epochDay in start..today }
            .associate { it.epochDay to (it.words + it.voiceWords).coerceAtLeast(if (TypingInsights.active(it)) 1 else 0) }
    }
    val peak = (counts.values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val entrance = rememberChartEntrance(ready = true)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(CALENDAR_WEEKS.toFloat() / DAYS_PER_WEEK)
            .chartEntrance(entrance)
            .clearAndSetSemantics {},
    ) {
        val pitch = size.width / CALENDAR_WEEKS
        val gap = 2.dp.toPx()
        val cell = pitch - gap
        val radius = CornerRadius(cell / 4f)
        for (week in 0 until CALENDAR_WEEKS) {
            // Columns arrive left to right, the oldest week first.
            val arrived = entrance.at(week, CALENDAR_WEEKS, CALENDAR_SPREAD)
            for (weekday in 0 until DAYS_PER_WEEK) {
                val day = start + week * DAYS_PER_WEEK + weekday
                if (day > today) continue
                val count = counts[day] ?: 0L
                // Square root, so a quiet day beside a big one still shows.
                val level = if (count <= 0L) 0f else (CALENDAR_FLOOR + (1f - CALENDAR_FLOOR) * sqrt(count.toFloat() / peak))
                drawRoundRect(
                    color = if (level == 0f) empty else accent.copy(alpha = level * arrived),
                    topLeft = Offset(week * pitch, weekday * pitch),
                    size = Size(cell, cell),
                    cornerRadius = radius,
                )
            }
        }
    }
}

/**
 * Every word, by how it went in: a row per way, with its share as a bar. One
 * hue throughout, since the rows are what tell the ways apart and the bar is
 * only how big each one is.
 */
@Composable
internal fun SourcesCard(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val sources = remember(totals) { TypingInsights.sources(totals).filter { it.second > 0L } }
    val sum = sources.sumOf { it.second }.coerceAtLeast(1L)
    val entrance = rememberChartEntrance(ready = true, durationMs = SOURCES_ENTRANCE_MS)
    SettingsGroup {
        sources.forEachIndexed { index, (source, count) ->
            item {
                val share = count.toFloat() / sum
                WmRow(
                    title = stringResource(sourceLabel(source)),
                    icon = sourceIcon(source),
                    trailing = {
                        Text(
                            stringResource(
                                R.string.statistics_source_share,
                                numbers.format(count),
                                (share * PERCENT).roundToInt(),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    supporting = {
                        LinearProgressIndicator(
                            progress = { share * entrance.at(index, sources.size, SOURCES_SPREAD) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .chartEntrance(entrance)
                                .clearAndSetSemantics {},
                        )
                    },
                )
            }
        }
    }
}

private fun sourceLabel(source: TypingInsights.Source): Int = when (source) {
    TypingInsights.Source.TYPED -> R.string.statistics_source_typed
    TypingInsights.Source.PREDICTED -> R.string.statistics_source_predicted
    TypingInsights.Source.COMPLETED -> R.string.statistics_source_completed
    TypingInsights.Source.GLIDED -> R.string.statistics_source_glided
    TypingInsights.Source.TWO_THUMB -> R.string.statistics_source_two_thumb
    TypingInsights.Source.SPOKEN -> R.string.statistics_source_spoken
}

private fun sourceIcon(source: TypingInsights.Source): ImageVector = when (source) {
    TypingInsights.Source.TYPED -> Icons.Outlined.Keyboard
    TypingInsights.Source.PREDICTED -> Icons.Outlined.AutoAwesome
    TypingInsights.Source.COMPLETED -> Icons.AutoMirrored.Outlined.ShortText
    TypingInsights.Source.GLIDED -> Icons.Outlined.Gesture
    TypingInsights.Source.TWO_THUMB -> Icons.Outlined.Swipe
    TypingInsights.Source.SPOKEN -> Icons.Outlined.Mic
}

/** The voice typing figures (#761), four tiles like the typing ones above. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoiceTiles(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val speaking = TypingInsights.speakingWpm(totals).toLong()
    val none = stringResource(R.string.statistics_value_none)
    val entrance = rememberChartEntrance(ready = true)
    SettingsGroup {
        item {
            FlowRow(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .chartEntrance(entrance),
            ) {
                StatTile(totals.voiceWords, stringResource(R.string.statistics_voice_words_label), entrance.seen, 0) {
                    numbers.format(it)
                }
                StatTile(totals.voicePhrases, stringResource(R.string.statistics_voice_phrases_label), entrance.seen, 1) {
                    numbers.format(it)
                }
                StatTile(totals.voiceMs, stringResource(R.string.statistics_voice_time_label), entrance.seen, 2) {
                    formatDuration(it)
                }
                StatTile(speaking, stringResource(R.string.statistics_voice_speed_label), entrance.seen, 3) {
                    if (speaking > 0L) stringResource(R.string.statistics_wpm_value, it.toInt()) else none
                }
            }
        }
    }
}

/**
 * The bests and the habits: fastest stretch, best day, word length and how
 * much gets taken back. A row is left out until there is a number for it.
 */
@Composable
internal fun BestsRows(totals: TypingStats.Totals, entries: List<TypingStats.DayEntry>) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val decimals = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 } }
    val best = remember(entries) { TypingInsights.bestDay(entries) }
    val length = TypingInsights.averageWordLength(totals)
    SettingsGroup {
        if (totals.peakBurstWpm > 0.0) {
            item {
                InsightRow(
                    Icons.Outlined.Bolt,
                    R.string.statistics_best_burst_title,
                    stringResource(R.string.statistics_best_burst_subtitle),
                    stringResource(R.string.statistics_wpm_value, totals.peakBurstWpm.toInt()),
                )
            }
        }
        if (best != null) {
            item {
                val words = best.words + best.voiceWords
                InsightRow(
                    Icons.Outlined.Today,
                    R.string.statistics_best_day_title,
                    bucketLabel(best.epochDay, StatsPeriod.DAY),
                    pluralStringResource(R.plurals.statistics_word_count, words.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), numbers.format(words)),
                )
            }
        }
        if (length > 0.0) {
            item {
                InsightRow(
                    Icons.Outlined.Abc,
                    R.string.statistics_word_length_title,
                    stringResource(R.string.statistics_word_length_subtitle),
                    pluralStringResource(R.plurals.statistics_letter_count, length.roundToInt(), decimals.format(length)),
                )
            }
        }
        if (totals.chars > 0L) {
            item {
                InsightRow(
                    Icons.AutoMirrored.Outlined.Backspace,
                    R.string.statistics_correction_title,
                    stringResource(R.string.statistics_correction_subtitle),
                    decimals.format(TypingInsights.correctionRate(totals)),
                )
            }
        }
    }
}

/** The counts turned into things to picture: novels, time saved, track laps, key presses. */
@Composable
internal fun EquivalentsRows(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val decimals = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 } }
    val saved = TypingInsights.timeSavedMs(totals)
    val laps = TypingInsights.trackLaps(totals)
    SettingsGroup {
        item {
            InsightRow(
                Icons.Outlined.AutoStories,
                R.string.statistics_novels_title,
                pluralStringResource(
                    R.plurals.statistics_novels_subtitle,
                    TypingInsights.NOVEL_WORDS.toInt(),
                    numbers.format(TypingInsights.NOVEL_WORDS),
                ),
                decimals.format(TypingInsights.novels(totals)),
            )
        }
        if (saved >= MINUTE_MS) {
            item {
                InsightRow(
                    Icons.Outlined.HourglassBottom,
                    R.string.statistics_time_saved_title,
                    stringResource(R.string.statistics_time_saved_subtitle),
                    formatDuration(saved),
                )
            }
        }
        if (laps >= MIN_LAPS) {
            item {
                InsightRow(
                    Icons.Outlined.Straighten,
                    R.string.statistics_laps_title,
                    stringResource(R.string.statistics_laps_subtitle, TypingInsights.TRACK_METRES.toInt()),
                    decimals.format(laps),
                )
            }
        }
        item {
            InsightRow(
                Icons.Outlined.TouchApp,
                R.string.statistics_presses_title,
                stringResource(R.string.statistics_presses_subtitle),
                numbers.format(TypingInsights.keyPresses(totals)),
            )
        }
    }
}

@Composable
internal fun InsightRow(icon: ImageVector, title: Int, subtitle: String, value: String) {
    WmRow(
        title = stringResource(title),
        subtitle = subtitle,
        icon = icon,
        trailing = {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

/**
 * The week as a punch card: a dot per weekday and hour, bigger for more
 * keystrokes, with the busiest day named below for the screen reader and
 * anyone who would rather read it.
 */
@Composable
internal fun PunchCard(weekHours: List<Long>) {
    val accent = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val peak = (weekHours.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val busiest = TypingInsights.busiestWeekday(weekHours)
    val dayNames = remember { weekdayNames() }
    val entrance = rememberChartEntrance(ready = busiest >= 0)
    SettingsGroup {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                for (weekday in 0 until DAYS_PER_WEEK) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            dayNames[weekday],
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .fillMaxWidth(DAY_LABEL_SHARE),
                        )
                        Canvas(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(HOURS.toFloat())
                                .chartEntrance(entrance)
                                .clearAndSetSemantics {},
                        ) {
                            val pitch = size.width / HOURS
                            val maxRadius = pitch * DOT_MAX
                            for (hour in 0 until HOURS) {
                                val count = weekHours.getOrElse(weekday * HOURS + hour) { 0L }
                                val centre = Offset(pitch * (hour + 0.5f), size.height / 2f)
                                val arrived = entrance.at(hour, HOURS, PUNCH_SPREAD)
                                if (count <= 0L) {
                                    drawCircle(labelColor.copy(alpha = DOT_EMPTY_ALPHA), radius = pitch * DOT_EMPTY, center = centre)
                                } else {
                                    // Area, not radius, follows the count.
                                    val r = maxRadius * sqrt(count.toFloat() / peak).coerceAtLeast(DOT_FLOOR)
                                    drawCircle(accent, radius = r * arrived, center = centre)
                                }
                            }
                        }
                    }
                }
                if (busiest >= 0) {
                    Text(
                        stringResource(R.string.statistics_busiest_day_label, fullWeekdayName(busiest)),
                        style = MaterialTheme.typography.bodySmall,
                        color = labelColor,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** Short weekday names, Monday first, in the user's language. */
private fun weekdayNames(): List<String> {
    val names = DateFormatSymbols.getInstance().shortWeekdays
    return MONDAY_FIRST.map { names[it] }
}

private fun fullWeekdayName(weekday: Int): String =
    DateFormatSymbols.getInstance().weekdays[MONDAY_FIRST[weekday]]

private val MONDAY_FIRST = listOf(
    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
    Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY,
)

/** Today, as the local epoch day the store keys its buckets by. */
internal fun todayEpochDay(): Int =
    TypingStatsMath.localEpochDay(System.currentTimeMillis(), TimeZone.getDefault())

private const val CALENDAR_WEEKS = 26
private const val DAYS_PER_WEEK = 7
private const val HOURS = 24

/** The lightest shade a day with any typing gets, so it never reads as empty. */
private const val CALENDAR_FLOOR = 0.25f
private const val CALENDAR_SPREAD = 0.5f

private const val SOURCES_ENTRANCE_MS = 700
private const val SOURCES_SPREAD = 0.4f
private const val PERCENT = 100f

private const val MINUTE_MS = 60_000L

/** Below a tenth of a lap the figure is not worth a row. */
private const val MIN_LAPS = 0.1

private const val DAY_LABEL_SHARE = 0.12f
private const val DOT_MAX = 0.45f
private const val DOT_FLOOR = 0.2f
private const val DOT_EMPTY = 0.06f
private const val DOT_EMPTY_ALPHA = 0.3f
private const val PUNCH_SPREAD = 0.5f
