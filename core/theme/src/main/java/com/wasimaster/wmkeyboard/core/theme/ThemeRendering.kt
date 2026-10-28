package com.wasimaster.wmkeyboard.core.theme

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Shared drawing helpers for gradients, key shapes, animation and image blur,
 * used by both the live keyboard (KbTheme/BoardBackground) and the settings
 * app's theme previews/editor so the two always render identically.
 */

/** Hue-rotated copy of [color]; alpha, saturation and value preserved. */
fun hueShift(color: Color, degrees: Float): Color {
    if (degrees == 0f) return color
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb() or (0xFF shl 24), hsv)
    hsv[0] = (hsv[0] + degrees).mod(360f)
    return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = color.alpha)
}

/**
 * Size-aware brush for a [GradientSpec]. [phase] (0..1) drives the theme
 * animation: FLOW slides a LINEAR gradient along its axis (mirror-tiled so the
 * loop is seamless), orbits a RADIAL center, and spins a SWEEP; HUE_CYCLE
 * rotates every stop's hue. Phase 0 with NONE is the static gradient.
 */
fun GradientSpec.brush(
    animation: ThemeAnimation = ThemeAnimation.NONE,
    phase: Float = 0f,
): Brush {
    val stops = colors
        .map { Color(it.toInt()) }
        // Shaders require >= 2 colors: pad a single color and fall back to a
        // transparent pair for a degenerate empty list (e.g. an imported theme
        // with "colors":[]), which would otherwise crash createShader().
        .let {
            when {
                it.isEmpty() -> listOf(Color.Transparent, Color.Transparent)
                it.size == 1 -> it + it
                else -> it
            }
        }
        .let {
            if (animation == ThemeAnimation.HUE_CYCLE) it.map { c -> hueShift(c, phase * 360f) } else it
        }
    val spec = this
    return object : ShaderBrush() {
        override fun createShader(size: Size): Shader = when (spec.type) {
            GradientType.LINEAR -> {
                val rad = Math.toRadians(spec.angleDeg.toDouble())
                val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
                val center = Offset(size.width / 2f, size.height / 2f)
                // Half-length that makes the gradient span the whole box at
                // any angle (projection of the box onto the gradient axis).
                val half = (abs(dir.x) * size.width + abs(dir.y) * size.height) / 2f
                // Mirror tiling repeats every 4×half; sliding by that per
                // cycle loops without a visible seam.
                val slide = if (animation == ThemeAnimation.FLOW) {
                    Offset(dir.x * 4f * half * phase, dir.y * 4f * half * phase)
                } else {
                    Offset.Zero
                }
                LinearGradientShader(
                    from = center - Offset(dir.x * half, dir.y * half) + slide,
                    to = center + Offset(dir.x * half, dir.y * half) + slide,
                    colors = stops,
                    tileMode = TileMode.Mirror,
                )
            }
            GradientType.RADIAL -> {
                val center = if (animation == ThemeAnimation.FLOW) {
                    Offset(
                        size.width * (0.5f + 0.22f * cos(phase * 2f * PI.toFloat())),
                        size.height * (0.5f + 0.22f * sin(phase * 2f * PI.toFloat())),
                    )
                } else {
                    Offset(size.width / 2f, size.height / 2f)
                }
                RadialGradientShader(
                    center = center,
                    radius = max(size.width, size.height) * 0.75f,
                    colors = stops,
                    tileMode = TileMode.Clamp,
                )
            }
            GradientType.SWEEP -> {
                val center = Offset(size.width / 2f, size.height / 2f)
                // Close the ring so the 360°→0° seam doesn't show.
                val shader = SweepGradientShader(center, stops + stops.first())
                val spin = spec.angleDeg + if (animation == ThemeAnimation.FLOW) phase * 360f else 0f
                if (spin != 0f) {
                    shader.setLocalMatrix(Matrix().apply { setRotate(spin, center.x, center.y) })
                }
                shader
            }
        }
    }
}

/**
 * The visible key outline for a shape kind; ROUNDED/CUT use [radiusDp].
 *
 * The rest size their own corners off the key, as a fraction of it. A shape
 * whose look *is* its proportion — a half-height arch, a hexagon's points, the
 * bite out of a ticket — would come apart at one end of the radius slider and
 * be a plain rectangle at the other, so it does not read the slider at all.
 *
 * [bleedDp] is how far the shape may spill past its own box on each side, and
 * only the slant uses it: see [SlantKeyShape]. Keys pass the gap they are inset
 * by; anything that clips to its bounds, or has no gap to spill into, leaves it
 * at zero.
 *
 * [KeyShapeKind.NONE] has no outline of its own, yet a pressed or latched key
 * still lights up, and a few surfaces borrow the key outline (the layer peek,
 * the trackpad, the autopilot overlay). All of those get the plain rounded one,
 * at [radiusDp].
 */
fun keyShapeFor(kind: KeyShapeKind, radiusDp: Int, bleedDp: Float = 0f): Shape = when (kind) {
    KeyShapeKind.NONE, KeyShapeKind.ROUNDED -> RoundedCornerShape(radiusDp.dp)
    KeyShapeKind.SHARP -> RectangleShape
    KeyShapeKind.PILL -> RoundedCornerShape(percent = 50)
    KeyShapeKind.CUT -> CutCornerShape(radiusDp.coerceIn(2, 14).dp)
    KeyShapeKind.SQUIRCLE -> SquircleKeyShape
    KeyShapeKind.ARCH -> ArchKeyShape
    KeyShapeKind.LEAF -> LeafKeyShape
    KeyShapeKind.SLANT -> SlantKeyShape(bleedDp)
    KeyShapeKind.HEXAGON -> HexagonKeyShape
    KeyShapeKind.SCALLOP -> ScallopKeyShape
    KeyShapeKind.TICKET -> TicketKeyShape
    KeyShapeKind.CIRCLE -> CircleKeyShape
}

/**
 * A centred circle inscribed in the key — the round cap people picture on a
 * typewriter. The touch target stays the full key box; only the paint shrinks.
 *
 * Only a box that is roughly square gets the circle. Anything much longer in
 * either direction becomes a stadium that still covers the whole box: a lone
 * circle in the middle of a spacebar reads as a hole rather than a key, and a
 * tall box — the on-key preview bubble is a key wide and twice a key tall —
 * would inscribe its circle around the *middle* and clip the label drawn near
 * the top clean off.
 */
private val CircleKeyShape: Shape = GenericShape { size, _ ->
    val d = min(size.width, size.height)
    if (circleBecomesStadium(size.width, size.height)) {
        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(d / 2f)))
    } else {
        addOval(Rect(Offset((size.width - d) / 2f, (size.height - d) / 2f), Size(d, d)))
    }
}

/**
 * Whether a box is too far from square for [KeyShapeKind.CIRCLE] to inscribe a
 * circle in it — the rule [CircleKeyShape] draws by, out here where a test can
 * reach it. It has to answer true for the tall boxes as well as the wide ones:
 * an inscribed circle only covers the middle, so a tall one clips whatever is
 * drawn at the top.
 */
fun circleBecomesStadium(width: Float, height: Float): Boolean =
    max(width, height) > min(width, height) * 1.6f

/** Round top, square bottom: a row of keys reads as a row of arches. */
private val ArchKeyShape: Shape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 50,
    bottomStartPercent = 12,
    bottomEndPercent = 12,
)

/** Two opposite corners fully round, the other two nearly square. */
private val LeafKeyShape: Shape = RoundedCornerShape(
    topStartPercent = 50,
    topEndPercent = 8,
    bottomStartPercent = 8,
    bottomEndPercent = 50,
)

/**
 * Parallelogram: both vertical sides lean right by a fraction of the height.
 *
 * Kept inside its box, the lean is taken out of the key — the top and the
 * bottom lose a third of the width a key has to begin with, and a column of
 * them reads as a row of thin straps. [bleedDp] pushes half the lean out past
 * each side instead, so the shape is exactly as wide as its box at every
 * height and the lean is spent on the gap between keys rather than on the key.
 * Neighbouring keys stay the same distance apart — the gap simply runs on a
 * diagonal — because their leaning edges are parallel.
 *
 * The spill is capped at half the lean (past that the shape would be *wider*
 * than the key) and at whatever the caller says is free, so a keyboard with the
 * gaps turned down never has two keys overlapping.
 */
private data class SlantKeyShape(private val bleedDp: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val lean = min(size.height * 0.22f, size.width * 0.3f)
        val bleed = min(lean / 2f, with(density) { bleedDp.dp.toPx() }).coerceAtLeast(0f)
        return Outline.Generic(
            Path().apply {
                moveTo(lean - bleed, 0f)
                lineTo(size.width + bleed, 0f)
                lineTo(size.width - lean + bleed, size.height)
                lineTo(-bleed, size.height)
                close()
            }
        )
    }
}

/**
 * Points left and right, flat top and bottom — the way round for a key, which
 * is wider than it is tall. A pointy-top hexagon would waste the width.
 */
private val HexagonKeyShape: Shape = GenericShape { size, _ ->
    val cut = min(size.width * 0.16f, size.height * 0.5f)
    val mid = size.height / 2f
    moveTo(cut, 0f)
    lineTo(size.width - cut, 0f)
    lineTo(size.width, mid)
    lineTo(size.width - cut, size.height)
    lineTo(cut, size.height)
    lineTo(0f, mid)
    close()
}

/**
 * A rim of even bumps, like the edge of a biscuit: a rectangle inset by the
 * bump depth, with a half-oval pushed back out along each step of each side.
 *
 * The bumps are counted per side from the key's own width and height, so they
 * come out roughly square whatever shape the key is — a spacebar gets more of
 * them, not longer ones. A radial ripple was the first attempt at this and
 * bunched its waves up at the corners, where the radius changes fastest.
 */
private val ScallopKeyShape: Shape = GenericShape { size, _ ->
    val bumps = scallopBumps(size.width, size.height)
    if (bumps == null) {
        addRect(Rect(Offset.Zero, size))
        return@GenericShape
    }
    val (across, down) = bumps
    val depth = min(size.width, size.height) / 7f
    val left = depth
    val top = depth
    val right = size.width - depth
    val bottom = size.height - depth
    val stepX = (right - left) / across
    val stepY = (bottom - top) / down
    // Angles run clockwise from three o'clock, so each half-oval starts at the
    // corner the previous one ended on and bulges away from the key's middle.
    moveTo(left, top)
    for (i in 0 until across) {
        val x = left + i * stepX
        arcTo(Rect(x, top - depth, x + stepX, top + depth), 180f, 180f, false)
    }
    for (j in 0 until down) {
        val y = top + j * stepY
        arcTo(Rect(right - depth, y, right + depth, y + stepY), 270f, 180f, false)
    }
    for (i in across - 1 downTo 0) {
        val x = left + i * stepX
        arcTo(Rect(x, bottom - depth, x + stepX, bottom + depth), 0f, 180f, false)
    }
    for (j in down - 1 downTo 0) {
        val y = top + j * stepY
        arcTo(Rect(left - depth, y, left + depth, y + stepY), 90f, 180f, false)
    }
    close()
}

/**
 * How many bumps [ScallopKeyShape] puts across the top and down each side of a
 * [width] by [height] box, or null when the box has nothing to put them on.
 *
 * The depth is a seventh of the short side: fewer, chunkier bumps than that
 * read as a cloud, and more of them disappear at the size a key is on screen.
 * A box laid out at zero on either side (#963: a long press in a tool panel,
 * under a scalloped theme) has no depth to count by. Zero over zero rounds as
 * NaN, which throws mid-draw, and a width over zero rounds to Int.MAX_VALUE
 * bumps, which would hang the keyboard instead. Such a box takes a plain
 * rectangle.
 */
internal fun scallopBumps(width: Float, height: Float): Pair<Int, Int>? {
    val depth = min(width, height) / 7f
    if (!(depth > 0f) || !width.isFinite() || !height.isFinite()) return null
    val across = max(2, ((width - 2f * depth) / (2f * depth)).roundToInt())
    val down = max(1, ((height - 2f * depth) / (2f * depth)).roundToInt())
    return across to down
}

/**
 * Concave corners, like a cinema ticket: the rectangle minus a circle at each
 * corner. Built with a path difference rather than four arcs because the arcs
 * have to meet the sides exactly, and one rounding error there shows as a nick.
 */
private val TicketKeyShape: Shape = object : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = min(size.width, size.height) * 0.25f
        val body = Path().apply { addRect(Rect(Offset.Zero, size)) }
        val bites = Path().apply {
            addOval(Rect(Offset.Zero, radius))
            addOval(Rect(Offset(size.width, 0f), radius))
            addOval(Rect(Offset(0f, size.height), radius))
            addOval(Rect(Offset(size.width, size.height), radius))
        }
        return Outline.Generic(Path().apply { op(body, bites, PathOperation.Difference) })
    }
}

/**
 * Superellipse (n = 4) stretched to the key bounds — the iOS-icon "squircle"
 * look, with corners that ease into the sides instead of arcing. Sampled as a
 * 64-gon; at key sizes the segments are sub-pixel.
 */
private val SquircleKeyShape: Shape = GenericShape { size, _ ->
    val a = size.width / 2.0
    val b = size.height / 2.0
    val n = 4.0
    val steps = 64
    for (i in 0..steps) {
        val t = 2.0 * PI * i / steps
        val ct = cos(t)
        val st = sin(t)
        val x = a + a * sign(ct) * abs(ct).pow(2.0 / n)
        val y = b + b * sign(st) * abs(st).pow(2.0 / n)
        if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
    }
    close()
}

/**
 * Cheap all-API blur: downscale with bilinear filtering, then upscale back.
 * Runs once per decode (not per frame), so heavy radii are fine. Radius is
 * the editor's 0..25 scale; 0 returns the bitmap untouched.
 */
fun Bitmap.blurredBy(radius: Float): Bitmap {
    if (radius < 0.5f) return this
    val factor = 1f + radius
    val w = max(8, (width / factor).toInt())
    val h = max(8, (height / factor).toInt())
    val small = Bitmap.createScaledBitmap(this, w, h, true)
    val out = Bitmap.createScaledBitmap(small, width, height, true)
    if (small !== out && small !== this) small.recycle()
    return out
}
