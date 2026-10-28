package com.wasimaster.wmkeyboard.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.ime.KeyboardControls

/**
 * "Show keyboard", as a Quick Settings tile: brings the keyboard up over
 * whatever app is in front, field or not, the way the shade controls' Show
 * button does.
 *
 * Both are the same summon ([KeyboardControls.Host.showKeyboard]). Pulling
 * the shade down takes the focus from the app, and the app getting it back
 * with no field would have Android hide the keyboard again; the summon's
 * forced show survives that hide, and it keeps asking until the shade is out
 * of the way. What the tile has to add is the collapse: a notification button
 * closes the shade by itself, a tile only through starting an activity. So it
 * starts [ShadeCollapseActivity], which draws nothing and is gone at once.
 *
 * Unavailable while WM Keyboard is not the keyboard in use: there is then no
 * keyboard of ours to bring up.
 */
class ShowKeyboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        draw()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::summon) else summon()
    }

    private fun summon() {
        val host = KeyboardControls.host ?: return draw()
        host.showKeyboard()
        collapse()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun collapse() {
        val intent = Intent(this, ShadeCollapseActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun draw() {
        val tile = qsTile ?: return
        tile.state = if (KeyboardControls.host != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_show_keyboard)
        tile.label = getString(R.string.qs_show_keyboard_label)
        tile.updateTile()
    }
}

/**
 * What [ShowKeyboardTileService] starts so that Android closes the shade.
 * No window (Theme.NoDisplay) and no task of its own to come back to, so the
 * app underneath keeps the focus the summon is waiting for.
 */
class ShadeCollapseActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
