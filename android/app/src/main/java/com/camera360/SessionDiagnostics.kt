package com.camera360

import kotlin.math.roundToInt

/**
 * Plain-text record of what the accelerometer-only pipeline did in one stitching run (per-photo heading and
 * pitch correction, which pairs matched and how well, dropped photos, FOV, coverage, timings). Written next to
 * the app's data after every manual-mode stitch so a problem on a real phone can be analysed from the numbers
 * instead of only from the finished picture. Pure Kotlin, JVM-tested.
 */
object SessionDiagnostics {

    fun format(
        photoNames: List<String>,
        gravityUp: List<FloatArray>,
        registration: YawRegistration.Result,
        fovMeasuredDeg: Double,
        fovUsedDeg: Double,
        fovAdjusted: Boolean,
        coverage: CoverageStats.Azimuth?,
        unreadablePhotos: Int,
        registrationMs: Long,
        stitchMs: Long,
        timingLines: List<String>
    ): String = buildString {
        val n = photoNames.size
        appendLine("Camera360 manual-mode diagnostics")
        appendLine("photos usable: $n (unreadable/no gravity: $unreadablePhotos)")
        appendLine("fov measured=${"%.1f".format(fovMeasuredDeg)} used=${"%.1f".format(fovUsedDeg)} adjusted=$fovAdjusted")
        val placed = (0 until n).count { !registration.headingsDeg[it].isNaN() }
        appendLine("registration: placed $placed/$n unreachable=${registration.unreachable} pairs=${registration.pairs.size}")
        appendLine("time: registration ${registrationMs} ms, stitch ${stitchMs} ms")
        timingLines.forEach { appendLine("  phase: $it") }
        if (coverage != null) {
            appendLine("coverage: ${(coverage.coveredFraction * 100).roundToInt()}% of the circle, largest gap ${coverage.largestGapDeg.roundToInt()} deg")
        }
        appendLine()
        appendLine("per photo: index name heading_deg pitch_offset_deg tilt_deg roll_deg")
        for (i in 0 until n) {
            val h = registration.headingsDeg[i]
            val up = gravityUp[i]
            appendLine(
                "  $i ${photoNames[i]} " +
                    (if (h.isNaN()) "DROPPED" else "%.1f".format(h)) + " " +
                    "%.2f".format(registration.pitchOffsetsDeg.getOrElse(i) { 0.0 }) + " " +
                    "%.1f".format(GravityMath.elevationDeg(up)) + " " +
                    "%.1f".format(GravityMath.rollDeg(up))
            )
        }
        appendLine()
        appendLine("pairs kept: i j delta_deg ncc cells peak_margin vertical_deg")
        for (p in registration.pairs.sortedWith(compareBy({ it.i }, { it.j }))) {
            appendLine("  ${p.i} ${p.j} ${"%.1f".format(p.deltaDeg)} ${"%.2f".format(p.ncc)} ${p.cells} ${"%.2f".format(p.margin)} ${"%.2f".format(p.verticalDeg)}")
        }
    }
}
