package com.camera360

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GravityMathTest {

    private fun up(az: Double, el: Double, roll: Double = 0.0) =
        PoseMath.upInDevice(PoseMath.rotationFromAzElRoll(az, el, roll)).map { it.toFloat() }.toFloatArray()

    @Test fun elevation_matchesThePoseTheGravityCameFrom() {
        for (el in listOf(-40.0, -10.0, 0.0, 25.0, 60.0)) {
            assertEquals(el, GravityMath.elevationDeg(up(123.0, el, 6.0)).toDouble(), 0.05)
        }
    }

    @Test fun roll_isZeroWhenUprightAndSignedWhenTipped() {
        assertEquals(0.0, GravityMath.rollDeg(up(0.0, 10.0, 0.0)).toDouble(), 0.05)
        val a = GravityMath.rollDeg(up(0.0, 10.0, 12.0)).toDouble()
        val b = GravityMath.rollDeg(up(0.0, 10.0, -12.0)).toDouble()
        assertEquals(12.0, kotlin.math.abs(a), 0.5)
        assertTrue("opposite tilts must have opposite signs ($a, $b)", a * b < 0)
    }

    @Test fun roll_isUndefinedWhenPointingStraightUp() {
        assertEquals(0.0, GravityMath.rollDeg(floatArrayOf(0.02f, 0.03f, -0.999f)).toDouble(), 0.0)
    }

    @Test fun angleBetween_isSymmetricAndMatchesKnownAngles() {
        val a = up(0.0, 0.0); val b = up(0.0, 10.0)
        assertEquals(10.0, GravityMath.angleBetweenDeg(a, b).toDouble(), 0.05)
        assertEquals(GravityMath.angleBetweenDeg(a, b), GravityMath.angleBetweenDeg(b, a), 1e-4f)
    }

    @Test fun steadiness_needsAFullWindowOfStillness() {
        val tr = GravityMath.SteadinessTracker(windowMs = 400, maxDriftDeg = 0.8f)
        val still = up(0.0, 5.0)
        var steady = false
        // 20 ms samples: not steady until 400 ms have elapsed
        for (t in 0..380 step 20) steady = tr.update(t.toLong(), still)
        assertFalse("must not claim steady before a full window", steady)
        for (t in 400..600 step 20) steady = tr.update(t.toLong(), still)
        assertTrue("steady after a full still window", steady)
    }

    @Test fun steadiness_dropsWhenThePhoneMoves_andRecovers() {
        val tr = GravityMath.SteadinessTracker(windowMs = 400, maxDriftDeg = 0.8f)
        var steady = false
        for (t in 0..600 step 20) steady = tr.update(t.toLong(), up(0.0, 5.0))
        assertTrue(steady)
        // swing by 3 degrees
        steady = tr.update(620, up(0.0, 8.0))
        assertFalse("a swing must break steadiness", steady)
        for (t in 640..1300 step 20) steady = tr.update(t.toLong(), up(0.0, 8.0))
        assertTrue("steady again after holding still at the new pose", steady)
    }

    @Test fun rollSign_isNegativeWhenTheRightEdgeIsTippedDown() {
        // right edge lower: gravity pulls toward +x, so world-up in device coordinates points toward -x
        val upRightEdgeDown = floatArrayOf(-0.1736f, 0.9848f, 0f)          // ~10 deg
        assertEquals(-10.0, GravityMath.rollDeg(upRightEdgeDown).toDouble(), 0.1)
        assertEquals(10.0, GravityMath.rollDeg(floatArrayOf(0.1736f, 0.9848f, 0f)).toDouble(), 0.1)
    }

    @Test fun rollLimit() {
        assertTrue(GravityMath.isRollAcceptable(5f))
        assertFalse(GravityMath.isRollAcceptable(-14f))
    }
}
