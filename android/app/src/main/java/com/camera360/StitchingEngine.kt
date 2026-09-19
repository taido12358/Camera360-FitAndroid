package com.camera360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
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
        val tanHalfVFov: Double
    )

    /** Device axes assumed for the back camera: lens points out the -Z (screen) axis. */
    private fun cameraBasisFromRotationMatrix(r: FloatArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        // R maps device-frame vectors to world-frame: world = R * device.
        // Right_world  = R * (1,0,0) = column 0 of R
        // Up_world     = R * (0,1,0) = column 1 of R
        // Fwd_world    = R * (0,0,-1) = -column 2 of R  (back lens points opposite the screen normal)
        //
        // The sensor's world frame is ENU (x=East, y=North, z=Up), but the
        // projection loop below builds its world directions as
        // (x=East, y=Up, z=North) — so swap components 1 and 2 of every basis
        // vector, otherwise the panorama's vertical axis would map to North
        // instead of Up.
        val right = doubleArrayOf(r[0].toDouble(), r[6].toDouble(), r[3].toDouble())
        val up = doubleArrayOf(r[1].toDouble(), r[7].toDouble(), r[4].toDouble())
        val fwd = doubleArrayOf(-r[2].toDouble(), -r[8].toDouble(), -r[5].toDouble())
        return Triple(right, up, fwd)
    }

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
                val bmp = applyExifRotation(decoded, exifRotationDegrees(input.file))

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
        val outBmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(outPixels, 0, OUT_W, 0, 0, OUT_W, OUT_H)
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().buffered().use { stream ->
            outBmp.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        outBmp.recycle()
        onProgress(1f)
    }

    /** Renders one output row into [out] (row [oy] of the equirectangular image). */
    private fun renderRow(
        oy: Int,
        frames: List<FrameData>,
        sinAz: DoubleArray,
        cosAz: DoubleArray,
        out: IntArray
    ) {
        // Equirectangular: top = +90° (zenith), bottom = -90° (nadir)
        val worldPitch = (0.5 - (oy + 0.5) / OUT_H) * PI
        val cosPitch = cos(worldPitch)
        val wy = sin(worldPitch)

        for (ox in 0 until OUT_W) {
            // Unit direction vector in world space (x=East, y=Up, z=North)
            val wx = cosPitch * sinAz[ox]
            val wz = cosPitch * cosAz[ox]

            var rAcc = 0.0; var gAcc = 0.0; var bAcc = 0.0; var wAcc = 0.0

            for (frame in frames) {
                // Project the world direction into this frame's camera space
                // using its real orientation (right/up/fwd), not a
                // reconstructed-from-azimuth/pitch approximation.
                val camZ = wx * frame.fwd[0] + wy * frame.fwd[1] + wz * frame.fwd[2]
                if (camZ <= 0.001) continue   // behind this camera

                val normX = (wx * frame.right[0] + wy * frame.right[1] + wz * frame.right[2]) / camZ
                val normY = (wx * frame.up[0] + wy * frame.up[1] + wz * frame.up[2]) / camZ

                val thH = frame.tanHalfHFov
                val thV = frame.tanHalfVFov
                if (abs(normX) >= thH || abs(normY) >= thV) continue  // outside FOV

                // Continuous frame pixel coordinates (top-left pixel centre = (0.5,0.5))
                val fx = (normX / thH + 1.0) * 0.5 * frame.width - 0.5
                val fy = (1.0 - (normY / thV + 1.0) * 0.5) * frame.height - 0.5

                // Bilinear sample — avoids the blocky/aliased look of nearest-pixel.
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
                val r = Color.red(c00) * w00 + Color.red(c10) * w10 + Color.red(c01) * w01 + Color.red(c11) * w11
                val g = Color.green(c00) * w00 + Color.green(c10) * w10 + Color.green(c01) * w01 + Color.green(c11) * w11
                val b = Color.blue(c00) * w00 + Color.blue(c10) * w10 + Color.blue(c01) * w01 + Color.blue(c11) * w11

                // Cosine weight: full at frame center, zero at edges — smooth blending
                val weight = (1.0 - abs(normX) / thH) * (1.0 - abs(normY) / thV)

                rAcc += r * weight
                gAcc += g * weight
                bAcc += b * weight
                wAcc += weight
            }

            out[oy * OUT_W + ox] = if (wAcc > 0.0) {
                Color.rgb(
                    (rAcc / wAcc).toInt().coerceIn(0, 255),
                    (gAcc / wAcc).toInt().coerceIn(0, 255),
                    (bAcc / wAcc).toInt().coerceIn(0, 255)
                )
            } else Color.BLACK
        }
    }

    /** EXIF rotation (clockwise degrees needed to display upright), 0 if unknown. */
    private fun exifRotationDegrees(file: File): Int = try {
        when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) { 0 }

    /** Returns [bmp] rotated clockwise by [degrees]; recycles [bmp] if a copy was made. */
    private fun applyExifRotation(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }
}
