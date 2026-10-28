package com.wasimaster.wmkeyboard.ime.share

import androidx.compose.ui.graphics.ImageBitmap
import com.wasimaster.wmkeyboard.core.share.SharePreview
import com.wasimaster.wmkeyboard.core.share.ShareTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the share panel draws (#970), owned by [ShareController] rather
 * than `KeyboardUiState`: the panel is the only reader. The pinned and recent
 * lists are settings, read from `KeyboardUiState.settings.share`.
 */
data class ShareUi(
    /** What is being shared, for the line at the top. */
    val preview: SharePreview = SharePreview(),
    /** Every app that takes this share, in the package manager's order. */
    val targets: List<ShareTarget> = emptyList(),
    val loading: Boolean = false,
    /** The share is text the field could take, so the sheet offers Insert. */
    val insertable: Boolean = false,
    /** The target the hold menu is open on. */
    val held: ShareTarget? = null,
)

sealed interface ShareAction {
    /** Send the share to [target]. */
    data class Pick(val target: ShareTarget) : ShareAction

    /** Open the hold menu on [target], or close it with null. */
    data class Hold(val target: ShareTarget?) : ShareAction

    data class TogglePin(val target: ShareTarget) : ShareAction

    data class ForgetRecent(val target: ShareTarget) : ShareAction

    /** The text, or the first file, onto the clipboard. */
    data object Copy : ShareAction

    /** The link, or the one file, in the app that opens it. */
    data object Open : ShareAction

    /** The text into the field, in place of sharing it. */
    data object Insert : ShareAction

    /** Android's own chooser, for its direct-share contacts and anything else it offers. */
    data object System : ShareAction

    data object Dismiss : ShareAction
}

/** What the share panel hands back; one bundle, see `ToolHoldCallbacks.share`. */
data class ShareCallbacks(
    val state: StateFlow<ShareUi> = MutableStateFlow(ShareUi()),
    val onAction: (ShareAction) -> Unit = {},
    /** One target's icon, through the controller's cache, decoded off the main thread. */
    val iconFor: suspend (ShareTarget) -> ImageBitmap? = { null },
)
