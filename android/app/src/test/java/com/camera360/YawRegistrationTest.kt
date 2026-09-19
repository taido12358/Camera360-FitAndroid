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

    private interface Scene {
        /** Brightness (0..255) seen along the world direction (east, north, up). */
        fun value(e: Double, n: Double, u: Double): Double
    }

    /**
     * A real photograph (an actual room interior) wrapped around the observer as an
     * equirectangular strip covering elevations -60..+60 deg — natural image statistics
     * (flat areas, edges, gradients) instead of synthetic sinusoids.
     */
    private class PhotoScene : Scene {
        // Binary PGM (P5), upright grayscale, header "P5\n<w> <h>\n255\n".
        private val iw: Int
        private val ih: Int
        private val gray: ByteArray

        init {
            val bytes = requireNotNull(PhotoScene::class.java.getResourceAsStream("/room_photo.pgm")) {
                "missing room_photo.pgm"
            }.readBytes()
            var pos = 0
            fun token(): String {
                while (bytes[pos].toInt().toChar().isWhitespace()) pos++
                val start = pos
                while (!bytes[pos].toInt().toChar().isWhitespace()) pos++
                return String(bytes, start, pos - start)
            }
            require(token() == "P5")
            iw = token().toInt(); ih = token().toInt(); token()
            pos++                                            // single whitespace after maxval
            gray = bytes.copyOfRange(pos, pos + iw * ih)
        }

        private fun px(x: Int, y: Int): Double =
            (gray[y.coerceIn(0, ih - 1) * iw + x.coerceIn(0, iw - 1)].toInt() and 0xFF).toDouble()

        override fun value(e: Double, n: Double, u: Double): Double {
            val az = Math.atan2(e, n)                                   // -pi..pi
            val el = Math.toDegrees(Math.asin(u.coerceIn(-1.0, 1.0)))
            if (el < -60.0 || el > 60.0) return 128.0
            val fx = (az + Math.PI) / (2 * Math.PI) * iw - 0.5
            val fy = (0.5 - el / 120.0) * ih - 0.5
            val x0 = Math.floor(fx).toInt(); val y0 = Math.floor(fy).toInt()
            val tx = fx - x0; val ty = fy - y0
            return (px(x0, y0) * (1 - tx) + px(x0 + 1, y0) * tx) * (1 - ty) +
                (px(x0, y0 + 1) * (1 - tx) + px(x0 + 1, y0 + 1) * tx) * ty
        }
    }

    /** A band-limited random texture defined on the unit sphere. */
    private class World(seed: Long) : Scene {
        private val rnd = Random(seed)
        private val dirs = Array(70) {
            val v = doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian())
            val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            val mag = 6.0 + rnd.nextDouble() * 34.0
            doubleArrayOf(v[0] / n * mag, v[1] / n * mag, v[2] / n * mag)
        }
        private val phase = DoubleArray(dirs.size) { rnd.nextDouble() * 6.283 }
        private val amp = DoubleArray(dirs.size) { 0.4 + rnd.nextDouble() }

        override fun value(e: Double, n: Double, u: Double): Double {
            var s = 0.0
            for (k in dirs.indices) s += amp[k] * sin(dirs[k][0] * e + dirs[k][1] * n + dirs[k][2] * u + phase[k])
            return (128.0 + 14.0 * s).coerceIn(0.0, 255.0)
        }
    }

    /** Photograph [world] with a virtual portrait phone at pose [r] (device→world). */
    private fun photo(
        world: Scene,
        r: FloatArray,
        gravityNoiseDeg: Double = 0.0,
        rnd: Random = Random(1),
        exposure: Double = 1.0,          // brightness multiplier about mid-grey (auto-exposure drift)
        pixelNoise: Double = 0.0         // additive gaussian sensor noise (0..255 scale)
    ): GrayFrame {
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
            var v = world.value(e / len, n / len, u / len)
            v = 128.0 + (v - 128.0) * exposure
            if (pixelNoise > 0.0) v += rnd.nextGaussian() * pixelNoise
            luma[py * w + px] = v.coerceIn(0.0, 255.0).toFloat()
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

    // ── Realistic conditions: real photo texture, exposure drift, sensor noise ─────

    @Test fun realPhoto_twoFrames_40degApart() {
        val scene = PhotoScene()
        val a = photo(scene, PoseMath.rotationFromAzElRoll(30.0, 0.0), rnd = Random(2))
        val b = photo(scene, PoseMath.rotationFromAzElRoll(70.0, 0.0), rnd = Random(3))
        val res = YawRegistration.estimateHeadings(listOf(a, b), longFov)
        assertTrue("should link", res.ok)
        assertEquals(40.0, res.headingsDeg[1], 1.5)
    }

    @Test fun realPhoto_fullCircle_withExposureDriftAndNoise() {
        val scene = PhotoScene()
        val rnd = Random(8)
        val azs = (0 until 9).map { it * 40.0 }
        val frames = azs.map { az ->
            val el = (rnd.nextDouble() - 0.5) * 16.0
            val roll = (rnd.nextDouble() - 0.5) * 6.0
            val exposure = 0.8 + rnd.nextDouble() * 0.4          // 0.8 .. 1.2: AE re-metering per shot
            photo(scene, PoseMath.rotationFromAzElRoll(az, el, roll), gravityNoiseDeg = 0.4, rnd = rnd,
                exposure = exposure, pixelNoise = 4.0)
        }
        val res = YawRegistration.estimateHeadings(frames, longFov)
        assertTrue("unreachable=${res.unreachable}", res.ok)
        for (k in azs.indices) {
            val err = YawRegistration.angularError(res.headingsDeg[k], azs[k])
            assertTrue("frame $k heading ${res.headingsDeg[k]} vs ${azs[k]} (err $err)", err < 3.0)
        }
    }

    @Test fun syntheticTexture_withExposureDrift_andHeavyNoise() {
        val world = World(41)
        val rnd = Random(5)
        val azs = listOf(0.0, 35.0, 70.0, 105.0)
        val frames = azs.map { photo(world, PoseMath.rotationFromAzElRoll(it, 0.0), rnd = rnd,
            exposure = 0.75 + rnd.nextDouble() * 0.5, pixelNoise = 8.0) }
        val res = YawRegistration.estimateHeadings(frames, longFov)
        assertTrue(res.ok)
        for (k in azs.indices) assertTrue("frame $k", YawRegistration.angularError(res.headingsDeg[k], azs[k]) < 2.0)
    }
    /**
     * Three-row hand-held-style sweep (30 photos, 36 deg apart, rows at -28/0/+28 deg elevation) of a
     * real room photo, with exposure drift, sensor noise and gravity noise. Part of that photo is a
     * hazy window with almost no texture, where correlation is unreliable — so the honest bar is:
     * most photos placed, and most placed photos accurate (never silently wrong across the board).
     */
    @Test fun threeRowSweep_realPhoto_mostPhotosPlacedAndAccurate() {
        val scene = PhotoScene()
        val rnd = Random(12)
        val frames = ArrayList<GrayFrame>()
        val truth = ArrayList<Double>()
        for (row in 0 until 3) for (k in 0 until 10) {
            val az = k * 36.0 + row * 6.0                       // rows are offset a little, like a hand-held sweep
            val el = (row - 1) * 28.0 + (rnd.nextDouble() - 0.5) * 6.0
            frames.add(photo(scene, PoseMath.rotationFromAzElRoll(az, el, (rnd.nextDouble() - 0.5) * 4.0),
                gravityNoiseDeg = 0.3, rnd = Random(rnd.nextLong()), exposure = 0.85 + rnd.nextDouble() * 0.3, pixelNoise = 3.0))
            truth.add(az)
        }
        val t0 = System.nanoTime()
        val res = YawRegistration.estimateHeadings(frames, longFov)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("registration took $ms ms on the JVM", ms < 15_000)

        val placed = truth.indices.filter { !res.headingsDeg[it].isNaN() }
        val errs = placed.map { YawRegistration.angularError(res.headingsDeg[it] - res.headingsDeg[0], truth[it] - truth[0]) }
        val accurate = errs.count { it < 4.0 }
        println("SWEEP placed=${placed.size}/30 accurate(<4deg)=$accurate maxErr=${"%.1f".format(errs.maxOrNull() ?: -1.0)}")
        assertTrue("only ${placed.size}/30 placed (unreachable=${res.unreachable})", placed.size >= 24)
        assertTrue("only $accurate/${placed.size} placed photos accurate (errs=${errs.map { "%.1f".format(it) }})",
            accurate >= placed.size * 0.8)
    }

    @Test fun triangleFilter_dropsTheWeakEdgeOfAnInconsistentTriangle_keepsTheRest() {
        // photos 0,1,2 at true headings 0, 40, 80: edges 0-1 (40) and 1-2 (40) are right, the weak 0-2 edge
        // claims 55 (should be 80) -> it is the culprit of the inconsistent triangle.
        val pairs = listOf(
            YawRegistration.PairMatch(0, 1, 40.0, 0.9, 900),
            YawRegistration.PairMatch(1, 2, 40.0, 0.85, 900),
            YawRegistration.PairMatch(0, 2, 55.0, 0.5, 400)
        )
        val kept = YawRegistration.filterByTriangles(pairs)
        assertEquals(listOf(0 to 1, 1 to 2), kept.map { it.i to it.j })
    }

    @Test fun triangleFilter_keepsConsistentTrianglesAndUncheckableEdges() {
        val consistent = listOf(
            YawRegistration.PairMatch(0, 1, 30.0, 0.7, 900),
            YawRegistration.PairMatch(1, 2, 30.0, 0.7, 900),
            YawRegistration.PairMatch(0, 2, 60.5, 0.6, 900),
            YawRegistration.PairMatch(2, 3, 25.0, 0.45, 900)        // in no triangle: cannot be checked, kept
        )
        assertEquals(4, YawRegistration.filterByTriangles(consistent).size)
    }

    @Test fun pitchBiasOfGravity_isEstimatedAndCorrected() {
        val world = World(52)
        val rnd = Random(9)
        val n = 9
        val azs = (0 until n).map { it * 40.0 }
        val els = (0 until n).map { (rnd.nextDouble() - 0.5) * 16.0 }
        var bias = (0 until n).map { (rnd.nextDouble() - 0.5) * 3.0 }          // +-1.5 deg accelerometer pitch error
        val meanBias = bias.average()
        bias = bias.map { it - meanBias }                                     // only relative bias is observable
        val frames = (0 until n).map { i ->
            val truth = PoseMath.rotationFromAzElRoll(azs[i], els[i])
            val clean = photo(world, truth)
            // same picture, but the phone believes it was pitched bias[i] higher than it really was
            val believed = PoseMath.rotationFromAzElRoll(azs[i], els[i] + bias[i])
            GrayFrame(clean.width, clean.height, clean.luma, PoseMath.upInDevice(believed))
        }
        val res = YawRegistration.estimateHeadings(frames, longFov)
        assertTrue("unreachable=${res.unreachable}", res.ok)
        val before = (0 until n).map { abs(bias[it]) }.average()
        val corrected = YawRegistration.poses(frames, res.headingsDeg, res.pitchOffsetsDeg)
        val after = (0 until n).map { abs(PoseMath.elevationDeg(corrected[it]) - els[it]) }.average()
        println("PITCH mean |error| before=${"%.2f".format(before)} after=${"%.2f".format(after)}")
        assertTrue("pitch error should shrink (before $before, after $after)", after < before * 0.6)
    }

    @Test fun everyPlacedPhoto_staysConnectedToTheRoot_evenWithManyFalseEdges() {
        // Regression (code review): a photo linked only through a dropped photo used to stay "placed" with a bogus
        // heading of ~1 deg. Property: on random graphs full of false edges, placed photos are always connected.
        val rnd = Random(2024)
        repeat(300) { trial ->
            val n = 9
            val truth = DoubleArray(n) { rnd.nextDouble() * 360.0 }
            val pairs = ArrayList<YawRegistration.PairMatch>()
            for (i in 0 until n) for (j in i + 1 until n) {
                if (rnd.nextDouble() > 0.35) continue
                val good = rnd.nextDouble() < 0.5
                val d = if (good) YawRegistration.wrap180(truth[j] - truth[i]) + rnd.nextGaussian() * 0.5 else (rnd.nextDouble() - 0.5) * 300.0
                pairs.add(YawRegistration.PairMatch(i, j, d, 0.4 + rnd.nextDouble() * 0.55, 500))
            }
            val res = YawRegistration.solve(n, pairs)
            val placed = (0 until n).filter { !res.headingsDeg[it].isNaN() }
            if (placed.size < 2) return@repeat
            // BFS over the returned edges among placed photos, starting anywhere placed
            val seen = HashSet<Int>(); val stack = ArrayDeque<Int>()
            stack.addLast(placed[0]); seen.add(placed[0])
            while (stack.isNotEmpty()) {
                val u = stack.removeLast()
                for (p in res.pairs) {
                    if (res.headingsDeg[p.i].isNaN() || res.headingsDeg[p.j].isNaN()) continue
                    val v = when (u) { p.i -> p.j; p.j -> p.i; else -> continue }
                    if (seen.add(v)) stack.addLast(v)
                }
            }
            assertTrue("trial $trial: placed photos $placed but only ${seen.sorted()} are connected through kept edges", seen.containsAll(placed))
            assertTrue("trial $trial: unreachable list must match NaN headings", res.unreachable.toSet() == (0 until n).filter { res.headingsDeg[it].isNaN() }.toSet())
        }
    }

    @Test fun wrap180_isUsableFromOutside() {
        assertEquals(-170.0, YawRegistration.wrap180(190.0), 1e-9)
        assertEquals(180.0, YawRegistration.wrap180(-180.0), 1e-9)
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
