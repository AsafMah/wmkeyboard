package com.wasimaster.wmkeyboard.app.modules

import android.app.Application
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.NotInBuild
import com.wasimaster.wmkeyboard.core.transliteration.NativeLinker

/**
 * The [installOnDemandDelivery] of every channel that is not Play.
 *
 * A build with native delivery (the GitHub build, F-Droid's behind its
 * opt-in, and the offline build, which imports instead of downloading) left
 * the native libraries behind optional features out of its APK and pinned
 * them in assets/native/pins.tsv: [NativeDelivery] reads the pins and puts a
 * downloading gate behind every gate they name. A build without the pins
 * (`-Pwmkb.nativeDelivery=false`) compiles
 * everything in, the default always-installed gates in FeatureModules are
 * already telling the truth, and this does nothing.
 *
 * F-Droid's build compiles the same code as the others but carries none of
 * Google's SDKs (ML Kit, LiteRT, LiteRT-LM), so the gates of the runtimes
 * built on them say so instead of claiming the runtime is here.
 */
fun installOnDemandDelivery(app: Application) {
    NativeDelivery.install(app, needOptIn = BuildConfig.ENABLE_FDROID, dlopen = NativeLinker::dlopen)
    if (!BuildConfig.GOOGLE_SDKS) {
        for (name in FeatureModules.googleRuntimes) FeatureModules.set(name, NotInBuild)
    }
}
