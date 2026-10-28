package com.wasimaster.wmkeyboard.ime

import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.ActionMode
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.annotation.RequiresApi
import androidx.core.view.WindowInsetsCompat
import com.wasimaster.wmkeyboard.core.debug.EnterIncidentRecorder
import com.wasimaster.wmkeyboard.core.debug.IncidentEvent
import java.util.Locale

/**
 * The keyboard's side of [EnterIncidentRecorder]: what to note at each
 * boundary an Enter press crosses, and nothing about what was typed.
 *
 * Touches are noted only where the bottom row's outer keys and the system's
 * keyboard bar sit — the bottom of the window, at either edge — so letters,
 * the space bar and gesture typing never are. Which view the touch landed on
 * is noted by class and resource name, which is what tells a press the
 * keyboard read from one the system's Back button took.
 */
internal object EnterIncidentProbe {

    private val rec get() = EnterIncidentRecorder

    fun on(): Boolean = rec.isRecording()

    // ---- lifecycle ----

    fun editor(event: IncidentEvent, info: EditorInfo?, restarting: Boolean) {
        if (!on()) return
        rec.record(
            event,
            "restarting" to restarting,
            "pkg" to info?.packageName,
            "inputType" to info?.inputType?.let(::hex),
            "imeOptions" to info?.imeOptions?.let(::hex),
            "actionId" to info?.actionId,
            "hasActionLabel" to (info?.actionLabel != null),
        )
    }

    fun note(event: IncidentEvent, vararg fields: Pair<String, Any?>) {
        if (on()) rec.record(event, *fields)
    }

    /**
     * Where a hide was asked for from: the nearest [CALLER_FRAMES] frames,
     * each its own field (from1 nearest) so none is cut by the token cap.
     */
    fun hide(event: IncidentEvent, vararg fields: Pair<String, Any?>) {
        if (!on()) return
        rec.record(event, *fields, *callers())
    }

    private const val CALLER_FRAMES = 6

    private fun callers(): Array<Pair<String, Any?>> = Throwable().stackTrace
        .drop(1)
        .filterNot { it.className.startsWith(EnterIncidentProbe::class.java.name) }
        .take(CALLER_FRAMES)
        .mapIndexed { i, frame ->
            // Simple class name, method and line: what fits a 64-character token.
            val cls = frame.className.substringAfterLast('.').takeLast(30)
            "from${i + 1}" to "$cls.${frame.methodName.take(24)}:${frame.lineNumber}"
        }
        .toTypedArray()

    // ---- keys ----

    /** Back and Enter as key events: a hardware key, an injected one, or the system bar's Back. */
    fun systemKey(event: KeyEvent, handled: Boolean, inputShown: Boolean) {
        if (!on()) return
        if (event.keyCode != KeyEvent.KEYCODE_BACK && event.keyCode != KeyEvent.KEYCODE_ENTER &&
            event.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            return
        }
        rec.record(
            IncidentEvent.SYSTEM_KEY,
            "key" to KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_"),
            "action" to if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP",
            "repeat" to event.repeatCount,
            "flags" to hex(event.flags),
            "virtualHard" to (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY != 0),
            "canceled" to event.isCanceled,
            "source" to hex(event.source),
            "deviceId" to event.deviceId,
            "downTime" to event.downTime,
            "eventTime" to event.eventTime,
            "handled" to handled,
            "inputShown" to inputShown,
        )
    }

    /** An on-screen key whose action is one of Enter's, as it reaches the service. */
    fun enterKey(action: String, deferred: Boolean) {
        if (on()) rec.record(IncidentEvent.ENTER_KEY, "action" to action, "deferred" to deferred)
    }

    fun route(route: String, vararg fields: Pair<String, Any?>) {
        if (on()) rec.record(IncidentEvent.ENTER_ROUTE, "route" to route, *fields)
    }

    fun ic(op: String, vararg fields: Pair<String, Any?>) {
        if (on()) rec.record(IncidentEvent.IC, "op" to op, *fields)
    }

    /**
     * [ic] seen through a wrapper that notes each editing call before it is
     * made and what it returned or threw — for the length of one Enter press
     * only, so ordinary typing is never traced. The before line is what shows
     * a call during which the field hid the keyboard, which would otherwise
     * leave no result to note. Whether to note is asked per call, so the
     * switch takes effect on a connection already open.
     */
    fun enterConnection(ic: InputConnection): InputConnection = if (on()) EnterConnection(ic) else ic

    private class EnterConnection(target: InputConnection) : InputConnectionWrapper(target, false) {

        private inline fun <T> call(op: String, vararg fields: Pair<String, Any?>, block: () -> T): T {
            if (!on()) return block()
            rec.record(IncidentEvent.IC, "op" to op, "phase" to "before", *fields)
            val result = try {
                block()
            } catch (e: RuntimeException) {
                rec.record(IncidentEvent.IC, "op" to op, "phase" to "threw", "error" to e.javaClass.simpleName)
                throw e
            }
            rec.record(IncidentEvent.IC, "op" to op, "phase" to "after", "result" to result)
            return result
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            call("commitText", "newline" to (text?.toString() == "\n"), "empty" to text.isNullOrEmpty()) {
                super.commitText(text, newCursorPosition)
            }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            call("setComposingText", "empty" to text.isNullOrEmpty()) { super.setComposingText(text, newCursorPosition) }

        override fun finishComposingText(): Boolean = call("finishComposingText") { super.finishComposingText() }

        override fun setComposingRegion(start: Int, end: Int): Boolean =
            call("setComposingRegion") { super.setComposingRegion(start, end) }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            call("deleteSurroundingText", "before" to beforeLength, "after" to afterLength) {
                super.deleteSurroundingText(beforeLength, afterLength)
            }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            call("deleteSurroundingTextInCodePoints", "before" to beforeLength, "after" to afterLength) {
                super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            }

        override fun setSelection(start: Int, end: Int): Boolean = call("setSelection") { super.setSelection(start, end) }

        override fun sendKeyEvent(event: KeyEvent?): Boolean = call(
            "sendKeyEvent",
            "key" to event?.keyCode?.let(::keyName),
            "action" to event?.action,
            "meta" to event?.metaState?.let(::hex),
        ) { super.sendKeyEvent(event) }

        override fun performEditorAction(editorAction: Int): Boolean =
            call("performEditorAction", "actionId" to editorAction) { super.performEditorAction(editorAction) }

        override fun performContextMenuAction(id: Int): Boolean =
            call("performContextMenuAction", "id" to id) { super.performContextMenuAction(id) }

        override fun commitCompletion(text: CompletionInfo?): Boolean = call("commitCompletion") { super.commitCompletion(text) }

        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean =
            call("commitCorrection") { super.commitCorrection(correctionInfo) }

        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
            call("performPrivateCommand") { super.performPrivateCommand(action, data) }

        override fun beginBatchEdit(): Boolean = call("beginBatchEdit") { super.beginBatchEdit() }

        override fun endBatchEdit(): Boolean = call("endBatchEdit") { super.endBatchEdit() }

        /** Enter's own keys by name; any other key only as "other", so no key history builds up. */
        private fun keyName(code: Int): String = when (code) {
            KeyEvent.KEYCODE_ENTER -> "ENTER"
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "NUMPAD_ENTER"
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "SHIFT"
            KeyEvent.KEYCODE_BACK -> "BACK"
            else -> "other"
        }
    }

    // ---- geometry ----

    private var lastInsets = ""

    fun insets(insets: WindowInsets, view: View) {
        if (!on()) return
        val compat = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
        val nav = compat.getInsets(WindowInsetsCompat.Type.navigationBars())
        val caption = compat.getInsets(WindowInsetsCompat.Type.captionBar())
        val gestures = compat.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        val key = "$nav $caption $gestures"
        if (key == lastInsets) return
        lastInsets = key
        rec.record(
            IncidentEvent.INSETS,
            "navBottom" to nav.bottom,
            "navLeft" to nav.left,
            "navRight" to nav.right,
            "captionBottom" to caption.bottom,
            "captionVisible" to compat.isVisible(WindowInsetsCompat.Type.captionBar()),
            "gestureBottom" to gestures.bottom,
            "viewH" to view.height,
        )
    }

    private var lastViews = ""

    fun views(window: Window?, inputView: View?, language: String?) {
        if (!on()) return
        val decor = window?.peekDecorView() ?: return
        val r = inputView?.let { boundsInWindow(it) }
        val systemRtl = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
        val key = "${decor.width}x${decor.height} $r ${decor.layoutDirection} $systemRtl $language"
        if (key == lastViews) return
        lastViews = key
        rec.record(
            IncidentEvent.WINDOW_VIEWS,
            "decorW" to decor.width,
            "decorH" to decor.height,
            "viewL" to r?.left,
            "viewT" to r?.top,
            "viewR" to r?.right,
            "viewB" to r?.bottom,
            "decorRtl" to (decor.layoutDirection == View.LAYOUT_DIRECTION_RTL),
            "viewRtl" to inputView?.let { it.layoutDirection == View.LAYOUT_DIRECTION_RTL },
            "systemRtl" to systemRtl,
            "systemLocale" to Locale.getDefault().toLanguageTag(),
            "lang" to language,
        )
    }

    private fun boundsInWindow(view: View): Rect {
        val at = IntArray(2)
        view.getLocationInWindow(at)
        return Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    /**
     * Whether a touch at ([x], [y]) in a window [width] wide is one worth
     * noting: below the top of the keyboard's bottom row (taken as the lowest
     * [BOTTOM_ZONE] of its input view) or below the view altogether, and in
     * the outer [EDGE_ZONE] at either side.
     */
    fun inZone(x: Float, y: Float, width: Int, viewTop: Int, viewBottom: Int): Boolean {
        if (width <= 0 || viewBottom <= viewTop) return false
        val zoneTop = viewBottom - (viewBottom - viewTop) * BOTTOM_ZONE
        if (y < zoneTop) return false
        return x < width * EDGE_ZONE || x > width * (1 - EDGE_ZONE)
    }

    const val BOTTOM_ZONE = 0.22f
    const val EDGE_ZONE = 0.25f

    /**
     * Puts an observer in front of the keyboard window's own touch handling.
     * Every call goes through to the window's callback unchanged; the observer
     * only reads.
     */
    fun install(window: Window, inputView: () -> View?, language: () -> String?) {
        val base = window.callback ?: return
        if (base is TouchObserver) return
        window.callback = TouchObserver(base, window, inputView, language)
    }

    private class Tracked(val downX: Float, val downY: Float, var maxMove: Float = 0f)

    internal class TouchObserver(
        private val base: Window.Callback,
        private val window: Window,
        private val inputView: () -> View?,
        private val language: () -> String?,
    ) : Window.Callback by base {

        private val tracked = HashMap<Int, Tracked>()

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (!on()) {
                tracked.clear()
                return base.dispatchTouchEvent(event)
            }
            val noted = runCatching { before(event) }.getOrDefault(false)
            val handled = base.dispatchTouchEvent(event)
            if (noted) {
                runCatching {
                    rec.record(
                        IncidentEvent.TOUCH,
                        "phase" to "after",
                        "action" to actionName(event.actionMasked),
                        "eventTime" to event.eventTime,
                        "handled" to handled,
                    )
                }
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                tracked.clear()
            }
            return handled
        }

        /** Notes the event if it starts or continues a zone touch; true when it did. */
        private fun before(event: MotionEvent): Boolean {
            val masked = event.actionMasked
            val index = event.actionIndex
            val id = event.getPointerId(index)
            when (masked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val decor = window.peekDecorView() ?: return false
                    val view = inputView()
                    views(window, view, language())
                    val r = view?.let { boundsInWindow(it) } ?: Rect(0, 0, decor.width, decor.height)
                    val x = event.getX(index)
                    val y = event.getY(index)
                    if (!inZone(x, y, decor.width, r.top, r.bottom)) return false
                    tracked[id] = Tracked(x, y)
                    val hit = deepestAt(decor, x.toInt(), y.toInt())
                    rec.record(
                        IncidentEvent.TOUCH,
                        "action" to actionName(masked),
                        "pointer" to id,
                        "x" to x.toInt(),
                        "y" to y.toInt(),
                        "rawY" to event.rawY.toInt(),
                        "inView" to r.contains(x.toInt(), y.toInt()),
                        // The deepest visible view under the point, from bounds alone: a
                        // candidate, not the confirmed recipient (z-order, interception
                        // and touch delegates are not considered).
                        "hitCandidate" to hit?.let(::describe),
                        "pointers" to event.pointerCount,
                        "tool" to event.getToolType(index),
                        "source" to hex(event.source),
                        "downTime" to event.downTime,
                        "eventTime" to event.eventTime,
                    )
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val t = tracked[event.getPointerId(i)] ?: continue
                        val dx = event.getX(i) - t.downX
                        val dy = event.getY(i) - t.downY
                        t.maxMove = maxOf(t.maxMove, kotlin.math.hypot(dx, dy))
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val ids = if (masked == MotionEvent.ACTION_CANCEL) {
                        (0 until event.pointerCount).map { event.getPointerId(it) }
                    } else {
                        listOf(id)
                    }
                    var any = false
                    for (pid in ids) {
                        val t = tracked.remove(pid) ?: continue
                        val i = event.findPointerIndex(pid).takeIf { it >= 0 } ?: index
                        rec.record(
                            IncidentEvent.TOUCH,
                            "action" to actionName(masked),
                            "pointer" to pid,
                            "x" to event.getX(i).toInt(),
                            "y" to event.getY(i).toInt(),
                            "maxMove" to t.maxMove.toInt(),
                            "canceled" to (masked == MotionEvent.ACTION_CANCEL ||
                                event.flags and MotionEvent.FLAG_CANCELED != 0),
                            "downTime" to event.downTime,
                            "eventTime" to event.eventTime,
                        )
                        any = true
                    }
                    return any
                }
            }
            return false
        }

        // Delegated by hand: Kotlin's `by` leaves a Java default method to the
        // interface's own empty body rather than forwarding it.
        override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
            base.onProvideKeyboardShortcuts(data, menu, deviceId)

        @RequiresApi(26)
        override fun onPointerCaptureChanged(hasCapture: Boolean) = base.onPointerCaptureChanged(hasCapture)

        override fun onSearchRequested(searchEvent: SearchEvent?): Boolean = base.onSearchRequested(searchEvent)

        override fun onWindowStartingActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
            base.onWindowStartingActionMode(callback, type)
    }

    /** The deepest visible view whose bounds hold a point in window coordinates; a guess at the recipient. */
    private fun deepestAt(root: View, x: Int, y: Int): View? {
        if (root.visibility != View.VISIBLE) return null
        val r = boundsInWindow(root)
        if (!r.contains(x, y)) return null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                deepestAt(root.getChildAt(i), x, y)?.let { return it }
            }
        }
        return root
    }

    /** A view's class and resource name: what it is, never what it shows. */
    private fun describe(view: View): String {
        val name = runCatching {
            if (view.id != View.NO_ID) view.resources.getResourceEntryName(view.id) else null
        }.getOrNull()
        return view.javaClass.simpleName.ifEmpty { "anon" } + (name?.let { ":$it" } ?: "") +
            ":" + view.javaClass.`package`?.name?.substringBefore('.').orEmpty()
    }

    private fun actionName(masked: Int) = when (masked) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_POINTER_DOWN -> "PDOWN"
        MotionEvent.ACTION_POINTER_UP -> "PUP"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_MOVE -> "MOVE"
        else -> "A$masked"
    }

    private fun hex(value: Int) = "0x" + Integer.toHexString(value)
}
