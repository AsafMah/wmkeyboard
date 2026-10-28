package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Khipro run backwards: the keys that spell a Bangla word on the Khipro
 * layout, for decoding a swipe over its grid (#541). The pieces are read off
 * Khipro itself, once, in the Rust engine
 * (`native/wm-engine/src/khipro_romanizer.rs`); this is the call into it.
 *
 * Letters and the slicer `/`, which a swipe over the Khipro grid passes
 * through (see [KEYS]). A word that needs any other key (the separator `;`,
 * the blinder) has no spelling here and is typed.
 */
object KhiproRomanizer {

    /**
     * The keys a swipe over the Khipro grid can pass through: the letters and
     * the slicer, which is how চন্দ্রবিন্দু and খণ্ড-ত are typed (`cand/` is চাঁদ).
     */
    const val KEYS = "abcdefghijklmnopqrstuvwxyz/"

    /** Builds the pieces now (about 55,000 Khipro conversions), off the thread that will first need them. */
    fun warm() {
        // The pieces are read off the spec in use, a downloaded one included.
        KhiproSpecs.ensureLoaded()
        EngineNative.khiproRomanizerWarm()
    }

    /** The fewest letters that Khipro turns into [word], or null when no cut of it converts back exactly. */
    fun romanize(word: String): String? = spellings(word).firstOrNull()

    /**
     * Every way of spelling [text] worth decoding a swipe against: the fewest
     * letters, and the same with the inherent `o` written out between
     * consonants where Khipro still reads the word (`ekhn` and `ekhon` for
     * এখন). Empty when there is none.
     */
    fun spellings(text: String): List<String> {
        KhiproSpecs.ensureLoaded()
        return EngineNative.khiproSpellings(text).toList()
    }
}
