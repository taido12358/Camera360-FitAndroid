package com.camera360

import kotlin.math.abs

/**
 * Exposure / white-balance gain compensation for stitching (Brown & Lowe,
 * "Automatic Panoramic Image Stitching using Invariant Features", 2007, section 6).
 *
 * Phone cameras re-meter auto-exposure and auto-white-balance for every shot,
 * so the same wall can be noticeably brighter or tinted in one frame than in
 * its neighbour, which shows up as visible seams. Given, for each pair of
 * frames, how many overlapping samples they share and the mean intensity each
 * frame shows over that overlap, [solve] finds one multiplicative gain per
 * frame minimising the intensity mismatch over all overlaps, with a weak
 * prior pulling every gain toward 1 (so the result cannot just drift to
 * "everything darker" and frames without overlap stay untouched).
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object GainCompensation {

    /** Std-dev of the intensity error between overlapping samples (0..255 scale). */
    private const val SIGMA_N = 10.0

    /** Std-dev of the gain prior around 1 — small = trust the raw exposure more. */
    private const val SIGMA_G = 0.25

    /** Gains outside this range mean the overlap estimate is unreliable; clamp. */
    private const val MIN_GAIN = 0.6
    private const val MAX_GAIN = 1.7

    /**
     * @param count `count[i][j]` = number of overlapping samples of frames i and j (symmetric).
     * @param mean  `mean[i][j]` = mean intensity of frame i over its overlap with frame j.
     * @return one gain per frame; 1.0 for frames without any overlap.
     */
    fun solve(count: Array<DoubleArray>, mean: Array<DoubleArray>): DoubleArray {
        val n = count.size
        val invN2 = 1.0 / (SIGMA_N * SIGMA_N)
        val invG2 = 1.0 / (SIGMA_G * SIGMA_G)

        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (i in 0 until n) {
            var total = 0.0
            for (j in 0 until n) {
                if (j == i || count[i][j] <= 0.0) continue
                total += count[i][j]
                a[i][i] += count[i][j] * mean[i][j] * mean[i][j] * invN2
                a[i][j] -= count[i][j] * mean[i][j] * mean[j][i] * invN2
            }
            if (total <= 0.0) {          // no overlap at all: leave gain at 1
                a[i][i] = 1.0
                b[i] = 1.0
            } else {
                a[i][i] += total * invG2
                b[i] = total * invG2
            }
        }
        val x = solveLinear(a, b)
        val clamped = DoubleArray(n) { if (x[it].isNaN()) 1.0 else x[it].coerceIn(MIN_GAIN, MAX_GAIN) }

        // Relative gains are what fix the seams; the absolute level is arbitrary.
        // Re-centre on a mean of 1 so the whole panorama cannot come out globally
        // darker/brighter than the photos were (e.g. when overlap data is noisy).
        // Frames without any overlap keep their gain of exactly 1 and are left out.
        val overlapping = (0 until n).filter { i -> (0 until n).any { j -> j != i && count[i][j] > 0.0 } }
        if (overlapping.isEmpty()) return clamped
        val mean = overlapping.map { clamped[it] }.average()
        if (mean <= 0.0 || mean.isNaN()) return clamped
        return DoubleArray(n) { i ->
            if (i in overlapping) (clamped[i] / mean).coerceIn(MIN_GAIN, MAX_GAIN) else clamped[i]
        }
    }

    /** Gaussian elimination with partial pivoting; 1.0 for a singular column. */
    internal fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
            if (abs(m[pivot][col]) < 1e-12) continue
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col..n) m[r][c] -= f * m[col][c]
            }
        }
        val x = DoubleArray(n) { 1.0 }
        for (i in n - 1 downTo 0) {
            if (abs(m[i][i]) < 1e-12) continue
            var s = m[i][n]
            for (j in i + 1 until n) s -= m[i][j] * x[j]
            x[i] = s / m[i][i]
        }
        return x
    }
}
