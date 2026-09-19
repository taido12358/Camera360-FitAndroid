package com.camera360

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.camera360.ui.CaptureScreen
import com.camera360.ui.theme.Camera360Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A sweep takes minutes and stitching ~25 s; a screen that dims or locks mid-session loses the user's place
        // (verified: the per-view keepScreenOn flag never reached the window flags on the test phone).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        // Test hook, debug builds only: `adb shell am start -n com.camera360/.MainActivity --ez force_manual true`
        // runs the accelerometer-only (manual) mode on a phone/emulator that does have a rotation-vector sensor,
        // so that mode's whole in-app flow can be exercised without owning a sensor-less phone.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && intent.getBooleanExtra("force_manual", false)) {
            ViewModelProvider(this)[CaptureViewModel::class.java].debugForceManualMode()
        }

        setContent {
            Camera360Theme {
                CaptureScreen()
            }
        }
    }
}
