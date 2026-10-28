package com.wasimaster.wmkeyboard.ime.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.wasimaster.wmkeyboard.core.music.MusicCardStyle
import com.wasimaster.wmkeyboard.core.music.SongMatch
import kotlin.math.max
import kotlin.math.min

/**
 * The picture a recognised song goes into the field as: the cover, and in
 * every style but [MusicCardStyle.COVER] the title, artist and album under
 * or beside it, on a ground coloured after the cover. Drawn with the
 * framework canvas, off the main thread, into a PNG the commit path turns
 * into whatever the field takes.
 *
 * The ground comes from the cover rather than the keyboard's theme: the
 * picture lands in someone else's chat, where the theme means nothing, and
 * a card the colour of its own cover is what Shazam and Spotify share.
 */
object MusicCard {

    fun render(style: MusicCardStyle, match: SongMatch, art: Bitmap?, footer: String): Bitmap {
        val w = style.width
        val h = style.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val tone = art?.let(::dominant) ?: FALLBACK
        if (style == MusicCardStyle.COVER) {
            drawGround(canvas, w, h, tone)
            drawArt(canvas, art, RectF(0f, 0f, w.toFloat(), h.toFloat()), corner = 0f, tone = tone)
            return out
        }
        drawGround(canvas, w, h, tone)
        val pad = w * PAD
        when (style) {
            MusicCardStyle.BANNER -> {
                val side = h - 2 * pad
                val artBox = RectF(pad, pad, pad + side, pad + side)
                drawArt(canvas, art, artBox, corner = side * CORNER, tone = tone)
                drawWords(canvas, match, left = artBox.right + pad, top = pad, width = w - artBox.right - 2 * pad, scale = h / BASE_HEIGHT, footer = footer)
            }
            MusicCardStyle.STORY -> {
                val side = w - 2 * pad
                val top = h * STORY_ART_TOP
                val artBox = RectF(pad, top, pad + side, top + side)
                drawArt(canvas, art, artBox, corner = side * CORNER, tone = tone)
                drawWords(canvas, match, left = pad, top = artBox.bottom + pad, width = side, scale = w / BASE_WIDTH, footer = footer)
            }
            MusicCardStyle.CARD, MusicCardStyle.COVER -> {
                val side = w - 2 * pad
                val artSide = side * CARD_ART
                val artBox = RectF((w - artSide) / 2f, pad, (w + artSide) / 2f, pad + artSide)
                drawArt(canvas, art, artBox, corner = artSide * CORNER, tone = tone)
                drawWords(canvas, match, left = pad, top = artBox.bottom + pad * 0.8f, width = side, scale = w / BASE_WIDTH, footer = footer, centred = true)
            }
        }
        return out
    }

    private fun drawGround(canvas: Canvas, w: Int, h: Int, tone: Int) {
        val top = blend(tone, Color.BLACK, 0.25f)
        val bottom = blend(tone, Color.BLACK, 0.65f)
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    private fun drawArt(canvas: Canvas, art: Bitmap?, box: RectF, corner: Float, tone: Int) {
        val clip = Path().apply { addRoundRect(box, corner, corner, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        if (art != null) {
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            // Centre-crop the cover into the box, whatever its shape.
            val scale = max(box.width() / art.width, box.height() / art.height)
            val dw = art.width * scale
            val dh = art.height * scale
            val left = box.left + (box.width() - dw) / 2f
            val top = box.top + (box.height() - dh) / 2f
            canvas.drawBitmap(art, null, RectF(left, top, left + dw, top + dh), paint)
        } else {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = blend(tone, Color.WHITE, 0.15f) }
            canvas.drawRect(box, fill)
            val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = GLYPH_ALPHA
                textSize = box.height() * GLYPH
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(NOTE, box.centerX(), box.centerY() - (glyph.descent() + glyph.ascent()) / 2f, glyph)
        }
        canvas.restore()
    }

    @Suppress("detekt.LongParameterList")
    private fun drawWords(
        canvas: Canvas,
        match: SongMatch,
        left: Float,
        top: Float,
        width: Float,
        scale: Float,
        footer: String,
        centred: Boolean = false,
    ) {
        val x = if (centred) left + width / 2f else left
        val align = if (centred) Paint.Align.CENTER else Paint.Align.LEFT
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TITLE_SP * scale
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = align
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = LINE_ALPHA
            textSize = LINE_SP * scale
            textAlign = align
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = SMALL_ALPHA
            textSize = SMALL_SP * scale
            textAlign = align
        }
        var y = top + title.textSize
        y = drawLines(canvas, fit(match.title, title, width, 2), x, y, title)
        if (match.artist.isNotBlank()) {
            y += line.textSize * GAP
            y = drawLines(canvas, fit(match.artist, line, width, 1), x, y, line)
        }
        val extra = listOf(match.album, match.year).filter { it.isNotBlank() }.joinToString(" · ")
        if (extra.isNotBlank()) {
            y += small.textSize * GAP
            y = drawLines(canvas, fit(extra, small, width, 1), x, y, small)
        }
        if (footer.isNotBlank()) {
            val foot = Paint(small).apply { alpha = FOOTER_ALPHA }
            canvas.drawText(footer, x, canvas.height - foot.textSize, foot)
        }
    }

    private fun drawLines(canvas: Canvas, lines: List<String>, x: Float, startY: Float, paint: Paint): Float {
        var y = startY
        for ((i, l) in lines.withIndex()) {
            if (i > 0) y += paint.textSize * LINE_HEIGHT
            canvas.drawText(l, x, y, paint)
        }
        return y
    }

    /** [text] broken into at most [maxLines] lines of [width], the last one ended with an ellipsis if cut. */
    private fun fit(text: String, paint: Paint, width: Float, maxLines: Int): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= width || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
            if (lines.size == maxLines) break
        }
        if (lines.size < maxLines && current.isNotEmpty()) lines += current
        val cut = lines.size == maxLines && (lines.joinToString(" ").length < text.trim().length)
        if (cut) lines[lines.lastIndex] = ellipsize(lines.last(), paint, width)
        return lines.map { if (paint.measureText(it) > width) ellipsize(it, paint, width) else it }
    }

    private fun ellipsize(line: String, paint: Paint, width: Float): String {
        var s = line
        while (s.isNotEmpty() && paint.measureText("$s…") > width) s = s.dropLast(1)
        return "$s…"
    }

    /** The cover's average colour, pulled towards a mid tone so the words read on it. */
    fun dominant(art: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(art, SAMPLE, SAMPLE, true)
        var r = 0L
        var g = 0L
        var b = 0L
        val pixels = IntArray(SAMPLE * SAMPLE)
        sample.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE)
        for (p in pixels) {
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
        }
        if (sample !== art) sample.recycle()
        val n = pixels.size
        val avg = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        // A near-grey cover gets the fallback blue rather than a grey card.
        val spread = max(max(r, g), b) - min(min(r, g), b)
        return if (spread / n < GREY_SPREAD) FALLBACK else avg
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    private const val FALLBACK = 0xFF1E88E5.toInt()
    private const val PAD = 0.07f
    private const val CORNER = 0.06f
    private const val CARD_ART = 0.72f
    private const val STORY_ART_TOP = 0.18f
    private const val BASE_WIDTH = 1080f
    private const val BASE_HEIGHT = 630f
    private const val TITLE_SP = 54f
    private const val LINE_SP = 40f
    private const val SMALL_SP = 32f
    private const val GAP = 1.1f
    private const val LINE_HEIGHT = 1.15f
    private const val LINE_ALPHA = 220
    private const val SMALL_ALPHA = 170
    private const val FOOTER_ALPHA = 110
    private const val GLYPH = 0.5f
    private const val GLYPH_ALPHA = 200
    private const val NOTE = "♪"
    private const val SAMPLE = 16
    private const val GREY_SPREAD = 18
}
