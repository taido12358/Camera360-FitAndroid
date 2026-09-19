package com.camera360

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * [rotationMatrix] is the raw (unsmoothed) device→world rotation matrix from
 * this sensor event — 9 floats, row-major, as produced by
 * [SensorManager.getRotationMatrixFromVector]. It is a fresh copy per event
 * (safe to hold onto), unlike [azimuth]/[pitch] which are smoothed for
 * on-screen guidance and should not be used for stitching pose.
 */
data class DeviceOrientation(
    val azimuth: Float,
    val pitch: Float,
    val rotationMatrix: FloatArray
)

class GyroscopeManager(context: Context) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val hasGyroscope: Boolean get() = rotationSensor != null

    fun orientationFlow(): Flow<DeviceOrientation> = callbackFlow {
        val sensor = rotationSensor ?: run { close(); return@callbackFlow }
        val rotMatrix = FloatArray(9)

        var smoothAz = -1f
        var smoothPitch = 0f
        val alpha = 0.25f   // low-pass smoothing factor; higher = more responsive, less smooth

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

                // Direction the back camera points, in the world ENU frame
                // (x=East, y=North, z=Up): device -Z axis = -(column 2 of R).
                // NOT SensorManager.getOrientation(): its pitch is the tilt of
                // the phone's *Y axis* (≈ -90° with the phone held upright and
                // the camera at the horizon) and its azimuth is degenerate in
                // exactly that upright pose. Camera-forward azimuth/elevation
                // is well-defined, with 0° = horizon and +90° = straight up,
                // which is what the frame targets and the AR overlay expect.
                val rawAz = PoseMath.azimuthDeg(rotMatrix)
                val rawPitch = PoseMath.elevationDeg(rotMatrix)

                if (smoothAz < 0f) {
                    smoothAz = rawAz
                    smoothPitch = rawPitch
                } else {
                    // Wrap-aware lerp for azimuth to avoid 360°→0° jump
                    val diff = ((rawAz - smoothAz + 540f) % 360f) - 180f
                    smoothAz = (smoothAz + diff * alpha + 360f) % 360f
                    smoothPitch += (rawPitch - smoothPitch) * alpha
                }

                // rotMatrix is reused by the listener on every event — copy it so
                // downstream collectors (esp. capture-time pose snapshot) aren't
                // aliased to a buffer that mutates out from under them.
                trySend(DeviceOrientation(smoothAz, smoothPitch, rotMatrix.copyOf()))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // SENSOR_DELAY_GAME (~20 ms) gives smoother AR tracking than SENSOR_DELAY_UI
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
