package com.camera360

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pure (no Android dependency) equirectangular renderer: given photos with known
 * camera orientation, paints every output direction from the photos that see it
 * (inverse projection, bilinear sampling, cosine-weighted blend, optional
 * exposure/white-balance gain compensation). [StitchingEngine] handles file/bitmap
 * I/O around it; keeping the maths here lets JVM tests render synthetic scenes with
 * known ground truth and measure the result.
 */
object EquirectRenderer {

    /** Marks an output pixel no photo covered (alpha 0, distinct from every rendered colour). */
    const val UNCOVERED = 0

    /** Min. blend weight (0 = frame edge, 1 = centre) for a sample to count in gain estimation. */
    private const val GAIN_MIN_WEIGHT = 0.25

    /**
     * One photo: ARGB [pixels] (upright), its [rotationMatrix] (row-major device→world, ENU) and the
     * field of view along its long side. Per-axis half-FOV tangents come from the focal length in
     * pixels, so any aspect ratio / orientation works.
     */
    class Frame(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        rotationMatrix: FloatArray,
        longSideFovDeg: Double
    ) {
        // World-space camera basis in the stitcher frame (E, Up, N): orthonormal, so camera-space
        // coordinates are (dot(w,right), dot(w,up), dot(w,fwd)).
        val right: DoubleArray
        val up: DoubleArray
        val fwd: DoubleArray
        val tanHalfHFov: Double
        val tanHalfVFov: Double

        /** Per-channel exposure/white-balance gain; 1.0 until [estimateGains] fills it in. */
        val gain = doubleArrayOf(1.0, 1.0, 1.0)

        init {
            val (r, u, f) = PoseMath.stitchBasis(rotationMatrix)
            right = r; up = u; fwd = f
            val focalPx = (maxOf(width, height) / 2.0) / tan(Math.toRadians(longSideFovDeg) / 2.0)
            tanHalfHFov = (width / 2.0) / focalPx
            tanHalfVFov = (height / 2.0) / focalPx
        }
    }

    /**
     * Renders [frames] to an [outW] x [outH] equirectangular image (azimuth 0..360 left to right,
     * elevation +90 at the top). Pixels no photo sees are [UNCOVERED]; all others are opaque ARGB.
     * [blendPower] sharpens (>1) or softens (<1) the seam blend; [onProgress] gets 0..1 from worker threads.
     */
    fun render(
        frames: List<Frame>,
        outW: Int,
        outH: Int,
        compensateExposure: Boolean = true,
        blendPower: Double = 1.0,
        onProgress: ((Float) -> Unit)? = null
    ): IntArray {
        if (compensateExposure && frames.size > 1) {
            val gains = estimateGains(frames)
            frames.forEachIndexed { i, fr -> for (c in 0 until 3) fr.gain[c] = gains[i][c] }
        } else {
            frames.forEach { fr -> fr.gain.fill(1.0) }
        }

        val out = IntArray(outW * outH)
        // sin/cos of each output column's azimuth are the same for every row.
        val sinAz = DoubleArray(outW) { sin(it.toDouble() / outW * 2.0 * PI) }
        val cosAz = DoubleArray(outW) { cos(it.toDouble() / outW * 2.0 * PI) }

        val rowsDone = AtomicInteger(0)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        // Rows are independent: render interleaved row sets on parallel threads.
        val threads = (0 until workers).map { w ->
            Thread {
                var oy = w
                while (oy < outH) {
                    renderRow(oy, outW, outH, frames, sinAz, cosAz, out, blendPower)
                    val done = rowsDone.incrementAndGet()
                    if (onProgress != null && done % 48 == 0) onProgress(done.toFloat() / outH)
                    oy += workers
                }
            }.also { it.start() }
        }
        threads.forEach { it.join() }
        return out
    }

    /**
     * Samples [frame] in world direction ([wx],[wy],[wz]) (stitcher frame: East, Up, North). Returns the
     * blend weight (0 if the frame does not see that direction) and writes the bilinearly interpolated
     * RGB (0..255) into [rgb].
     */
    private fun sampleFrame(frame: Frame, wx: Double, wy: Double, wz: Double, rgb: DoubleArray): Double {
        val camZ = wx * frame.fwd[0] + wy * frame.fwd[1] + wz * frame.fwd[2]
        if (camZ <= 0.001) return 0.0   // behind this camera

        val normX = (wx * frame.right[0] + wy * frame.right[1] + wz * frame.right[2]) / camZ
        val normY = (wx * frame.up[0] + wy * frame.up[1] + wz * frame.up[2]) / camZ

        val thH = frame.tanHalfHFov
        val thV = frame.tanHalfVFov
        if (abs(normX) >= thH || abs(normY) >= thV) return 0.0  // outside FOV

        // Continuous frame pixel coordinates (top-left pixel centre = (0.5,0.5))
        val fx = (normX / thH + 1.0) * 0.5 * frame.width - 0.5
        val fy = (1.0 - (normY / thV + 1.0) * 0.5) * frame.height - 0.5

        val x0 = floor(fx).toInt().coerceIn(0, frame.width - 1)
        val y0 = floor(fy).toInt().coerceIn(0, frame.height - 1)
        val x1 = (x0 + 1).coerceAtMost(frame.width - 1)
        val y1 = (y0 + 1).coerceAtMost(frame.height - 1)
        val tx = (fx - x0).coerceIn(0.0, 1.0)
        val ty = (fy - y0).coerceIn(0.0, 1.0)
        val p = frame.pixels
        val c00 = p[y0 * frame.width + x0]; val c10 = p[y0 * frame.width + x1]
        val c01 = p[y1 * frame.width + x0]; val c11 = p[y1 * frame.width + x1]
        val w00 = (1 - tx) * (1 - ty); val w10 = tx * (1 - ty)
        val w01 = (1 - tx) * ty;       val w11 = tx * ty
        rgb[0] = red(c00) * w00 + red(c10) * w10 + red(c01) * w01 + red(c11) * w11
        rgb[1] = green(c00) * w00 + green(c10) * w10 + green(c01) * w01 + green(c11) * w11
        rgb[2] = blue(c00) * w00 + blue(c10) * w10 + blue(c01) * w01 + blue(c11) * w11

        // Cosine-style weight: full at frame centre, zero at the edges, for a smooth blend
        return (1.0 - abs(normX) / thH) * (1.0 - abs(normY) / thV)
    }

    /**
     * Estimates a per-frame, per-channel exposure/white-balance gain (see [GainCompensation]) by sampling
     * the sphere on a coarse grid and comparing every pair of frames that see the same direction. Only
     * samples well inside both frames (blend weight >= [GAIN_MIN_WEIGHT]) are used, so lens vignetting
     * at frame edges does not bias the estimate.
     */
    fun estimateGains(frames: List<Frame>): Array<DoubleArray> {
        val n = frames.size
        val gridW = 360
        val gridH = 180
        val count = Array(n) { DoubleArray(n) }
        // sum[c][i][j]: frame i's channel-c value summed over the samples it shares with frame j
        val sum = Array(3) { Array(n) { DoubleArray(n) } }

        val rgb = Array(n) { DoubleArray(3) }
        val hit = IntArray(n)
        for (gy in 0 until gridH) {
            val pitch = (0.5 - (gy + 0.5) / gridH) * PI
            val cp = cos(pitch)
            val wy = sin(pitch)
            for (gx in 0 until gridW) {
                val az = (gx + 0.5) / gridW * 2.0 * PI
                val wx = cp * sin(az)
                val wz = cp * cos(az)
                var k = 0
                for (i in 0 until n) {
                    if (sampleFrame(frames[i], wx, wy, wz, rgb[k]) >= GAIN_MIN_WEIGHT) {
                        hit[k] = i
                        k++
                    }
                }
                for (p in 0 until k) for (q in 0 until k) {
                    if (p == q) continue
                    val i = hit[p]
                    val j = hit[q]
                    count[i][j] += 1.0
                    for (c in 0 until 3) sum[c][i][j] += rgb[p][c]
                }
            }
        }

        val gains = Array(n) { DoubleArray(3) { 1.0 } }
        for (c in 0 until 3) {
            val mean = Array(n) { i ->
                DoubleArray(n) { j -> if (count[i][j] > 0.0) sum[c][i][j] / count[i][j] else 0.0 }
            }
            val g = GainCompensation.solve(count, mean)
            for (i in 0 until n) gains[i][c] = g[i]
        }
        return gains
    }

    /** Renders one output row into [out] (row [oy] of the [outW] x [outH] equirectangular image). */
    private fun renderRow(
        oy: Int,
        outW: Int,
        outH: Int,
        frames: List<Frame>,
        sinAz: DoubleArray,
        cosAz: DoubleArray,
        out: IntArray,
        blendPower: Double
    ) {
        // Equirectangular: top = +90 deg (zenith), bottom = -90 deg (nadir)
        val worldPitch = (0.5 - (oy + 0.5) / outH) * PI
        val cosPitch = cos(worldPitch)
        val wy = sin(worldPitch)
        val rgb = DoubleArray(3)

        for (ox in 0 until outW) {
            // Unit direction vector in world space (x=East, y=Up, z=North)
            val wx = cosPitch * sinAz[ox]
            val wz = cosPitch * cosAz[ox]

            var rAcc = 0.0; var gAcc = 0.0; var bAcc = 0.0; var wAcc = 0.0

            for (frame in frames) {
                var weight = sampleFrame(frame, wx, wy, wz, rgb)
                if (weight <= 0.0) continue
                if (blendPower != 1.0) weight = Math.pow(weight, blendPower)
                rAcc += rgb[0] * frame.gain[0] * weight
                gAcc += rgb[1] * frame.gain[1] * weight
                bAcc += rgb[2] * frame.gain[2] * weight
                wAcc += weight
            }

            out[oy * outW + ox] = if (wAcc > 0.0) {
                argb(
                    (rAcc / wAcc).toInt().coerceIn(0, 255),
                    (gAcc / wAcc).toInt().coerceIn(0, 255),
                    (bAcc / wAcc).toInt().coerceIn(0, 255)
                )
            } else UNCOVERED
        }
    }

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
