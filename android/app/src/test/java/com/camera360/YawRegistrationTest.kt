package com.camera360

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Ground-truth tests for the accelerometer-only pipeline: a synthetic textured
 * "world" is photographed by virtual portrait cameras at known headings/pitch/roll,
 * and [YawRegistration] must recover the headings from the images + gravity alone.
 */
class YawRegistrationTest {

    private val longFov = 66.0
    private val w = 96
    private val h = 128

    /** A band-limited random texture defined on the unit sphere. */
    private class World(seed: Long) {
        private val rnd = Random(seed)
        private val dirs = Array(70) {
            val v = doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian())
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            val mag = 6.0 + rnd.nextDouble() * 34.0
            doubleArrayOf(v[0] / n * mag, v[1] / n * mag, v[2] / n * mag)
        }
        private val phase = DoubleArray(dirs.size) { rnd.nextDouble() * 6.283 }
        private val amp = DoubleArray(dirs.size) { 0.4 + rnd.nextDouble() }

        fun value(e: Double, n: Double, u: Double): Double {
            var s = 0.0
            for (k in dirs.indices) s += amp[k] * sin(dirs[k][0] * e + dirs[k][1] * n + dirs[k][2] * u + phase[k])
            return (128.0 + 14.0 * s).coerceIn(0.0, 255.0)
        }
    }

    /** Photograph [world] with a virtual portrait phone at pose [r] (device→world). */
    private fun photo(world: World, r: FloatArray, gravityNoiseDeg: Double = 0.0, rnd: Random = Random(1)): GrayFrame {
        val focal = (maxOf(w, h) / 2.0) / tan(Math.toRadians(longFov) / 2.0)
        val luma = FloatArray(w * h)
        for (py in 0 until h) for (px in 0 until w) {
            val dx = (px + 0.5 - w / 2.0) / focal
            val dy = -(py + 0.5 - h / 2.0) / focal
            val dz = -1.0
            val e = r[0] * dx + r[1] * dy + r[2] * dz
            val n = r[3] * dx + r[4] * dy + r[5] * dz
            val u = r[6] * dx + r[7] * dy + r[8] * dz
            val len = sqrt(e * e + n * n + u * u)
            luma[py * w + px] = world.value(e / len, n / len, u / len).toFloat()
        }
        var up = PoseMath.upInDevice(r)
        if (gravityNoiseDeg > 0.0) {
            val s = Math.toRadians(gravityNoiseDeg)
            up = doubleArrayOf(up[0] + rnd.nextGaussian() * s, up[1] + rnd.nextGaussian() * s, up[2] + rnd.nextGaussian() * s)
        }
        return GrayFrame(w, h, luma, up)
    }

    // ── PoseMath.rotationFromGravity ───────────────────────────────────────────

    @Test fun rotationFromGravity_reproducesKnownPose() {
        for ((az, el, roll) in listOf(
            Triple(0.0, 0.0, 0.0), Triple(123.0, 30.0, 10.0), Triple(270.0, -35.0, -20.0), Triple(45.0, 5.0, 0.0)
        )) {
            val truth = PoseMath.rotationFromAzElRoll(az, el, roll)
            val rebuilt = PoseMath.rotationFromGravity(PoseMath.upInDevice(truth), az)
            for (i in 0 until 9) assertEquals("az=$az el=$el roll=$roll [$i]", truth[i].toDouble(), rebuilt[i].toDouble(), 1e-4)
        }
    }

    @Test fun rotationFromGravity_headingDrivesAzimuth_gravityDrivesElevation() {
        val up = PoseMath.upInDevice(PoseMath.rotationFromAzElRoll(0.0, 25.0, 8.0))
        for (heading in listOf(0.0, 90.0, 200.0, 359.0)) {
            val r = PoseMath.rotationFromGravity(up, heading)
            assertEquals(heading, PoseMath.azimuthDeg(r).toDouble().let { if (it > 359.9) 0.0 else it }.let { if (heading == 359.0 && it < 1) 360.0 else it }, 0.05)
            assertEquals(25.0, PoseMath.elevationDeg(r).toDouble(), 0.05)
        }
    }

    // ── YawRegistration ────────────────────────────────────────────────────────

    @Test fun twoFrames_40degApart_levelPhone() {
        val world = World(7)
        val a = photo(world, PoseMath.rotationFromAzElRoll(0.0, 0.0))
        val b = photo(world, PoseMath.rotationFromAzElRoll(40.0, 0.0))
        val res = YawRegistration.estimateHeadings(listOf(a, b), longFov)
        assertTrue("should link the two photos", res.ok)
        assertEquals(40.0, res.headingsDeg[1], 1.0)
    }

    @Test fun twoFrames_withPitchAndRoll() {
        val world = World(11)
        val a = photo(world, PoseMath.rotationFromAzElRoll(10.0, 20.0, 5.0))
        val b = photo(world, PoseMath.rotationFromAzElRoll(48.0, 15.0, -4.0))
        val res = YawRegistration.estimateHeadings(listOf(a, b), longFov)
        assertTrue(res.ok)
        assertEquals(38.0, res.headingsDeg[1], 1.5)
    }

    @Test fun negativeDirection_isRecovered() {
        val world = World(5)
        val a = photo(world, PoseMath.rotationFromAzElRoll(100.0, 0.0))
        val b = photo(world, PoseMath.rotationFromAzElRoll(65.0, 0.0))
        val res = YawRegistration.estimateHeadings(listOf(a, b), longFov)
        assertTrue(res.ok)
        assertEquals(-35.0, res.headingsDeg[1], 1.0)
    }

    @Test fun fullCircle_nineFrames_withNoise_closesTheLoop() {
        val world = World(21)
        val rnd = Random(3)
        val azs = (0 until 9).map { it * 40.0 }
        val frames = azs.map { az ->
            val el = (rnd.nextDouble() - 0.5) * 20.0
            val roll = (rnd.nextDouble() - 0.5) * 8.0
            photo(world, PoseMath.rotationFromAzElRoll(az, el, roll), gravityNoiseDeg = 0.3, rnd = rnd)
        }
        val res = YawRegistration.estimateHeadings(frames, longFov)
        assertTrue("unreachable=${res.unreachable}", res.ok)
        for (k in azs.indices) {
            val err = YawRegistration.angularError(res.headingsDeg[k], azs[k])
            assertTrue("frame $k heading ${res.headingsDeg[k]} vs ${azs[k]} (err $err)", err < 2.0)
        }
    }

    @Test fun shuffledCaptureOrder_stillSolves() {
        val world = World(33)
        val azs = listOf(0.0, 120.0, 40.0, 240.0, 80.0, 200.0, 160.0, 280.0, 320.0)
        val frames = azs.map { photo(world, PoseMath.rotationFromAzElRoll(it, 0.0)) }
        val res = YawRegistration.estimateHeadings(frames, longFov)
        assertTrue("unreachable=${res.unreachable}", res.ok)
        for (k in azs.indices) {
            val err = YawRegistration.angularError(res.headingsDeg[k], azs[k])
            assertTrue("frame $k err $err", err < 2.0)
        }
    }

    @Test fun unrelatedPhotos_areNotLinked() {
        val a = photo(World(1), PoseMath.rotationFromAzElRoll(0.0, 0.0))
        val b = photo(World(2), PoseMath.rotationFromAzElRoll(30.0, 0.0))
        val res = YawRegistration.estimateHeadings(listOf(a, b), longFov)
        assertFalse("unrelated content must not be reported as matching", res.ok)
        assertEquals(listOf(1), res.unreachable)
    }

    @Test fun strayFirstShot_doesNotSinkTheOthers() {
        val stray = photo(World(99), PoseMath.rotationFromAzElRoll(0.0, 0.0))
        val world = World(4)
        val azs = listOf(100.0, 140.0, 180.0, 220.0)
        val frames = listOf(stray) + azs.map { photo(world, PoseMath.rotationFromAzElRoll(it, 0.0)) }
        val res = YawRegistration.estimateHeadings(frames, longFov)
        assertEquals("only the stray photo is left out", listOf(0), res.unreachable)
        // the others still get consistent relative headings (anchored on the linked group)
        val rel = (1 until frames.size).map { res.headingsDeg[it] - res.headingsDeg[1] }
        rel.forEachIndexed { k, v -> assertEquals(azs[k] - azs[0], v, 1.5) }
    }

    @Test fun nonOverlappingPhotos_areNotLinked() {
        val world = World(9)
        val a = photo(world, PoseMath.rotationFromAzElRoll(0.0, 0.0))
        val b = photo(world, PoseMath.rotationFromAzElRoll(180.0, 0.0))
        val res = YawRegistration.estimateHeadings(listOf(a, b), longFov)
        assertFalse(res.ok)
    }

    @Test fun loopClosure_spreadsAccumulatedError_inGraphSolver() {
        // Chain of 4 photos each measured 91 deg apart plus a closing edge saying photo 3 -> photo 0
        // is 90 deg further round. The 360 deg loop only closes if the extra degree is distributed.
        val pairs = listOf(
            YawRegistration.PairMatch(0, 1, 91.0, 0.9, 1000),
            YawRegistration.PairMatch(1, 2, 91.0, 0.9, 1000),
            YawRegistration.PairMatch(2, 3, 91.0, 0.9, 1000),
            YawRegistration.PairMatch(3, 0, 90.0, 0.9, 1000)   // heading_0 - heading_3 = 90 (mod 360)
        )
        val res = YawRegistration.solve(4, pairs)
        assertTrue(res.ok)
        val closure = (res.headingsDeg[0] + 360.0) - res.headingsDeg[3]
        assertTrue("loop should be consistent, got $closure", abs(closure - 90.0) < 1.0)
    }
}
