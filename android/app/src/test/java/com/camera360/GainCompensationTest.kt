package com.camera360

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GainCompensationTest {

    /** Builds count/mean matrices for frames that each see [scene] scaled by their own exposure. */
    private fun overlapStats(exposure: DoubleArray, scene: Double, samples: Double = 1000.0)
        : Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val n = exposure.size
        val count = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else samples } }
        val mean = Array(n) { i -> DoubleArray(n) { _ -> exposure[i] * scene } }
        return count to mean
    }

    @Test fun equalExposure_givesUnitGains() {
        val (count, mean) = overlapStats(doubleArrayOf(1.0, 1.0, 1.0), scene = 120.0)
        val g = GainCompensation.solve(count, mean)
        g.forEach { assertEquals(1.0, it, 1e-6) }
    }

    @Test fun brighterFrame_isPulledDown_relativeToDarker() {
        // Frame 1 was exposed 25% brighter than frame 0 for the same scene.
        val (count, mean) = overlapStats(doubleArrayOf(1.0, 1.25), scene = 100.0)
        val g = GainCompensation.solve(count, mean)
        assertTrue("bright frame should get the smaller gain", g[1] < g[0])
        // After correction the two frames must agree on the overlap.
        assertEquals(g[0] * mean[0][1], g[1] * mean[1][0], 3.0)
    }

    @Test fun chainOfFrames_allEndUpConsistent() {
        // 4 frames with progressively drifting exposure, each overlapping only its neighbours.
        val exposure = doubleArrayOf(1.0, 1.1, 1.2, 1.3)
        val n = exposure.size
        val scene = 110.0
        val count = Array(n) { i -> DoubleArray(n) { j -> if (kotlin.math.abs(i - j) == 1) 800.0 else 0.0 } }
        val mean = Array(n) { i -> DoubleArray(n) { exposure[i] * scene } }
        val g = GainCompensation.solve(count, mean)
        for (i in 0 until n - 1) {
            val a = g[i] * exposure[i] * scene
            val b = g[i + 1] * exposure[i + 1] * scene
            assertEquals("neighbours $i/${i + 1} should match after correction", a, b, 3.0)
        }
    }

    @Test fun frameWithoutOverlap_keepsGainOne() {
        val count = Array(3) { i -> DoubleArray(3) { j -> if (i != j && i < 2 && j < 2) 500.0 else 0.0 } }
        val mean = Array(3) { i -> DoubleArray(3) { if (i == 1) 130.0 else 100.0 } }
        val g = GainCompensation.solve(count, mean)
        assertEquals(1.0, g[2], 1e-9)
    }

    @Test fun absurdMismatch_isClamped() {
        val (count, mean) = overlapStats(doubleArrayOf(1.0, 5.0), scene = 40.0)
        val g = GainCompensation.solve(count, mean)
        g.forEach { assertTrue(it in 0.6..1.7) }
    }

    @Test fun darkOrEmptyStats_doNotProduceNaN() {
        val (count, mean) = overlapStats(doubleArrayOf(1.0, 1.0), scene = 0.0)
        GainCompensation.solve(count, mean).forEach { assertTrue(!it.isNaN()) }
    }
}
