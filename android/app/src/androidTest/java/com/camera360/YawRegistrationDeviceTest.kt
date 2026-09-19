package com.camera360

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Runs the accelerometer-only registration on the real device CPU so its speed
 * (and the absence of device-specific surprises) is measured, not guessed:
 * a 3-row, 30-photo sweep of a synthetic textured sphere with known poses.
 */
@RunWith(AndroidJUnit4::class)
class YawRegistrationDeviceTest {

    private val longFov = 66.0
    private val w = 108      // matches the app's 192 px long side for a 3:4 portrait photo
    private val h = 144

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

    private fun photo(world: World, r: FloatArray): GrayFrame {
        val focal = (maxOf(w, h) / 2.0) / tan(Math.toRadians(longFov) / 2.0)
        val luma = FloatArray(w * h)
        for (py in 0 until h) for (px in 0 until w) {
            val dx = (px + 0.5 - w / 2.0) / focal
            val dy = -(py + 0.5 - h / 2.0) / focal
            val e = r[0] * dx + r[1] * dy - r[2]
            val n = r[3] * dx + r[4] * dy - r[5]
            val u = r[6] * dx + r[7] * dy - r[8]
            val len = sqrt(e * e + n * n + u * u)
            luma[py * w + px] = world.value(e / len, n / len, u / len).toFloat()
        }
        return GrayFrame(w, h, luma, PoseMath.upInDevice(r))
    }

    @Test fun thirtyPhotoSweep_timingAndAccuracyOnDevice() {
        val world = World(3)
        val frames = ArrayList<GrayFrame>()
        val truth = ArrayList<Double>()
        for (row in 0 until 3) for (k in 0 until 10) {
            val az = k * 36.0 + row * 6.0
            frames.add(photo(world, PoseMath.rotationFromAzElRoll(az, (row - 1) * 28.0, 0.0)))
            truth.add(az)
        }
        val t0 = System.nanoTime()
        val res = YawRegistration.estimateHeadings(frames, longFov, onTiming = { Log.i("YawRegDevice", "phases: $it") })
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("YawRegDevice", "30 photos registered in $ms ms; unreachable=${res.unreachable}; pairs=${res.pairs.size}")

        assertTrue("unreachable=${res.unreachable}", res.ok)
        for (i in truth.indices) {
            val err = YawRegistration.angularError(res.headingsDeg[i] - res.headingsDeg[0], truth[i] - truth[0])
            assertTrue("photo $i err $err", err < 3.0)
        }
        assertTrue("registration took $ms ms on this device", ms < 60_000)
    }
}
