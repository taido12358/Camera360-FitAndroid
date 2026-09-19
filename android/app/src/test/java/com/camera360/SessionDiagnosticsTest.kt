package com.camera360

import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDiagnosticsTest {

    @Test fun report_containsPerPhotoPairsDroppedPhotosFovAndTimings() {
        val pairs = listOf(
            YawRegistration.PairMatch(0, 1, 32.4, 0.91, 812, 0.40, 0.25),
            YawRegistration.PairMatch(1, 2, 30.1, 0.78, 700, 0.22, -0.5)
        )
        val res = YawRegistration.Result(
            headingsDeg = doubleArrayOf(0.0, 32.4, 62.5, Double.NaN),
            unreachable = listOf(3),
            pairs = pairs,
            pitchOffsetsDeg = doubleArrayOf(0.1, -0.2, 0.1, 0.0)
        )
        val up = floatArrayOf(0f, 1f, 0f)
        val text = SessionDiagnostics.format(
            photoNames = listOf("manual_0001.jpg", "manual_0002.jpg", "manual_0003.jpg", "manual_0004.jpg"),
            gravityUp = List(4) { up },
            registration = res,
            fovMeasuredDeg = 69.6, fovUsedDeg = 69.6, fovAdjusted = false,
            coverage = CoverageStats.Azimuth(0.42, 200.0),
            unreadablePhotos = 1, registrationMs = 8123, stitchMs = 13100,
            timingLines = listOf("prep=1ms")
        )
        println(text)
        assertTrue(text.contains("placed 3/4"))
        assertTrue(text.contains("unreachable=[3]"))
        assertTrue(text.contains("manual_0004.jpg DROPPED"))
        assertTrue(text.contains("manual_0002.jpg 32.4"))
        assertTrue(text.contains("fov measured=69.6 used=69.6 adjusted=false"))
        assertTrue(text.contains("coverage: 42% of the circle, largest gap 200 deg"))
        assertTrue(text.contains("registration 8123 ms, stitch 13100 ms"))
        assertTrue(text.contains("0 1 32.4 0.91 812 0.40 0.25"))
        assertTrue(text.contains("unreadable/no gravity: 1"))
    }
}
