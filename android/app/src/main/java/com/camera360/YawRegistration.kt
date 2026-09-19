package com.camera360

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A small grayscale copy of one captured photo plus the gravity direction the
 * phone measured when it was taken. [upDev] is world "up" in device
 * coordinates (accelerometer reading at rest); [luma] is row-major 0..255,
 * upright (EXIF already applied), x = device right, y = device up on screen.
 */
class GrayFrame(
    val width: Int,
    val height: Int,
    val luma: FloatArray,
    val upDev: DoubleArray
)

/**
 * Recovers the compass heading of every photo for phones that only have an
 * accelerometer (no gyroscope / magnetometer).
 *
 * Gravity already fixes each photo's pitch and roll exactly; the only unknown
 * left is its heading (rotation about the vertical axis). Photos are warped
 * into a common gravity-levelled (azimuth, elevation) frame in which every
 * photo initially "looks" at azimuth 0; if photo j was really turned by
 * Δ = heading_j - heading_i relative to photo i, then the scene at azimuth α in
 * photo j appears at α + Δ in photo i. So Δ is found as a plain horizontal
 * shift that maximises normalised cross-correlation of the overlap, for every
 * pair of photos. Headings then follow from a weighted least-squares solve
 * over all pairs, which also spreads the accumulated error when the photos
 * close a 360° loop.
 *
 * Pure Kotlin (no Android dependencies) so it can be unit-tested on the JVM.
 */
object YawRegistration {

    /** Grid covers elevations in [-MAX_ELEVATION_DEG, +MAX_ELEVATION_DEG]. */
    private const val MAX_ELEVATION_DEG = 85.0
    private const val COARSE_STEP_DEG = 2.0
    private const val FINE_STEP_DEG = 1.0

    /** Overlap needed for a shift to count: at least this share of the smaller footprint... */
    private const val MIN_OVERLAP_FRACTION = 0.15
    private const val MIN_OVERLAP_CELLS = 40

    /** Fine-stage overlap floor (1 deg cells): ~12x12 deg of shared scene. */
    private const val MIN_FINE_CELLS = 150

    /** Minimum correlation for the coarse candidate to be refined / a pair edge to be kept. */
    private const val MIN_COARSE_NCC = 0.25
    const val MIN_NCC = 0.45

    /** Half width (deg) of the refinement search around the coarse peak, and its step. */
    private const val REFINE_HALF_RANGE_DEG = 3.0
    private const val REFINE_STEP_DEG = 0.25

    /** Residual (deg) at which a pair measurement's weight has fallen to 1/2 in the robust solve. */
    private const val ROBUST_SCALE_DEG = 3.0

    data class PairMatch(val i: Int, val j: Int, val deltaDeg: Double, val ncc: Double, val cells: Int)

    class Result(
        /** Heading (deg) of each photo relative to photo 0; NaN for photos that could not be linked. */
        val headingsDeg: DoubleArray,
        /** Indices of photos not linked (directly or transitively) to photo 0. */
        val unreachable: List<Int>,
        val pairs: List<PairMatch>
    ) {
        val ok: Boolean get() = unreachable.isEmpty()
    }

    // ── Sampling a photo in the gravity-levelled frame ─────────────────────────

    private class Sampler(private val frame: GrayFrame, private val hp: FloatArray, longSideFovDeg: Double) {
        private val w = frame.width
        private val h = frame.height
        private val focal = (max(w, h) / 2.0) / tan(Math.toRadians(longSideFovDeg) / 2.0)
        private val r = PoseMath.rotationFromGravity(frame.upDev, 0.0)

        /** High-passed luma of the photo in direction ([azDeg], [elDeg]); NaN if not visible. */
        fun sample(azDeg: Double, elDeg: Double): Float {
            val az = Math.toRadians(azDeg)
            val el = Math.toRadians(elDeg)
            val dE = cos(el) * sin(az)
            val dN = cos(el) * cos(az)
            val dU = sin(el)
            val devX = r[0] * dE + r[3] * dN + r[6] * dU
            val devY = r[1] * dE + r[4] * dN + r[7] * dU
            val devZ = r[2] * dE + r[5] * dN + r[8] * dU
            val camZ = -devZ                        // camera looks along device -Z
            if (camZ <= 0.05) return Float.NaN
            val px = devX / camZ * focal + w / 2.0 - 0.5
            val py = -(devY / camZ) * focal + h / 2.0 - 0.5
            if (px < 0.0 || py < 0.0 || px > w - 1.0 || py > h - 1.0) return Float.NaN
            val x0 = floor(px).toInt(); val y0 = floor(py).toInt()
            val x1 = min(x0 + 1, w - 1); val y1 = min(y0 + 1, h - 1)
            val tx = (px - x0).toFloat(); val ty = (py - y0).toFloat()
            val a = hp[y0 * w + x0] * (1 - tx) + hp[y0 * w + x1] * tx
            val b = hp[y1 * w + x0] * (1 - tx) + hp[y1 * w + x1] * tx
            return a * (1 - ty) + b * ty
        }
    }

    /** Photo luma minus its local mean (box blur): removes exposure/vignetting so NCC sees texture only. */
    private fun highPass(f: GrayFrame, radius: Int = 4): FloatArray {
        val w = f.width; val h = f.height
        val integral = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0.0
            for (x in 0 until w) {
                rowSum += f.luma[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - radius); val x1 = min(w, x + radius + 1)
            val y0 = max(0, y - radius); val y1 = min(h, y + radius + 1)
            val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] -
                integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
            out[y * w + x] = f.luma[y * w + x] - (sum / ((x1 - x0) * (y1 - y0))).toFloat()
        }
        return out
    }

    private class Grid(val step: Double, val nAz: Int, val nEl: Int, val v: FloatArray)

    private fun project(s: Sampler, step: Double): Grid {
        val nAz = (360.0 / step).roundToInt()
        val nEl = (2 * MAX_ELEVATION_DEG / step).toInt() + 1
        val v = FloatArray(nAz * nEl)
        for (m in 0 until nEl) {
            val el = -MAX_ELEVATION_DEG + m * step
            for (k in 0 until nAz) v[m * nAz + k] = s.sample(-180.0 + k * step, el)
        }
        return Grid(step, nAz, nEl, v)
    }

    // ── Normalised cross-correlation ───────────────────────────────────────────

    private class Accum {
        var n = 0; var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        fun reset() { n = 0; sa = 0.0; sb = 0.0; saa = 0.0; sbb = 0.0; sab = 0.0 }
        fun add(a: Float, b: Float) {
            n++; sa += a; sb += b; saa += a.toDouble() * a; sbb += b.toDouble() * b; sab += a.toDouble() * b
        }
        fun ncc(): Double {
            if (n < 2) return 0.0
            val cov = n * sab - sa * sb
            val va = n * saa - sa * sa
            val vb = n * sbb - sb * sb
            if (va <= 1e-9 || vb <= 1e-9) return 0.0
            return cov / sqrt(va * vb)
        }
    }

    private fun validCount(g: Grid) = g.v.count { !it.isNaN() }

    /** Coarse search over every circular shift of photo i relative to j. Returns (shiftDeg, ncc, cells) or null. */
    private fun coarseMatch(gi: Grid, gj: Grid): Triple<Double, Double, Int>? {
        val minCells = max(MIN_OVERLAP_CELLS, (MIN_OVERLAP_FRACTION * min(validCount(gi), validCount(gj))).toInt())
        var bestShift = 0; var bestNcc = -2.0; var bestCells = 0

        // Only photo j's visible cells matter (a portrait photo covers a small part of the
        // sphere), so walk a compact list instead of the whole azimuth/elevation grid.
        val nAz = gj.nAz
        val cnt = validCount(gj)
        val rowBase = IntArray(cnt); val col = IntArray(cnt); val bv = FloatArray(cnt)
        var c = 0
        for (m in 0 until gj.nEl) for (k in 0 until nAz) {
            val b = gj.v[m * nAz + k]
            if (b.isNaN()) continue
            rowBase[c] = m * nAz; col[c] = k; bv[c] = b; c++
        }

        val acc = Accum()
        for (shift in 0 until nAz) {
            acc.reset()
            for (t in 0 until cnt) {
                var kk = col[t] + shift; if (kk >= nAz) kk -= nAz
                val a = gi.v[rowBase[t] + kk]
                if (a.isNaN()) continue
                acc.add(a, bv[t])
            }
            if (acc.n < minCells) continue
            val ncc = acc.ncc()
            if (ncc > bestNcc) { bestNcc = ncc; bestShift = shift; bestCells = acc.n }
        }
        if (bestNcc < MIN_COARSE_NCC) return null
        val deg = wrap180(bestShift * gj.step)
        return Triple(deg, bestNcc, bestCells)
    }

    private class Cell(val az: Double, val el: Double, val value: Float)

    private fun cellsOf(sampler: Sampler): List<Cell> {
        val out = ArrayList<Cell>()
        var el = -MAX_ELEVATION_DEG
        while (el <= MAX_ELEVATION_DEG) {
            var az = -180.0
            while (az < 180.0) {
                val v = sampler.sample(az, el)
                if (!v.isNaN()) out.add(Cell(az, el, v))
                az += FINE_STEP_DEG
            }
            el += FINE_STEP_DEG
        }
        return out
    }

    /** Refines Δ around [coarseDeg] by sampling photo i directly at sub-degree shifts. */
    private fun refine(si: Sampler, jCells: List<Cell>, coarseDeg: Double): Triple<Double, Double, Int> {
        var bestD = coarseDeg; var bestNcc = -2.0; var bestN = 0
        var d = coarseDeg - REFINE_HALF_RANGE_DEG
        while (d <= coarseDeg + REFINE_HALF_RANGE_DEG + 1e-9) {
            val acc = Accum()
            for (c in jCells) {
                val a = si.sample(c.az + d, c.el)
                if (!a.isNaN()) acc.add(a, c.value)
            }
            val ncc = if (acc.n >= MIN_FINE_CELLS) acc.ncc() else -2.0
            if (ncc > bestNcc) { bestNcc = ncc; bestD = d; bestN = acc.n }
            d += REFINE_STEP_DEG
        }
        return Triple(bestD, bestNcc, bestN)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Registers every pair of [frames] (all captured with the same camera, whose
     * field of view along the sensor's long side is [longSideFovDeg]) and solves
     * for each frame's heading relative to frame 0.
     */
    fun estimateHeadings(frames: List<GrayFrame>, longSideFovDeg: Double): Result {
        val n = frames.size
        if (n == 0) return Result(DoubleArray(0), emptyList(), emptyList())

        val samplers = frames.map { Sampler(it, highPass(it), longSideFovDeg) }
        val coarse = samplers.map { project(it, COARSE_STEP_DEG) }

        val pairs = ArrayList<PairMatch>()
        val cellCache = HashMap<Int, List<Cell>>()
        for (i in 0 until n) for (j in i + 1 until n) {
            val c = coarseMatch(coarse[i], coarse[j]) ?: continue
            val jCells = cellCache.getOrPut(j) { cellsOf(samplers[j]) }
            val (delta, ncc, cells) = refine(samplers[i], jCells, c.first)
            if (ncc >= MIN_NCC) pairs.add(PairMatch(i, j, wrap180(delta), ncc, cells))
        }
        return solve(n, pairs)
    }

    /** Solves headings from pair measurements (exposed for testing the graph logic on its own). */
    fun solve(n: Int, pairs: List<PairMatch>): Result {
        val heading = DoubleArray(n) { Double.NaN }

        // Anchor on the largest group of mutually-linked photos (ties: the group holding photo 0),
        // so one stray first shot cannot make every other photo look "unlinked".
        val group = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (group[r] != r) r = group[r]; return r }
        for (p in pairs) { val a = find(p.i); val b = find(p.j); if (a != b) group[maxOf(a, b)] = minOf(a, b) }
        val sizes = HashMap<Int, Int>()
        for (k in 0 until n) sizes.merge(find(k), 1, Int::plus)
        val bestGroup = sizes.entries.maxWithOrNull(compareBy<Map.Entry<Int, Int>>({ it.value }, { it.key == find(0) }))?.key ?: find(0)
        val root = (0 until n).first { find(it) == bestGroup }
        heading[root] = 0.0

        // Initial headings: maximum-weight spanning tree grown from the root (Prim).
        val inTree = BooleanArray(n); inTree[root] = true
        while (true) {
            var best: PairMatch? = null; var bestFrom = -1
            for (p in pairs) {
                val a = inTree[p.i]; val b = inTree[p.j]
                if (a == b) continue
                if (best == null || p.ncc > best.ncc) { best = p; bestFrom = if (a) p.i else p.j }
            }
            val e = best ?: break
            if (bestFrom == e.i) { heading[e.j] = heading[e.i] + e.deltaDeg; inTree[e.j] = true }
            else { heading[e.i] = heading[e.j] - e.deltaDeg; inTree[e.i] = true }
        }
        val unreachable = (0 until n).filter { !inTree[it] }

        // Refine with every edge: unwrap each measurement to the 360° branch nearest the current
        // estimate, then weighted least squares (this is what closes a 360° loop).
        val nodes = (0 until n).filter { inTree[it] }
        if (nodes.size > 1) {
            val idx = HashMap<Int, Int>().also { m -> nodes.filter { it != root }.forEachIndexed { k, v -> m[v] = k } }
            val m = idx.size

            fun branch(p: PairMatch) =
                p.deltaDeg + 360.0 * ((heading[p.j] - heading[p.i] - p.deltaDeg) / 360.0).roundToInt()

            fun cauchy(residualDeg: Double) =
                1.0 / (1.0 + (residualDeg / ROBUST_SCALE_DEG) * (residualDeg / ROBUST_SCALE_DEG))

            // Start from the spanning-tree headings: a measurement that already disagrees with
            // them by many degrees is a false match and must not get a vote in the first solve
            // (otherwise it drags the whole chain before it can be down-weighted).
            val robust = DoubleArray(pairs.size) { e ->
                val p = pairs[e]
                if (!inTree[p.i] || !inTree[p.j]) 1.0 else cauchy(heading[p.j] - heading[p.i] - branch(p))
            }

            repeat(8) {
                val a = Array(m) { DoubleArray(m) }
                val b = DoubleArray(m)
                val chosen = DoubleArray(pairs.size)
                for ((e, p) in pairs.withIndex()) {
                    if (!inTree[p.i] || !inTree[p.j]) continue
                    val d = branch(p)
                    chosen[e] = d
                    val w = p.ncc * p.ncc * robust[e]
                    val ki = idx[p.i]; val kj = idx[p.j]
                    if (kj != null) { a[kj][kj] += w; b[kj] += w * d }
                    if (ki != null) { a[ki][ki] += w; b[ki] -= w * d }
                    if (ki != null && kj != null) { a[ki][kj] -= w; a[kj][ki] -= w }
                }
                val x = GainCompensation.solveLinear(a, b)
                for ((node, k) in idx) heading[node] = x[k]
                // Robust re-weighting (Cauchy): a measurement that disagrees with the rest by many
                // degrees is a false match, not evidence, so its weight collapses toward 0.
                for ((e, p) in pairs.withIndex()) {
                    if (!inTree[p.i] || !inTree[p.j]) continue
                    robust[e] = cauchy(heading[p.j] - heading[p.i] - chosen[e])
                }
            }
        }
        // Report relative to photo 0 whenever it is part of the solved group.
        if (!heading[0].isNaN() && heading[0] != 0.0) {
            val h0 = heading[0]
            for (k in 0 until n) if (!heading[k].isNaN()) heading[k] -= h0
        }
        return Result(heading, unreachable, pairs)
    }

    private fun wrap180(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Full-frame rotation matrices for [frames] given their [headingsDeg]. */
    fun poses(frames: List<GrayFrame>, headingsDeg: DoubleArray): List<FloatArray> =
        frames.mapIndexed { i, f -> PoseMath.rotationFromGravity(f.upDev, headingsDeg[i]) }

    /** Largest absolute difference between two headings on the circle, in degrees. */
    fun angularError(a: Double, b: Double): Double = abs(wrap180(a - b))
}
