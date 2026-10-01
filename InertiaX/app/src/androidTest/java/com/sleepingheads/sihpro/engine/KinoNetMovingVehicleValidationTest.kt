package com.sleepingheads.sihpro.engine

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepingheads.sihpro.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Real Moving Vehicle / Dynamic KinoNet Validation Test
 *
 * Runs with foreground MainActivity active to ensure genuine physical GPS_PROVIDER
 * callbacks and IMU sensor streams from the physical Samsung Galaxy A15 (SM-A156E).
 *
 * Preprocessing:
 * 1. Phone-to-vehicle mount alignment R_b_to_v from calibrated gravity vector
 * 2. Gravity subtraction on vehicle vertical axis
 * 3. Gyroscope bias subtraction
 * 4. 20-sample sliding window [1, 6, 20] Float32 tensor
 * 5. Time-matched independent reference speed from LocationManager.GPS_PROVIDER
 */
@RunWith(AndroidJUnit4::class)
class KinoNetMovingVehicleValidationTest {

    companion object {
        private const val TAG = "KinoNetMovingVal"
        private const val OBSERVATION_TIMEOUT_SEC = 60L
        private const val MIN_WINDOWS_TARGET = 30
    }

    @Test
    fun testRealMovingVehicleValidation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("Target context must not be null", appContext)
        Log.i(TAG, "=== STARTING REAL MOVING VEHICLE KINONET VALIDATION ===")

        // Log exact test-device OS metadata
        Log.i(TAG, "DEVICE_METADATA: SDK_INT=${Build.VERSION.SDK_INT}")
        Log.i(TAG, "DEVICE_METADATA: RELEASE=${Build.VERSION.RELEASE}")
        Log.i(TAG, "DEVICE_METADATA: MODEL=${Build.MODEL}")
        Log.i(TAG, "DEVICE_METADATA: MANUFACTURER=${Build.MANUFACTURER}")
        Log.i(TAG, "DEVICE_METADATA: DEVICE=${Build.DEVICE}")

        instrumentation.uiAutomation.grantRuntimePermission(
            appContext.packageName,
            "android.permission.ACCESS_FINE_LOCATION"
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            appContext.packageName,
            "android.permission.ACCESS_COARSE_LOCATION"
        )

        // Launch foreground MainActivity to ensure full foreground GNSS/IMU priority
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        assertNotNull("Activity scenario launched", scenario)

        val kinoNet = KinoNetInference(appContext)
        assertTrue("KinoNet model loaded", kinoNet.isModelLoaded)
        assertFalse("KinoNet must not use fallback", kinoNet.isUsingFallback)

        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        assertNotNull("Physical Accelerometer required", accelSensor)
        assertNotNull("Physical Gyroscope required", gyroSensor)

        Log.i(TAG, "SENSOR_METADATA: ACCEL_NAME=${accelSensor?.name} VENDOR=${accelSensor?.vendor} RESOLUTION=${accelSensor?.resolution} MAX_RANGE=${accelSensor?.maximumRange}")
        Log.i(TAG, "SENSOR_METADATA: GYRO_NAME=${gyroSensor?.name} VENDOR=${gyroSensor?.vendor} RESOLUTION=${gyroSensor?.resolution} MAX_RANGE=${gyroSensor?.maximumRange}")

        var latestGpsSpeedMps = 0.0
        var latestGpsAccuracyM = 999.0
        var latestGpsTimestampMs = 0L
        var latestGpsElapsedNanos = 0L
        var latestGpsProvider = "none"
        var latestGpsHasSpeed = false
        var gpsCallbackCount = 0

        val satsInView = AtomicInteger(0)
        val satsUsed = AtomicInteger(0)

        val gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val total = status.satelliteCount
                var used = 0
                for (i in 0 until total) {
                    if (status.usedInFix(i)) used++
                }
                satsInView.set(total)
                satsUsed.set(used)
            }
        }

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Strictly accept ONLY genuine GPS_PROVIDER fixes for reference ground truth
                if (location.provider == LocationManager.GPS_PROVIDER) {
                    gpsCallbackCount++
                    latestGpsProvider = location.provider ?: "gps"
                    latestGpsHasSpeed = location.hasSpeed()
                    latestGpsSpeedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0
                    latestGpsAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 999.0
                    latestGpsTimestampMs = location.time
                    latestGpsElapsedNanos = location.elapsedRealtimeNanos

                    Log.i(
                        TAG,
                        "GENUINE_GPS_FIX: count=$gpsCallbackCount provider=$latestGpsProvider hasSpeed=$latestGpsHasSpeed speed=%.4fm/s acc=%.2fm lat=%.6f lon=%.6f elapsedNanos=$latestGpsElapsedNanos satsInView=${satsInView.get()} satsUsed=${satsUsed.get()}".format(
                            latestGpsSpeedMps, latestGpsAccuracyM, location.latitude, location.longitude
                        )
                    )
                } else {
                    Log.i(TAG, "IGNORED_NON_GPS_PROVIDER_FIX: provider=${location.provider} (strictly excluded from ground truth)")
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        // Register on main thread
        Handler(Looper.getMainLooper()).post {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener)
                    locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
                }
                val lastKnown = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastKnown != null && lastKnown.provider == LocationManager.GPS_PROVIDER) {
                    latestGpsSpeedMps = if (lastKnown.hasSpeed()) lastKnown.speed.toDouble() else 0.0
                    latestGpsAccuracyM = if (lastKnown.hasAccuracy()) lastKnown.accuracy.toDouble() else 999.0
                    latestGpsHasSpeed = lastKnown.hasSpeed()
                    latestGpsProvider = lastKnown.provider ?: "gps"
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Location permission error", e)
            }
        }

        // 1. Gravity & Gyro Bias Capture (first 25 samples)
        val calibLatch = CountDownLatch(25)
        val rawAccels = ConcurrentLinkedDeque<DoubleArray>()
        val rawGyros = ConcurrentLinkedDeque<DoubleArray>()

        val calibListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        rawAccels.add(doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()))
                        calibLatch.countDown()
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        rawGyros.add(doubleArrayOf(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble()))
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(calibListener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(calibListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)

        val calibDone = calibLatch.await(5, TimeUnit.SECONDS)
        sensorManager.unregisterListener(calibListener)
        assertTrue("Calibration samples collected", calibDone && rawAccels.isNotEmpty())

        var gMeanX = 0.0; var gMeanY = 0.0; var gMeanZ = 0.0
        val accList = rawAccels.toList()
        for (a in accList) {
            gMeanX += a[0]; gMeanY += a[1]; gMeanZ += a[2]
        }
        gMeanX /= accList.size; gMeanY /= accList.size; gMeanZ /= accList.size

        val gNorm = sqrt(gMeanX * gMeanX + gMeanY * gMeanY + gMeanZ * gMeanZ)
        val gUnit = doubleArrayOf(gMeanX / gNorm, gMeanY / gNorm, gMeanZ / gNorm)

        var wBiasX = 0.0; var wBiasY = 0.0; var wBiasZ = 0.0
        val gyroList = rawGyros.toList()
        if (gyroList.isNotEmpty()) {
            for (w in gyroList) {
                wBiasX += w[0]; wBiasY += w[1]; wBiasZ += w[2]
            }
            wBiasX /= gyroList.size; wBiasY /= gyroList.size; wBiasZ /= gyroList.size
        }
        val gyroBias = doubleArrayOf(wBiasX, wBiasY, wBiasZ)

        // Construct rotation matrix R_b_to_v such that vehicle Z = unit gravity vector
        val zAxis = gUnit.clone()
        val temp = if (Math.abs(zAxis[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val yAxis = doubleArrayOf(
            zAxis[1] * temp[2] - zAxis[2] * temp[1],
            zAxis[2] * temp[0] - zAxis[0] * temp[2],
            zAxis[0] * temp[1] - zAxis[1] * temp[0]
        )
        val yNorm = sqrt(yAxis[0] * yAxis[0] + yAxis[1] * yAxis[1] + yAxis[2] * yAxis[2])
        yAxis[0] /= yNorm; yAxis[1] /= yNorm; yAxis[2] /= yNorm
        val xAxis = doubleArrayOf(
            yAxis[1] * zAxis[2] - yAxis[2] * zAxis[1],
            yAxis[2] * zAxis[0] - yAxis[0] * zAxis[2],
            yAxis[0] * zAxis[1] - yAxis[1] * zAxis[0]
        )
        val R_b_to_v = arrayOf(xAxis, yAxis, zAxis)

        Log.i(TAG, "PHONE_TO_VEHICLE_ORIENTATION: R_b_to_v=[[%.4f, %.4f, %.4f], [%.4f, %.4f, %.4f], [%.4f, %.4f, %.4f]]".format(
            xAxis[0], xAxis[1], xAxis[2], yAxis[0], yAxis[1], yAxis[2], zAxis[0], zAxis[1], zAxis[2]
        ))
        Log.i(TAG, "CALIBRATED_GRAVITY_VECTOR=[%.4f, %.4f, %.4f] norm=%.4f".format(gMeanX, gMeanY, gMeanZ, gNorm))
        Log.i(TAG, "CALIBRATED_GYRO_BIAS=[%.6f, %.6f, %.6f]".format(wBiasX, wBiasY, wBiasZ))

        // 2. Real Moving Window Capture Loop
        val recordedWindows = mutableListOf<MovingWindowRecord>()
        val motionLatch = CountDownLatch(MIN_WINDOWS_TARGET + 20)
        var latestGyroRead = doubleArrayOf(0.0, 0.0, 0.0)

        val motionListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> {
                        latestGyroRead = doubleArrayOf(
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble()
                        )
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val rawA = doubleArrayOf(
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble()
                        )
                        val rawW = latestGyroRead.clone()

                        kinoNet.feedRawSample(
                            rawAccel = rawA,
                            rawGyro = rawW,
                            rPhoneToVehicle = R_b_to_v,
                            fwdAccelBias = 0.0,
                            gyroBias = gyroBias
                        )

                        if (kinoNet.isReady && recordedWindows.size < MIN_WINDOWS_TARGET) {
                            val tensor = kinoNet.getChannelFirstInputTensor()
                            val pred = kinoNet.predictDetailed()

                            if (tensor != null && pred != null) {
                                val md5 = computeMd5(tensor)
                                val b64 = tensorToBase64(tensor)

                                var accMin = Float.MAX_VALUE; var accMax = -Float.MAX_VALUE; var accSum = 0.0
                                var gyroMin = Float.MAX_VALUE; var gyroMax = -Float.MAX_VALUE; var gyroSum = 0.0

                                for (c in 0 until 6) {
                                    for (t in 0 until 20) {
                                        val v = tensor[c * 20 + t]
                                        if (c < 3) {
                                            if (v < accMin) accMin = v
                                            if (v > accMax) accMax = v
                                            accSum += v
                                        } else {
                                            if (v < gyroMin) gyroMin = v
                                            if (v > gyroMax) gyroMax = v
                                            gyroSum += v
                                        }
                                    }
                                }

                                val record = MovingWindowRecord(
                                    index = recordedWindows.size,
                                    hash = md5,
                                    timestampMs = System.currentTimeMillis(),
                                    refGpsProvider = latestGpsProvider,
                                    refGpsHasSpeed = latestGpsHasSpeed,
                                    refGpsSpeedMps = latestGpsSpeedMps,
                                    refGpsAccuracyM = latestGpsAccuracyM,
                                    predSpeedMps = pred.speedMps,
                                    logVar = pred.logVar,
                                    variance = pred.variance,
                                    accMin = accMin,
                                    accMax = accMax,
                                    accMean = accSum / 60.0,
                                    gyroMin = gyroMin,
                                    gyroMax = gyroMax,
                                    gyroMean = gyroSum / 60.0,
                                    base64Tensor = b64
                                )
                                recordedWindows.add(record)
                            }
                        }
                        motionLatch.countDown()
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(motionListener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(motionListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)

        val finished = motionLatch.await(OBSERVATION_TIMEOUT_SEC, TimeUnit.SECONDS)
        sensorManager.unregisterListener(motionListener)
        Handler(Looper.getMainLooper()).post {
            try {
                locationManager.removeUpdates(locationListener)
                locationManager.unregisterGnssStatusCallback(gnssCallback)
            } catch (_: Exception) {}
        }
        scenario.close()
        kinoNet.close()

        assertTrue("Recorded at least 20 windows", recordedWindows.size >= 20)
        Log.i(TAG, "CAPTURED_REAL_MOVING_WINDOWS=${recordedWindows.size}")
        Log.i(TAG, "TOTAL_GENUINE_GPS_CALLBACKS=$gpsCallbackCount")

        Log.i(TAG, "=== DUMPING MOVING VEHICLE WINDOWS FOR PYTHON PARITY AND REGIME ANALYSIS ===")
        for (w in recordedWindows) {
            Log.i(
                TAG,
                "DRIVE_WINDOW_ENTRY: idx=${w.index} hash=${w.hash} t_ms=${w.timestampMs} ref_provider=${w.refGpsProvider} ref_has_speed=${w.refGpsHasSpeed} ref_speed=%.4f ref_acc=%.2f pred_speed=%.6f logvar=%.6f var=%.6f acc_min=%.4f acc_max=%.4f acc_mean=%.4f gyro_min=%.4f gyro_max=%.4f gyro_mean=%.4f tensor_b64=${w.base64Tensor}".format(
                    w.refGpsSpeedMps, w.refGpsAccuracyM, w.predSpeedMps, w.logVar, w.variance, w.accMin, w.accMax, w.accMean, w.gyroMin, w.gyroMax, w.gyroMean
                )
            )
        }
    }

    data class MovingWindowRecord(
        val index: Int,
        val hash: String,
        val timestampMs: Long,
        val refGpsProvider: String,
        val refGpsHasSpeed: Boolean,
        val refGpsSpeedMps: Double,
        val refGpsAccuracyM: Double,
        val predSpeedMps: Double,
        val logVar: Double,
        val variance: Double,
        val accMin: Float,
        val accMax: Float,
        val accMean: Double,
        val gyroMin: Float,
        val gyroMax: Float,
        val gyroMean: Double,
        val base64Tensor: String
    )

    private fun computeMd5(floats: FloatArray): String {
        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) bytes.putFloat(f)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes.array())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun tensorToBase64(floats: FloatArray): String {
        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) bytes.putFloat(f)
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
    }
}
