package com.camera360.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Full-screen viewer for the stitched panorama: pinch to zoom, drag to pan, double-tap to reset.
 * The panorama is wide (e.g. 3840x1920), so it is shown "fit" first and can be zoomed to inspect seams.
 */
@Composable
fun PanoramaViewer(path: String, onClose: () -> Unit) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val bmp = withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (!file.exists()) null else {
                    // cap the decoded width so a huge panorama cannot exhaust memory
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > 4096) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (e: OutOfMemoryError) { null } catch (e: Exception) { null }
        }
        bitmap = bmp
        failed = bmp == null
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 8f)
                    val z = newScale / scale
                    // graphicsLayer scales about the view centre and then translates: keep the point under the
                    // pinch centroid fixed, follow the fingers 1:1 and never let the picture leave the screen.
                    val c = Offset(centroid.x - size.width / 2f, centroid.y - size.height / 2f)
                    val t = c + pan - (c - offset) * z
                    val maxX = (newScale - 1f) * size.width / 2f
                    val maxY = (newScale - 1f) * size.height / 2f
                    offset = Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                    scale = newScale
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
            },
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Panorama",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
            )
        } else {
            Text(
                if (failed) "Không mở được ảnh panorama" else "Đang tải…",
                color = Color.White, fontSize = 15.sp
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                .clickable(onClick = onClose)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text("Đóng", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }

        Text(
            "Chụm hai ngón để phóng to · kéo để di chuyển · chạm đúp để đặt lại",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp)
        )
    }
}
