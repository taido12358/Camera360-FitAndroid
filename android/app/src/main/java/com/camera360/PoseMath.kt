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
