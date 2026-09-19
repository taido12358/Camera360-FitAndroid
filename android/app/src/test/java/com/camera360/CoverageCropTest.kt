package com.camera360

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageCropTest {

    private val w = 200
    private val h = 100

    private fun mask(vararg rects: IntArray): BooleanArray {
        // rect = [xFrom, xToExclusive, yFrom, yToExclusive]; x wraps modulo w
        val m = BooleanArray(w * h)
        for (r in rects) for (y in r[2] until r[3]) for (xx in r[0] until r[1]) m[y * w + (xx % w)] = true
        return m
    }

    @Test fun plainRectangle_isCroppedWithPadding() {
        val b = CoverageCrop.bounds(mask(intArrayOf(50, 120, 30, 70)), w, h, pad = 4)!!
        assertEquals(46, b.x0)
        assertEquals(70 + 8, b.width)          // 70 covered + 4 pad each side
        assertEquals(26, b.y0)
        assertEquals(40 + 8, b.height)
    }

    @Test fun regionWrappingAroundTheSeam_isOneContiguousCrop() {
        // covered columns 170..199 and 0..29 (60 wide, straddling the azimuth seam)
        val m = mask(intArrayOf(170, 230, 40, 60))
        val b = CoverageCrop.bounds(m, w, h, pad = 5)!!
        assertEquals(165, b.x0)
        assertEquals(70, b.width)
        // every covered column must fall inside the crop, taking wrap into account
        for (x in listOf(170, 199, 0, 29)) {
            val rel = ((x - b.x0) % w + w) % w
            assertTrue("column $x outside crop", rel < b.width)
        }
    }

    @Test fun fullAzimuthCoverage_keepsAllColumnsButCropsRows() {
        val b = CoverageCrop.bounds(mask(intArrayOf(0, 200, 20, 80)), w, h, pad = 3)!!
        assertEquals(0, b.x0)
        assertEquals(w, b.width)
        assertEquals(17, b.y0)
        assertEquals(66, b.height)
    }

    @Test fun twoSeparateRegions_cutsTheLargestGapOnly() {
        // 20..59 and 100..149; largest uncovered circular gap is 150..19 (70 wide)
        val b = CoverageCrop.bounds(mask(intArrayOf(20, 60, 10, 90), intArrayOf(100, 150, 10, 90)), w, h, pad = 2)!!
        assertEquals(18, b.x0)
        assertEquals(134, b.width)                 // 130 covered columns (20..149) + 2 pad each side
        for (x in (20 until 60) + (100 until 150)) assertTrue(((x - b.x0) % w + w) % w < b.width)
    }

    @Test fun strayPixels_doNotStretchTheCrop() {
        val m = mask(intArrayOf(50, 100, 30, 60))
        m[5 * w + 190] = true                       // one lone covered pixel far away
        val b = CoverageCrop.bounds(m, w, h, pad = 4)!!
        assertEquals(46, b.x0)
        assertEquals(58, b.width)
        assertEquals(26, b.y0)
        assertEquals(38, b.height)
    }

    @Test fun nothingCovered_returnsNull() {
        assertNull(CoverageCrop.bounds(BooleanArray(w * h), w, h))
    }
}
