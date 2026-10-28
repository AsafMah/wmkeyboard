package com.wasimaster.wmkeyboard.core.tools.speedtest

/**
 * How the result card is coloured. [THEME] takes the keyboard's own colours
 * at the moment it is drawn; the rest are fixed, so a card looks the same
 * whatever theme is on.
 */
enum class SpeedCardStyle { THEME, DARK, LIGHT, GRADIENT, MONO }

/** The card's pixel size. Chat apps show a square or 4:5 best; a story is 9:16; a sticker is small and square. */
enum class SpeedCardSize(val width: Int, val height: Int) {
    SQUARE(1080, 1080),
    PORTRAIT(1080, 1350),
    STORY(1080, 1920),
    WIDE(1200, 675),
    STICKER(512, 512),
    ;

    val aspect: Float get() = width.toFloat() / height
}

/**
 * What the card shows. [FULL] has every number, the sparkline and the
 * footer; [GAUGE] is one dial with the numbers under it; [COMPACT] is the
 * four numbers in a grid; [MINIMAL] is the two speeds and nothing else.
 */
enum class SpeedCardLayout { FULL, GAUGE, COMPACT, MINIMAL }

/** The colours a card is drawn in, ARGB. [gradientEnd] is only read by [SpeedCardStyle.GRADIENT]. */
data class SpeedCardPalette(
    val background: Int,
    val surface: Int,
    val accent: Int,
    val text: Int,
    val muted: Int,
    val gradientEnd: Int = background,
) {
    companion object {
        val DARK = SpeedCardPalette(
            background = 0xFF121417.toInt(),
            surface = 0xFF1E2227.toInt(),
            accent = 0xFF4FC3F7.toInt(),
            text = 0xFFF5F7FA.toInt(),
            muted = 0xFF9AA4AE.toInt(),
        )
        val LIGHT = SpeedCardPalette(
            background = 0xFFF7F8FA.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            accent = 0xFF1E88E5.toInt(),
            text = 0xFF14181C.toInt(),
            muted = 0xFF6B7580.toInt(),
        )
        val GRADIENT = SpeedCardPalette(
            background = 0xFF1A237E.toInt(),
            surface = 0x33FFFFFF,
            accent = 0xFF80DEEA.toInt(),
            text = 0xFFFFFFFF.toInt(),
            muted = 0xCCE3F2FD.toInt(),
            gradientEnd = 0xFF6A1B9A.toInt(),
        )
        val MONO = SpeedCardPalette(
            background = 0xFFFFFFFF.toInt(),
            surface = 0xFFF2F2F2.toInt(),
            accent = 0xFF000000.toInt(),
            text = 0xFF000000.toInt(),
            muted = 0xFF555555.toInt(),
        )

        /** The fixed palette for [style], or null for [SpeedCardStyle.THEME], whose colours the caller brings. */
        fun fixed(style: SpeedCardStyle): SpeedCardPalette? = when (style) {
            SpeedCardStyle.THEME -> null
            SpeedCardStyle.DARK -> DARK
            SpeedCardStyle.LIGHT -> LIGHT
            SpeedCardStyle.GRADIENT -> GRADIENT
            SpeedCardStyle.MONO -> MONO
        }
    }
}

/** Everything that decides how one card looks, apart from the numbers on it. */
data class SpeedCardSpec(
    val style: SpeedCardStyle = SpeedCardStyle.THEME,
    val size: SpeedCardSize = SpeedCardSize.SQUARE,
    val layout: SpeedCardLayout = SpeedCardLayout.FULL,
    val unit: SpeedUnit = SpeedUnit.MBPS,
    val showIp: Boolean = false,
)

/** The words on the card, in the user's language. The renderer has no resources of its own. */
data class SpeedCardLabels(
    val download: String = "Download",
    val upload: String = "Upload",
    val ping: String = "Ping",
    val jitter: String = "Jitter",
    /** The footer's "than last time" note, with `%s` for the signed percentage. */
    val vsLast: String = "%s vs last run",
    val wordmark: String = "WM Keyboard",
    /** Written where the upload number would be when the run skipped it. */
    val skipped: String = "—",
)
