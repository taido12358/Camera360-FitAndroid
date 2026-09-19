package com.camera360

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * Runs the real Android stitching path (JPEG decode with EXIF rotation -> renderer -> crop -> JPEG encode)
 * on the device with a realistic session size (30 photos of 4000x3000 as the Galaxy A12 writes them), and logs
 * time and memory so out-of-memory risk on low-RAM phones is measured, not guessed.
 */
@RunWith(AndroidJUnit4::class)
class StitchingDeviceTest {

    private fun writeFrame(file: File, seed: Int) {
        // 4000x3000 landscape sensor pixels + EXIF "rotate 90 cw": exactly what the Galaxy A12 delivers
        // (verified by pulling a real capture: 4000x3000, orientation 6, ~2.6 MB)
        val w = 4000; val h = 3000
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (128 + 60 * sin(x * 0.045 + seed) * sin(y * 0.06 + seed * 0.7) + 30 * sin((x + y) * 0.02)).toInt().coerceIn(0, 255)
            px[y * w + x] = Color.rgb(v, (v * 0.9).toInt(), (255 - v) / 2 + 60)
        }
        val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
    }

    @Test fun thirtyPhotoSession_stitchesWithoutRunningOutOfMemory() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "stitch_test").also { it.deleteRecursively(); it.mkdirs() }
        val shared = File(dir, "shared_12mp.jpg").also { writeFrame(it, 3) }
        val inputs = ArrayList<StitchingEngine.FrameInput>()
        for (row in 0 until 3) for (k in 0 until 10) {
            // one real-size JPEG shared by all 30 poses (writing 30 x 12 MP on the device would dominate the test)
            val f = shared
            inputs.add(StitchingEngine.FrameInput(f, PoseMath.rotationFromAzElRoll(k * 36.0 + row * 6.0, (row - 1) * 28.0, 0.0)))
        }
        val out = File(dir, "pano.jpg")

        val rt = Runtime.getRuntime()
        Log.i("StitchDevice", "maxHeap=${rt.maxMemory() / 1048576} MB, start used=${(rt.totalMemory() - rt.freeMemory()) / 1048576} MB")
        var peakUsed = 0L
        val sampler = Thread {
            try {
                while (true) {
                    peakUsed = maxOf(peakUsed, rt.totalMemory() - rt.freeMemory())
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) {}
        }.also { it.start() }

        val t0 = System.nanoTime()
        runBlocking { StitchingEngine.stitch(inputs, out, hFovDeg = 66.0, cropToContent = false) { } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        sampler.interrupt(); sampler.join()

        Log.i("StitchDevice", "30 photos stitched in $ms ms; peak java heap ${peakUsed / 1048576} MB of ${rt.maxMemory() / 1048576} MB; " +
            "native heap ${Debug.getNativeHeapAllocatedSize() / 1048576} MB; output ${out.length() / 1024} KB")
        assertTrue("panorama file must exist", out.exists() && out.length() > 10_000)
        dir.deleteRecursively()
    }
}
