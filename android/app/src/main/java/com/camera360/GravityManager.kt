package com.camera360

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.sqrt

/**
 * World "up" in device coordinates, from the accelerometer alone — the only
 * orientation sensor guaranteed on every phone (the Galaxy A12 test phone has
 * no gyroscope, magnetometer or rotation-vector sensor). Used when
 * [GyroscopeManager.hasGyroscope] is false: it pins down each photo's pitch and
 * roll, while the missing heading is recovered from the images
 * ([YawRegistration]).
 */
class GravityManager(context: Context) {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // TYPE_GRAVITY is already low-passed when present; otherwise filter the accelerometer ourselves.
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val isAvailable: Boolean get() = gravitySensor != null || accelSensor != null

    /** Unit vector pointing up, in device coordinates, updated continuously. */
    fun upFlow(): Flow<FloatArray> = callbackFlow {
        val sensor = gravitySensor ?: accelSensor ?: run { close(); return@callbackFlow }
        val filtered = gravitySensor == null
        val g = FloatArray(3)
        var primed = false
        val alpha = 0.12f   // accelerometer low-pass factor (only used without TYPE_GRAVITY)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!primed || !filtered) {
                    for (i in 0..2) g[i] = event.values[i]
                    primed = true
                } else {
                    for (i in 0..2) g[i] += (event.values[i] - g[i]) * alpha
                }
                val n = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (n < 1e-3f) return
                trySend(floatArrayOf(g[0] / n, g[1] / n, g[2] / n))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sensorManager.unregisterListener(listener) }
    }
}
