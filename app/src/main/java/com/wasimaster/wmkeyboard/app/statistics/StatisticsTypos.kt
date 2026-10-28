package com.wasimaster.wmkeyboard.app.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.chartEntrance
import com.wasimaster.wmkeyboard.app.habitLabel
import com.wasimaster.wmkeyboard.app.rememberChartEntrance
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.HelpOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bedtime
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Campaign
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Celebration
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteForever
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteSweep
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EmojiEmotions
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Fastfood
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Gesture
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Schedule
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Shield
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextIncrease
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Timer
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Verified
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.WbSunny
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Weekend
import com.wasimaster.wmkeyboard.core.prediction.CorrectionMemory
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.tools.TypingInsights
import com.wasimaster.wmkeyboard.core.tools.TypingStats
import java.text.NumberFormat
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * The fourth part of the Statistics screen: what this typist gets wrong and
 * how (typos, the slips behind them, the words they fix most), who they are
 * when they type (badges read off the hours, the week, the backspaces and
 * the punctuation), the punctuation itself, the record book, and the
 * languages the words were typed in. Every number but the slips and the fix
 * pairs is worked out by [TypingInsights] from what [TypingStats] keeps; the
 * slips and pairs are read off the learned corrections store, which already
 * shows them on its own screen, and are never written into the statistics.
 */

/**
 * The four typo tiles: autocorrects, hand fixes, typos per hundred words,
 * and how often an autocorrect stood. The last shows a dash until
 * [TypingInsights.MIN_VERDICTS] have been judged.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TyposTiles(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val decimals = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; minimumFractionDigits = 1 } }
    val none = stringResource(R.string.statistics_value_none)
    val rateTenths = (TypingInsights.typoRate(totals) * TENTHS).roundToLong()
    val trust = TypingInsights.autocorrectTrust(totals)
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
                StatTile(totals.autocorrects, stringResource(R.string.statistics_autocorrects_label), entrance.seen, 0) {
                    numbers.format(it)
                }
                StatTile(totals.handFixes, stringResource(R.string.statistics_hand_fixes_label), entrance.seen, 1) {
                    numbers.format(it)
                }
                StatTile(rateTenths, stringResource(R.string.statistics_typo_rate_label), entrance.seen, 2) {
                    if (totals.words > 0L) decimals.format(it / TENTHS) else none
                }
                StatTile(((trust ?: 0.0) * PERCENT).roundToLong(), stringResource(R.string.statistics_trust_label), entrance.seen, 3) {
                    if (trust != null) stringResource(R.string.statistics_percent_value, it.toInt()) else none
                }
            }
        }
    }
}

/**
 * The typo rows under the tiles: the best clean run and the offers taken.
 * Each waits until it has a number.
 */
@Composable
internal fun TypoRows(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    if (totals.bestCleanRun <= 0L && totals.offersTaken <= 0L) return
    SettingsGroup {
        if (totals.bestCleanRun > 0L) {
            item {
                InsightRow(
                    Icons.Outlined.Verified,
                    R.string.statistics_clean_run_title,
                    stringResource(R.string.statistics_clean_run_subtitle),
                    pluralStringResource(
                        R.plurals.statistics_word_count,
                        totals.bestCleanRun.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        numbers.format(totals.bestCleanRun),
                    ),
                )
            }
        }
        if (totals.offersTaken > 0L) {
            item {
                InsightRow(
                    Icons.Outlined.CheckCircle,
                    R.string.statistics_offers_taken_title,
                    stringResource(R.string.statistics_offers_taken_subtitle),
                    numbers.format(totals.offersTaken),
                )
            }
        }
    }
}

/**
 * This hand's signature slips and the words it fixes most, from the learned
 * corrections store, with a row through to the full list. Shown only when
 * there is something in either list.
 */
@Composable
internal fun SlipRows(
    habits: List<CorrectionMemory.Habit>,
    pairs: List<Pair<String, CorrectionMemory.Taught>>,
    onOpenLearnedCorrections: () -> Unit,
) {
    val shownHabits = habits.take(SLIPS_SHOWN)
    val shownPairs = pairs.take(SLIPS_SHOWN)
    if (shownHabits.isEmpty() && shownPairs.isEmpty()) return
    SettingsGroup {
        for (habit in shownHabits) {
            item {
                WmRow(
                    title = habitLabel(habit),
                    subtitle = pluralStringResource(R.plurals.typing_habit_times, habit.count, habit.count),
                    icon = Icons.Outlined.Spellcheck,
                )
            }
        }
        for ((typed, taught) in shownPairs) {
            item {
                WmRow(
                    title = "$typed → ${taught.fixed}",
                    subtitle = pluralStringResource(R.plurals.statistics_fix_pair_times, taught.count, taught.count),
                    icon = Icons.Outlined.EditNote,
                )
            }
        }
        item {
            NavRow(
                R.string.statistics_learned_corrections_action,
                route = "learnedcorrections",
                onClick = onOpenLearnedCorrections,
            )
        }
    }
}

/** A row per badge: its name, and the habit that earned it. */
@Composable
internal fun PersonaRows(badges: List<TypingInsights.Persona>) {
    if (badges.isEmpty()) return
    SettingsGroup {
        for (badge in badges) {
            item {
                WmRow(
                    title = stringResource(personaTitle(badge)),
                    subtitle = stringResource(personaReason(badge)),
                    icon = personaIcon(badge),
                    accent = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun personaTitle(p: TypingInsights.Persona): Int = when (p) {
    TypingInsights.Persona.NIGHT_OWL -> R.string.statistics_persona_night_owl
    TypingInsights.Persona.EARLY_BIRD -> R.string.statistics_persona_early_bird
    TypingInsights.Persona.LUNCH_TYPIST -> R.string.statistics_persona_lunch
    TypingInsights.Persona.WEEKEND_TYPIST -> R.string.statistics_persona_weekend
    TypingInsights.Persona.WEEKDAY_TYPIST -> R.string.statistics_persona_weekday
    TypingInsights.Persona.CAREFUL -> R.string.statistics_persona_careful
    TypingInsights.Persona.FEARLESS -> R.string.statistics_persona_fearless
    TypingInsights.Persona.SHOUTER -> R.string.statistics_persona_shouter
    TypingInsights.Persona.EXCLAIMER -> R.string.statistics_persona_exclaimer
    TypingInsights.Persona.QUESTIONER -> R.string.statistics_persona_questioner
    TypingInsights.Persona.FULL_STOP -> R.string.statistics_persona_full_stop
    TypingInsights.Persona.ELLIPSIS -> R.string.statistics_persona_ellipsis
}

private fun personaReason(p: TypingInsights.Persona): Int = when (p) {
    TypingInsights.Persona.NIGHT_OWL -> R.string.statistics_persona_night_owl_reason
    TypingInsights.Persona.EARLY_BIRD -> R.string.statistics_persona_early_bird_reason
    TypingInsights.Persona.LUNCH_TYPIST -> R.string.statistics_persona_lunch_reason
    TypingInsights.Persona.WEEKEND_TYPIST -> R.string.statistics_persona_weekend_reason
    TypingInsights.Persona.WEEKDAY_TYPIST -> R.string.statistics_persona_weekday_reason
    TypingInsights.Persona.CAREFUL -> R.string.statistics_persona_careful_reason
    TypingInsights.Persona.FEARLESS -> R.string.statistics_persona_fearless_reason
    TypingInsights.Persona.SHOUTER -> R.string.statistics_persona_shouter_reason
    TypingInsights.Persona.EXCLAIMER -> R.string.statistics_persona_exclaimer_reason
    TypingInsights.Persona.QUESTIONER -> R.string.statistics_persona_questioner_reason
    TypingInsights.Persona.FULL_STOP -> R.string.statistics_persona_full_stop_reason
    TypingInsights.Persona.ELLIPSIS -> R.string.statistics_persona_ellipsis_reason
}

private fun personaIcon(p: TypingInsights.Persona): ImageVector = when (p) {
    TypingInsights.Persona.NIGHT_OWL -> Icons.Outlined.Bedtime
    TypingInsights.Persona.EARLY_BIRD -> Icons.Outlined.WbSunny
    TypingInsights.Persona.LUNCH_TYPIST -> Icons.Outlined.Fastfood
    TypingInsights.Persona.WEEKEND_TYPIST -> Icons.Outlined.Weekend
    TypingInsights.Persona.WEEKDAY_TYPIST -> Icons.Outlined.Schedule
    TypingInsights.Persona.CAREFUL -> Icons.Outlined.Shield
    TypingInsights.Persona.FEARLESS -> Icons.Outlined.Bolt
    TypingInsights.Persona.SHOUTER -> Icons.Outlined.Campaign
    TypingInsights.Persona.EXCLAIMER -> Icons.Outlined.Celebration
    TypingInsights.Persona.QUESTIONER -> Icons.AutoMirrored.Outlined.HelpOutline
    TypingInsights.Persona.FULL_STOP -> Icons.Outlined.CheckCircle
    TypingInsights.Persona.ELLIPSIS -> Icons.Outlined.MoreHoriz
}

/**
 * The marks as one stacked bar, biggest share first, with a row per mark
 * under it, and the shouted words last. The bar is a picture only; the rows
 * say the same in words and numbers.
 */
@Composable
internal fun PunctuationCard(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val shares = remember(totals) { TypingInsights.punctuationShares(totals) }
    val accent = MaterialTheme.colorScheme.primary
    val entrance = rememberChartEntrance(ready = shares.isNotEmpty())
    SettingsGroup {
        if (shares.isNotEmpty()) {
            item {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(BAR_HEIGHT)
                        .chartEntrance(entrance)
                        .clearAndSetSemantics {},
                ) {
                    val gap = 2.dp.toPx()
                    val radius = CornerRadius(size.height / 2f)
                    var x = 0f
                    shares.forEachIndexed { index, (_, share) ->
                        // Each segment grows in from the left after the one before it.
                        val arrived = entrance.at(index, shares.size, BAR_SPREAD)
                        val width = (size.width * share.toFloat() - gap).coerceAtLeast(0f) * arrived
                        // Alpha steps down the ranking: one hue, lighter for the rarer marks.
                        val alpha = (1f - index * ALPHA_STEP).coerceAtLeast(MIN_ALPHA)
                        drawRoundRect(
                            color = accent.copy(alpha = alpha),
                            topLeft = Offset(x, 0f),
                            size = Size(width, size.height),
                            cornerRadius = radius,
                        )
                        x += size.width * share.toFloat()
                    }
                }
            }
            for ((mark, share) in shares) {
                item {
                    WmRow(
                        title = stringResource(markName(mark)),
                        leading = {
                            Text(
                                mark.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailing = {
                            Text(
                                stringResource(
                                    R.string.statistics_source_share,
                                    numbers.format(totals.punctuation[mark] ?: 0L),
                                    (share * PERCENT).roundToInt(),
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
        if (totals.shoutedWords > 0L) {
            item {
                InsightRow(
                    Icons.Outlined.Campaign,
                    R.string.statistics_shouted_title,
                    stringResource(R.string.statistics_shouted_subtitle),
                    numbers.format(totals.shoutedWords),
                )
            }
        }
    }
}

private fun markName(mark: Char): Int = when (mark) {
    '!' -> R.string.statistics_mark_exclamation
    '?' -> R.string.statistics_mark_question
    '.' -> R.string.statistics_mark_full_stop
    ',' -> R.string.statistics_mark_comma
    ';' -> R.string.statistics_mark_semicolon
    ':' -> R.string.statistics_mark_colon
    TypingStats.ELLIPSIS -> R.string.statistics_mark_ellipsis
    '\'' -> R.string.statistics_mark_apostrophe
    else -> R.string.statistics_mark_quote
}

/** Whether the punctuation section has anything to show. */
internal fun hasPunctuation(totals: TypingStats.Totals): Boolean =
    totals.shoutedWords > 0L || totals.punctuation.values.any { it > 0L }

/** The record book: the longest of everything, each row hidden while it is still zero. */
@Composable
internal fun RecordBookRows(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val decimals = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 } }
    SettingsGroup {
        if (totals.longestWordLetters > 0) {
            item {
                InsightRow(
                    Icons.Outlined.TextIncrease,
                    R.string.statistics_longest_word_title,
                    stringResource(R.string.statistics_longest_word_subtitle),
                    pluralStringResource(R.plurals.statistics_letter_count, totals.longestWordLetters, numbers.format(totals.longestWordLetters)),
                )
            }
        }
        if (totals.longestGlideLetters > 0) {
            item {
                InsightRow(
                    Icons.Outlined.Gesture,
                    R.string.statistics_longest_glide_title,
                    stringResource(R.string.statistics_longest_glide_subtitle),
                    pluralStringResource(R.plurals.statistics_letter_count, totals.longestGlideLetters, numbers.format(totals.longestGlideLetters)),
                )
            }
        }
        if (totals.longestBurstMs > 0L) {
            item {
                InsightRow(
                    Icons.Outlined.Timer,
                    R.string.statistics_longest_session_title,
                    stringResource(R.string.statistics_longest_session_subtitle, (TypingStats.BURST_GAP_MS / SECOND_MS).toInt()),
                    formatBurst(totals.longestBurstMs),
                )
            }
        }
        if (totals.longestBackspaceRun > 0L) {
            item {
                InsightRow(
                    Icons.Outlined.DeleteSweep,
                    R.string.statistics_backspace_run_title,
                    stringResource(R.string.statistics_backspace_run_subtitle),
                    numbers.format(totals.longestBackspaceRun),
                )
            }
        }
        if (totals.wordsDeletedWhole > 0L) {
            item {
                InsightRow(
                    Icons.Outlined.DeleteForever,
                    R.string.statistics_words_deleted_title,
                    stringResource(R.string.statistics_words_deleted_subtitle),
                    numbers.format(totals.wordsDeletedWhole),
                )
            }
        }
        if (totals.emojiSent > 0L) {
            item {
                InsightRow(
                    Icons.Outlined.EmojiEmotions,
                    R.string.statistics_emoji_title,
                    stringResource(R.string.statistics_emoji_subtitle, decimals.format(TypingInsights.emojiPer100Words(totals))),
                    numbers.format(totals.emojiSent),
                )
            }
        }
    }
}

/** Whether the record book has a row to show. */
internal fun hasRecords(totals: TypingStats.Totals): Boolean =
    totals.longestWordLetters > 0 || totals.longestGlideLetters > 0 || totals.longestBurstMs > 0L ||
        totals.longestBackspaceRun > 0L || totals.wordsDeletedWhole > 0L || totals.emojiSent > 0L

/** A stretch of typing as minutes and seconds, or hours and minutes past an hour. */
@Composable
private fun formatBurst(ms: Long): String =
    if (ms >= HOUR_MS) {
        formatDuration(ms)
    } else {
        stringResource(
            R.string.statistics_duration_minutes_seconds,
            (ms / MINUTE_MS).toInt(),
            ((ms % MINUTE_MS) / SECOND_MS).toInt(),
        )
    }

/**
 * The languages typed in: how many, then a row per language with its share
 * of the words, most first, up to [LANGUAGES_SHOWN]. Ids the registry does
 * not know are shown as they are.
 */
@Composable
internal fun LanguagesCard(totals: TypingStats.Totals) {
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val languages = remember(totals) { TypingInsights.topLanguages(totals) }
    if (languages.isEmpty()) return
    val entrance = rememberChartEntrance(ready = true, durationMs = LANGUAGES_ENTRANCE_MS)
    val shown = languages.take(LANGUAGES_SHOWN)
    SettingsGroup {
        item {
            Text(
                pluralStringResource(R.plurals.statistics_languages_count, languages.size, languages.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        shown.forEachIndexed { index, (id, share) ->
            item {
                WmRow(
                    title = LanguageRegistry.byId(id).displayName.ifEmpty { id },
                    icon = Icons.Outlined.Translate,
                    trailing = {
                        Text(
                            stringResource(
                                R.string.statistics_source_share,
                                numbers.format(totals.wordsByLanguage[id] ?: 0L),
                                (share * PERCENT).roundToInt(),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    supporting = {
                        LinearProgressIndicator(
                            progress = { share.toFloat() * entrance.at(index, shown.size, LANGUAGES_SPREAD) },
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

/** Slips and fix pairs shown on the statistics screen; the full list has its own screen. */
private const val SLIPS_SHOWN = 3
private const val LANGUAGES_SHOWN = 8

private val BAR_HEIGHT = 14.dp
private const val BAR_SPREAD = 0.5f
private const val ALPHA_STEP = 0.12f
private const val MIN_ALPHA = 0.25f
private const val LANGUAGES_ENTRANCE_MS = 700
private const val LANGUAGES_SPREAD = 0.4f

private const val TENTHS = 10.0
private const val PERCENT = 100.0
private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L
