package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Khipro (ক্ষিপ্র): Bengali typed compositionally from lowercase Latin keys
 * (issue #400, https://khipro.khiproteam.com). Every keystroke has one
 * meaning, so a word has exactly one reading and there is nothing to rank.
 *
 * The method is defined as an m17n input method, and the interpreter that
 * runs the official specs lives in the Rust engine
 * (`native/wm-engine/src/khipro.rs`, with the specs under
 * `native/wm-engine/data/khipro/`); this is the call into it. A newer set of
 * specs downloaded since ([KhiproSpecs]) is loaded into the engine the first
 * time either call runs.
 */
object Khipro {

    /**
     * Which spec a keystroke is read against: the edition, and whether the key
     * came from a touchscreen or a hardware keyboard. Within an edition the two
     * differ only in what a phone keyboard already has keys for: the desktop
     * spec turns digits into Bengali digits, `.` into the danda and `$` into ৳,
     * the touchscreen one leaves all of that to the keys and spells ॥ as `।f`.
     *
     * The declaration order is the ordinal the engine reads; do not reorder.
     */
    enum class Variant(val dark: Boolean, val desktop: Boolean) {
        TOUCHSCREEN(dark = false, desktop = false),
        DESKTOP(dark = false, desktop = true),
        DARK_TOUCHSCREEN(dark = true, desktop = false),
        DARK_DESKTOP(dark = true, desktop = true),
        ;

        companion object {
            /** The spec of edition [dark] for a key typed on a hardware keyboard or not. */
            fun of(dark: Boolean, desktop: Boolean): Variant =
                entries.first { it.dark == dark && it.desktop == desktop }
        }
    }

    /** The Bengali [roman] spells under [variant]. Empty in, empty out. */
    fun convert(roman: String, variant: Variant = Variant.TOUCHSCREEN): String {
        if (roman.isEmpty()) return ""
        KhiproSpecs.ensureLoaded()
        return EngineNative.khipro(roman, variant.ordinal)
    }

    /** Parses [variant]'s spec now rather than on the first keystroke. */
    fun warm(variant: Variant) {
        KhiproSpecs.ensureLoaded()
        EngineNative.khiproWarm(variant.ordinal)
    }
}
