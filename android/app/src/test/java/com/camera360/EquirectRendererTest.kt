package com.camera360

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * End-to-end geometry/quality checks for the pose-driven renderer: a coloured, textured
 * synthetic sphere is photographed by virtual cameras at known poses; the rendered
 * equirectangular panorama must match the sphere's true appearance pixel for pixel.
 */
class EquirectRendererTest {

    private val longFov = 66.0
    private val pw = 240
    private val ph = 320
    private val outW = 720
    private val outH = 360

    /** Band-limited random colour texture on the unit sphere (one independent field per channel). */
    private class Sphere(seed: Long) {
        private class Field(seed: Long) {
            private val rnd = Random(seed)
            private val dirs = Array(60) {
                val v = doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian())
                val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                val mag = 3.0 + rnd.nextDouble() * 12.0
                doubleArrayOf(v[0] / n * mag, v[1] / n * mag, v[2] / n * mag)
            }
            private val phase = DoubleArray(dirs.size) { rnd.nextDouble() * 6.283 }
            private val amp = DoubleArray(dirs.size) { 0.4 + rnd.nextDouble() }
            fun value(e: Double, n: Double, u: Double): Double {
                var s = 0.0
                for (k in dirs.indices) s += amp[k] * sin(dirs[k][0] * e + dirs[k][1] * n + dirs[k][2] * u + phase[k])
                return 128.0 + 10.0 * s
            }
        }
        private val fields = arrayOf(Field(seed), Field(seed + 1), Field(seed + 2))
        fun rgb(e: Double, n: Double, u: Double) = DoubleArray(3) { fields[it].value(e, n, u).coerceIn(0.0, 255.0) }
    }

    /** ARGB photo of [sphere] from pose [r], with a per-channel brightness multiplier [gain] (auto-exposure / AWB drift). */
    private fun photo(sphere: Sphere, r: FloatArray, gain: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)): EquirectRenderer.Frame {
        val focal = (maxOf(pw, ph) / 2.0) / tan(Math.toRadians(longFov) / 2.0)
        val px = IntArray(pw * ph)
        for (y in 0 until ph) for (x in 0 until pw) {
            val dx = (x + 0.5 - pw / 2.0) / focal
            val dy = -(y + 0.5 - ph / 2.0) / focal
            val e = r[0] * dx + r[1] * dy - r[2]
            val n = r[3] * dx + r[4] * dy - r[5]
            val u = r[6] * dx + r[7] * dy - r[8]
            val len = sqrt(e * e + n * n + u * u)
            val c = sphere.rgb(e / len, n / len, u / len)
            fun ch(i: Int) = (c[i] * gain[i]).toInt().coerceIn(0, 255)
            px[y * pw + x] = (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return EquirectRenderer.Frame(px, pw, ph, r, longFov)
    }

    /** RMS colour error (0..255 scale) between the render and the true sphere, over covered pixels; also the covered share. */
    private fun rmse(sphere: Sphere, out: IntArray): Pair<Double, Double> {
        var sum = 0.0; var count = 0
        for (oy in 0 until outH) {
            val pitch = (0.5 - (oy + 0.5) / outH) * PI
            for (ox in 0 until outW) {
                val px = out[oy * outW + ox]
                if (px == EquirectRenderer.UNCOVERED) continue
                val az = ox.toDouble() / outW * 2 * PI
                val t = sphere.rgb(cos(pitch) * sin(az), cos(pitch) * cos(az), sin(pitch))
                val got = doubleArrayOf(((px shr 16) and 0xFF).toDouble(), ((px shr 8) and 0xFF).toDouble(), (px and 0xFF).toDouble())
                for (c in 0..2) { val d = got[c] - t[c]; sum += d * d }
                count += 3
            }
        }
        return sqrt(sum / count) to count / 3.0 / (outW * outH)
    }

    private fun sweep(sphere: Sphere, rnd: Random? = null, gains: (Random?) -> DoubleArray = { doubleArrayOf(1.0, 1.0, 1.0) },
                      yawNoiseDeg: Double = 0.0): List<EquirectRenderer.Frame> {
        val frames = ArrayList<EquirectRenderer.Frame>()
        for (row in 0 until 3) for (k in 0 until 10) {
            val az = k * 36.0 + row * 6.0
            val el = (row - 1) * 28.0
            val yaw = az + (if (rnd != null && yawNoiseDeg > 0) rnd.nextGaussian() * yawNoiseDeg else 0.0)
            // the photo is rendered from the TRUE pose, but the renderer is told the (noisy) one
            val truePose = PoseMath.rotationFromAzElRoll(az, el, 0.0)
            val told = PoseMath.rotationFromAzElRoll(yaw, el, 0.0)
            val f = photo(sphere, truePose, gains(rnd))
            frames.add(EquirectRenderer.Frame(f.pixels, pw, ph, told, longFov))
        }
        return frames
    }

    @Test fun exactPoses_reproduceTheTrueSphere() {
        val sphere = Sphere(5)
        val out = EquirectRenderer.render(sweep(sphere), outW, outH, compensateExposure = false)
        val (err, covered) = rmse(sphere, out)
        println("RENDER exact poses: rmse=${"%.2f".format(err)} covered=${"%.0f".format(covered * 100)}%")
        assertTrue("three rows of 10 photos must cover most of the sphere, got $covered", covered > 0.60)
        assertTrue("geometry must match the true sphere (rmse $err)", err < 6.0)
    }

    @Test fun cullingFrames_doesNotChangeASinglePixel_andIsFaster() {
        val sphere = Sphere(5)
        val frames = sweep(sphere)
        val t0 = System.nanoTime()
        val brute = EquirectRenderer.render(frames, outW, outH, compensateExposure = false, cullFrames = false)
        val t1 = System.nanoTime()
        val culled = EquirectRenderer.render(frames, outW, outH, compensateExposure = false, cullFrames = true)
        val t2 = System.nanoTime()
        println("RENDER culling: brute=${(t1 - t0) / 1_000_000} ms culled=${(t2 - t1) / 1_000_000} ms")
        var diff = 0
        for (i in brute.indices) if (brute[i] != culled[i]) diff++
        assertEquals("culling must not change any pixel", 0, diff)
        assertTrue("culling should be clearly faster (brute ${(t1 - t0) / 1_000_000} ms vs ${(t2 - t1) / 1_000_000} ms)", (t2 - t1) < (t1 - t0) * 0.8)
    }

    @Test fun aFailureOnARenderThread_reachesTheCaller_insteadOfLeavingBlackRows() {
        val sphere = Sphere(5)
        val good = sweep(sphere)
        // a frame with too few pixels makes sampleFrame index out of bounds on some row -> must throw, not return a partial image
        val broken = EquirectRenderer.Frame(IntArray(4), pw, ph, PoseMath.rotationFromAzElRoll(0.0, 0.0), longFov)
        var thrown = false
        try { EquirectRenderer.render(good + broken, outW, outH, compensateExposure = false) } catch (t: Throwable) { thrown = true }
        assertTrue("a worker-thread exception must propagate", thrown)
    }

    @Test fun horizontalFlipOrWrongAxes_wouldBeCaught() {
        // Sanity check of the test itself: a render whose poses are mirrored east<->west must be much worse.
        val sphere = Sphere(5)
        val frames = ArrayList<EquirectRenderer.Frame>()
        for (row in 0 until 3) for (k in 0 until 10) {
            val az = k * 36.0 + row * 6.0
            val el = (row - 1) * 28.0
            val f = photo(sphere, PoseMath.rotationFromAzElRoll(az, el, 0.0))
            frames.add(EquirectRenderer.Frame(f.pixels, pw, ph, PoseMath.rotationFromAzElRoll(-az, el, 0.0), longFov))
        }
        val (err, _) = rmse(sphere, EquirectRenderer.render(frames, outW, outH, compensateExposure = false))
        assertTrue("mirrored poses should be clearly wrong (rmse $err)", err > 15.0)
    }

    @Test fun exposureCompensation_removesTheSeamsCausedByAutoExposureDrift() {
        val sphere = Sphere(8)
        val drift = { r: Random? -> DoubleArray(3) { 0.78 + r!!.nextDouble() * 0.44 } }     // 0.78 .. 1.22 per channel
        val without = EquirectRenderer.render(sweep(sphere, Random(4), drift), outW, outH, compensateExposure = false)
        val with = EquirectRenderer.render(sweep(sphere, Random(4), drift), outW, outH, compensateExposure = true)
        val e0 = rmse(sphere, without).first
        val e1 = rmse(sphere, with).first
        println("RENDER exposure drift: rmse without=${"%.2f".format(e0)} with=${"%.2f".format(e1)}")
        assertTrue("compensation should clearly help (without $e0, with $e1)", e1 < e0 * 0.85)
    }

    @Test fun blendPower_effectWithSmallYawErrors() {
        val sphere = Sphere(11)
        for (p in listOf(0.5, 1.0, 2.0, 4.0)) {
            val out = EquirectRenderer.render(sweep(sphere, Random(6), yawNoiseDeg = 0.6), outW, outH,
                compensateExposure = false, blendPower = p)
            println("RENDER blendPower=$p with 0.6 deg yaw error: rmse=${"%.2f".format(rmse(sphere, out).first)}")
        }
        assertEquals(1.0, 1.0, 0.0)   // informational (see printed numbers)
    }
}
