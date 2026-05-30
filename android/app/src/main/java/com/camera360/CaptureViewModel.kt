package com.camera360

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One of the 24 capture targets fixed in world space.
 * [azimuth] and [pitch] define its absolute world-space position.
 * [capturedAzimuth]/[capturedPitch] store the actual device orientation
 * at capture time — used for accurate stitching.
 */
data class FrameTarget(
    val index: Int,
    val azimuth: Float,               // world-fixed target azimuth 0..359°
    val pitch: Float,                 // world-fixed target pitch -90..+90°
    val captured: Boolean = false,
    val capturedAzimuth: Float = azimuth,
    val capturedPitch: Float = pitch,
    val filePath: String? = null      // internal storage path for stitching
)

/** 24 frames: 3 rows × 8 columns — covers the full sphere around the photographer */
private fun generateFrames(): List<FrameTarget> = buildList {
    listOf(-35f, 0f, 35f).forEachIndexed { row, pitch ->
        repeat(8) { col ->
            add(FrameTarget(index = row * 8 + col, azimuth = col * 45f, pitch = pitch))
        }
    }
}

data class CaptureState(
    val capturedFrames: Int = 0,
    val totalFrames: Int = 24,
    val isCapturing: Boolean = false,
    val lastError: String? = null,
    val currentAzimuth: Float = 0f,
    val currentPitch: Float = 0f,
    val frames: List<FrameTarget> = generateFrames(),
    // Stitching
    val isStitching: Boolean = false,
    val stitchProgress: Float = 0f,
    val stitchedFilePath: String? = null,
    val stitchError: String? = null
) {
    val nearestUncapturedIndex: Int
        get() {
            var nearest = -1
            var minDist = Float.MAX_VALUE
            frames.forEachIndexed { i, f ->
                if (!f.captured) {
                    val d = angularDist(currentAzimuth, currentPitch, f.azimuth, f.pitch)
                    if (d < minDist) { minDist = d; nearest = i }
                }
            }
            return nearest
        }

    val isAligned: Boolean
        get() {
            val i = nearestUncapturedIndex
            if (i < 0) return false
            return angularDist(currentAzimuth, currentPitch, frames[i].azimuth, frames[i].pitch) < 10f
        }

    val allCaptured: Boolean get() = frames.all { it.captured }

    /** (captured, total) per row: 0 = lower (-35°), 1 = middle (0°), 2 = upper (+35°) */
    val rowProgress: List<Pair<Int, Int>>
        get() = frames.chunked(8).map { row -> row.count { it.captured } to row.size }

    val directionHint: String
        get() {
            val i = nearestUncapturedIndex
            if (i < 0) return ""
            val f = frames[i]
            val raw = ((f.azimuth - currentAzimuth + 360f) % 360f)
            val relAz = if (raw > 180f) raw - 360f else raw
            val relPitch = f.pitch - currentPitch
            return buildString {
                when {
                    relAz > 20f  -> append("Xoay phải →")
                    relAz < -20f -> append("← Xoay trái")
                }
                if (isNotEmpty() && (relPitch > 15f || relPitch < -15f)) append("  ")
                when {
                    relPitch > 15f  -> append("↑ Ngước lên")
                    relPitch < -15f -> append("↓ Cúi xuống")
                }
            }
        }
}

fun angleDiff(a: Float, b: Float): Float {
    val d = ((a - b + 360f) % 360f)
    return if (d > 180f) 360f - d else d
}

private fun angularDist(az1: Float, p1: Float, az2: Float, p2: Float): Float {
    val dAz = angleDiff(az1, az2)
    val dP = abs(p1 - p2)
    return sqrt(dAz * dAz + dP * dP)
}

class CaptureViewModel : ViewModel() {
    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor()

    fun startSensor(context: Context) {
        viewModelScope.launch {
            GyroscopeManager(context).orientationFlow().collect { o ->
                _state.value = _state.value.copy(currentAzimuth = o.azimuth, currentPitch = o.pitch)
            }
        }
    }

    fun capturePhoto(imageCapture: ImageCapture, context: Context) {
        val s = _state.value
        if (s.isCapturing || s.allCaptured) return
        val idx = s.nearestUncapturedIndex
        if (idx < 0) return

        // Capture the exact device orientation at the moment of shutter press
        val snapAz = s.currentAzimuth
        val snapPitch = s.currentPitch

        _state.value = s.copy(isCapturing = true, lastError = null)

        // Save to internal storage — needed for stitching access on all Android versions
        val framesDir = File(context.filesDir, "frames").also { it.mkdirs() }
        val frameFile = File(framesDir, "frame_%04d.jpg".format(idx + 1))
        val outputOptions = ImageCapture.OutputFileOptions.Builder(frameFile).build()

        imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                // Mirror to the device gallery so the user can view individual frames
                copyToGallery(context, frameFile,
                    "Camera360_%04d_%d.jpg".format(idx + 1, System.currentTimeMillis()))

                val updated = _state.value.frames.toMutableList()
                updated[idx] = updated[idx].copy(
                    captured = true,
                    capturedAzimuth = snapAz,
                    capturedPitch = snapPitch,
                    filePath = frameFile.absolutePath
                )
                _state.value = _state.value.copy(
                    capturedFrames = _state.value.capturedFrames + 1,
                    isCapturing = false,
                    frames = updated
                )
            }

            override fun onError(ex: ImageCaptureException) {
                Log.e("CaptureVM", "Capture failed", ex)
                _state.value = _state.value.copy(isCapturing = false, lastError = ex.message ?: "Lỗi chụp ảnh")
            }
        })
    }

    fun stitchPanorama(context: Context) {
        val s = _state.value
        if (!s.allCaptured || s.isStitching) return

        val inputs = s.frames.mapNotNull { f ->
            val path = f.filePath ?: return@mapNotNull null
            val file = File(path)
            if (!file.exists()) return@mapNotNull null
            StitchingEngine.FrameInput(file, f.capturedAzimuth, f.capturedPitch)
        }

        if (inputs.size < s.totalFrames) {
            _state.value = s.copy(stitchError = "Thiếu ${s.totalFrames - inputs.size} frame — vui lòng chụp lại")
            return
        }

        _state.value = s.copy(isStitching = true, stitchProgress = 0f, stitchError = null, stitchedFilePath = null)

        viewModelScope.launch {
            try {
                val outputFile = File(context.filesDir, "panorama_${System.currentTimeMillis()}.jpg")

                withContext(Dispatchers.IO) {
                    StitchingEngine.stitch(inputs, outputFile) { progress ->
                        _state.value = _state.value.copy(stitchProgress = progress)
                    }
                    val panoramaName = "Camera360_panorama_${System.currentTimeMillis()}.jpg"
                    copyToGallery(context, outputFile, panoramaName)
                }

                _state.value = _state.value.copy(
                    isStitching = false,
                    stitchProgress = 1f,
                    stitchedFilePath = outputFile.absolutePath
                )
            } catch (e: Exception) {
                Log.e("CaptureVM", "Stitching failed", e)
                _state.value = _state.value.copy(
                    isStitching = false,
                    stitchError = "Lỗi ghép ảnh: ${e.message}"
                )
            }
        }
    }

    fun resetForNewSession() {
        _state.value = CaptureState()
    }

    private fun copyToGallery(context: Context, source: File, displayName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera360")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                    ?.let { uri ->
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            source.inputStream().use { inp -> inp.copyTo(out) }
                        }
                    }
            } else {
                @Suppress("DEPRECATION")
                val galleryDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "Camera360"
                ).also { it.mkdirs() }
                val dest = File(galleryDir, displayName)
                source.copyTo(dest, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
            }
        } catch (e: Exception) {
            Log.e("CaptureVM", "Gallery copy failed", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdown()
    }
}
