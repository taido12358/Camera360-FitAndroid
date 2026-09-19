package com.camera360

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Equirectangular panorama stitcher using known camera poses (full 3D device
 * orientation, not azimuth/pitch alone — see [FrameInput.rotationMatrix]).
 *
 * This object does the Android side: decoding the JPEGs (EXIF-rotated upright),
 * cropping, and encoding. The actual inverse-projection rendering — for every
 * output pixel (worldAz, worldPitch) find the frames covering that direction,
 * sample each bilinearly, gain-compensate and blend — lives in the pure
 * [EquirectRenderer], which is unit-tested against synthetic ground truth.
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
    private const val MAX_FRAME_LONG_SIDE = 960

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

    /**
     * Stitch [inputs] into an equirectangular JPEG at [outputFile], using
     * [hFovDeg] as the camera's field of view along the sensor's LONG side
     * (the "horizontal" FOV of a landscape frame) — pass the value measured
     * from [android.hardware.camera2.CameraCharacteristics] for best accuracy;
     * only fall back to [CAMERA_HFOV_DEG] if measurement failed. The FOV across
     * the short side is derived from the frame's pixel aspect ratio, so this
     * works for whatever orientation the frame ends up in after EXIF rotation.
     * [onProgress] is called with 0..1 from worker threads — safe to update StateFlow.
     */
    suspend fun stitch(
        inputs: List<FrameInput>,
        outputFile: File,
        hFovDeg: Double = CAMERA_HFOV_DEG,
        cropToContent: Boolean = false,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        onProgress(0f)

        // ── Load frames at reduced resolution ──────────────────────────────
        val frames = inputs.mapNotNull { input ->
            try {
                // Decoded, EXIF-rotated upright (CameraX stores sensor-oriented pixels plus a rotation tag, and
                // the stitching basis assumes an upright portrait frame) and scaled to a bounded size.
                val bmp = ImageIo.loadUpright(input.file, MAX_FRAME_LONG_SIDE) ?: return@mapNotNull null

                val sw = bmp.width; val sh = bmp.height
                if (sw <= 0 || sh <= 0) { bmp.recycle(); return@mapNotNull null }
                val pixels = IntArray(sw * sh)
                bmp.getPixels(pixels, 0, sw, 0, 0, sw, sh)
                bmp.recycle()

                EquirectRenderer.Frame(pixels, sw, sh, input.rotationMatrix, hFovDeg)
            } catch (e: Exception) { null }
        }

        if (frames.isEmpty()) throw IllegalStateException("No frames could be loaded for stitching")
        onProgress(0.05f)

        // ── Inverse equirectangular projection (gain-compensated blend) ─────
        val outPixels = EquirectRenderer.render(frames, OUT_W, OUT_H) { p -> onProgress(0.05f + 0.90f * p) }

        // ── Write output JPEG ───────────────────────────────────────────────
        onProgress(0.95f)

        // Rendered pixels always have alpha 0xFF; UNCOVERED (0) marks directions no photo saw.
        // Optionally crop to what was actually covered (a partial sweep would otherwise be a small
        // picture in a mostly black 2:1 frame); the horizontal axis is azimuth, so the crop may wrap.
        var outW = OUT_W
        var outH = OUT_H
        var pixels = outPixels
        if (cropToContent) {
            val covered = BooleanArray(OUT_W * OUT_H) { outPixels[it] != EquirectRenderer.UNCOVERED }
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
        for (i in pixels.indices) if (pixels[i] == EquirectRenderer.UNCOVERED) pixels[i] = Color.BLACK

        val outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        outBmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
        outputFile.parentFile?.mkdirs()
        outputFile.outputStream().buffered().use { stream ->
            outBmp.compress(Bitmap.CompressFormat.JPEG, 92, stream)
        }
        outBmp.recycle()
        onProgress(1f)
    }
}
