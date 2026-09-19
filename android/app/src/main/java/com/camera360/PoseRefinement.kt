package com.camera360

import kotlin.math.abs

/**
 * Sharpens sensor-derived camera poses (phones that DO have a rotation-vector sensor) with the same image
 * registration used for accelerometer-only phones. Indoors the compass part of the rotation vector is
 * disturbed by metal and wiring and can be several degrees off between neighbouring shots, which shows up
 * as ghosting at the seams; registering the photos against each other pins the relative headings to
 * a fraction of a degree.
 *
 * Deliberately conservative: a photo's registered heading is used only if it agrees with its sensor
 * heading to within [TRUST_DEG] (after removing the one global offset); otherwise the sensor pose stays.
 * If too few photos can be checked the sensor poses are returned untouched. Pure Kotlin (JVM-tested).
 */
object PoseRefinement {

    /** A registered heading further than this from the sensor's (after the global offset) is not trusted. */
    private const val TRUST_DEG = 12.0

    /** Refinement is only applied if at least this share of the photos was placed and trusted. */
    private const val MIN_TRUSTED_SHARE = 0.6

    class Result(
        /** Poses to render with: refined for trusted photos, the sensor pose for the rest. */
        val poses: List<FloatArray>,
        /** Number of photos whose heading came from registration. */
        val refinedCount: Int,
        /** Heading change applied per photo (degrees, registered - sensor); 0 where the sensor pose was kept. */
        val headingChangeDeg: DoubleArray
    )

    /**
     * [gray] are small grayscale copies of the photos, [sensorPoses] the device-to-world matrices from the
     * rotation-vector sensor (same order), [fovDeg] the long-side field of view.
     */
    fun refine(gray: List<GrayFrame>, sensorPoses: List<FloatArray>, fovDeg: Double): Result {
        require(gray.size == sensorPoses.size)
        val n = gray.size
        val unchanged = Result(sensorPoses, 0, DoubleArray(n))
        if (n < 3) return unchanged

        val reg = YawRegistration.estimateHeadings(gray, fovDeg)
        val sensorHeading = DoubleArray(n) { PoseMath.azimuthDeg(sensorPoses[it]).toDouble() }

        // The registration's zero is arbitrary; the sensor's is absolute. Estimate the single offset between
        // them robustly (circular median of registered - sensor) so a few bad photos cannot skew it.
        val placed = (0 until n).filter { !reg.headingsDeg[it].isNaN() }
        if (placed.size < n * MIN_TRUSTED_SHARE) return unchanged
        // Circular median: centre the differences on their circular mean first, so a cluster straddling +-180 deg
        // is not split by the wrap point, then take the median of the deviations.
        val diffs = placed.map { reg.headingsDeg[it] - sensorHeading[it] }
        val meanRad = kotlin.math.atan2(diffs.sumOf { kotlin.math.sin(Math.toRadians(it)) }, diffs.sumOf { kotlin.math.cos(Math.toRadians(it)) })
        val reference = Math.toDegrees(meanRad)
        val deviations = diffs.map { YawRegistration.wrap180(it - reference) }.sorted()
        val offset = reference + deviations[deviations.size / 2]

        val trusted = BooleanArray(n)
        val change = DoubleArray(n)
        for (i in placed) {
            val d = YawRegistration.wrap180(reg.headingsDeg[i] - sensorHeading[i] - offset)
            if (abs(d) <= TRUST_DEG) { trusted[i] = true; change[i] = d }
        }
        val trustedCount = trusted.count { it }
        if (trustedCount < n * MIN_TRUSTED_SHARE) return unchanged

        // Registered pose = gravity of the photo + registered heading (sensor heading + change) + pitch bias fix.
        val registered = YawRegistration.poses(
            gray,
            DoubleArray(n) { sensorHeading[it] + change[it] },
            reg.pitchOffsetsDeg
        )
        val poses = List(n) { if (trusted[it]) registered[it] else sensorPoses[it] }
        return Result(poses, trustedCount, change)
    }

}
