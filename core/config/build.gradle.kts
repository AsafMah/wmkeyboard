import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    // Applied at the root with their versions; see build.gradle.kts there.
    id("org.jetbrains.kotlinx.kover")
    id("com.autonomousapps.dependency-analysis")
}

// Coverage for the root's merged report (`./gradlew koverHtmlReportUnit`):
// this module's debug unit tests.
kover {
    currentProject {
        createVariant("unit") { add("debug") }
    }
}

// Build-wide flags and API keys for library modules. The app module keeps its
// own copies of these fields for the app-package screens; both read the same
// local.properties / environment values, so they cannot drift.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun apiKey(propertyName: String, envName: String): String =
    localProperties.getProperty(propertyName)?.trim()
        ?: System.getenv(envName)?.trim()
        ?: ""

/** The Google Home APIs SDK is checked in under feature/ime/libs/googlehome; without it the flag cannot be on. */
val googleHomeSdk: Boolean = rootProject.file("feature/ime/libs/googlehome").listFiles()?.any { it.name.endsWith(".aar") } == true ||
    rootProject.file("feature/ime/libs/googlehome/m2/com/google/android/gms/play-services-home").isDirectory

fun flag(propertyName: String, envName: String): Boolean =
    (providers.gradleProperty(propertyName).orNull
        ?: localProperties.getProperty(propertyName)
        ?: System.getenv(envName)
        ?: "false").toBoolean()

/**
 * Whether the build carries Google's SDKs: every channel but F-Droid.
 * :core:intelligence, :core:voice, :core:content and :feature:ime read the
 * same flag to choose their src/google or src/nogoogle sources.
 */
val googleSdks: Boolean = !flag("wmkb.enableFdroid", "WMKB_ENABLE_FDROID")

/**
 * Whether the build carries the Qualcomm NPU runtime for on-device AI (#804).
 * On unless switched off, and never in the F-Droid build: the QNN libraries
 * are Qualcomm's own licence. :core:intelligence, :app and settings.gradle.kts
 * read the same two properties to decide what gets packaged.
 */
val npuRuntime: Boolean =
    (providers.gradleProperty("wmkb.enableNpu").orNull
        ?: localProperties.getProperty("wmkb.enableNpu")
        ?: System.getenv("WMKB_ENABLE_NPU")
        ?: "true").toBoolean() && !flag("wmkb.enableFdroid", "WMKB_ENABLE_FDROID")

android {
    namespace = "com.wasimaster.wmkeyboard.config"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // A library module gets no version from the app, so these have to be
        // stated. They come from gradle.properties, the same place :app reads,
        // because DebugLog stamps them on every crash report: hardcoded copies
        // drifted and reported "1.0 (1)" for every build ever shipped.
        buildConfigField("int", "VERSION_CODE", providers.gradleProperty("wmkb.versionCode").get())
        buildConfigField("String", "VERSION_NAME", "\"${providers.gradleProperty("wmkb.versionName").get()}\"")
        buildConfigField("String", "KLIPY_API_KEY", "\"${apiKey("wmkb.klipyApiKey", "WMKB_KLIPY_API_KEY")}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"${apiKey("wmkb.giphyApiKey", "WMKB_GIPHY_API_KEY")}\"")
        buildConfigField("String", "BRAVE_API_KEY", "\"${apiKey("wmkb.braveApiKey", "WMKB_BRAVE_API_KEY")}\"")
        buildConfigField("String", "TRANSLATE_API_KEY", "\"${apiKey("wmkb.translateApiKey", "WMKB_TRANSLATE_API_KEY")}\"")
        buildConfigField("String", "UNSPLASH_API_KEY", "\"${apiKey("wmkb.unsplashApiKey", "WMKB_UNSPLASH_API_KEY")}\"")
        buildConfigField("String", "PEXELS_API_KEY", "\"${apiKey("wmkb.pexelsApiKey", "WMKB_PEXELS_API_KEY")}\"")
        // The SmartThings OAuth app this build signs in through (smart home
        // tool). Blank leaves only the pasted-token way in.
        buildConfigField("String", "SMARTTHINGS_CLIENT_ID", "\"${apiKey("wmkb.smartThingsClientId", "WMKB_SMARTTHINGS_CLIENT_ID")}\"")
        buildConfigField("String", "SMARTTHINGS_CLIENT_SECRET", "\"${apiKey("wmkb.smartThingsClientSecret", "WMKB_SMARTTHINGS_CLIENT_SECRET")}\"")
        buildConfigField("Boolean", "ENABLE_PLAY_STORE", "${flag("wmkb.enablePlayStore", "WMKB_ENABLE_PLAY_STORE")}")
        buildConfigField("Boolean", "ENABLE_FDROID", "${flag("wmkb.enableFdroid", "WMKB_ENABLE_FDROID")}")
        // Diagnostic builds only: on an uncaught exception, replace Android's
        // "app has stopped" dialog with a screen showing the stack trace, with
        // buttons to copy or share it. For getting a trace off a device that
        // belongs to someone else — no adb, no developer options, and a crash
        // at startup means the in-app log screen is unreachable. Off by default;
        // build with -Pwmkb.enableCrashScreen=true.
        buildConfigField("Boolean", "ENABLE_CRASH_SCREEN", "${flag("wmkb.enableCrashScreen", "WMKB_ENABLE_CRASH_SCREEN")}")
        // The features built on Google's SDKs (ML Kit, LiteRT, LiteRT-LM):
        // every build has them except F-Droid's, which may carry no Google
        // artifact (fdroid/README.md). Grammar is Harper, open source, so it
        // is in every build.
        buildConfigField("Boolean", "GOOGLE_SDKS", "$googleSdks")
        buildConfigField("Boolean", "ENABLE_ML_KIT_HANDWRITING", "$googleSdks")
        buildConfigField("Boolean", "ENABLE_ML_KIT_SCANNERS", "$googleSdks")
        buildConfigField("Boolean", "ENABLE_GRAMMAR", "true")
        // On-device AI is in every build: llama.cpp runs GGUF models and needs
        // nothing from Google. LiteRT-LM, the runtime for .litertlm and .task
        // models, is a Google artifact, so F-Droid's build offers GGUF only.
        buildConfigField("Boolean", "ENABLE_LOCAL_LLM", "true")
        buildConfigField("Boolean", "ENABLE_LITERT_LM", "$googleSdks")
        buildConfigField("Boolean", "ENABLE_WHISPER", "$googleSdks")
        // The NPU as a Compute choice for on-device AI: see npuRuntime above.
        buildConfigField("Boolean", "ENABLE_NPU", "$npuRuntime")
        // The Google Home APIs: their SDK is left out of F-Droid's build, and
        // on in every other build that has it unpacked under
        // feature/ime/libs/googlehome. The grant only works for a signing
        // certificate that has an OAuth client, which is a property of the
        // channel, not of a flag.
        buildConfigField("Boolean", "ENABLE_GOOGLE_HOME", "${googleSdks && googleHomeSdk}")
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        extraWarnings.set(true)
        freeCompilerArgs.addAll("-Xreport-all-warnings")
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

dependencies {
}
