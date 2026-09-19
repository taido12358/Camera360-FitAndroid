package com.camera360

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Live shooting feedback from the accelerometer's gravity direction alone (pure Kotlin, JVM-tested).
 * [up] is world "up" in device coordinates, unit length; the back camera looks along device -Z and the
 * top of the screen is device +Y.
 */
object GravityMath {

    /** Camera elevation: 0 = level with the horizon, +90 = pointing at the sky, -90 = at the ground. */
    fun elevationDeg(up: FloatArray): Float =
        Math.toDegrees(asin((-up[2]).toDouble().coerceIn(-1.0, 1.0))).toFloat()

    /**
     * Roll about the camera axis: 0 when the phone is upright in portrait (screen top toward the sky),
     * positive when tipped toward its right edge. Undefined (returns 0) when the camera points nearly
     * straight up/down, where roll has no meaning.
     */
    fun rollDeg(up: FloatArray): Float {
        val horizontal = sqrt((up[0] * up[0] + up[1] * up[1]).toDouble())
        if (horizontal < 0.15) return 0f
        return Math.toDegrees(atan2(up[0].toDouble(), up[1].toDouble())).toFloat()
    }

    /** Angle in degrees between two unit gravity directions. */
    fun angleBetweenDeg(a: FloatArray, b: FloatArray): Float {
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(dot)).toFloat()
    }

    /**
     * Decides whether the phone is being held still: the gravity direction must have moved by no more than
     * [maxDriftDeg] over the last [windowMs]. Fed with (timestamp, up) samples as they arrive.
     */
    class SteadinessTracker(private val windowMs: Long = 400L, private val maxDriftDeg: Float = 0.8f) {
        private class Sample(val t: Long, val up: FloatArray)
        private val samples = ArrayDeque<Sample>()

        /** Adds a sample; returns true if the phone has been steady for the whole window. */
        fun update(timeMs: Long, up: FloatArray): Boolean {
            samples.addLast(Sample(timeMs, up.copyOf()))
            while (samples.size > 1 && timeMs - samples.first().t > windowMs * 2) samples.removeFirst()
            val oldestInWindow = samples.firstOrNull { timeMs - it.t <= windowMs } ?: return false
            // need to have observed a full window, otherwise "steady" is just "just started"
            if (timeMs - samples.first().t < windowMs) return false
            return samples.filter { timeMs - it.t <= windowMs }.all { angleBetweenDeg(it.up, oldestInWindow.up) <= maxDriftDeg &&
                angleBetweenDeg(it.up, up) <= maxDriftDeg }
        }

        fun reset() = samples.clear()
    }

    /** True if [rollDeg] is small enough for a clean sweep (a big roll makes rows tilt). */
    fun isRollAcceptable(rollDeg: Float, limitDeg: Float = 8f) = abs(rollDeg) <= limitDeg
}
