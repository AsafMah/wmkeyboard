package com.wasimaster.wmkeyboard.ime.speedtest

import androidx.compose.ui.graphics.ImageBitmap
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardLayout
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardPalette
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSize
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardSpec
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedCardStyle
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestPhase
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedTestResult
import com.wasimaster.wmkeyboard.core.tools.speedtest.SpeedUnit

/** Where the speed test panel is. */
enum class SpeedTestStage {
    /** Nothing run yet in this process and nothing remembered: the Start button. */
    IDLE,

    /** Data saving wants a word first: how much a run costs, and whether it may go. */
    ASK_METERED,
    RUNNING,

    /** A result is on screen, with its card and the ways to send it. */
    DONE,
    ERROR,
}

/**
 * The speed test panel's state, held by [SpeedTestController] and published
 * on the keyboard's state so the panel reads one copy. [preview] is the card
 * drawn small for the panel; the full-size one is drawn when it is sent.
 */
data class SpeedTestUi(
    val stage: SpeedTestStage = SpeedTestStage.IDLE,
    /** The phase under way while [SpeedTestStage.RUNNING]. */
    val phase: SpeedTestPhase? = null,
    /** The speed right now, for the gauge. */
    val liveMbps: Double = 0.0,
    /** The running phase's samples, for the live line. */
    val liveSamples: List<Float> = emptyList(),
    /** The median ping once the ping phase is done. */
    val pingMs: Double? = null,
    /** The run on screen: just finished, or the last one remembered. */
    val result: SpeedTestResult? = null,
    /** The run before [result], for the card's "vs last run" line. */
    val previous: SpeedTestResult? = null,
    val preview: ImageBitmap? = null,
    /** What [preview] was drawn with, so a stale preview can be told from the chips. */
    val previewSpec: SpeedCardSpec? = null,
    /** The full-size card is being drawn and written for a send. */
    val rendering: Boolean = false,
    val error: String? = null,
    /** What a run is likely to move, for the data note and the metered question. */
    val estimatedMb: Int = 0,
    /** On [SpeedTestStage.ASK_METERED]: whether "Run anyway" is offered, or the policy says no. */
    val canAllow: Boolean = true,
    /** The server a run goes to, named on the panel while it runs. */
    val serverLabel: String = "",
)

/** One control on the speed test panel. */
sealed interface SpeedTestAction {
    data object Start : SpeedTestAction

    /** "Run anyway" on the metered question: grants the feature for the session and starts. */
    data object AllowMetered : SpeedTestAction
    data object Cancel : SpeedTestAction

    /** A fresh run from the results screen, or after an error. */
    data object Again : SpeedTestAction
    data class SetStyle(val style: SpeedCardStyle) : SpeedTestAction
    data class SetSize(val size: SpeedCardSize) : SpeedTestAction
    data class SetLayout(val layout: SpeedCardLayout) : SpeedTestAction
    data class SetUnit(val unit: SpeedUnit) : SpeedTestAction

    /** The card into the field. */
    data object Send : SpeedTestAction

    /** The card into Pictures/WM Keyboard. */
    data object Save : SpeedTestAction

    /** The card to another app's share screen. */
    data object Share : SpeedTestAction

    /** The result as one line of text into the field. */
    data object InsertText : SpeedTestAction

    /**
     * The keyboard theme's colours, as the panel sees them. The card's
     * [SpeedCardStyle.THEME] draws with these; the panel sends them when it
     * composes and whenever the theme changes under it.
     */
    data class Theme(val palette: SpeedCardPalette) : SpeedTestAction
}
