package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "WM Keyboard" and "WM Translate" in other apps' text selection menus
 * (#916), both activity aliases of [TextActionsActivity], so switching one
 * off takes it out of every menu rather than leaving an entry that does
 * nothing.
 *
 * Follows the settings from the process start, as [AskAiSelectionMenu]
 * does: the translate entry also goes when the Translate tool is turned off,
 * which can happen on the Tools screen, in a restored backup or in a sync.
 */
internal object TextActionsSelectionMenu {
    const val ACTIONS_ALIAS = "com.wasimaster.wmkeyboard.app.TextActionsSelectionMenu"
    const val TRANSLATE_ALIAS = "com.wasimaster.wmkeyboard.app.TranslateSelectionMenu"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> DebugLog.e("TextActionsSelectionMenu", "watch failed", failure) },
    )

    @Volatile
    private var started = false

    fun start(context: Context, repository: SettingsRepository) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            repository.settings
                // Before the first unlock the store reads as the defaults,
                // which would put back an entry the user took out.
                .filter { DirectBoot.isUserUnlocked(app) }
                .map { settings ->
                    val prefs = settings.selectionMacros
                    prefs.systemMenu to (prefs.systemMenuTranslate && ToolbarTool.TRANSLATE in settings.enabledTools)
                }
                .distinctUntilChanged()
                .collect { (actions, translate) ->
                    apply(app, ACTIONS_ALIAS, actions)
                    apply(app, TRANSLATE_ALIAS, translate)
                }
        }
    }

    /**
     * Writes only when the state is wrong, so a process start pays one read
     * each. Explicit both ways: the translate alias is off in the manifest,
     * so its "default" is off.
     */
    private fun apply(context: Context, alias: String, enabled: Boolean) {
        val component = ComponentName(context.packageName, alias)
        val pm = context.packageManager
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            if (pm.getComponentEnabledSetting(component) != state) {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
