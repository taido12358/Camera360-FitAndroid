package com.camera360

/**
 * Crop rectangle for an equirectangular render that only covers part of the
 * sphere (e.g. a hand-held sweep of a few dozen photos). The horizontal axis is
 * azimuth, so it **wraps**: the crop may start near the right edge and continue
 * from the left edge.
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object CoverageCrop {

    /**
     * @param x0     first column of the crop (0 until width); columns run x0, x0+1, ... modulo the image width
     * @param width  number of columns kept (== image width when azimuth is fully covered)
     * @param y0     first row kept
     * @param height number of rows kept
     */
    data class Bounds(val x0: Int, val width: Int, val y0: Int, val height: Int)

    /**
     * Smallest crop containing all covered pixels.
     *
     * A row/column only counts as covered when at least [MIN_COVERAGE_FRACTION] of it is,
     * so a few stray pixels at the fringe of the blend do not stretch the crop.
     * [pad] extra pixels of margin are kept on every side that is actually cropped.
     * Returns null if nothing is covered.
     */
    fun bounds(covered: BooleanArray, w: Int, h: Int, pad: Int = 6): Bounds? {
        require(covered.size == w * h)
        val colCount = IntArray(w)
        val rowCount = IntArray(h)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) if (covered[base + x]) { colCount[x]++; rowCount[y]++ }
        }
        val minRowPixels = maxOf(2, (w * MIN_COVERAGE_FRACTION).toInt())
        val minColPixels = maxOf(2, (h * MIN_COVERAGE_FRACTION).toInt())

        var yMin = -1; var yMax = -1
        for (y in 0 until h) if (rowCount[y] >= minRowPixels) { if (yMin < 0) yMin = y; yMax = y }
        if (yMin < 0) return null
        val y0 = maxOf(0, yMin - pad)
        val y1 = minOf(h - 1, yMax + pad)

        val colCovered = BooleanArray(w) { colCount[it] >= minColPixels }
        if (colCovered.none { it }) return null

        // Largest circular run of uncovered columns is the part to cut away.
        var bestGapStart = -1; var bestGapLen = 0
        var x = 0
        // start scanning at a covered column so runs are never split by the array boundary
        val first = colCovered.indexOfFirst { it }
        var i = 0
        while (i < w) {
            val col = (first + i) % w
            if (!colCovered[col]) {
                val start = col
                var len = 0
                while (i < w && !colCovered[(first + i) % w]) { len++; i++ }
                if (len > bestGapLen) { bestGapLen = len; bestGapStart = start }
            } else i++
        }
        x = 0
        if (bestGapLen == 0) return Bounds(0, w, y0, y1 - y0 + 1)   // full azimuth coverage

        val keep = minOf(w, w - bestGapLen + 2 * pad)
        val startCol = ((bestGapStart + bestGapLen - pad) % w + w) % w
        return Bounds(startCol, keep, y0, y1 - y0 + 1)
    }

    /** Fraction of a row/column that must be covered for it to count. */
    private const val MIN_COVERAGE_FRACTION = 0.004
}

/**
 * How much of the horizontal circle (azimuth) a panorama actually covers, for telling the user
 * where to shoot more. Pure Kotlin, JVM-tested.
 */
object CoverageStats {

    /** [coveredFraction] of the 360 deg circle seen by any photo, and the [largestGapDeg] left uncovered. */
    data class Azimuth(val coveredFraction: Double, val largestGapDeg: Double)

    /**
     * A column counts as covered when at least [minCoverage] of its rows are; [covered] is row-major.
     * The gap search is circular (azimuth wraps).
     */
    fun azimuth(covered: BooleanArray, w: Int, h: Int, minCoverage: Double = 0.004): Azimuth {
        require(covered.size == w * h)
        val minPixels = maxOf(2, (h * minCoverage).toInt())
        val colOk = BooleanArray(w)
        for (x in 0 until w) {
            var c = 0
            for (y in 0 until h) if (covered[y * w + x]) c++
            colOk[x] = c >= minPixels
        }
        val coveredCols = colOk.count { it }
        if (coveredCols == 0) return Azimuth(0.0, 360.0)
        if (coveredCols == w) return Azimuth(1.0, 0.0)

        val first = colOk.indexOfFirst { it }
        var largest = 0
        var i = 0
        while (i < w) {
            if (!colOk[(first + i) % w]) {
                var len = 0
                while (i < w && !colOk[(first + i) % w]) { len++; i++ }
                if (len > largest) largest = len
            } else i++
        }
        return Azimuth(coveredCols.toDouble() / w, largest * 360.0 / w)
    }
}
