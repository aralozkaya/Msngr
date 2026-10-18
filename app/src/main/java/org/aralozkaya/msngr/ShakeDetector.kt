package org.aralozkaya.msngr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

internal class ShakeGestureDetector(
    private val forceThreshold: Float = 2.7f,
    private val cooldownNanos: Long = 900_000_000L,
) {
    private var lastShakeNanos = 0L

    fun onAcceleration(x: Float, y: Float, z: Float, timestampNanos: Long): Boolean {
        val gravity = SensorManager.GRAVITY_EARTH
        val force = sqrt(x * x + y * y + z * z) / gravity
        if (force < forceThreshold) return false
        if (lastShakeNanos != 0L && timestampNanos - lastShakeNanos < cooldownNanos) return false
        lastShakeNanos = timestampNanos
        return true
    }
}

@Composable
internal fun DetectShake(onShake: () -> Unit) {
    val context = LocalContext.current
    val currentOnShake by rememberUpdatedState(onShake)
    val detector = remember { ShakeGestureDetector() }

    DisposableEffect(context, detector) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || event.values.size < 3) return
                if (
                    detector.onAcceleration(
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2],
                        timestampNanos = event.timestamp,
                    )
                ) {
                    currentOnShake()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (accelerometer != null) {
            sensorManager.registerListener(
                listener,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME,
            )
        }
        onDispose { sensorManager.unregisterListener(listener) }
    }
}
