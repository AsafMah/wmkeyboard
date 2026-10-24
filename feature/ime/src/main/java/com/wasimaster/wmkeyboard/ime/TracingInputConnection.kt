package com.wasimaster.wmkeyboard.ime

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.debug.EnterTraceEvent

/**
 * Calls the keyboard makes into the field, written to the opt-in phone
 * diagnostic log and, separately, to logcat for
 * working out which of them an editor reacts to (#760: Gmail's compose body
 * dropping the keyboard after one of WM's keystrokes, and not after
 * HeliBoard's).
 *
 * The phone trace accepts only fixed event names, before dispatch to the editor.
 * The older, more detailed logcat trace is off unless asked for on the device:
 *
 *     adb shell setprop log.tag.WMKB-IC VERBOSE
 *
 * then `adb logcat -s WMKB-IC`. Only method names, offsets and lengths are
 * logged, never the text, so a trace can be shared like the debug log.
 */
internal class TracingInputConnection(val base: InputConnection) : InputConnectionWrapper(base, true) {

    private val logcatEnabled = enabled()

    override fun beginBatchEdit(): Boolean =
        log(EnterTraceEvent.BEGIN_BATCH_EDIT, { "beginBatchEdit" }) { super.beginBatchEdit() }

    override fun endBatchEdit(): Boolean =
        log(EnterTraceEvent.END_BATCH_EDIT, { "endBatchEdit" }) { super.endBatchEdit() }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        log(EnterTraceEvent.COMMIT_TEXT, { "commitText len=${text?.length} pos=$newCursorPosition" }) {
            super.commitText(text, newCursorPosition)
        }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        log(EnterTraceEvent.SET_COMPOSING_TEXT, { "setComposingText len=${text?.length} pos=$newCursorPosition" }) {
            super.setComposingText(text, newCursorPosition)
        }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        log(EnterTraceEvent.SET_COMPOSING_REGION, { "setComposingRegion $start..$end" }) {
            super.setComposingRegion(start, end)
        }

    override fun finishComposingText(): Boolean =
        log(EnterTraceEvent.FINISH_COMPOSING_TEXT, { "finishComposingText" }) { super.finishComposingText() }

    override fun setSelection(start: Int, end: Int): Boolean =
        log(EnterTraceEvent.SET_SELECTION, { "setSelection $start..$end" }) { super.setSelection(start, end) }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        log(EnterTraceEvent.DELETE_SURROUNDING_TEXT, { "deleteSurroundingText $beforeLength,$afterLength" }) {
            super.deleteSurroundingText(beforeLength, afterLength)
        }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        log(
            EnterTraceEvent.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS,
            { "deleteSurroundingTextInCodePoints $beforeLength,$afterLength" },
        ) {
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

    override fun sendKeyEvent(event: KeyEvent?): Boolean =
        log(
            EnterTraceEvent.SEND_KEY_EVENT,
            { "sendKeyEvent action=${event?.action} code=${event?.keyCode} meta=${event?.metaState}" },
        ) {
            super.sendKeyEvent(event)
        }

    override fun performEditorAction(editorAction: Int): Boolean =
        log(EnterTraceEvent.PERFORM_EDITOR_ACTION, { "performEditorAction $editorAction" }) {
            super.performEditorAction(editorAction)
        }

    override fun performContextMenuAction(id: Int): Boolean =
        log(EnterTraceEvent.PERFORM_CONTEXT_MENU_ACTION, { "performContextMenuAction $id" }) {
            super.performContextMenuAction(id)
        }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
        log(EnterTraceEvent.PERFORM_PRIVATE_COMMAND, { "performPrivateCommand $action" }) {
            super.performPrivateCommand(action, data)
        }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
        log(EnterTraceEvent.REQUEST_CURSOR_UPDATES, { "requestCursorUpdates $cursorUpdateMode" }) {
            super.requestCursorUpdates(cursorUpdateMode)
        }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        trace(EnterTraceEvent.GET_TEXT_BEFORE_CURSOR) {
            super.getTextBeforeCursor(n, flags).also {
                if (logcatEnabled) Log.v(TAG, "getTextBeforeCursor $n -> ${it?.length}")
            }
        }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        trace(EnterTraceEvent.GET_TEXT_AFTER_CURSOR) {
            super.getTextAfterCursor(n, flags).also {
                if (logcatEnabled) Log.v(TAG, "getTextAfterCursor $n -> ${it?.length}")
            }
        }

    override fun getSelectedText(flags: Int): CharSequence? =
        trace(EnterTraceEvent.GET_SELECTED_TEXT) {
            super.getSelectedText(flags).also {
                if (logcatEnabled) Log.v(TAG, "getSelectedText -> ${it?.length}")
            }
        }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        trace(EnterTraceEvent.GET_EXTRACTED_TEXT) {
            super.getExtractedText(request, flags).also {
                if (logcatEnabled) {
                    Log.v(TAG, "getExtractedText -> start=${it?.startOffset} sel=${it?.selectionStart}..${it?.selectionEnd}")
                }
            }
        }

    private inline fun log(event: EnterTraceEvent, call: () -> String, block: () -> Boolean): Boolean =
        trace(event) { block().also { if (logcatEnabled) Log.v(TAG, "${call()} -> $it") } }

    private inline fun <T> trace(event: EnterTraceEvent, block: () -> T): T {
        DebugLog.traceEnter(event)
        return block()
    }

    companion object {
        const val TAG = "WMKB-IC"

        /** Read once per connection by the service, not on every call. */
        fun enabled(): Boolean = Log.isLoggable(TAG, Log.VERBOSE)

        /** The editor's side of the conversation, for the same trace. */
        fun noteSelection(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int, candStart: Int, candEnd: Int) {
            if (!enabled()) return
            Log.v(TAG, "onUpdateSelection $oldStart..$oldEnd -> $newStart..$newEnd cand=$candStart..$candEnd")
        }
    }
}
