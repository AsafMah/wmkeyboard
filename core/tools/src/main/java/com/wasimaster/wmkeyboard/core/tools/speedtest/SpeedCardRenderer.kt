package com.wasimaster.wmkeyboard.core.tools.speedtest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the result card. Everything is laid out in a thousand units across,
 * whatever the pixel size, and the canvas is scaled to fit: so a new
 * [SpeedCardSize] is one enum entry, and a preview is the same picture drawn
 * small ([render]'s `scale`). Plain [Canvas] and [Paint], no Compose, so it
 * runs on any thread and under Robolectric.
 *
 * The words come in as [SpeedCardLabels]; this has no resources of its own.
 */
object SpeedCardRenderer {

    private const val W = 1000f
    private const val MARGIN = 70f

    /**
     * The card for [result]. [theme] is the palette [SpeedCardStyle.THEME]
     * draws with; with none the dark palette stands in. [previous] gives the
     * footer its "vs last run" note. [scale] below 1 draws a preview.
     */
    fun render(
        result: SpeedTestResult,
        spec: SpeedCardSpec,
        labels: SpeedCardLabels = SpeedCardLabels(),
        theme: SpeedCardPalette? = null,
        previous: SpeedTestResult? = null,
        locale: Locale = Locale.getDefault(),
        scale: Float = 1f,
    ): Bitmap {
        val palette = SpeedCardPalette.fixed(spec.style) ?: theme ?: SpeedCardPalette.DARK
        val width = (spec.size.width * scale).roundToInt().coerceAtLeast(1)
        val height = (spec.size.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val s = width / W
        canvas.scale(s, s)
        val board = Board(
            height = height / s,
            palette = palette,
            labels = labels,
            locale = locale,
            spec = spec,
            result = result,
            previous = previous,
        )
        board.background(canvas)
        when (spec.layout) {
            SpeedCardLayout.FULL -> board.full(canvas)
            SpeedCardLayout.GAUGE -> board.gauge(canvas)
            SpeedCardLayout.COMPACT -> board.compact(canvas)
            SpeedCardLayout.MINIMAL -> board.minimal(canvas)
        }
        return bitmap
    }

    /** One card being drawn: the geometry and the paints, in units. */
    private class Board(
        val height: Float,
        val palette: SpeedCardPalette,
        val labels: SpeedCardLabels,
        val locale: Locale,
        val spec: SpeedCardSpec,
        val result: SpeedTestResult,
        val previous: SpeedTestResult?,
    ) {
        /** A card wider than it is tall: the numbers go left, the graph right. */
        val wide: Boolean get() = height < 800f

        /** Taller than 4:5: the content floats in the middle rather than hugging the top. */
        val tall: Boolean get() = height > 1300f

        private val bold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val regular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private val unit = SpeedTestMath.unitLabel(spec.unit)

        private fun paint(size: Float, color: Int, heavy: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                this.color = color
                typeface = if (heavy) bold else regular
                textAlign = align
            }

        private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

        private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

        private fun speed(mbps: Double): String = SpeedTestMath.formatSpeed(mbps, spec.unit, locale)
        private fun ms(value: Double): String = SpeedTestMath.formatMs(value, locale)
        private fun date(): String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(result.at))

        /** [text] cut to [maxWidth] with an ellipsis, as [p] draws it. */
        private fun fit(text: String, p: Paint, maxWidth: Float): String {
            if (p.measureText(text) <= maxWidth) return text
            var t = text
            while (t.isNotEmpty() && p.measureText("$t…") > maxWidth) t = t.dropLast(1)
            return "$t…"
        }

        fun background(canvas: Canvas) {
            val rect = RectF(0f, 0f, W, height)
            if (spec.style == SpeedCardStyle.GRADIENT) {
                val p = Paint().apply {
                    shader = LinearGradient(0f, 0f, W, height, palette.background, palette.gradientEnd, Shader.TileMode.CLAMP)
                }
                canvas.drawRect(rect, p)
            } else {
                canvas.drawRect(rect, fill(palette.background))
            }
            // A soft disc of the accent behind the top right, so a flat card is not a flat card.
            if (spec.style != SpeedCardStyle.MONO) {
                canvas.drawCircle(W * 0.92f, -height * 0.05f, W * 0.42f, fill(alpha(palette.accent, 0x14)))
            }
        }

        // ---- shared pieces ----

        /** The wordmark on the left and the date on the right, on one line at [y]. */
        private fun header(canvas: Canvas, y: Float, size: Float = 28f) {
            canvas.drawText(labels.wordmark, MARGIN, y, paint(size, palette.muted, heavy = true))
            canvas.drawText(date(), W - MARGIN, y, paint(size, palette.muted, align = Paint.Align.RIGHT))
        }

        /** The network, the server, and how this run compares with the last, on the bottom line. */
        private fun footer(canvas: Canvas, y: Float, size: Float = 30f) {
            val p = paint(size, palette.muted)
            val server = listOf(result.serverName, result.serverCity).filter { it.isNotBlank() }.joinToString(" · ")
            val parts = buildList {
                if (result.isp.isNotBlank()) add(result.isp)
                if (server.isNotBlank()) add(server)
                if (spec.showIp && result.ip.isNotBlank()) add(result.ip)
            }
            val delta = SpeedTestMath.deltaPercent(result.downMbps, previous?.downMbps)
            val deltaText = delta?.let { labels.vsLast.format(locale, (if (it >= 0) "+" else "") + "$it%") }
            val deltaPaint = paint(size, if (delta != null && delta >= 0) palette.accent else palette.muted, heavy = true, align = Paint.Align.RIGHT)
            val deltaWidth = deltaText?.let { deltaPaint.measureText(it) + 30f } ?: 0f
            canvas.drawText(fit(parts.joinToString(" · "), p, W - 2 * MARGIN - deltaWidth), MARGIN, y, p)
            if (deltaText != null) canvas.drawText(deltaText, W - MARGIN, y, deltaPaint)
        }

        /**
         * A speed with its unit set small after it, the baseline shared.
         * Returns the width drawn. [align] CENTER centres the pair on [x].
         */
        private fun bigNumber(
            canvas: Canvas,
            text: String,
            unitText: String,
            x: Float,
            baseline: Float,
            size: Float,
            color: Int = palette.text,
            align: Paint.Align = Paint.Align.LEFT,
            arrow: String? = null,
        ): Float {
            val np = paint(size, color, heavy = true)
            val up = paint(size * 0.26f, palette.muted)
            val ap = paint(size * 0.5f, palette.accent, heavy = true)
            val gap = size * 0.08f
            val arrowWidth = arrow?.let { ap.measureText(it) + gap } ?: 0f
            val total = arrowWidth + np.measureText(text) + gap + up.measureText(unitText)
            var cx = when (align) {
                Paint.Align.LEFT -> x
                Paint.Align.CENTER -> x - total / 2f
                Paint.Align.RIGHT -> x - total
            }
            if (arrow != null) {
                canvas.drawText(arrow, cx, baseline - size * 0.08f, ap)
                cx += arrowWidth
            }
            canvas.drawText(text, cx, baseline, np)
            cx += np.measureText(text) + gap
            canvas.drawText(unitText, cx, baseline, up)
            return total
        }

        /** A small caption over a number. */
        private fun caption(canvas: Canvas, text: String, x: Float, y: Float, size: Float = 32f, align: Paint.Align = Paint.Align.LEFT) {
            canvas.drawText(text.uppercase(locale), x, y, paint(size, palette.muted).apply { letterSpacing = 0.08f; textAlign = align })
        }

        /** The download phase as a filled line in [area]. */
        private fun sparkline(canvas: Canvas, area: RectF) {
            val points = SpeedTestMath.sparkline(result.downSamples, 32)
            val line = Path()
            val body = Path()
            val stepX = area.width() / (points.size - 1).coerceAtLeast(1)
            val baseline = area.bottom
            points.forEachIndexed { i, v ->
                val x = area.left + stepX * i
                val y = area.bottom - v.coerceIn(0f, 1f) * area.height() * 0.92f - area.height() * 0.04f
                if (i == 0) {
                    line.moveTo(x, y)
                    body.moveTo(x, baseline)
                    body.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    body.lineTo(x, y)
                }
            }
            body.lineTo(area.right, baseline)
            body.close()
            canvas.drawPath(body, fill(alpha(palette.accent, 0x40)))
            canvas.drawPath(line, stroke(palette.accent, 6f))
            canvas.drawLine(area.left, baseline, area.right, baseline, stroke(alpha(palette.muted, 0x60), 2f))
        }

        /** A rounded panel of the surface colour. */
        private fun panel(canvas: Canvas, rect: RectF, radius: Float = 36f) {
            canvas.drawRoundRect(rect, radius, radius, fill(palette.surface))
        }

        // ---- layouts ----

        fun full(canvas: Canvas) {
            val top = MARGIN + 32f
            header(canvas, top)
            val footerY = height - MARGIN
            val upText = result.upMbps?.let(::speed) ?: labels.skipped
            if (wide) {
                // Numbers on the left half, the graph on the right.
                val colW = (W - 2 * MARGIN - 50f) / 2f
                // A wide card is 562 units tall: everything sits above footerY
                // (492) with room for the footer's own line.
                val left = MARGIN
                var y = top + 80f
                caption(canvas, labels.download, left, y)
                y += 130f
                bigNumber(canvas, speed(result.downMbps), unit, left, y, 125f, arrow = "↓")
                y += 58f
                caption(canvas, labels.upload, left, y)
                caption(canvas, labels.ping, left + colW / 2f, y)
                y += 60f
                bigNumber(canvas, upText, unit, left, y, 58f, arrow = "↑")
                bigNumber(canvas, ms(result.pingMs), "ms", left + colW / 2f, y, 58f)
                sparkline(canvas, RectF(left + colW + 50f, top + 90f, W - MARGIN, footerY - 70f))
                footer(canvas, footerY, 26f)
                return
            }
            // The block's own height, so a tall card can centre it.
            val block = 34f + 20f + 190f + 60f + 34f + 20f + 92f + 70f + 180f
            val available = (footerY - 60f) - (top + 40f)
            var y = top + 40f + if (tall) (available - block) / 2f else 60f
            caption(canvas, labels.download, MARGIN, y + 34f)
            y += 34f + 20f + 190f
            bigNumber(canvas, speed(result.downMbps), unit, MARGIN, y, 190f, arrow = "↓")
            y += 60f
            val half = (W - 2 * MARGIN) / 2f
            caption(canvas, labels.upload, MARGIN, y + 34f)
            caption(canvas, labels.ping, MARGIN + half, y + 34f)
            y += 34f + 20f + 92f
            bigNumber(canvas, upText, unit, MARGIN, y, 92f, arrow = "↑")
            val pingWidth = bigNumber(canvas, ms(result.pingMs), "ms", MARGIN + half, y, 92f)
            // Jitter rides beside the ping, small.
            val jp = paint(30f, palette.muted)
            canvas.drawText(
                fit("${labels.jitter} ${ms(result.jitterMs)} ms", jp, half - pingWidth - 30f),
                MARGIN + half + pingWidth + 24f,
                y,
                jp,
            )
            y += 70f
            sparkline(canvas, RectF(MARGIN, y, W - MARGIN, y + 180f))
            footer(canvas, footerY)
        }

        fun gauge(canvas: Canvas) {
            val top = MARGIN + 32f
            header(canvas, top)
            val footerY = height - MARGIN
            // The dial, the row of three under it (170 below its bottom edge, on
            // a card that is not wide) and the footer all have to fit: on a
            // square card that is 1000 units, on a wide one 562.
            val radius = if (wide) 150f else 270f
            val cx = if (wide) MARGIN + radius + 40f else W / 2f
            val dialTop = top + if (wide) 30f else if (tall) (footerY - top - 2 * radius - 280f) / 2f else 40f
            val cy = dialTop + radius
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            val start = 135f
            val sweep = 270f
            canvas.drawArc(bounds, start, sweep, false, stroke(alpha(palette.muted, 0x40), 34f))
            val fraction = SpeedTestMath.gaugeFraction(result.downMbps)
            if (fraction > 0f) canvas.drawArc(bounds, start, sweep * fraction, false, stroke(palette.accent, 34f))
            // Ticks at 1, 10, 100 and 1000.
            val tickPaint = paint(22f, palette.muted, align = Paint.Align.CENTER)
            for ((i, label) in listOf("1", "10", "100", "1000").withIndex()) {
                val angle = Math.toRadians((start + sweep * i / 3f).toDouble())
                val r = radius + 52f
                canvas.drawText(label, cx + (r * cos(angle)).toFloat(), cy + (r * sin(angle)).toFloat() + 8f, tickPaint)
            }
            bigNumber(canvas, speed(result.downMbps), unit, cx, cy + 30f, if (wide) 84f else 150f, align = Paint.Align.CENTER)
            caption(canvas, labels.download, cx, cy + (if (wide) 80f else 100f), align = Paint.Align.CENTER)
            // Under (or beside) the dial: upload, ping, jitter.
            val upText = result.upMbps?.let(::speed) ?: labels.skipped
            val rowY: Float
            val xs: List<Float>
            if (wide) {
                val left = cx + radius + 100f
                val colW = (W - MARGIN - left) / 2f
                xs = listOf(left, left + colW)
                rowY = cy - 40f
                caption(canvas, labels.upload, xs[0], rowY - 70f)
                caption(canvas, labels.ping, xs[1], rowY - 70f)
                bigNumber(canvas, upText, unit, xs[0], rowY, 64f, arrow = "↑")
                bigNumber(canvas, ms(result.pingMs), "ms", xs[1], rowY, 64f)
                caption(canvas, labels.jitter, xs[0], rowY + 70f)
                bigNumber(canvas, ms(result.jitterMs), "ms", xs[0], rowY + 140f, 64f)
            } else {
                val colW = (W - 2 * MARGIN) / 3f
                xs = List(3) { MARGIN + colW * it + colW / 2f }
                rowY = cy + radius + 170f
                for ((i, label) in listOf(labels.upload, labels.ping, labels.jitter).withIndex()) {
                    caption(canvas, label, xs[i], rowY - 70f, align = Paint.Align.CENTER)
                }
                bigNumber(canvas, upText, unit, xs[0], rowY, 60f, align = Paint.Align.CENTER, arrow = "↑")
                bigNumber(canvas, ms(result.pingMs), "ms", xs[1], rowY, 60f, align = Paint.Align.CENTER)
                bigNumber(canvas, ms(result.jitterMs), "ms", xs[2], rowY, 60f, align = Paint.Align.CENTER)
            }
            footer(canvas, footerY, if (wide) 26f else 30f)
        }

        fun compact(canvas: Canvas) {
            val top = MARGIN + 32f
            header(canvas, top)
            val footerY = height - MARGIN
            val cells = listOf(
                Triple(labels.download, speed(result.downMbps), unit),
                Triple(labels.upload, result.upMbps?.let(::speed) ?: labels.skipped, unit),
                Triple(labels.ping, ms(result.pingMs), "ms"),
                Triple(labels.jitter, ms(result.jitterMs), "ms"),
            )
            val gap = 28f
            if (wide) {
                val cellW = (W - 2 * MARGIN - 3 * gap) / 4f
                val cellH = footerY - 70f - (top + 60f)
                cells.forEachIndexed { i, (label, value, u) ->
                    val rect = RectF(MARGIN + i * (cellW + gap), top + 60f, MARGIN + i * (cellW + gap) + cellW, top + 60f + cellH)
                    cell(canvas, rect, label, value, u, 84f)
                }
            } else {
                val cellW = (W - 2 * MARGIN - gap) / 2f
                val cellH = if (tall) 330f else ((footerY - 70f) - (top + 60f) - gap) / 2f
                val gridH = 2 * cellH + gap
                val gridTop = top + 60f + if (tall) ((footerY - 70f) - (top + 60f) - gridH) / 2f else 0f
                cells.forEachIndexed { i, (label, value, u) ->
                    val col = i % 2
                    val row = i / 2
                    val left = MARGIN + col * (cellW + gap)
                    val cellTop = gridTop + row * (cellH + gap)
                    cell(canvas, RectF(left, cellTop, left + cellW, cellTop + cellH), label, value, u, 104f)
                }
            }
            footer(canvas, footerY, if (wide) 26f else 30f)
        }

        private fun cell(canvas: Canvas, rect: RectF, label: String, value: String, unitText: String, size: Float) {
            panel(canvas, rect)
            val pad = 34f
            caption(canvas, label, rect.left + pad, rect.top + pad + 30f, 28f)
            var fitSize = size
            val np = paint(fitSize, palette.text, heavy = true)
            val up = paint(fitSize * 0.26f, palette.muted)
            while (fitSize > 40f && np.measureText(value) + up.measureText(unitText) + fitSize * 0.08f > rect.width() - 2 * pad) {
                fitSize -= 6f
                np.textSize = fitSize
                up.textSize = fitSize * 0.26f
            }
            bigNumber(canvas, value, unitText, rect.left + pad, rect.bottom - pad - 10f, fitSize)
        }

        fun minimal(canvas: Canvas) {
            val cx = W / 2f
            val size = if (wide) 190f else 270f
            val cy = height / 2f
            bigNumber(canvas, speed(result.downMbps), unit, cx, cy - 20f, size, align = Paint.Align.CENTER, arrow = "↓")
            caption(canvas, labels.download, cx, cy - size - 20f, 34f, align = Paint.Align.CENTER)
            val upText = result.upMbps?.let(::speed) ?: labels.skipped
            bigNumber(canvas, upText, unit, cx, cy + size * 0.55f, size * 0.42f, align = Paint.Align.CENTER, arrow = "↑")
            val bottom = paint(26f, palette.muted, heavy = true, align = Paint.Align.CENTER)
            canvas.drawText(labels.wordmark, cx, height - MARGIN, bottom)
            canvas.drawText(
                "${ms(result.pingMs)} ms · ${date()}",
                cx,
                height - MARGIN - 44f,
                paint(26f, palette.muted, align = Paint.Align.CENTER),
            )
        }
    }

    /** A theme palette from four colours of the keyboard, ARGB. */
    fun themePalette(background: Int, key: Int, accent: Int, text: Int, muted: Int): SpeedCardPalette {
        // A near-transparent board (a photo theme) would leave the card see-through; make it solid.
        val solidBackground = if (Color.alpha(background) < 0xF0) {
            Color.rgb(Color.red(background), Color.green(background), Color.blue(background))
        } else {
            background
        }
        return SpeedCardPalette(
            background = solidBackground,
            surface = if (Color.alpha(key) < 0x40) blend(solidBackground, text, 0.08f) else key,
            accent = accent,
            text = text,
            muted = if (Color.alpha(muted) < 0x80) blend(text, solidBackground, 0.45f) else muted,
            gradientEnd = blend(solidBackground, accent, 0.35f),
        )
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt().coerceIn(0, 255)
        return Color.argb(255, ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
}
