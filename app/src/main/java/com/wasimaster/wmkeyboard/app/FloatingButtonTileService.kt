package com.wasimaster.wmkeyboard.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
 * The floating button, on and off, as a Quick Settings tile (Telegram group):
 * the same switch as Advanced › Floating button, two swipes away from any app.
 *
 * The button draws over other apps, which needs the overlay grant. Turning it
 * on without that grant opens the button's own settings page instead, where
 * the grant is asked for with its disclosure, rather than flipping a switch
 * that would draw nothing.
 */
class FloatingButtonTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        val repository = SettingsRepository(applicationContext)
        scope.launch { draw(repository.settings.first().summonButton.enabled) }
    }

    override fun onClick() {
        super.onClick()
        val repository = SettingsRepository(applicationContext)
        scope.launch {
            val on = repository.settings.first().summonButton.enabled
            if (!on && !Settings.canDrawOverlays(this@FloatingButtonTileService)) {
                openSettingsPage()
                return@launch
            }
            repository.setSummonButton(!on)
            draw(!on)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSettingsPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("wmkeyboard://settings/floating_button"))
            .setPackage(packageName)
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

    private fun draw(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_floating_button)
        tile.label = getString(R.string.qs_floating_button_label)
        tile.updateTile()
    }
}
