package com.camera360

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.camera360.ui.CaptureScreen
import com.camera360.ui.theme.Camera360Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A sweep takes minutes and stitching ~25 s; a screen that dims or locks mid-session loses the user's place
        // (verified: the per-view keepScreenOn flag never reached the window flags on the test phone).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            Camera360Theme {
                CaptureScreen()
            }
        }
    }
}
