package com.camera360

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Locks in the pose conventions that were wrong until 2026-09-19 (see
 * rules/android-native/sensors-gyroscope.md): camera-forward azimuth/elevation
 * (not getOrientation), and the ENU → (E, Up, N) axis swap used by the stitcher.
 */
class PoseMathTest {

    private val eps = 1e-4

    /** Row-major device→world matrix from the three device axes expressed in ENU. */
    private fun matrix(x: DoubleArray, y: DoubleArray, z: DoubleArray) = floatArrayOf(
        x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
        x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
        x[2].toFloat(), y[2].toFloat(), z[2].toFloat()
    )

    /**
     * Phone in portrait looking at compass [az], elevation [el], no roll —
     * built independently of PoseMath from first principles.
     */
    private fun pose(az: Double, el: Double, rollDeg: Double = 0.0): FloatArray {
        val a = Math.toRadians(az); val p = Math.toRadians(el)
        val f = doubleArrayOf(Math.cos(p) * Math.sin(a), Math.cos(p) * Math.cos(a), Math.sin(p))
        val z = doubleArrayOf(-f[0], -f[1], -f[2])
        // device Y = world Up projected perpendicular to forward
        val y = doubleArrayOf(-Math.sin(p) * Math.sin(a), -Math.sin(p) * Math.cos(a), Math.cos(p))
        val x = doubleArrayOf(
            y[1] * z[2] - y[2] * z[1], y[2] * z[0] - y[0] * z[2], y[0] * z[1] - y[1] * z[0]
        )
        val r = Math.toRadians(rollDeg)
        val c = Math.cos(r); val s = Math.sin(r)
        val xr = DoubleArray(3) { x[it] * c + y[it] * s }
        val yr = DoubleArray(3) { -x[it] * s + y[it] * c }
        return matrix(xr, yr, z)
    }

    @Test fun uprightFacingNorth_isAzimuth0_Elevation0() {
        val r = pose(0.0, 0.0)
        assertEquals(0.0, PoseMath.azimuthDeg(r).toDouble(), 1e-2)
        assertEquals(0.0, PoseMath.elevationDeg(r).toDouble(), 1e-2)
    }

    @Test fun elevation_isCameraElevation_notGetOrientationPitch() {
        // Upright phone, camera at horizon: getOrientation() would say -90°.
        assertEquals(0.0, PoseMath.elevationDeg(pose(123.0, 0.0)).toDouble(), 1e-2)
        assertEquals(35.0, PoseMath.elevationDeg(pose(0.0, 35.0)).toDouble(), 1e-2)
        assertEquals(-35.0, PoseMath.elevationDeg(pose(200.0, -35.0)).toDouble(), 1e-2)
    }

    @Test fun azimuth_isCompassHeading() {
        for (az in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 315.0)) {
            assertEquals(az, PoseMath.azimuthDeg(pose(az, 0.0)).toDouble(), 1e-2)
        }
    }

    @Test fun azimuthAndElevation_ignorePhoneRoll() {
        val r = pose(210.0, 20.0, rollDeg = 40.0)
        assertEquals(210.0, PoseMath.azimuthDeg(r).toDouble(), 1e-2)
        assertEquals(20.0, PoseMath.elevationDeg(r).toDouble(), 1e-2)
    }

    @Test fun stitchBasis_uprightFacingNorth() {
        val (right, up, fwd) = PoseMath.stitchBasis(pose(0.0, 0.0))
        // stitcher frame is (East, Up, North)
        assertVec(doubleArrayOf(1.0, 0.0, 0.0), right)
        assertVec(doubleArrayOf(0.0, 1.0, 0.0), up)
        assertVec(doubleArrayOf(0.0, 0.0, 1.0), fwd)
    }

    @Test fun stitchBasis_isOrthonormal() {
        val (right, up, fwd) = PoseMath.stitchBasis(pose(77.0, -23.0, rollDeg = 15.0))
        assertEquals(1.0, dot(right, right), eps)
        assertEquals(1.0, dot(up, up), eps)
        assertEquals(1.0, dot(fwd, fwd), eps)
        assertEquals(0.0, dot(right, up), eps)
        assertEquals(0.0, dot(right, fwd), eps)
        assertEquals(0.0, dot(up, fwd), eps)
    }

    /**
     * The end-to-end consistency the panorama depends on: a world direction
     * equal to where the camera points must land at the exact frame centre
     * (camX = camY = 0, camZ = 1) for any pose.
     */
    @Test fun cameraForwardDirection_projectsToFrameCentre() {
        for (az in listOf(0.0, 45.0, 135.0, 270.0)) for (el in listOf(-35.0, 0.0, 35.0)) {
            for (roll in listOf(0.0, 25.0)) {
                val (right, up, fwd) = PoseMath.stitchBasis(pose(az, el, roll))
                val d = PoseMath.stitchDirection(az, el)
                assertEquals("camX az=$az el=$el", 0.0, dot(d, right), eps)
                assertEquals("camY az=$az el=$el", 0.0, dot(d, up), eps)
                assertEquals("camZ az=$az el=$el", 1.0, dot(d, fwd), eps)
            }
        }
    }

    @Test fun pointAbove_landsInUpperHalfOfFrame() {
        // Camera at the horizon looking north; a point 10° higher must have camY > 0.
        val (_, up, fwd) = PoseMath.stitchBasis(pose(0.0, 0.0))
        val d = PoseMath.stitchDirection(0.0, 10.0)
        assert(dot(d, up) > 0.1)
        assert(dot(d, fwd) > 0.9)
    }

    @Test fun pointToTheEast_landsRightOfFrame() {
        val (right, _, _) = PoseMath.stitchBasis(pose(0.0, 0.0))
        assert(dot(PoseMath.stitchDirection(20.0, 0.0), right) > 0.3)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun assertVec(expected: DoubleArray, actual: DoubleArray) {
        for (i in 0..2) assertEquals("component $i", expected[i], actual[i], eps)
    }
}
