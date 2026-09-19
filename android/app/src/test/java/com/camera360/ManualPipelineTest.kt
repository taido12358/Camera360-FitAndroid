package com.camera360

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Whole manual-mode pipeline on a synthetic world with known truth, exactly as the app runs it:
 * photos + accelerometer gravity only (no headings) -> [YawRegistration] headings and pitch
 * corrections -> poses -> [EquirectRenderer] -> compare with the true sphere.
 */
class ManualPipelineTest {

    private val longFov = 66.0
    private val outW = 720
    private val outH = 360

    private class Sphere(seed: Long) {
        private class Field(seed: Long) {
            private val rnd = Random(seed)
            private val dirs = Array(80) {
                val v = doubleArrayOf(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian())
                val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                val mag = 6.0 + rnd.nextDouble() * 30.0
                doubleArrayOf(v[0] / n * mag, v[1] / n * mag, v[2] / n * mag)
            }
            private val phase = DoubleArray(dirs.size) { rnd.nextDouble() * 6.283 }
            private val amp = DoubleArray(dirs.size) { 0.4 + rnd.nextDouble() }
            fun value(e: Double, n: Double, u: Double): Double {
                var s = 0.0
                for (k in dirs.indices) s += amp[k] * sin(dirs[k][0] * e + dirs[k][1] * n + dirs[k][2] * u + phase[k])
                return 128.0 + 11.0 * s
            }
        }
        private val fields = arrayOf(Field(seed), Field(seed + 1), Field(seed + 2))
        fun rgb(e: Double, n: Double, u: Double) = DoubleArray(3) { fields[it].value(e, n, u).coerceIn(0.0, 255.0) }
    }

    /** ARGB pixels of [sphere] seen from pose [r] at [w] x [h], with per-channel exposure [gain]. */
    private fun shoot(sphere: Sphere, r: FloatArray, w: Int, h: Int, gain: DoubleArray): IntArray {
        val focal = (maxOf(w, h) / 2.0) / tan(Math.toRadians(longFov) / 2.0)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = (x + 0.5 - w / 2.0) / focal
            val dy = -(y + 0.5 - h / 2.0) / focal
            val e = r[0] * dx + r[1] * dy - r[2]
            val n = r[3] * dx + r[4] * dy - r[5]
            val u = r[6] * dx + r[7] * dy - r[8]
            val len = sqrt(e * e + n * n + u * u)
            val c = sphere.rgb(e / len, n / len, u / len)
            fun ch(i: Int) = (c[i] * gain[i]).toInt().coerceIn(0, 255)
            px[y * w + x] = (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        return px
    }

    private fun luma(px: IntArray) = FloatArray(px.size) {
        val c = px[it]
        0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
    }

    private fun rmse(sphere: Sphere, out: IntArray, mask: BooleanArray? = null): Double {
        var sum = 0.0; var count = 0
        for (oy in 0 until outH) {
            val pitch = (0.5 - (oy + 0.5) / outH) * PI
            for (ox in 0 until outW) {
                val px = out[oy * outW + ox]
                if (px == EquirectRenderer.UNCOVERED) continue
                if (mask != null && !mask[oy * outW + ox]) continue
                val az = ox.toDouble() / outW * 2 * PI
                val t = sphere.rgb(cos(pitch) * sin(az), cos(pitch) * cos(az), sin(pitch))
                val got = doubleArrayOf(((px shr 16) and 0xFF).toDouble(), ((px shr 8) and 0xFF).toDouble(), (px and 0xFF).toDouble())
                for (c in 0..2) { val d = got[c] - t[c]; sum += d * d }
                count += 3
            }
        }
        return sqrt(sum / count)
    }

    private class Shot(val az: Double, val el: Double, val roll: Double, val gain: DoubleArray)

    private fun plan(rnd: Random): List<Shot> = buildList {
        for (row in 0 until 3) for (k in 0 until 10) {
            add(Shot(
                az = k * 36.0 + row * 6.0 + (rnd.nextDouble() - 0.5) * 6.0,        // hand-held: not evenly spaced
                el = (row - 1) * 28.0 + (rnd.nextDouble() - 0.5) * 8.0,
                roll = (rnd.nextDouble() - 0.5) * 4.0,
                gain = DoubleArray(3) { 0.85 + rnd.nextDouble() * 0.3 }               // AE / AWB drift
            ))
        }
    }

    @Test fun fullManualPipeline_reproducesTheWorld() {
        val sphere = Sphere(23)
        val rnd = Random(31)
        val shots = plan(rnd)

        // What the phone actually delivers: pixels + a slightly wrong (noisy) gravity vector per photo.
        val trueBias = shots.map { (rnd.nextDouble() - 0.5) * 2.0 }                   // +-1 deg pitch error
        val mean = trueBias.average()
        val bias = trueBias.map { it - mean }
        val gray = ArrayList<GrayFrame>()
        val rgbPixels = ArrayList<IntArray>()
        for ((i, s) in shots.withIndex()) {
            val truth = PoseMath.rotationFromAzElRoll(s.az, s.el, s.roll)
            val small = shoot(sphere, truth, 108, 144, s.gain)
            val big = shoot(sphere, truth, 240, 320, s.gain)
            val believed = PoseMath.rotationFromAzElRoll(s.az, s.el + bias[i], s.roll)
            gray.add(GrayFrame(108, 144, luma(small), PoseMath.upInDevice(believed)))
            rgbPixels.add(big)
        }

        val res = YawRegistration.estimateHeadings(gray, longFov)
        val placed = shots.indices.filter { !res.headingsDeg[it].isNaN() }
        println("PIPELINE placed ${placed.size}/${shots.size}, unreachable=${res.unreachable}")
        assertTrue("most photos must be placed (${placed.size}/${shots.size})", placed.size >= shots.size * 0.8)

        fun renderWith(offsets: DoubleArray?): IntArray {
            val poses = YawRegistration.poses(gray, res.headingsDeg, offsets)
            val frames = placed.map { EquirectRenderer.Frame(rgbPixels[it], 240, 320, poses[it], longFov) }
            return EquirectRenderer.render(frames, outW, outH, compensateExposure = true)
        }

        // The panorama's azimuth origin is photo 0's heading; rotate the truth accordingly by shifting columns.
        val originAz = shots[0].az
        fun shifted(out: IntArray): IntArray {
            val s = Math.round(originAz / 360.0 * outW).toInt()
            return IntArray(out.size) { i -> val y = i / outW; val x = i % outW; out[y * outW + (x - s + outW) % outW] }
        }

        val plainOut = shifted(renderWith(null))
        val corrected = shifted(renderWith(res.pitchOffsetsDeg))
        val e0 = rmse(sphere, plainOut)
        val e1 = rmse(sphere, corrected)
        println("PIPELINE rmse gravity-only=${"%.2f".format(e0)} with pitch correction=${"%.2f".format(e1)}")
        assertTrue("pipeline output must resemble the true world (rmse $e1)", e1 < 25.0)
        assertTrue("pitch correction must not make things worse (plain $e0, corrected $e1)", e1 <= e0 * 1.05)
    }
}
