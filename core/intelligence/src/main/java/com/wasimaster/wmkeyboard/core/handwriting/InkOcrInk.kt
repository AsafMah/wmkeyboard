package com.wasimaster.wmkeyboard.core.handwriting

import com.wasimaster.wmkeyboard.core.ocr.PaddleOcrPipeline
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws panel ink as the line of text PaddleOCR's recognisers read
 * ([InkOcrModels]): black pen on white, [PaddleOcrPipeline.REC_HEIGHT]
 * pixels tall, as wide as the writing needs.
 *
 * The recognisers were trained on lines a text detector cut tightly out of
 * photos, so the ink is scaled by its own bounding box, the way such a crop
 * frames its text: the written height fills [INNER_SHARE] of the strip, with
 * the rest left as margin above and below. A short mark (a comma, a dash) is
 * not blown up to full height: the box is taken to be at least
 * [MIN_HEIGHT_OF_AREA] of the writing area tall.
 *
 * The pen is [PEN_SHARE] of the strip's height wide, about a ballpoint's
 * line at the size text is photographed, and drawn with anti-aliased edges,
 * since a detector's crop is never binary.
 *
 * Pure arithmetic, no Android: InkOcrInkTest runs it on the JVM.
 */
internal object InkOcrInk {

    private const val INNER_SHARE = 0.75f
    private const val PEN_SHARE = 0.06f
    private const val MIN_HEIGHT_OF_AREA = 0.2f

    /** The widest strip drawn, in recogniser widths; anything longer is squeezed into it. */
    private const val MAX_WIDTHS = 24

    private const val WHITE = 255f

    /**
     * [strokes] as one level strip, or null when there is no ink. [areaHeight]
     * is the writing area's height in the strokes' own pixels.
     */
    fun render(strokes: List<HwStroke>, areaHeight: Float): PaddleOcrPipeline.Strip? {
        val box = ZinniaInk.boxOf(strokes) ?: return null
        val height = PaddleOcrPipeline.REC_HEIGHT
        val inner = height * INNER_SHARE
        val written = max(box.height, max(areaHeight * MIN_HEIGHT_OF_AREA, 1f))
        var scale = inner / written
        val margin = (height - inner) / 2f
        val maxWidth = PaddleOcrPipeline.REC_WIDTH * MAX_WIDTHS
        if (box.width * scale + 2 * margin > maxWidth) scale = (maxWidth - 2 * margin) / max(box.width, 1f)
        val width = ceil(box.width * scale + 2 * margin).toInt().coerceIn(height, maxWidth)
        // The ink sits centred on the strip's middle, however tall it is.
        val dx = margin - box.left * scale
        val dy = height / 2f - (box.top + box.bottom) / 2f * scale
        val coverage = FloatArray(width * height)
        val radius = height * PEN_SHARE / 2f
        for (stroke in strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            if (points.size == 1) {
                val x = points[0].x * scale + dx
                val y = points[0].y * scale + dy
                segment(coverage, width, height, x, y, x, y, radius)
                continue
            }
            for (i in 1 until points.size) {
                segment(
                    coverage, width, height,
                    points[i - 1].x * scale + dx, points[i - 1].y * scale + dy,
                    points[i].x * scale + dx, points[i].y * scale + dy,
                    radius,
                )
            }
        }
        val rgb = FloatArray(width * height * 3)
        for (i in coverage.indices) {
            val shade = WHITE * (1f - coverage[i])
            rgb[i * 3] = shade
            rgb[i * 3 + 1] = shade
            rgb[i * 3 + 2] = shade
        }
        return PaddleOcrPipeline.Strip(width, rgb)
    }

    /**
     * Inks the round-capped line from (x0, y0) to (x1, y1), [radius] pixels
     * either side of it: each pixel takes the share of it the pen covers,
     * by its centre's distance from the line, and keeps the most ink any
     * line has given it.
     */
    private fun segment(
        coverage: FloatArray, width: Int, height: Int,
        x0: Float, y0: Float, x1: Float, y1: Float, radius: Float,
    ) {
        val reach = radius + 1f
        val left = floor(min(x0, x1) - reach).toInt().coerceAtLeast(0)
        val right = ceil(max(x0, x1) + reach).toInt().coerceAtMost(width - 1)
        val top = floor(min(y0, y1) - reach).toInt().coerceAtLeast(0)
        val bottom = ceil(max(y0, y1) + reach).toInt().coerceAtMost(height - 1)
        val vx = x1 - x0
        val vy = y1 - y0
        val length2 = vx * vx + vy * vy
        for (py in top..bottom) {
            val cy = py + 0.5f
            for (px in left..right) {
                val cx = px + 0.5f
                val t = if (length2 == 0f) 0f else (((cx - x0) * vx + (cy - y0) * vy) / length2).coerceIn(0f, 1f)
                val ex = cx - (x0 + t * vx)
                val ey = cy - (y0 + t * vy)
                val cover = (radius + 0.5f - sqrt(ex * ex + ey * ey)).coerceIn(0f, 1f)
                val at = py * width + px
                if (cover > coverage[at]) coverage[at] = cover
            }
        }
    }

    /** The strip's ink, 0 for white up to 1, for tests: column [x], row [y]. */
    fun inkAt(strip: PaddleOcrPipeline.Strip, x: Int, y: Int): Float =
        1f - strip.rgb[(y * strip.width + x) * 3] / WHITE

    /** How many columns of [strip] hold any ink, for tests. */
    fun inkedColumns(strip: PaddleOcrPipeline.Strip): Int =
        (0 until strip.width).count { x ->
            (0 until PaddleOcrPipeline.REC_HEIGHT).any { y -> inkAt(strip, x, y) > 0f }
        }
}
