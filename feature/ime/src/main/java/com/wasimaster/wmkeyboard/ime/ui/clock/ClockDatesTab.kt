package com.wasimaster.wmkeyboard.ime.ui.clock

import android.icu.util.MeasureUnit
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.clock.ClockFormats
import com.wasimaster.wmkeyboard.core.clock.Countdown
import com.wasimaster.wmkeyboard.core.clock.DateMath
import com.wasimaster.wmkeyboard.core.clock.LocalDay
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Event
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardDoubleArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.clock.ClockAction
import com.wasimaster.wmkeyboard.ime.clock.ClockController
import com.wasimaster.wmkeyboard.ime.clock.ClockText
import com.wasimaster.wmkeyboard.ime.clock.ClockUi
import com.wasimaster.wmkeyboard.ime.clock.DateField
import com.wasimaster.wmkeyboard.ime.clock.DatesMode
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.ToolPanelChip
import java.util.TimeZone

/**
 * Date maths without typing a date: steppers move a day or a month at a
 * time, and the answer updates as they move. Between two dates, a date plus
 * or minus some days, weeks, months or working days, and countdowns to the
 * days that matter.
 */
@Composable
internal fun DatesTab(state: KeyboardUiState, ui: ClockUi, act: (ClockAction) -> Unit) {
    val now = rememberNow(60_000L)
    val today = ClockController.today(now)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolPanelChip(stringResource(R.string.ime_clock_dates_between), selected = ui.dates.mode == DatesMode.BETWEEN) {
                act(ClockAction.DatesModeSet(DatesMode.BETWEEN))
            }
            ToolPanelChip(stringResource(R.string.ime_clock_dates_add), selected = ui.dates.mode == DatesMode.ADD) {
                act(ClockAction.DatesModeSet(DatesMode.ADD))
            }
            ToolPanelChip(
                if (ui.data.countdowns.isEmpty()) {
                    stringResource(R.string.ime_clock_dates_countdowns)
                } else {
                    stringResource(R.string.ime_clock_dates_countdowns_count, ui.data.countdowns.size)
                },
                selected = ui.dates.mode == DatesMode.COUNTDOWNS,
            ) { act(ClockAction.DatesModeSet(DatesMode.COUNTDOWNS)) }
        }
        Spacer(Modifier.height(4.dp))
        when (ui.dates.mode) {
            DatesMode.BETWEEN -> Between(ui, today, act)
            DatesMode.ADD -> AddDays(ui, today, act)
            DatesMode.COUNTDOWNS -> Countdowns(ui, now, today, act)
        }
    }
}

@Composable
private fun Between(ui: ClockUi, today: LocalDay, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val from = ui.dates.from ?: today
    val to = ui.dates.to ?: today
    val d = DateMath.between(from, to)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        DateStepper(stringResource(R.string.ime_clock_from), from, DateField.FROM, act)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.Outlined.SwapVert, stringResource(R.string.ime_clock_swap_desc), size = 26.dp) { act(ClockAction.DatesSwap) }
            Spacer(Modifier.weight(1f))
        }
        DateStepper(stringResource(R.string.ime_clock_to), to, DateField.TO, act)
        Spacer(Modifier.height(6.dp))
        ClockCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ClockText.amount(kotlin.math.abs(d.days), MeasureUnit.DAY),
                        color = kb.suggestionText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val parts = listOfNotNull(
                        d.years.takeIf { it > 0 }?.let { ClockText.amount(it.toLong(), MeasureUnit.YEAR) },
                        d.months.takeIf { it > 0 }?.let { ClockText.amount(it.toLong(), MeasureUnit.MONTH) },
                        d.restDays.takeIf { it > 0 && (d.years > 0 || d.months > 0) }?.let { ClockText.amount(it.toLong(), MeasureUnit.DAY) },
                    )
                    if (parts.isNotEmpty()) Text(parts.joinToString(", "), color = kb.secondaryText, fontSize = 12.sp)
                    val weeks = kotlin.math.abs(d.weeks)
                    Text(
                        listOfNotNull(
                            weeks.takeIf { it > 0 }?.let { ClockText.amount(it, MeasureUnit.WEEK) },
                            pluralStringResource(R.plurals.ime_clock_workdays, kotlin.math.abs(d.workdays).toInt(), kotlin.math.abs(d.workdays).toInt()),
                        ).joinToString(" · "),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                    )
                }
                RoundButton(Icons.Outlined.ContentPaste, stringResource(R.string.ime_clock_insert), primary = true) {
                    act(ClockAction.DatesInsert)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun AddDays(ui: ClockUi, today: LocalDay, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val base = ui.dates.base ?: today
    val result = DateMath.add(base, ui.dates.amount.toLong(), ui.dates.unit)
    val zone = TimeZone.getDefault()
    Column(Modifier.verticalScroll(rememberScrollState())) {
        DateStepper(stringResource(R.string.ime_clock_start_date), base, DateField.BASE, act)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Outlined.KeyboardDoubleArrowLeft, stringResource(R.string.ime_clock_less_ten_desc), size = 28.dp) { act(ClockAction.AddAmount(-10)) }
            Spacer(Modifier.width(4.dp))
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_clock_less_one_desc), size = 28.dp) { act(ClockAction.AddAmount(-1)) }
            Text(
                (if (ui.dates.amount > 0) "+" else "") + ui.dates.amount,
                color = kb.suggestionText,
                style = digits(20.sp, FontWeight.Medium),
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_clock_more_one_desc), size = 28.dp) { act(ClockAction.AddAmount(1)) }
            Spacer(Modifier.width(4.dp))
            RoundButton(Icons.Outlined.KeyboardDoubleArrowRight, stringResource(R.string.ime_clock_more_ten_desc), size = 28.dp) { act(ClockAction.AddAmount(10)) }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DateMath.Unit.entries.forEach { unit ->
                ToolPanelChip(stringResource(unitLabel(unit)), selected = ui.dates.unit == unit) { act(ClockAction.AddUnit(unit)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        ClockCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ClockFormats.longDate(result.startMillis(zone), zone),
                        color = kb.suggestionText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.ime_clock_week_day_of_year, result.isoWeek, result.dayOfYear),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                    )
                }
                RoundButton(Icons.Outlined.ContentPaste, stringResource(R.string.ime_clock_insert), primary = true) {
                    act(ClockAction.DatesInsert)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Countdowns(ui: ClockUi, now: Long, today: LocalDay, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val adding = ui.dates.newCountdown
    if (adding != null) {
        Column {
            DateStepper(stringResource(R.string.ime_clock_countdown_date), adding, DateField.COUNTDOWN, act)
            Spacer(Modifier.height(4.dp))
            Text(
                pluralStringResource(
                    R.plurals.ime_clock_days_from_today,
                    (adding.epochDay - today.epochDay).toInt(),
                    (adding.epochDay - today.epochDay).toInt(),
                ),
                color = kb.secondaryText,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.weight(1f))
                PillButton(stringResource(R.string.ime_clock_cancel)) { act(ClockAction.CancelCountdown) }
                PillButton(stringResource(R.string.ime_clock_save), primary = true) { act(ClockAction.SaveCountdown) }
            }
        }
        return
    }
    if (ui.data.countdowns.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Event,
            title = stringResource(R.string.ime_clock_countdowns_empty_title),
            body = stringResource(R.string.ime_clock_countdowns_empty_body),
        ) {
            PillButton(stringResource(R.string.ime_clock_new_countdown), icon = Icons.Outlined.Add, primary = true) {
                act(ClockAction.NewCountdown)
            }
        }
        return
    }
    var menuFor by remember { mutableStateOf<Long?>(null) }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(ui.data.countdowns, key = { it.id }) { c ->
            CountdownCard(c, today, menuFor == c.id, onHold = { menuFor = if (menuFor == c.id) null else c.id }, act = act)
        }
        item(key = "new") {
            PillButton(stringResource(R.string.ime_clock_new_countdown), icon = Icons.Outlined.Add, height = 32.dp) {
                act(ClockAction.NewCountdown)
            }
        }
    }
}

@Composable
private fun CountdownCard(c: Countdown, today: LocalDay, menuOpen: Boolean, onHold: () -> Unit, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    val day = LocalDay.of(c.target, zone)
    val days = (day.epochDay - today.epochDay).toInt()
    ClockCard(
        modifier = Modifier.fillMaxWidth(),
        highlighted = menuOpen,
        onClick = { act(ClockAction.InsertCountdown(c.id)) },
        onLongClick = onHold,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, color = kb.suggestionText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ClockFormats.longDate(c.target, zone), color = kb.secondaryText, fontSize = 11.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    kotlin.math.abs(days).toString(),
                    color = if (days == 0) kb.accent else kb.suggestionText,
                    style = digits(24.sp, FontWeight.Medium),
                )
                Text(
                    when {
                        days > 0 -> pluralStringResource(R.plurals.ime_clock_days_left, days)
                        days < 0 -> pluralStringResource(R.plurals.ime_clock_days_ago, -days)
                        else -> stringResource(R.string.ime_clock_today)
                    },
                    color = kb.secondaryText,
                    fontSize = 10.sp,
                )
            }
            if (menuOpen) {
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Outlined.Delete, stringResource(R.string.ime_clock_delete), size = 32.dp) {
                    act(ClockAction.DeleteCountdown(c.id))
                }
            }
        }
    }
}

/**
 * One date with steppers: a month back, a day back, the date (tap for
 * today, press and hold to type one), a day on, a month on.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DateStepper(label: String, day: LocalDay, field: DateField, act: (ClockAction) -> Unit) {
    val kb = LocalKbTheme.current
    val zone = TimeZone.getDefault()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = kb.secondaryText, fontSize = 11.sp, modifier = Modifier.width(54.dp), maxLines = 1)
        RoundButton(Icons.Outlined.KeyboardDoubleArrowLeft, stringResource(R.string.ime_clock_month_back_desc), size = 28.dp) {
            act(ClockAction.DateStep(field, 0, -1))
        }
        Spacer(Modifier.width(2.dp))
        RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.ime_clock_day_back_desc), size = 28.dp) {
            act(ClockAction.DateStep(field, -1))
        }
        Column(
            Modifier
                .weight(1f)
                .combinedClickable(
                    onLongClickLabel = stringResource(R.string.ime_clock_date_type_desc),
                    onLongClick = { act(ClockAction.DateType(field)) },
                ) { act(ClockAction.DateToday(field)) }
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                ClockFormats.shortDate(day.startMillis(zone), zone),
                color = kb.suggestionText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(day.year.toString(), color = kb.secondaryText, fontSize = 10.sp)
        }
        RoundButton(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.ime_clock_day_on_desc), size = 28.dp) {
            act(ClockAction.DateStep(field, 1))
        }
        Spacer(Modifier.width(2.dp))
        RoundButton(Icons.Outlined.KeyboardDoubleArrowRight, stringResource(R.string.ime_clock_month_on_desc), size = 28.dp) {
            act(ClockAction.DateStep(field, 0, 1))
        }
    }
}

private fun unitLabel(unit: DateMath.Unit): Int = when (unit) {
    DateMath.Unit.DAYS -> R.string.ime_clock_unit_days
    DateMath.Unit.WEEKS -> R.string.ime_clock_unit_weeks
    DateMath.Unit.MONTHS -> R.string.ime_clock_unit_months
    DateMath.Unit.YEARS -> R.string.ime_clock_unit_years
    DateMath.Unit.WORKDAYS -> R.string.ime_clock_unit_workdays
}
