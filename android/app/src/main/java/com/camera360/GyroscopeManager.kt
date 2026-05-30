package com.camera360

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DeviceOrientation(val azimuth: Float, val pitch: Float)

class GyroscopeManager(context: Context) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val hasGyroscope: Boolean get() = rotationSensor != null

    fun orientationFlow(): Flow<DeviceOrientation> = callbackFlow {
        val sensor = rotationSensor ?: run { close(); return@callbackFlow }
        val rotMatrix = FloatArray(9)
        val angles = FloatArray(3)

        var smoothAz = -1f
        var smoothPitch = 0f
        val alpha = 0.25f   // low-pass smoothing factor; higher = more responsive, less smooth

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                SensorManager.getOrientation(rotMatrix, angles)

                val rawAz = ((Math.toDegrees(angles[0].toDouble()).toFloat() + 360f) % 360f)
                val rawPitch = Math.toDegrees(angles[1].toDouble()).toFloat()

                if (smoothAz < 0f) {
                    smoothAz = rawAz
                    smoothPitch = rawPitch
                } else {
                    // Wrap-aware lerp for azimuth to avoid 360°→0° jump
                    val diff = ((rawAz - smoothAz + 540f) % 360f) - 180f
                    smoothAz = (smoothAz + diff * alpha + 360f) % 360f
                    smoothPitch += (rawPitch - smoothPitch) * alpha
                }

                trySend(DeviceOrientation(smoothAz, smoothPitch))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // SENSOR_DELAY_GAME (~20 ms) gives smoother AR tracking than SENSOR_DELAY_UI
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
