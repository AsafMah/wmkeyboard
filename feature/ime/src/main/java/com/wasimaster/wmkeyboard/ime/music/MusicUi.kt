package com.wasimaster.wmkeyboard.ime.music

import android.graphics.Bitmap
import com.wasimaster.wmkeyboard.core.music.MusicCardStyle
import com.wasimaster.wmkeyboard.core.music.MusicHistoryEntry
import com.wasimaster.wmkeyboard.core.music.MusicLink
import com.wasimaster.wmkeyboard.core.music.MusicTextFormat
import com.wasimaster.wmkeyboard.core.music.SongMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class MusicTab { LISTEN, HISTORY }

/** Where the Music ID panel is in its one job: listen, ask, show. */
sealed interface MusicPhase {
    data object Idle : MusicPhase

    /** What the phone itself is playing, read before the microphone was needed. */
    data class NowPlaying(val match: SongMatch, val art: Bitmap?) : MusicPhase

    data class Listening(val level: Float, val secondsLeft: Int) : MusicPhase

    /** The clip is being asked about; [via] names who is being asked. */
    data class Recognizing(val via: String) : MusicPhase

    data class Result(val match: SongMatch, val art: Bitmap?) : MusicPhase

    /** Everyone asked heard the clip and knew no song. [silent] when the microphone heard nothing at all. */
    data class NoMatch(val silent: Boolean) : MusicPhase

    data class Failed(val message: String) : MusicPhase

    data object NeedsMic : MusicPhase

    /** No recogniser can answer: nothing on the list is on and set up. */
    data object NeedsProvider : MusicPhase

    /** Data saving holds the clip back until the user says to use data. */
    data object MeteredAsk : MusicPhase
}

/**
 * Everything the Music ID panel draws, owned by [MusicController] rather
 * than `KeyboardUiState`: the panel is the only reader, and a level meter
 * ticking should not be a reason for the whole keyboard state to change.
 */
data class MusicUi(
    val tab: MusicTab = MusicTab.LISTEN,
    val phase: MusicPhase = MusicPhase.Idle,
    val history: List<MusicHistoryEntry> = emptyList(),
    /** The Picture chip's strip of styles is open under the result. */
    val pictureStrip: Boolean = false,
    /** The recognisers that are on and set up, by name, in list order. */
    val onlineNames: List<String> = emptyList(),
    /** A one-line notice over the panel, cleared on the next action. */
    val notice: String? = null,
    /** Dictation holds the microphone, so Listen waits. */
    val micBusy: Boolean = false,
) {
    /** The song on screen, from a result or from what the phone plays. */
    val match: SongMatch?
        get() = when (val p = phase) {
            is MusicPhase.Result -> p.match
            is MusicPhase.NowPlaying -> p.match
            else -> null
        }

    val art: Bitmap?
        get() = when (val p = phase) {
            is MusicPhase.Result -> p.art
            is MusicPhase.NowPlaying -> p.art
            else -> null
        }
}

sealed interface MusicAction {
    data object Listen : MusicAction

    /** Stop the microphone early and ask about what was heard. */
    data object Stop : MusicAction

    /** Listen even though the phone is playing something itself. */
    data object ListenAnyway : MusicAction

    /** Type the song in [format], or in the default format when null. */
    data class Insert(val format: MusicTextFormat? = null) : MusicAction

    /** Put the song into the field as a picture in [style], or the default style when null. */
    data class InsertPicture(val style: MusicCardStyle? = null) : MusicAction

    data class PictureStrip(val open: Boolean) : MusicAction

    data class Open(val link: MusicLink) : MusicAction

    data object Copy : MusicAction

    data class Tab(val tab: MusicTab) : MusicAction

    /** A history row: the song back on the Listen tab, ready to insert. */
    data class HistoryPick(val id: Long) : MusicAction

    data class HistoryDelete(val id: Long) : MusicAction

    data object HistoryClear : MusicAction

    /** Data saving asked; the user says yes for this session. */
    data object UseData : MusicAction

    data object RequestMic : MusicAction

    data object OpenSettings : MusicAction

    data object Dismiss : MusicAction
}

/** What the Music ID panel hands back; one bundle, see `ToolHoldCallbacks.music`. */
data class MusicCallbacks(
    val state: StateFlow<MusicUi> = MutableStateFlow(MusicUi()),
    val onAction: (MusicAction) -> Unit = {},
)
