package com.wasimaster.wmkeyboard.app

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "Keep keyboard on screen", as a Quick Settings tile.
 *
 * The same setting the toolbar's pin tool and the Layout screen's row write
 * (issue #58), reachable from the one place that is always two swipes away
 * whatever app is in front. It exists alongside the shade controls rather than
 * instead of them: a tile needs no permission at all, so it still works for
 * someone who refused notifications.
 *
 * Showing the keyboard is a tile of its own, [ShowKeyboardTileService]: a
 * summon, which outlasts the focus the shade takes from the app, rather than a
 * state this one could toggle.
 */
class PinKeyboardTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val repository = SettingsRepository(applicationContext)
        scope.launch {
            val pinned = repository.settings.first().persistentKeyboard
            repository.setPersistentKeyboard(!pinned)
            draw(!pinned)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun refresh() {
        val repository = SettingsRepository(applicationContext)
        scope.launch { draw(repository.settings.first().persistentKeyboard) }
    }

    private fun draw(pinned: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (pinned) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_pin_keyboard)
        tile.label = getString(R.string.qs_pin_keyboard_label)
        tile.updateTile()
    }
}
