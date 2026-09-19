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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/** Angular distance (degrees) within which the device is considered "aligned" to a target. */
private const val ALIGNMENT_THRESHOLD_DEG = 10f

/** How often the auto-capture loop polls alignment state. */
private const val AUTO_CAPTURE_POLL_MS = 60L

/** Long side (px) of the grayscale copies used for image registration in manual mode. */
private const val GRAY_LONG_SIDE = 192

/** How long alignment must hold continuously before auto-capture fires — avoids motion blur / false triggers while swinging past a target. */
private const val AUTO_CAPTURE_STABLE_MS = 350L

/**
 * One of the 24 capture targets fixed in world space.
 * [azimuth] and [pitch] define its absolute world-space position.
 * [capturedAzimuth]/[capturedPitch] store the actual device orientation
 * at capture time — used for the on-screen guide/debugging.
 * [capturedRotationMatrix] stores the actual full device orientation (9-float
 * device→world rotation matrix) at capture time — this is what stitching uses;
 * it stays accurate even if the phone was rolled/tilted, unlike azimuth/pitch
 * alone. Null until captured, or if the device has no orientation sensor.
 */
data class FrameTarget(
    val index: Int,
    val azimuth: Float,               // world-fixed target azimuth 0..359°
    val pitch: Float,                 // world-fixed target pitch -90..+90°
    val captured: Boolean = false,
    val capturedAzimuth: Float = azimuth,
    val capturedPitch: Float = pitch,
    val capturedRotationMatrix: FloatArray? = null,
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

/**
 * One photo taken in manual mode (device without a rotation-vector sensor).
 * [gravityUp] is world "up" in device coordinates at the moment of the shutter
 * press (from the accelerometer) — pitch/roll for stitching; the heading is
 * recovered later from the images ([YawRegistration]).
 */
class ManualShot(val filePath: String, val gravityUp: FloatArray?)

data class CaptureState(
    val capturedFrames: Int = 0,
    // Manual mode (no rotation-vector sensor): free-form list of photos + live gravity.
    val manualShots: List<ManualShot> = emptyList(),
    val currentGravityUp: FloatArray? = null,
    // Non-fatal note after a successful stitch (e.g. some photos had to be left out).
    val stitchNotice: String? = null,
    val totalFrames: Int = 24,
    val isCapturing: Boolean = false,
    val lastError: String? = null,
    val currentAzimuth: Float = 0f,
    val currentPitch: Float = 0f,
    val currentRotationMatrix: FloatArray? = null,
    // Measured from the bound camera's real CameraCharacteristics when available
    // (see CaptureScreen) — falls back to StitchingEngine.CAMERA_HFOV_DEG if null.
    val measuredHFovDeg: Double? = null,
    // null = not yet determined (checked once at sensor start-up); false = the
    // device has no rotation-vector sensor — auto-capture, alignment guidance
    // and stitching (which requires a per-frame pose) are all unavailable, so
    // the UI must fall back to manual-shutter mode and say so explicitly
    // rather than silently degrading (see CaptureViewModel.ensureGyroscopeChecked).
    val hasGyroscope: Boolean? = null,
    val frames: List<FrameTarget> = generateFrames(),
    // Auto-capture: 0..1 progress while holding alignment steady, for UI feedback.
    val holdProgress: Float = 0f,
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
            // Without a gyroscope, currentAzimuth/currentPitch never move off
            // their initial (0,0) default, so "alignment" would otherwise be
            // spuriously true whenever a target happens to sit near (0,0) —
            // refuse to report alignment when we have no real orientation data.
            if (hasGyroscope != true) return false
            val i = nearestUncapturedIndex
            if (i < 0) return false
            return angularDist(currentAzimuth, currentPitch, frames[i].azimuth, frames[i].pitch) < ALIGNMENT_THRESHOLD_DEG
        }

    val allCaptured: Boolean get() = frames.all { it.captured }

    /** True when this device has no rotation-vector sensor: free-form shots + image-based stitching. */
    val isManualMode: Boolean get() = hasGyroscope == false

    /** Photos taken so far, in whichever mode is active. */
    val shotCount: Int get() = if (isManualMode) manualShots.size else capturedFrames

    /** (captured, total) per row: 0 = lower (-35°), 1 = middle (0°), 2 = upper (+35°) */
    val rowProgress: List<Pair<Int, Int>>
        get() = frames.chunked(8).map { row -> row.count { it.captured } to row.size }

    val directionHint: String
        get() {
            // Same reasoning as isAligned above — a frozen (0,0) reading isn't
            // real guidance and would just mislead the user.
            if (hasGyroscope != true) return ""
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
    private var autoCaptureJob: Job? = null

    // Cached result of the gyroscope presence check — computed once (see
    // ensureGyroscopeChecked) so startSensor/startAutoCapture agree on it
    // regardless of which one runs first from CaptureScreen's LaunchedEffects.
    private var hasGyroscope: Boolean? = null

    /**
     * Determines once whether this device has a rotation-vector sensor and
     * publishes it to [CaptureState.hasGyroscope] so the UI can show a clear
     * "no gyroscope — manual mode" message instead of silently degrading
     * (auto-capture misfiring off a frozen orientation, stitching failing
     * only after all 24 frames are manually captured).
     */
    private fun ensureGyroscopeChecked(context: Context): Boolean {
        hasGyroscope?.let { return it }
        val present = GyroscopeManager(context.applicationContext).hasGyroscope
        hasGyroscope = present
        _state.value = _state.value.copy(hasGyroscope = present)
        return present
    }

    private var gravityJob: Job? = null

    fun startSensor(context: Context) {
        if (!ensureGyroscopeChecked(context)) {
            startGravity(context)
            return
        }
        viewModelScope.launch {
            GyroscopeManager(context.applicationContext).orientationFlow().collect { o ->
                _state.value = _state.value.copy(
                    currentAzimuth = o.azimuth,
                    currentPitch = o.pitch,
                    currentRotationMatrix = o.rotationMatrix
                )
            }
        }
    }

    /** Manual mode: keep the latest gravity direction so each shot can record its pitch/roll. */
    private fun startGravity(context: Context) {
        if (gravityJob != null) return
        val gm = GravityManager(context.applicationContext)
        if (!gm.isAvailable) return
        gravityJob = viewModelScope.launch {
            gm.upFlow().collect { up -> _state.value = _state.value.copy(currentGravityUp = up) }
        }
    }

    /** Set once the bound camera's real horizontal FOV has been measured (see CaptureScreen). */
    fun setMeasuredHFov(fovDeg: Double) {
        _state.value = _state.value.copy(measuredHFovDeg = fovDeg)
    }

    /**
     * Watches alignment and fires [capturePhoto] automatically once the device
     * has held alignment to the nearest target for [AUTO_CAPTURE_STABLE_MS] —
     * "chỉ chụp khi xoay đúng vị trí". A manual shutter (still wired in the UI)
     * remains available as a fallback. Idempotent — safe to call more than once
     * (e.g. from recomposition); only the first call starts the loop.
     */
    fun startAutoCapture(imageCapture: ImageCapture, context: Context) {
        // No gyroscope → no reliable orientation, so there's nothing to
        // "align" to and no pose to record for stitching. Leave the user on
        // manual-shutter mode (see CaptureState.hasGyroscope / CaptureScreen)
        // instead of running a loop that would otherwise fire off a frozen
        // (0,0) reading.
        if (!ensureGyroscopeChecked(context)) return
        if (autoCaptureJob != null) return
        autoCaptureJob = viewModelScope.launch {
            var alignedSinceMs = -1L
            while (isActive) {
                delay(AUTO_CAPTURE_POLL_MS)
                val s = _state.value
                if (s.allCaptured || s.isCapturing || s.isStitching) {
                    alignedSinceMs = -1L
                    if (s.holdProgress != 0f) _state.value = _state.value.copy(holdProgress = 0f)
                    continue
                }
                if (s.isAligned) {
                    val now = System.currentTimeMillis()
                    if (alignedSinceMs < 0L) alignedSinceMs = now
                    val progress = ((now - alignedSinceMs).toFloat() / AUTO_CAPTURE_STABLE_MS).coerceIn(0f, 1f)
                    if (progress != s.holdProgress) _state.value = _state.value.copy(holdProgress = progress)
                    if (now - alignedSinceMs >= AUTO_CAPTURE_STABLE_MS) {
                        alignedSinceMs = -1L
                        _state.value = _state.value.copy(holdProgress = 0f)
                        capturePhoto(imageCapture, context)
                    }
                } else {
                    alignedSinceMs = -1L
                    if (s.holdProgress != 0f) _state.value = _state.value.copy(holdProgress = 0f)
                }
            }
        }
    }

    fun capturePhoto(imageCapture: ImageCapture, context: Context) {
        val s = _state.value
        if (s.isCapturing || s.allCaptured || s.isStitching) return
        if (s.isManualMode) {
            captureManualShot(imageCapture, context)
            return
        }
        val idx = s.nearestUncapturedIndex
        if (idx < 0) return

        // Capture the exact device orientation at the moment of shutter press
        val snapAz = s.currentAzimuth
        val snapPitch = s.currentPitch
        val snapMatrix = s.currentRotationMatrix

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
                    capturedRotationMatrix = snapMatrix,
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

    /** Manual mode: take a photo and remember the gravity direction it was taken with. */
    private fun captureManualShot(imageCapture: ImageCapture, context: Context) {
        val s = _state.value
        val gravity = s.currentGravityUp?.copyOf()
        _state.value = s.copy(isCapturing = true, lastError = null, stitchNotice = null)

        val framesDir = File(context.filesDir, "frames").also { it.mkdirs() }
        val frameFile = File(framesDir, "manual_%04d.jpg".format(s.manualShots.size + 1))
        val outputOptions = ImageCapture.OutputFileOptions.Builder(frameFile).build()

        imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                copyToGallery(context, frameFile,
                    "Camera360_manual_%d.jpg".format(System.currentTimeMillis()))
                val cur = _state.value
                _state.value = cur.copy(
                    isCapturing = false,
                    manualShots = cur.manualShots + ManualShot(frameFile.absolutePath, gravity),
                    lastError = if (gravity == null) "Không đọc được cảm biến gia tốc — ảnh này không thể dùng để ghép" else null
                )
            }

            override fun onError(ex: ImageCaptureException) {
                Log.e("CaptureVM", "Manual capture failed", ex)
                _state.value = _state.value.copy(isCapturing = false, lastError = ex.message ?: "Lỗi chụp ảnh")
            }
        })
    }

    /** Manual mode: drop the most recent shot (e.g. it was blurry). */
    fun undoLastManualShot() {
        val s = _state.value
        if (s.isStitching || s.manualShots.isEmpty()) return
        _state.value = s.copy(manualShots = s.manualShots.dropLast(1), lastError = null, stitchError = null, stitchNotice = null)
    }

    /**
     * Manual mode stitching: recover each photo's heading from the images
     * (gravity gives pitch/roll — see [YawRegistration]), then render with the
     * regular pose-driven [StitchingEngine].
     */
    private fun stitchManual(context: Context) {
        val s = _state.value
        if (s.isStitching) return
        val shots = s.manualShots
        if (shots.size < 2) {
            _state.value = s.copy(stitchError = "Cần ít nhất 2 ảnh chồng lấn nhau để ghép")
            return
        }
        _state.value = s.copy(isStitching = true, stitchProgress = 0f, stitchError = null, stitchNotice = null, stitchedFilePath = null)

        viewModelScope.launch {
            try {
                val hFov = s.measuredHFovDeg ?: StitchingEngine.CAMERA_HFOV_DEG
                val outputFile = File(context.filesDir, "panorama_${System.currentTimeMillis()}.jpg")
                var dropped = 0

                withContext(Dispatchers.Default) {
                    // 1) small grayscale copies + gravity for every usable shot
                    val usableShots = ArrayList<ManualShot>()
                    val gray = ArrayList<GrayFrame>()
                    for ((k, shot) in shots.withIndex()) {
                        val up = shot.gravityUp
                        val g = if (up == null) null else loadGray(File(shot.filePath), up)
                        if (g != null) { usableShots.add(shot); gray.add(g) } else dropped++
                        _state.value = _state.value.copy(stitchProgress = 0.05f * (k + 1) / shots.size)
                    }
                    if (gray.size < 2) throw IllegalStateException("Không đọc được đủ ảnh để ghép")

                    // 2) heading of each photo from image registration
                    val reg = YawRegistration.estimateHeadings(gray, hFov)
                    _state.value = _state.value.copy(stitchProgress = 0.30f)
                    val linked = gray.indices.filter { !reg.headingsDeg[it].isNaN() }
                    if (linked.size < 2) {
                        throw IllegalStateException(
                            "Không tìm thấy phần chung giữa các ảnh. Hãy chụp lại: xoay chậm, mỗi ảnh chồng lấn " +
                                "khoảng 30–50% với ảnh trước, và hướng vào cảnh có nhiều chi tiết (không phải tường trơn)."
                        )
                    }
                    dropped += gray.size - linked.size
                    val poses = YawRegistration.poses(gray, reg.headingsDeg)

                    // 3) pose-driven rendering (with exposure compensation)
                    val inputs = linked.map { StitchingEngine.FrameInput(File(usableShots[it].filePath), poses[it]) }
                    StitchingEngine.stitch(inputs, outputFile, hFovDeg = hFov, cropToContent = true) { p ->
                        _state.value = _state.value.copy(stitchProgress = 0.30f + 0.70f * p)
                    }
                    copyToGallery(context, outputFile, "Camera360_panorama_${System.currentTimeMillis()}.jpg")
                }

                _state.value = _state.value.copy(
                    isStitching = false,
                    stitchProgress = 1f,
                    stitchedFilePath = outputFile.absolutePath,
                    stitchNotice = if (dropped > 0) "Đã bỏ $dropped ảnh không đủ phần chung với các ảnh còn lại" else null
                )
            } catch (e: Exception) {
                Log.e("CaptureVM", "Manual stitching failed", e)
                _state.value = _state.value.copy(isStitching = false, stitchError = e.message ?: "Lỗi ghép ảnh")
            }
        }
    }

    /** Small upright grayscale copy of a photo for image registration. */
    private fun loadGray(file: File, gravityUp: FloatArray): GrayFrame? {
        if (!file.exists()) return null
        val bmp = ImageIo.loadUpright(file, GRAY_LONG_SIDE) ?: return null
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val luma = FloatArray(w * h) {
            val c = px[it]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        return GrayFrame(w, h, luma, doubleArrayOf(gravityUp[0].toDouble(), gravityUp[1].toDouble(), gravityUp[2].toDouble()))
    }

    fun stitchPanorama(context: Context) {
        if (_state.value.isManualMode) {
            stitchManual(context)
            return
        }
        val s = _state.value
        if (!s.allCaptured || s.isStitching) return

        // Stitching needs a per-frame device pose (rotation matrix) for every
        // shot; without a gyroscope none of the captured frames have one.
        // Fail fast with a clear, specific reason instead of falling through
        // to the generic "missing frames" message below.
        if (s.hasGyroscope == false) {
            _state.value = s.copy(stitchError = "Thiết bị không có cảm biến con quay hồi chuyển — không thể ghép panorama tự động")
            return
        }

        val inputs = s.frames.mapNotNull { f ->
            val path = f.filePath ?: return@mapNotNull null
            val file = File(path)
            val matrix = f.capturedRotationMatrix ?: return@mapNotNull null
            if (!file.exists()) return@mapNotNull null
            StitchingEngine.FrameInput(file, matrix)
        }

        if (inputs.size < s.totalFrames) {
            _state.value = s.copy(stitchError = "Thiếu ${s.totalFrames - inputs.size} frame (thiếu dữ liệu cảm biến hoặc ảnh) — vui lòng chụp lại")
            return
        }

        _state.value = s.copy(isStitching = true, stitchProgress = 0f, stitchError = null, stitchedFilePath = null)

        viewModelScope.launch {
            try {
                val outputFile = File(context.filesDir, "panorama_${System.currentTimeMillis()}.jpg")
                val hFov = s.measuredHFovDeg ?: StitchingEngine.CAMERA_HFOV_DEG

                withContext(Dispatchers.IO) {
                    StitchingEngine.stitch(inputs, outputFile, hFovDeg = hFov) { progress ->
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
        // Preserve the already-determined gyroscope presence (and the last
        // measured FOV) across a reset — re-running ensureGyroscopeChecked
        // would short-circuit on the cached `hasGyroscope` field and never
        // republish it to the fresh CaptureState, leaving the banner/guard
        // logic thinking it's still "unknown".
        _state.value = CaptureState(
            hasGyroscope = hasGyroscope,
            measuredHFovDeg = _state.value.measuredHFovDeg,
            currentGravityUp = _state.value.currentGravityUp
        )
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
        autoCaptureJob?.cancel()
        executor.shutdown()
    }
}
