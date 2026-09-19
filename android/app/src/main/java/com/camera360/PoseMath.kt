package com.camera360

import kotlin.math.asin
import kotlin.math.atan2

/**
 * Pure (no Android dependency) pose math shared by [GyroscopeManager] and
 * [StitchingEngine], so the conventions live in exactly one place and can be
 * unit-tested on the JVM.
 *
 * Rotation matrices are 9-float, row-major, device→world, as produced by
 * `SensorManager.getRotationMatrixFromVector`. The world frame is ENU
 * (x = East, y = North, z = Up). Device axes: +X = right edge of the screen,
 * +Y = top of the screen, +Z = out of the screen; the back camera looks along
 * -Z. Column j of R is the world-space direction of device axis j.
 */
object PoseMath {

    /** World-space (ENU) direction the back camera points: -column 2 of R. */
    fun cameraForwardEnu(r: FloatArray): DoubleArray =
        doubleArrayOf(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())

    /** Compass heading of the camera: 0° = north, 90° = east, range [0, 360). */
    fun azimuthDeg(r: FloatArray): Float {
        val f = cameraForwardEnu(r)
        return ((Math.toDegrees(atan2(f[0], f[1])).toFloat() + 360f) % 360f)
    }

    /** Camera elevation: 0° = horizon, +90° = straight up, -90° = straight down. */
    fun elevationDeg(r: FloatArray): Float {
        val f = cameraForwardEnu(r)
        return Math.toDegrees(asin(f[2].coerceIn(-1.0, 1.0))).toFloat()
    }

    /**
     * Camera basis (right, up, forward) as unit vectors in the stitcher's
     * world frame **(x = East, y = Up, z = North)** — i.e. the ENU components
     * of the sensor matrix with components 1 and 2 swapped.
     */
    fun stitchBasis(r: FloatArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val right = doubleArrayOf(r[0].toDouble(), r[6].toDouble(), r[3].toDouble())
        val up = doubleArrayOf(r[1].toDouble(), r[7].toDouble(), r[4].toDouble())
        val fwd = doubleArrayOf(-r[2].toDouble(), -r[8].toDouble(), -r[5].toDouble())
        return Triple(right, up, fwd)
    }

    /**
     * Device→world rotation matrix (row-major, ENU) for a device that only has
     * an accelerometer: [upDev] is the world "up" direction expressed in device
     * coordinates (the accelerometer reading at rest, any length), which fixes
     * pitch and roll exactly; [headingDeg] is the compass heading of the back
     * camera's horizontal direction, which no accelerometer can provide and
     * must come from elsewhere (image registration, see [YawRegistration]).
     *
     * Rows are the world axes in device coordinates: East, North, Up. When the
     * camera points (almost) straight up/down its horizontal heading is
     * undefined, so the device's +Y axis is used as the reference instead.
     */
    fun rotationFromGravity(upDev: DoubleArray, headingDeg: Double): FloatArray {
        val u = normalized(upDev)
        var h = doubleArrayOf(0.0, 0.0, -1.0)                       // camera forward, device coords
        h = subtractScaled(h, u, dot(h, u))                         // horizontal part
        if (norm(h) < 1e-3) h = subtractScaled(doubleArrayOf(0.0, 1.0, 0.0), u, u[1])
        h = normalized(h)
        val e0 = cross(h, u)                                        // "right of heading" direction
        val th = Math.toRadians(headingDeg)
        val c = kotlin.math.cos(th)
        val s = kotlin.math.sin(th)
        val n = DoubleArray(3) { c * h[it] - s * e0[it] }
        val e = DoubleArray(3) { s * h[it] + c * e0[it] }
        return floatArrayOf(
            e[0].toFloat(), e[1].toFloat(), e[2].toFloat(),
            n[0].toFloat(), n[1].toFloat(), n[2].toFloat(),
            u[0].toFloat(), u[1].toFloat(), u[2].toFloat()
        )
    }

    /**
     * Rotation matrix for a portrait phone whose camera looks at compass
     * [azimuthDeg] / [elevationDeg], rolled by [rollDeg] about the camera axis.
     * Independent construction, mainly to generate ground-truth poses in tests
     * and simulations.
     */
    fun rotationFromAzElRoll(azimuthDeg: Double, elevationDeg: Double, rollDeg: Double = 0.0): FloatArray {
        val a = Math.toRadians(azimuthDeg)
        val p = Math.toRadians(elevationDeg)
        val f = doubleArrayOf(
            kotlin.math.cos(p) * kotlin.math.sin(a),
            kotlin.math.cos(p) * kotlin.math.cos(a),
            kotlin.math.sin(p)
        )
        val z = doubleArrayOf(-f[0], -f[1], -f[2])
        val y = doubleArrayOf(
            -kotlin.math.sin(p) * kotlin.math.sin(a),
            -kotlin.math.sin(p) * kotlin.math.cos(a),
            kotlin.math.cos(p)
        )
        val x = cross(y, z)
        val r = Math.toRadians(rollDeg)
        val c = kotlin.math.cos(r)
        val s = kotlin.math.sin(r)
        val xr = DoubleArray(3) { x[it] * c + y[it] * s }
        val yr = DoubleArray(3) { -x[it] * s + y[it] * c }
        return floatArrayOf(
            xr[0].toFloat(), yr[0].toFloat(), z[0].toFloat(),
            xr[1].toFloat(), yr[1].toFloat(), z[1].toFloat(),
            xr[2].toFloat(), yr[2].toFloat(), z[2].toFloat()
        )
    }

    /** World "up" in device coordinates for rotation matrix [r]: its third row. */
    fun upInDevice(r: FloatArray): DoubleArray =
        doubleArrayOf(r[6].toDouble(), r[7].toDouble(), r[8].toDouble())

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray) = kotlin.math.sqrt(dot(a, a))
    private fun normalized(a: DoubleArray): DoubleArray {
        val n = norm(a)
        return if (n < 1e-12) doubleArrayOf(0.0, 1.0, 0.0) else DoubleArray(3) { a[it] / n }
    }
    private fun subtractScaled(a: DoubleArray, b: DoubleArray, k: Double) = DoubleArray(3) { a[it] - k * b[it] }
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    /** Stitcher-frame (E, Up, N) unit vector for a compass azimuth / elevation in degrees. */
    fun stitchDirection(azimuthDeg: Double, elevationDeg: Double): DoubleArray {
        val az = Math.toRadians(azimuthDeg)
        val el = Math.toRadians(elevationDeg)
        return doubleArrayOf(
            kotlin.math.cos(el) * kotlin.math.sin(az),
            kotlin.math.sin(el),
            kotlin.math.cos(el) * kotlin.math.cos(az)
        )
    }
}
