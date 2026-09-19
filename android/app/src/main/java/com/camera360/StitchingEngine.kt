package com.camera360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.*

/**
 * Equirectangular panorama stitcher using known camera poses (full 3D device
 * orientation, not azimuth/pitch alone — see [FrameInput.rotationMatrix]).
 *
 * Algorithm: inverse projection — for every output pixel (worldAz, worldPitch),
 * find which captured frames cover that direction, sample each one with cosine
 * weight, and blend. No feature matching needed because we know exact poses.
 */
object StitchingEngine {

    /**
     * Fallback horizontal FOV (degrees) used only when the actual camera's FOV
     * could not be measured (see [android.hardware.camera2.CameraCharacteristics]
     * in CaptureScreen). Prefer passing a measured value to [stitch].
     */
    const val CAMERA_HFOV_DEG = 65.0

    /** Output equirectangular size (2:1 ratio — standard for 360° photos). */
    private const val OUT_W = 3840
    private const val OUT_H = 1920

    /** Max long-side for loaded frames to keep peak memory reasonable. */
    private const val MAX_FRAME_LONG_SIDE = 1024

    /** Marks an output pixel no photo covered (fully transparent, distinct from any rendered colour). */
    private const val UNCOVERED = 0

    /** Min. blend weight (0 = frame edge, 1 = centre) for a sample to count in gain estimation. */
    private const val GAIN_MIN_WEIGHT = 0.25

    /**
     * One captured frame plus the device's exact orientation at capture time.
     *
     * [rotationMatrix] is the 9-float, row-major device→world rotation matrix
     * (as produced by [android.hardware.SensorManager.getRotationMatrixFromVector])
     * captured at the moment of the shutter press. Using the full matrix — not
     * just azimuth/pitch — is what lets stitching stay accurate even if the
     * phone was slightly rolled/tilted between shots.
     */
    data class FrameInput(
        val file: File,
        val rotationMatrix: FloatArray
    )

    private data class FrameData(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        // World-space camera basis vectors derived from the frame's rotation
        // matrix — orthonormal, so camera-space = (dot(w,right), dot(w,up), dot(w,fwd)).
        val right: DoubleArray,
        val up: DoubleArray,
        val fwd: DoubleArray,
        val tanHalfHFov: Double,
        val tanHalfVFov: Double,
        // Per-channel exposure/white-balance gain (see estimateGains); filled in
        // after all frames are loaded, 1.0 until then.
        val gain: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)
    )

    /** Camera basis in the stitcher world frame (E, Up, N) — see [PoseMath.stitchBasis]. */
    private fun cameraBasisFromRotationMatrix(r: FloatArray) = PoseMath.stitchBasis(r)

    /**
     * Stitch [inputs] into an equirectangular JPEG at [outputFile], using
     * [hFovDeg] as the camera's field of view along the sensor's LONG side
     * (the "horizontal" FOV of a landscape frame) — pass the value measured
     * from [android.hardware.camera2.CameraCharacteristics] for best accuracy;
     * only fall back to [CAMERA_HFOV_DEG] if measurement failed. The FOV across
     * the short side is derived from the frame's pixel aspect ratio, so this
     * works for whatever orientation the frame ends up in after EXIF rotation.
     * [onProgress] is called with 0..1 on the IO thread — safe to update StateFlow.
     */
    suspend fun stitch(
        inputs: List<FrameInput>,
        outputFile: File,
        hFovDeg: Double = CAMERA_HFOV_DEG,
        cropToContent: Boolean = false,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        onProgress(0f)

        val tanHalfLong = tan(Math.toRadians(hFovDeg) / 2.0)

        // ── Load frames at reduced resolution ──────────────────────────────
        val frames = inputs.mapNotNull { input ->
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(input.file.absolutePath, opts)
                val longSide = maxOf(opts.outWidth, opts.outHeight)
                val sampleSize = (longSide / MAX_FRAME_LONG_SIDE).coerceAtLeast(1)

                val loadOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded = BitmapFactory.decodeFile(input.file.absolutePath, loadOpts)
                    ?: return@mapNotNull null
                // CameraX stores the sensor-oriented pixels plus an EXIF rotation tag;
                // BitmapFactory ignores the tag, so apply it here — the stitching basis
                // (right = device +X, up = device +Y) assumes an upright portrait frame.
                val bmp = ImageIo.applyExifRotation(decoded, ImageIo.exifRotationDegrees(input.file))

                val sw = bmp.width; val sh = bmp.height
                if (sw <= 0 || sh <= 0) { bmp.recycle(); return@mapNotNull null }
                val pixels = IntArray(sw * sh)
                bmp.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                bmp.recycle()

                val (right, up, fwd) = cameraBasisFromRotationMatrix(input.rotationMatrix)
                // Focal length in pixels from the long-side FOV, then the true
                // (rectilinear) half-FOV tangents for each axis.
                val focalPx = (maxOf(sw, sh) / 2.0) / tanHalfLong
                FrameData(
                    pixels = pixels, width = sw, height = sh,
                    right = right, up = up, fwd = fwd,
                    tanHalfHFov = (sw / 2.0) / focalPx,
                    tanHalfVFov = (sh / 2.0) / focalPx
                )
            } catch (e: Exception) { null }
        }

        if (frames.isEmpty()) throw IllegalStateException("No frames could be loaded for stitching")

        // Even out per-shot auto-exposure / white-balance differences before blending.
        if (frames.size > 1) {
            val gains = estimateGains(frames)
            frames.forEachIndexed { i, fr -> for (c in 0 until 3) fr.gain[c] = gains[i][c] }
        }
        onProgress(0.05f)

        // ── Inverse equirectangular projection ─────────────────────────────
        val outPixels = IntArray(OUT_W * OUT_H)

        // sin/cos of each output column's azimuth are the same for every row.
        val sinAz = DoubleArray(OUT_W) { sin(it.toDouble() / OUT_W * 2.0 * PI) }  // 0..2π
        val cosAz = DoubleArray(OUT_W) { cos(it.toDouble() / OUT_W * 2.0 * PI) }

        val rowsDone = AtomicInteger(0)
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

        // Rows are independent, so render interleaved row sets in parallel.
        coroutineScope {
            (0 until workers).map { w ->
                async(Dispatchers.Default) {
                    var oy = w
                    while (oy < OUT_H) {
                        renderRow(oy, frames, sinAz, cosAz, outPixels)
                        val done = rowsDone.incrementAndGet()
                        if (done % 48 == 0) onProgress(0.05f + 0.90f * done.toFloat() / OUT_H)
                        oy += workers
                    }
                }
            }.awaitAll()
        }

        // ── Write output JPEG ───────────────────────────────────────────────
        onProgress(0.95f)

        // Rendered pixels always have alpha 0xFF; UNCOVERED (0) marks directions no photo saw.
        // Optionally crop to what was actually covered (a partial sweep would otherwise be a small
        // picture in a mostly black 2:1 frame); the horizontal axis is azimuth, so the crop may wrap.
        var outW = OUT_W
        var outH = OUT_H
        var pixels = outPixels
        if (cropToContent) {
            val covered = BooleanArray(OUT_W * OUT_H) { outPixels[it] != UNCOVERED }
            val b = CoverageCrop.bounds(covered, OUT_W, OUT_H)
            if (b != null && (b.width < OUT_W || b.height < OUT_H)) {
                outW = b.width
                outH = b.height
                pixels = IntArray(outW * outH) { i ->
                    val x = (b.x0 + i % outW) % OUT_W
                    val y = b.y0 + i / outW
                    outPixels[y * OUT_W + x]
                }
            }
        }
        for (i in pixels.indices) if (pixels[i] == UNCOVERED) pixels[i] = Color.BLACK

        val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().buffered().use { stream ->
            outBmp.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        outBmp.recycle()
        onProgress(1f)
    }

    /**
     * Samples [frame] in world direction ([wx],[wy],[wz]) (stitcher frame:
     * East, Up, North). Returns the blend weight (0 if the frame does not see
     * that direction) and writes the bilinearly interpolated RGB (0..255)
     * into [rgb].
     */
    private fun sampleFrame(frame: FrameData, wx: Double, wy: Double, wz: Double, rgb: DoubleArray): Double {
        // Project the world direction into this frame's camera space using its
        // real orientation (right/up/fwd).
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

        // Bilinear sample: avoids the blocky/aliased look of nearest-pixel.
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
        rgb[0] = Color.red(c00) * w00 + Color.red(c10) * w10 + Color.red(c01) * w01 + Color.red(c11) * w11
        rgb[1] = Color.green(c00) * w00 + Color.green(c10) * w10 + Color.green(c01) * w01 + Color.green(c11) * w11
        rgb[2] = Color.blue(c00) * w00 + Color.blue(c10) * w10 + Color.blue(c01) * w01 + Color.blue(c11) * w11

        // Cosine weight: full at frame center, zero at edges, for smooth blending
        return (1.0 - abs(normX) / thH) * (1.0 - abs(normY) / thV)
    }

    /**
     * Estimates a per-frame, per-channel exposure/white-balance gain (see
     * [GainCompensation]) by sampling the sphere on a coarse grid and
     * comparing every pair of frames that see the same direction. Only samples
     * well inside both frames (blend weight >= [GAIN_MIN_WEIGHT]) are used, so
     * lens vignetting at frame edges does not bias the estimate.
     */
    private fun estimateGains(frames: List<FrameData>): Array<DoubleArray> {
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

    /** Renders one output row into [out] (row [oy] of the equirectangular image). */
    private fun renderRow(
        oy: Int,
        frames: List<FrameData>,
        sinAz: DoubleArray,
        cosAz: DoubleArray,
        out: IntArray
    ) {
        // Equirectangular: top = +90 deg (zenith), bottom = -90 deg (nadir)
        val worldPitch = (0.5 - (oy + 0.5) / OUT_H) * PI
        val cosPitch = cos(worldPitch)
        val wy = sin(worldPitch)
        val rgb = DoubleArray(3)

        for (ox in 0 until OUT_W) {
            // Unit direction vector in world space (x=East, y=Up, z=North)
            val wx = cosPitch * sinAz[ox]
            val wz = cosPitch * cosAz[ox]

            var rAcc = 0.0; var gAcc = 0.0; var bAcc = 0.0; var wAcc = 0.0

            for (frame in frames) {
                val weight = sampleFrame(frame, wx, wy, wz, rgb)
                if (weight <= 0.0) continue
                rAcc += rgb[0] * frame.gain[0] * weight
                gAcc += rgb[1] * frame.gain[1] * weight
                bAcc += rgb[2] * frame.gain[2] * weight
                wAcc += weight
            }

            out[oy * OUT_W + ox] = if (wAcc > 0.0) {
                Color.rgb(
                    (rAcc / wAcc).toInt().coerceIn(0, 255),
                    (gAcc / wAcc).toInt().coerceIn(0, 255),
                    (bAcc / wAcc).toInt().coerceIn(0, 255)
                )
            } else UNCOVERED
        }
    }
}
