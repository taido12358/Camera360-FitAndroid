package com.camera360

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverageStatsTest {
    private val w = 360
    private val h = 100

    private fun cols(vararg ranges: IntRange): BooleanArray {
        val m = BooleanArray(w * h)
        for (r in ranges) for (x in r) for (y in 20 until 80) m[y * w + (x % w)] = true
        return m
    }

    @Test fun full_isFullWithNoGap() {
        val a = CoverageStats.azimuth(cols(0..359), w, h)
        assertEquals(1.0, a.coveredFraction, 1e-9); assertEquals(0.0, a.largestGapDeg, 1e-9)
    }

    @Test fun oneOpenGap_isMeasuredInDegrees() {
        val a = CoverageStats.azimuth(cols(0..99, 200..359), w, h)     // hole 100..199 = 100 deg
        assertEquals(260.0 / 360.0, a.coveredFraction, 1e-9); assertEquals(100.0, a.largestGapDeg, 1e-9)
    }

    @Test fun gapAcrossTheSeam_isOneGap() {
        val a = CoverageStats.azimuth(cols(50..249), w, h)             // uncovered 250..359 and 0..49 = 160 deg
        assertEquals(160.0, a.largestGapDeg, 1e-9); assertEquals(200.0 / 360.0, a.coveredFraction, 1e-9)
    }

    @Test fun twoGaps_reportTheLarger() {
        val a = CoverageStats.azimuth(cols(0..59, 90..199, 260..359), w, h)   // gaps 30 and 60
        assertEquals(60.0, a.largestGapDeg, 1e-9)
    }

    @Test fun nothing_isAllGap() {
        val a = CoverageStats.azimuth(BooleanArray(w * h), w, h)
        assertEquals(0.0, a.coveredFraction, 1e-9); assertEquals(360.0, a.largestGapDeg, 1e-9)
    }
}
