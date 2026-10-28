package com.wasimaster.wmkeyboard.ime.ui

import android.graphics.Insets
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import com.wasimaster.wmkeyboard.core.clipboard.ClipItem
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The keys stay clear of the system's keyboard bar while it is shown.
 *
 * The system adds that bar to the keyboard's own window, along its bottom and
 * over the keys, and its hide button sends Back from the touch. One UI reports
 * the bar as a visible caption bar with a navigation bar inset of 0, and the
 * keys laid out under it: the lower part of Enter, at the right end of the
 * bottom row, sat under the hide button, so a tap there hid the keyboard
 * instead of entering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxxhdpi")
class KeyboardBarClearanceTest {

    @Test
    fun shownCaptionBarCountsWhenTheNavigationBarIsZero() {
        assertEquals(BAR, insetsOf(nav = 0, caption = BAR).bottom)
    }

    @Test
    fun navigationBarCoveringTheCaptionBarIsKept() {
        assertEquals(BAR, insetsOf(nav = BAR, caption = BAR).bottom)
        assertEquals(BAR, insetsOf(nav = BAR, caption = 0).bottom)
    }

    @Test
    fun noBarsNoInset() {
        assertEquals(0, insetsOf(nav = 0, caption = 0).bottom)
    }

    @Test
    fun enterIsNotUnderTheShownBarWhenTheNavigationBarIsZero() {
        val bottom = Board(nav = 0, caption = BAR).drawnEnterBottom()
        assertTrue("Enter drawn ${BAR - bottom}px under the keyboard bar", bottom >= BAR - STEP)
    }

    @Test
    fun enterIsNotUnderTheBarWhenTheNavigationBarCoversIt() {
        val bottom = Board(nav = BAR, caption = BAR).drawnEnterBottom()
        assertTrue("Enter drawn ${BAR - bottom}px under the keyboard bar", bottom >= BAR - STEP)
    }

    private fun insetsOf(nav: Int, caption: Int): ImeNavigationBars {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return ImeNavigationBars().apply { update(windowInsets(nav, caption), View(activity)) }
    }

    private fun windowInsets(nav: Int, caption: Int): WindowInsets = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, nav))
        .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, nav))
        .setVisible(WindowInsets.Type.navigationBars(), true)
        .setInsets(WindowInsets.Type.captionBar(), Insets.of(0, 0, 0, caption))
        .setInsetsIgnoringVisibility(WindowInsets.Type.captionBar(), Insets.of(0, 0, 0, caption))
        .setVisible(WindowInsets.Type.captionBar(), caption > 0)
        .setInsets(WindowInsets.Type.mandatorySystemGestures(), Insets.of(0, 0, 0, maxOf(nav, caption)))
        .build()

    /** The keyboard window's frame and screen, at the height the window gives it. */
    private inner class Board(nav: Int, caption: Int) {
        private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        private val width = activity.resources.displayMetrics.widthPixels
        private val height = (WINDOW_DP * activity.resources.displayMetrics.density).toInt()
        private val keys = ArrayList<String>()
        private val root = FrameLayout(activity)

        init {
            val bars = ImeNavigationBars()
            val frame = StableMeasureFrame(activity, bars)
            val insets = windowInsets(nav, caption)
            // Recorded before the first composition: a later change waits on
            // a frame that Robolectric's Compose clock does not always run
            // for the second board in a JVM.
            frame.dispatchApplyWindowInsets(insets)
            val screen = ComposeView(activity)
            val state = MutableStateFlow(KeyboardUiState())
            screen.setContent {
                CompositionLocalProvider(LocalImeNavigationBars provides bars) {
                    KeyboardScreen(
                        stateFlow = state,
                        onKey = { k: Key -> keys += k.action.toString() },
                        onSuggestion = {},
                        onEmoji = {},
                        onEmojiQueryTap = {},
                        onPanelChange = { _: PanelMode -> },
                        onClipboardItem = { _: ClipItem -> },
                        onClipboardPin = { _: ClipItem -> },
                        onClipboardDelete = { _: ClipItem -> },
                    )
                }
            }
            frame.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            activity.setContentView(root, ViewGroup.LayoutParams(width, height))
            idle()
            root.dispatchApplyWindowInsets(insets)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
            idle()
        }

        /**
         * How far above the window's bottom Enter is drawn. Its touch area
         * reaches on down to the bottom (#649), so it is read off the rows
         * instead: Enter's top, less the height of the row over it.
         */
        fun drawnEnterBottom(): Int {
            val taps = column(fromBottom = 1 until height / 2)
            val enterTop = taps.indexOfLast { it == ENTER } + 1
            val above = taps.drop(enterTop).firstOrNull { it != null }
            val aboveTop = taps.indexOfLast { it == above } + 1
            assertTrue("no Enter and key over it in the column: $taps", enterTop > 0 && above != null && aboveTop > enterTop)
            return (enterTop - (aboveTop - enterTop)) * STEP
        }

        /** What a tap sends at each height in [fromBottom], [STEP] px apart, near the right edge where Enter is. */
        fun column(fromBottom: IntRange): List<String?> = fromBottom.step(STEP).map { up ->
            keys.clear()
            val x = width - 24f
            val y = (height - up).toFloat()
            val t = SystemClock.uptimeMillis()
            root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
            root.dispatchTouchEvent(MotionEvent.obtain(t, t + 33, MotionEvent.ACTION_UP, x, y, 0))
            idle()
            keys.singleOrNull()
        }

        private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    }

    private companion object {
        /** One UI's keyboard bar on a 3.75 density phone, 45dp; here a 4.0 density, so 48dp. */
        const val BAR = 192
        const val WINDOW_DP = 526
        const val ENTER = "Enter"
        const val STEP = 4
    }
}
