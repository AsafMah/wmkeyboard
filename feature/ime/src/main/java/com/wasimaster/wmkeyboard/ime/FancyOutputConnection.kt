package com.wasimaster.wmkeyboard.ime

import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.wasimaster.wmkeyboard.core.script.FancyStyle
import com.wasimaster.wmkeyboard.core.script.FancyStyles

/**
 * The field's connection while Fancy Text types a [style] (#950). The keyboard
 * composes, predicts and corrects plain letters; this is where they turn into
 * the style, whichever path committed them: a word off the space bar, a strip
 * pick, an autocorrection, a glide, a dictated phrase.
 *
 * Only commits are restyled here. The word being composed is already styled
 * by the service (see `composedPreview` there), because the keyboard measures
 * and reads back its own composing region and has to find what it put there;
 * restyling it again here would be harmless for every style that composes
 * (styling styled text changes nothing), but the count would no longer be the
 * service's own.
 *
 * Spans are dropped on the way: a styled word is a different string, and an
 * autocorrection mark offering the plain spelling back would offer the wrong
 * text.
 */
internal class FancyOutputConnection(
    val base: InputConnection,
    val style: FancyStyle,
) : InputConnectionWrapper(base, true) {

    /** Set around a commit that is already styled, or must stay plain. */
    var passThrough = false

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (passThrough || text.isNullOrEmpty()) return super.commitText(text, newCursorPosition)
        return super.commitText(FancyStyles.transform(text.toString(), style), newCursorPosition)
    }
}
