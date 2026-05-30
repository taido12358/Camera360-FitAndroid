package com.camera360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.*

/**
 * Equirectangular panorama stitcher using known camera poses (azimuth + pitch).
 *
 * Algorithm: inverse projection — for every output pixel (worldAz, worldPitch),
 * find which captured frames cover that direction, sample each one with cosine
 * weight, and blend. No feature matching needed because we know exact poses.
 */
object StitchingEngine {

    /** Assumed horizontal FOV of the phone camera (degrees). */
    const val CAMERA_HFOV_DEG = 65.0

    /** Output equirectangular size (2:1 ratio — standard for 360° photos). */
    private const val OUT_W = 3840
    private const val OUT_H = 1920

    /** Max long-side for loaded frames to keep peak memory reasonable. */
    private const val MAX_FRAME_LONG_SIDE = 1024

    data class FrameInput(
        val file: File,
        val azimuth: Float,   // actual azimuth at capture time
        val pitch: Float      // actual pitch at capture time
    )

    private data class FrameData(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val azimuth: Float,
        val pitch: Float,
        val tanHalfHFov: Double,
        val tanHalfVFov: Double
    )

    /**
     * Stitch [inputs] into an equirectangular JPEG at [outputFile].
     * [onProgress] is called with 0..1 on the IO thread — safe to update StateFlow.
     */
    suspend fun stitch(
        inputs: List<FrameInput>,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        onProgress(0f)

        val hFovRad = Math.toRadians(CAMERA_HFOV_DEG)
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

                val vFovRad = hFovRad * sh / sw
                FrameData(
                    pixels = pixels, width = sw, height = sh,
                    azimuth = input.azimuth, pitch = input.pitch,
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
                    val caz = Math.toRadians(frame.azimuth.toDouble())
                    val cp  = Math.toRadians(frame.pitch.toDouble())

                    // Camera basis vectors in world space
                    // Right  = (cos(caz),            0,          -sin(caz))
                    // Up     = (-sin(caz)*sin(cp),   cos(cp),    -cos(caz)*sin(cp))
                    // Fwd    = (sin(caz)*cos(cp),    sin(cp),    cos(caz)*cos(cp))

                    val camX = wx * cos(caz)                         + wz * (-sin(caz))
                    val camY = wx * (-sin(caz) * sin(cp)) + wy * cos(cp) + wz * (-cos(caz) * sin(cp))
                    val camZ = wx * sin(caz) * cos(cp)   + wy * sin(cp)  + wz * cos(caz) * cos(cp)

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
