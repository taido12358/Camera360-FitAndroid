package com.camera360

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File

/**
 * Shared JPEG loading for the stitchers. CameraX stores sensor-oriented pixels
 * plus an EXIF rotation tag, and [BitmapFactory] ignores that tag, so every
 * decode of a captured frame must go through [loadUpright].
 */
object ImageIo {

    /** EXIF rotation (clockwise degrees needed to display upright), 0 if unknown. */
    fun exifRotationDegrees(file: File): Int = try {
        when (ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) { 0 }

    /** Returns [bmp] rotated clockwise by [degrees]; recycles [bmp] if a copy was made. */
    fun applyExifRotation(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    /**
     * Decodes [file] downsampled so its long side is at most ~[maxLongSide]
     * pixels, rotated upright per EXIF. Null if the file cannot be decoded.
     */
    fun loadUpright(file: File, maxLongSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longSide = maxOf(bounds.outWidth, bounds.outHeight)
        val sample = (longSide / maxLongSide).coerceAtLeast(1)
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        val upright = applyExifRotation(decoded, exifRotationDegrees(file))
        // inSampleSize only takes powers of two (1280 px with a 960 px target stays 1280), so finish exactly.
        val longest = maxOf(upright.width, upright.height)
        if (longest <= maxLongSide * 1.1) return upright
        val scale = maxLongSide.toDouble() / longest
        val scaled = Bitmap.createScaledBitmap(
            upright, maxOf(1, (upright.width * scale).toInt()), maxOf(1, (upright.height * scale).toInt()), true
        )
        if (scaled !== upright) upright.recycle()
        return scaled
    }
}
