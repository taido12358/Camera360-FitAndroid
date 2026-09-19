package com.camera360.ui

import android.Manifest
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.camera360.CaptureState
import com.camera360.CaptureViewModel
import com.camera360.StitchingEngine
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// ── Perspective projection helpers ──────────────────────────────────────────────

/**
 * Projects a world-space point at ([targetAz]°, [targetPitch]°) onto the screen
 * using proper 3D perspective math given the current camera direction
 * ([camAz]°, [camPitch]°) and a horizontal FOV of [hFovDeg]°.
 *
 * Returns null when the point is behind the camera.
 */
private fun projectToScreen(
    targetAz: Float, targetPitch: Float,
    camAz: Float, camPitch: Float,
    screenW: Float, screenH: Float,
    hFovDeg: Double = StitchingEngine.CAMERA_HFOV_DEG
): Offset? {
    val caz = Math.toRadians(camAz.toDouble())
    val cp  = Math.toRadians(camPitch.toDouble())
    val taz = Math.toRadians(targetAz.toDouble())
    val tp  = Math.toRadians(targetPitch.toDouble())

    // World unit vector of the target (spherical → Cartesian)
    val wx = cos(tp) * sin(taz)
    val wy = sin(tp)
    val wz = cos(tp) * cos(taz)

    // Camera basis vectors in world space
    // Right = (cos(caz),            0,           -sin(caz))
    // Up    = (-sin(caz)*sin(cp),   cos(cp),     -cos(caz)*sin(cp))
    // Fwd   = (sin(caz)*cos(cp),    sin(cp),      cos(caz)*cos(cp))
    val camX = wx * cos(caz)                            + wz * (-sin(caz))
    val camY = wx * (-sin(caz) * sin(cp)) + wy * cos(cp) + wz * (-cos(caz) * sin(cp))
    val camZ = wx * sin(caz) * cos(cp)   + wy * sin(cp)  + wz * cos(caz) * cos(cp)

    if (camZ <= 0.01) return null   // behind camera

    // Perspective divide → screen coordinates
    val focalLen = (screenW / 2.0) / tan(Math.toRadians(hFovDeg / 2.0))
    val sx = (screenW / 2.0 + focalLen * camX / camZ).toFloat()
    val sy = (screenH / 2.0 - focalLen * camY / camZ).toFloat()
    return Offset(sx, sy)
}

/**
 * Same projection as [projectToScreen], but the camera pose is the device's
 * full device→world rotation matrix ([r], row-major, ENU world frame) instead
 * of an azimuth/pitch pair — so phone roll is respected and the overlay dots
 * stay glued to the real scene when the phone is tilted sideways. Uses the
 * same basis convention as StitchingEngine (right = column 0, up = column 1,
 * forward = -column 2 of R).
 */
private fun projectToScreenWithMatrix(
    targetAz: Float, targetPitch: Float,
    r: FloatArray,
    screenW: Float, screenH: Float,
    hFovDeg: Double
): Offset? {
    val taz = Math.toRadians(targetAz.toDouble())
    val tp  = Math.toRadians(targetPitch.toDouble())
    // ENU: x = East, y = North, z = Up
    val e = cos(tp) * sin(taz)
    val n = cos(tp) * cos(taz)
    val u = sin(tp)

    val camX = e * r[0] + n * r[3] + u * r[6]
    val camY = e * r[1] + n * r[4] + u * r[7]
    val camZ = -(e * r[2] + n * r[5] + u * r[8])

    if (camZ <= 0.01) return null   // behind camera

    val focalLen = (screenW / 2.0) / tan(Math.toRadians(hFovDeg / 2.0))
    val sx = (screenW / 2.0 + focalLen * camX / camZ).toFloat()
    val sy = (screenH / 2.0 - focalLen * camY / camZ).toFloat()
    return Offset(sx, sy)
}

/** Projects with the full rotation matrix when available, else az/pitch only. */
private fun projectTarget(
    targetAz: Float, targetPitch: Float,
    state: CaptureState,
    screenW: Float, screenH: Float,
    hFovDeg: Double
): Offset? {
    val r = state.currentRotationMatrix
    return if (r != null) {
        projectToScreenWithMatrix(targetAz, targetPitch, r, screenW, screenH, hFovDeg)
    } else {
        projectToScreen(
            targetAz, targetPitch,
            state.currentAzimuth, state.currentPitch,
            screenW, screenH, hFovDeg
        )
    }
}

/**
 * Reads the bound back camera's real horizontal FOV from its
 * [CameraCharacteristics] (focal length + physical sensor size) instead of
 * relying on [StitchingEngine.CAMERA_HFOV_DEG]'s hardcoded assumption — this
 * is what both the AR guide overlay and the final stitch should use for
 * accurate projection. Returns null if the device doesn't report the needed
 * characteristics.
 */
// Uses androidx.annotation.OptIn (fully-qualified), not kotlin.OptIn: CameraX's
// experimental markers are androidx.annotation.RequiresOptIn-based, which Android
// Lint's UnsafeOptInUsageError check only recognizes via the AndroidX OptIn
// annotation — kotlin.OptIn compiles (with a "has no effect" warning) but doesn't
// satisfy lint, so assembling would compile fine yet `lint`/`lintDebug` would fail.
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
private fun measureHorizontalFovDeg(camera: androidx.camera.core.Camera): Double? {
    return try {
        val chars = Camera2CameraInfo.from(camera.cameraInfo)
        val focalLengths = chars.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val sensorSize = chars.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focal = focalLengths?.firstOrNull()
        if (focal == null || focal <= 0f || sensorSize == null || sensorSize.width <= 0f) return null
        val fov = Math.toDegrees(2.0 * atan((sensorSize.width / (2.0 * focal)).toDouble()))
        // Guard against a degenerate (NaN/zero/negative) result feeding a
        // tan(hFov/2) == 0 divide in projectToScreen's focalLen calculation,
        // which would otherwise blow up dot positions to Infinity/NaN.
        if (fov.isNaN() || fov <= 0.0) return null
        fov
    } catch (e: Exception) {
        null
    }
}

// ── Main screen ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CaptureScreen(viewModel: CaptureViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionList = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }
    val permissionState = rememberMultiplePermissionsState(permissionList)

    LaunchedEffect(Unit) {
        if (!permissionState.allPermissionsGranted) permissionState.launchMultiplePermissionRequest()
    }
    LaunchedEffect(permissionState.allPermissionsGranted) {
        if (permissionState.allPermissionsGranted) viewModel.startSensor(context)
    }

    if (!permissionState.allPermissionsGranted) {
        PermissionDeniedScreen { permissionState.launchMultiplePermissionRequest() }
        return
    }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    // "Khi điện thoại xoay đến đúng vị trí thì mới chụp" — auto-fires capturePhoto
    // once alignment holds steady; manual shutter below remains a fallback.
    LaunchedEffect(Unit) {
        viewModel.startAutoCapture(imageCapture, context)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Camera preview ────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }.also { pv ->
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build()
                            .also { it.surfaceProvider = pv.surfaceProvider }
                        try {
                            provider.unbindAll()
                            val camera = provider.bindToLifecycle(lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                            measureHorizontalFovDeg(camera)?.let { viewModel.setMeasuredHFov(it) }
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ── 3D sphere guide: 24 world-fixed dots projected onto screen ────
        // Skipped when there's no gyroscope: without real orientation data the
        // dots can't actually track device movement, so drawing them would
        // just be a misleading "AR" overlay that never moves as you turn.
        if (state.hasGyroscope != false) {
            val textMeasurer = rememberTextMeasurer()
            SphereGuideOverlay(
                modifier = Modifier.fillMaxSize(),
                state = state,
                textMeasurer = textMeasurer
            )
        }

        // ── No-gyroscope banner — always visible in manual mode so the user
        // understands up front why auto-capture/AR guidance are unavailable,
        // instead of only discovering it after capturing all 24 frames and
        // hitting a stitch error (see CaptureViewModel.ensureGyroscopeChecked). ──
        if (state.hasGyroscope == false) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .background(Color(0xFFB00020).copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    "Thiết bị không có cảm biến con quay hồi chuyển — chỉ hỗ trợ chụp thủ công, " +
                        "không thể tự động căn chỉnh hoặc ghép panorama.",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── Top HUD: total + per-row progress ────────────────────────────
        // Pushed down when the no-gyroscope banner above is showing so the two don't overlap.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = if (state.hasGyroscope == false) 52.dp else 12.dp)
                .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        state.stitchedFilePath != null -> "Panorama 360° đã hoàn chỉnh!"
                        state.isStitching -> "Đang ghép panorama…"
                        state.allCaptured -> "Đã chụp đủ 24/24 — Sẵn sàng ghép!"
                        else -> "${state.capturedFrames} / 24 frames"
                    },
                    color = when {
                        state.stitchedFilePath != null -> Color(0xFF4CAF50)
                        state.allCaptured -> Color(0xFFFFEB3B)
                        else -> Color.White
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                if (!state.allCaptured) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        listOf("↓ Dưới", "→ Giữa", "↑ Trên").forEachIndexed { i, label ->
                            val (done, total) = state.rowProgress.getOrElse(i) { 0 to 8 }
                            Text(
                                text = "$label $done/$total",
                                color = if (done == total) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.72f),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // ── Direction hint ────────────────────────────────────────────────
        val hint = state.directionHint
        if (hint.isNotEmpty() && !state.allCaptured && !state.isStitching) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 108.dp)
                    .background(Color.Black.copy(alpha = 0.52f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(hint, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        // ── Auto-capture hold indicator — fills as alignment holds steady,
        // then fires the shot automatically (see CaptureViewModel.startAutoCapture) ──
        if (state.isAligned && !state.allCaptured) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 120.dp)
                    .background(Color(0xFFFFEB3B).copy(alpha = 0.93f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 18.dp, vertical = 7.dp)
            ) {
                Text(
                    if (state.holdProgress >= 0.999f) "Đang chụp…" else "Giữ yên…",
                    color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.holdProgress },
                    modifier = Modifier.width(90.dp).height(3.dp),
                    color = Color.Black,
                    trackColor = Color.Black.copy(alpha = 0.25f)
                )
            }
        }

        // ── Bottom action area ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                // Stitching in progress
                state.isStitching -> StitchingProgressUI(progress = state.stitchProgress)

                // Panorama ready — show open-gallery button
                state.stitchedFilePath != null -> StitchCompleteUI(
                    onOpenGallery = {
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            type = "image/jpeg"
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                    },
                    onReset = { viewModel.resetForNewSession() }
                )

                // All frames captured, but stitching needs per-frame gyroscope
                // poses this device doesn't have — say so plainly instead of
                // letting the user tap "Stitch" into the same error.
                state.allCaptured && state.hasGyroscope == false -> NoGyroscopeStitchUnavailableUI(
                    onReset = { viewModel.resetForNewSession() }
                )

                // All frames captured — offer stitching
                state.allCaptured -> StitchPromptUI(
                    onStitch = { viewModel.stitchPanorama(context) }
                )

                // Normal capture mode — shutter button
                else -> ShutterButton(
                    isCapturing = state.isCapturing,
                    isAligned = state.isAligned,
                    onCapture = { viewModel.capturePhoto(imageCapture, context) }
                )
            }
        }

        // ── Errors ───────────────────────────────────────────────────────
        (state.lastError ?: state.stitchError)?.let { err ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 160.dp)
                    .background(Color(0xFFB00020).copy(alpha = 0.88f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(text = err, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

// ── 3D Sphere overlay ────────────────────────────────────────────────────────────
// Each dot's screen position is recomputed every frame via proper perspective
// projection — giving the "points fixed in world space" AR effect.
@Composable
private fun SphereGuideOverlay(
    modifier: Modifier,
    state: CaptureState,
    textMeasurer: androidx.compose.ui.text.TextMeasurer
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val dotR = 26.dp.toPx()
        val nearestIdx = state.nearestUncapturedIndex
        val hFov = state.measuredHFovDeg ?: StitchingEngine.CAMERA_HFOV_DEG

        // Faint level lines at each row's pitch angle. Two points on the row,
        // ±40° either side of the camera's heading, so the line tilts with
        // phone roll instead of always being drawn horizontally.
        listOf(-35f, 0f, 35f).forEach { rowPitch ->
            val left = projectTarget(state.currentAzimuth - 40f, rowPitch, state, w, h, hFov)
            val right = projectTarget(state.currentAzimuth + 40f, rowPitch, state, w, h, hFov)
            if (left != null && right != null) {
                drawLine(
                    Color.White.copy(alpha = 0.10f),
                    left, right,
                    strokeWidth = 1.dp.toPx()
                )
            }
        }

        // Draw each of the 24 world-fixed frame dots
        state.frames.forEach { frame ->
            val pos = projectTarget(
                frame.azimuth, frame.pitch,
                state, w, h, hFov
            ) ?: return@forEach   // null = behind camera; skip

            val x = pos.x; val y = pos.y

            // Cull dots far off-screen (with generous margin so edge dots still draw)
            if (x < -dotR * 3 || x > w + dotR * 3 || y < -dotR * 3 || y > h + dotR * 3) return@forEach

            val isNearest = !frame.captured && frame.index == nearestIdx
            val isAligned = isNearest && state.isAligned

            // Compute screen distance from center to modulate opacity
            val screenDist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            val maxDist    = sqrt(cx * cx + cy * cy)
            val alpha = when {
                frame.captured -> 0.90f
                isAligned      -> 1.00f
                isNearest      -> 0.95f
                else           -> (1f - 0.55f * screenDist / maxDist).coerceIn(0.35f, 0.80f)
            }

            val dotColor = when {
                frame.captured -> Color(0xFF4CAF50)   // green  = done
                isAligned      -> Color(0xFFFFEB3B)   // yellow = camera aligned
                isNearest      -> Color(0xFFFF9800)   // orange = next target
                else           -> Color.White
            }

            // Filled background
            drawCircle(
                color = dotColor.copy(alpha = alpha * if (frame.captured) 0.35f else 0.15f),
                radius = dotR,
                center = Offset(x, y)
            )
            // Outline ring — thicker for the nearest/aligned dot
            drawCircle(
                color = dotColor.copy(alpha = alpha),
                radius = dotR,
                center = Offset(x, y),
                style = Stroke(width = when {
                    isAligned -> 4.dp.toPx()
                    isNearest -> 3.dp.toPx()
                    else      -> 2.dp.toPx()
                })
            )

            if (frame.captured) {
                // Checkmark
                val s = dotR * 0.45f
                drawLine(dotColor.copy(alpha = alpha),
                    Offset(x - s, y),         Offset(x - s * 0.10f, y + s * 0.70f),
                    2.5.dp.toPx(), StrokeCap.Round)
                drawLine(dotColor.copy(alpha = alpha),
                    Offset(x - s * 0.10f, y + s * 0.70f), Offset(x + s * 0.80f, y - s * 0.55f),
                    2.5.dp.toPx(), StrokeCap.Round)
            } else {
                // Frame number
                val numText = "${frame.index + 1}"
                val style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = dotColor.copy(alpha = alpha)
                )
                val layout = textMeasurer.measure(numText, style)
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x - layout.size.width / 2f, y - layout.size.height / 2f)
                )
            }
        }

        // Center crosshair — shows exactly where camera is pointing
        val cs  = 22.dp.toPx()
        val gap = 10.dp.toPx()
        val cc  = Color.White.copy(alpha = 0.88f)
        val cw  = 2.dp.toPx()
        drawLine(cc, Offset(cx, cy - gap - cs), Offset(cx, cy - gap), cw, cap = StrokeCap.Round)
        drawLine(cc, Offset(cx, cy + gap),      Offset(cx, cy + gap + cs), cw, cap = StrokeCap.Round)
        drawLine(cc, Offset(cx - gap - cs, cy), Offset(cx - gap, cy), cw, cap = StrokeCap.Round)
        drawLine(cc, Offset(cx + gap, cy),      Offset(cx + gap + cs, cy), cw, cap = StrokeCap.Round)
        drawCircle(cc, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
    }
}

// ── Bottom action components ─────────────────────────────────────────────────────

// Capture is automatic once the device holds alignment steady (see
// CaptureViewModel.startAutoCapture) — this button is a manual fallback for
// when the sensor can't confirm alignment (e.g. no gyroscope) or the user
// wants to force a shot early.
@Composable
private fun ShutterButton(isCapturing: Boolean, isAligned: Boolean, onCapture: () -> Unit) {
    val ringColor  = if (isAligned) Color(0xFFFFEB3B) else Color.White
    val innerColor = when { isCapturing -> Color.Gray; isAligned -> Color(0xFFFFEB3B); else -> Color.White }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(88.dp).clip(CircleShape).border(4.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(70.dp).clip(CircleShape).background(innerColor)
                    .clickable(enabled = !isCapturing, onClick = onCapture)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text("Chụp thủ công (dự phòng)", color = Color.White.copy(alpha = 0.65f), fontSize = 10.sp)
    }
}

@Composable
private fun StitchPromptUI(onStitch: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("24/24 frames hoàn tất!", color = Color(0xFF4CAF50), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .background(Color(0xFF1565C0), RoundedCornerShape(12.dp))
                .clickable(onClick = onStitch)
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text("Ghép Panorama 360°", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NoGyroscopeStitchUnavailableUI(onReset: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "24/24 ảnh đã chụp, nhưng thiết bị không có cảm biến\ncon quay hồi chuyển nên không thể ghép panorama.",
            color = Color(0xFFFFEB3B),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                .clickable(onClick = onReset)
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text("Chụp lại", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StitchingProgressUI(progress: Float) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.70f), RoundedCornerShape(14.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Đang ghép panorama… ${(progress * 100).toInt()}%",
            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = Color(0xFF1565C0),
            trackColor = Color.White.copy(alpha = 0.25f)
        )
    }
}

@Composable
private fun StitchCompleteUI(onOpenGallery: () -> Unit, onReset: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(Color(0xFF1B5E20).copy(alpha = 0.93f), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Text(
            "Panorama 360° đã được lưu\nvào Thư viện ảnh!",
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF4CAF50), RoundedCornerShape(10.dp))
                    .clickable(onClick = onOpenGallery)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text("Mở Thư viện", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text("Chụp lại", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun PermissionDeniedScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Cần quyền truy cập Camera", color = Color.White, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .clickable(onClick = onRequest)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text("Cấp quyền", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}
