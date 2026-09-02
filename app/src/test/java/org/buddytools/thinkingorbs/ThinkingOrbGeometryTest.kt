package org.buddytools.thinkingorbs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkingOrbGeometryTest {
    @Test
    fun defaultBandHasStableDepthSortedGeometry() {
        val frame = ThinkingOrbGeometry.band(size = 64f, timeSeconds = 0.0)

        assertEquals(566, frame.size)
        assertTrue(frame.zipWithNext().all { (a, b) -> a.z <= b.z })
        assertTrue(frame.all { it.x.isFinite() && it.y.isFinite() && it.radius >= 0.3f })
        assertTrue(frame.all { it.alpha in 0f..1f && it.accent in 0f..1f })
    }

    @Test
    fun animationParametersChangeTheRenderedBand() {
        val base = ThinkingOrbGeometry.band(64f, 1.25)
        val denser = ThinkingOrbGeometry.band(64f, 1.25, ThinkingOrbParameters(dots = 1.5f))
        val reversed = ThinkingOrbGeometry.band(64f, 1.25, ThinkingOrbParameters(reverse = true))

        assertTrue(denser.size > base.size)
        assertTrue(reversed.zip(base).any { (a, b) -> abs(a.x - b.x) > 0.5f })
    }
}
