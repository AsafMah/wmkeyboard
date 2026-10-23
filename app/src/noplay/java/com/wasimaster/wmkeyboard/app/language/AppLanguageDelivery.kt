package com.wasimaster.wmkeyboard.app.language

import android.app.Activity
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.AppLanguage
import com.wasimaster.wmkeyboard.app.LanguagePackDownloads
import com.wasimaster.wmkeyboard.app.matchAvailable
import com.wasimaster.wmkeyboard.core.modules.LanguagePackResources
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import kotlinx.coroutines.launch

/**
 * Builds outside Play have nothing for SplitCompat to wire up. The Play
 * version of this file is in `src/play/java`.
 */
fun appLanguageSplitCompat(activity: Activity) = Unit

/**
 * Makes sure the resources for [tag] are on the phone, then runs [onReady].
 * Every build outside Play carries its languages in the APK, except the
 * standard GitHub build, which fetches the language's pack from its release
 * ([LanguagePackDownloads]), or on the offline build says which pack to
 * import. [onReady] runs on failure too, after saying so:
 * the app then shows English, and the choice stays, so it can be picked again.
 */
fun fetchAppLanguage(activity: Activity, chosen: String?, onReady: () -> Unit) {
    // System default (null) means the phone's language, which needs its pack
    // as much as a language picked by name.
    val tag = chosen ?: matchAvailable(AppLanguage.systemLocale().toLanguageTag(), AppLanguage.available)
    if (!LanguagePackDownloads.active || tag == null || LanguagePackDownloads.isEnglish(tag) ||
        LanguagePackResources.has(activity, tag)
    ) {
        onReady()
        return
    }
    // The offline build takes packs only as imports: name the file to get.
    if (!InternetPermission.granted) {
        val message = activity.getString(R.string.app_language_pack_import, LanguagePackDownloads.fileName(tag))
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        onReady()
        return
    }
    val owner = activity as? LifecycleOwner ?: run { onReady(); return }
    Toast.makeText(activity, R.string.app_language_pack_downloading, Toast.LENGTH_SHORT).show()
    owner.lifecycleScope.launch {
        if (!LanguagePackDownloads.ensure(activity.applicationContext, tag)) {
            Toast.makeText(activity, R.string.app_language_pack_failed, Toast.LENGTH_LONG).show()
        }
        if (!activity.isFinishing && !activity.isDestroyed) onReady()
    }
}
