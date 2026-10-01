package com.sleepingheads.sihpro.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.sleepingheads.sihpro.data.model.GnssSample
import com.sleepingheads.sihpro.data.model.ImuSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android Sensor Bridge
 *
 * Subscribes to the Android [SensorManager] (accelerometer + gyroscope)
 * and [LocationManager] (GPS/GNSS), converts their callbacks into
 * [ImuSample] / [GnssSample] data classes, and forwards them to
 * [GeoReckonEngine].
 *
 * Usage:
 *   val bridge = SensorBridge(context, engine, scope)
 *   bridge.start()          // in onResume / ViewModel.init
 *   bridge.stop()           // in onPause  / ViewModel.onCleared
 *
 * IMU hardware rate: SENSOR_DELAY_GAME (~50 Hz).
 * The engine itself only runs EKF at 10 Hz via down-sampling (every 5th sample).
 */
class SensorBridge(
    private val context: Context,
    private val engine: GeoReckonEngine,
    private val scope: CoroutineScope
) {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gyroSensor:  Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Latest gyro (may arrive at different time than accel on some devices)
    private var latestGyro = FloatArray(3)
    private var latestGyroTs = 0L
    private var sampleCounter = 0

    // Down-sample factor: 50 Hz hardware → 10 Hz engine
    private val DOWN_SAMPLE = 5

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    latestGyro = event.values.copyOf()
                    latestGyroTs = event.timestamp
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    sampleCounter++
                    // Down-sample to ~10 Hz
                    if (sampleCounter % DOWN_SAMPLE != 0) return

                    val imu = ImuSample(
                        timestampNs = event.timestamp,
                        accelX = event.values[0].toDouble(),
                        accelY = event.values[1].toDouble(),
                        accelZ = event.values[2].toDouble(),
                        gyroX = latestGyro[0].toDouble(),
                        gyroY = latestGyro[1].toDouble(),
                        gyroZ = latestGyro[2].toDouble()
                    )
                    scope.launch(Dispatchers.Default) {
                        engine.onImuSample(imu)
                    }
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val gnss = GnssSample(
                timestampNs = location.elapsedRealtimeNanos,
                latitude = location.latitude,
                longitude = location.longitude,
                speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
                valid = location.accuracy < 50f // reject fixes with > 50m accuracy
            )
            scope.launch(Dispatchers.Default) {
                engine.onGnssSample(gnss)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            // GNSS lost — send invalid fix
            scope.launch(Dispatchers.Default) {
                engine.onGnssSample(
                    GnssSample(
                        timestampNs = System.nanoTime(),
                        latitude = Double.NaN, longitude = Double.NaN,
                        speedMps = null, accuracyM = null, valid = false
                    )
                )
            }
        }
    }

    /**
     * Start listening to IMU and GPS sensors.
     * Call from Activity.onResume() or ViewModel.init().
     *
     * Requires: ACCESS_FINE_LOCATION permission for GPS.
     */
    fun start() {
        accelSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroSensor?.let {
            sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_GAME)
        }

        try {
            // GPS updates every 1 second / 1 metre minimum
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener
            )
        } catch (e: SecurityException) {
            // ACCESS_FINE_LOCATION not granted — engine will run in IMU-only mode
        }
    }

    /**
     * Stop all sensor listeners. Call from Activity.onPause() or ViewModel.onCleared().
     */
    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
    }
}
