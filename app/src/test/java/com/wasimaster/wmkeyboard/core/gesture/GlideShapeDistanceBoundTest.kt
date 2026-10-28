package com.wasimaster.wmkeyboard.core.gesture

import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlideShapeStore.distance] with a cut-off answers exactly what the plain
 * mean distance says (issue #966, the seeding scan): a shape under the
 * cut-off gets its true distance, one over it is reported over it, and the
 * cheaper bound that skips the roots never changes which is which.
 */
class GlideShapeDistanceBoundTest {

    private companion object {
        /** NativeGlide.SEED_RADIUS. */
        const val SEED_RADIUS = 0.35f
    }

    /** The distance as its definition reads: the mean point distance, every root taken. */
    private fun plain(a: ByteArray, b: ByteArray): Float {
        val n = GlideWorkspace.SHAPE_POINTS
        var sum = 0f
        for (j in 0 until n) {
            val dx = (a[2 * j] - b[2 * j]).toFloat()
            val dy = (a[2 * j + 1] - b[2 * j + 1]).toFloat()
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / (n * GlideShapeStore.QUANT)
    }

    private fun shape(random: Random, spread: Int): ByteArray =
        ByteArray(GlideShapeStore.POINTS) { random.nextInt(-spread, spread + 1).toByte() }

    @Test
    fun `a cut-off never changes which shapes are near`() {
        val random = Random(966)
        var near = 0
        var far = 0
        repeat(4000) {
            val a = shape(random, 60)
            // Mostly far, sometimes a small wobble away, as a store's shapes sit around a stroke.
            val b = if (random.nextInt(4) == 0) ByteArray(a.size) { (a[it] + random.nextInt(-4, 5)).toByte() } else shape(random, 60)
            val exact = plain(a, b)
            // SEED_RADIUS is NativeGlide's, which is internal to :core:prediction
            // and so out of this module's reach; the value is copied.
            for (radius in floatArrayOf(SEED_RADIUS, GlideShapeStore.MERGE_RADIUS, GlideShapeStore.REJECT_RADIUS, 2f)) {
                val bounded = GlideShapeStore.distance(a, b, radius)
                if (exact <= radius) {
                    assertEquals("under $radius", exact, bounded, 1e-5f)
                    near++
                } else {
                    assertTrue("over $radius: $bounded", bounded > radius)
                    far++
                }
            }
        }
        // Both branches were exercised.
        assertTrue("$near near", near > 100)
        assertTrue("$far far", far > 100)
    }

    @Test
    fun `without a cut-off the distance is the plain mean`() {
        val random = Random(1)
        repeat(200) {
            val a = shape(random, 90)
            val b = shape(random, 90)
            assertEquals(plain(a, b), GlideShapeStore.distance(a, b), 1e-5f)
        }
        val same = shape(random, 50)
        assertEquals(0f, GlideShapeStore.distance(same, same, 0.1f), 0f)
    }
}
