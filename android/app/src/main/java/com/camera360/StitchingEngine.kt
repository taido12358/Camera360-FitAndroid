package com.camera360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
        val right = doubleArrayOf(r[0].toDouble(), r[3].toDouble(), r[6].toDouble())
        val up = doubleArrayOf(r[1].toDouble(), r[4].toDouble(), r[7].toDouble())
        val fwd = doubleArrayOf(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())
        return Triple(right, up, fwd)
    }

    /**
     * Stitch [inputs] into an equirectangular JPEG at [outputFile], using
     * [hFovDeg] as the camera's horizontal field of view — pass the value
     * measured from [android.hardware.camera2.CameraCharacteristics] for best
     * accuracy; only fall back to [CAMERA_HFOV_DEG] if measurement failed.
     * [onProgress] is called with 0..1 on the IO thread — safe to update StateFlow.
     */
    suspend fun stitch(
        inputs: List<FrameInput>,
        outputFile: File,
        hFovDeg: Double = CAMERA_HFOV_DEG,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        onProgress(0f)

        val hFovRad = Math.toRadians(hFovDeg)
        val tanHalfHFov = tan(hFovRad / 2.0)

        // ── Load frames at reduced resolution ──────────────────────────────
        val frames = inputs.mapNotNull { input ->
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(input.file.absolutePath, opts)
                val longSide = maxOf(opts.outWidth, opts.outHeight)
                val sampleSize = (longSide / MAX_FRAME_LONG_SIDE).coerceAtLeast(1)

                val loadOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val bmp = BitmapFactory.decodeFile(input.file.absolutePath, loadOpts)
                    ?: return@mapNotNull null

                val sw = bmp.width; val sh = bmp.height
                val pixels = IntArray(sw * sh)
                bmp.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                bmp.recycle()

                val (right, up, fwd) = cameraBasisFromRotationMatrix(input.rotationMatrix)
                val vFovRad = hFovRad * sh / sw
                FrameData(
                    pixels = pixels, width = sw, height = sh,
                    right = right, up = up, fwd = fwd,
                    tanHalfHFov = tanHalfHFov,
                    tanHalfVFov = tan(vFovRad / 2.0)
                )
            } catch (e: Exception) { null }
        }

        if (frames.isEmpty()) throw IllegalStateException("No frames could be loaded for stitching")
        onProgress(0.05f)

        // ── Inverse equirectangular projection ─────────────────────────────
        val outPixels = IntArray(OUT_W * OUT_H) { Color.BLACK }

        for (oy in 0 until OUT_H) {
            // Equirectangular: top = +90° (zenith), bottom = -90° (nadir)
            val worldPitch = (0.5 - oy.toDouble() / OUT_H) * PI
            val cosPitch = cos(worldPitch)
            val sinPitch = sin(worldPitch)

            for (ox in 0 until OUT_W) {
                val worldAz = ox.toDouble() / OUT_W * 2.0 * PI  // 0..2π (left = west)

                // Unit direction vector in world space
                val wx = cosPitch * sin(worldAz)
                val wy = sinPitch
                val wz = cosPitch * cos(worldAz)

                var rAcc = 0.0; var gAcc = 0.0; var bAcc = 0.0; var wAcc = 0.0

                frames.forEach { frame ->
                    // Project the world direction into this frame's camera space
                    // using its real orientation (right/up/fwd), not a
                    // reconstructed-from-azimuth/pitch approximation.
                    val camX = wx * frame.right[0] + wy * frame.right[1] + wz * frame.right[2]
                    val camY = wx * frame.up[0] + wy * frame.up[1] + wz * frame.up[2]
                    val camZ = wx * frame.fwd[0] + wy * frame.fwd[1] + wz * frame.fwd[2]

                    if (camZ <= 0.001) return@forEach   // behind this camera

                    val normX = camX / camZ   // rectilinear projection
                    val normY = camY / camZ

                    val thH = frame.tanHalfHFov
                    val thV = frame.tanHalfVFov

                    if (abs(normX) >= thH || abs(normY) >= thV) return@forEach  // outside FOV

                    // Frame pixel coordinates (top-left = (0,0))
                    val pixX = ((normX / thH + 1.0) * 0.5 * frame.width)
                        .toInt().coerceIn(0, frame.width - 1)
                    val pixY = ((1.0 - (normY / thV + 1.0) * 0.5) * frame.height)
                        .toInt().coerceIn(0, frame.height - 1)

                    val color = frame.pixels[pixY * frame.width + pixX]

                    // Cosine weight: full at frame center, zero at edges — smooth blending
                    val weight = (1.0 - abs(normX) / thH) * (1.0 - abs(normY) / thV)

                    rAcc += Color.red(color)   * weight
                    gAcc += Color.green(color) * weight
                    bAcc += Color.blue(color)  * weight
                    wAcc += weight
                }

                outPixels[oy * OUT_W + ox] = if (wAcc > 0.0) {
                    Color.rgb(
                        (rAcc / wAcc).toInt().coerceIn(0, 255),
                        (gAcc / wAcc).toInt().coerceIn(0, 255),
                        (bAcc / wAcc).toInt().coerceIn(0, 255)
                    )
                } else Color.BLACK
            }

            if (oy % 48 == 0) onProgress(0.05f + 0.90f * (oy + 1).toFloat() / OUT_H)
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
}
