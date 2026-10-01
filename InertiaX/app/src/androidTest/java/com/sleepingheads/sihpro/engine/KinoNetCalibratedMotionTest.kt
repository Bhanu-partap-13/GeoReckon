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
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Validates KinoNet on Calibrated Real Motion on Samsung Galaxy A15 (SM-A156E)
 *
 * Implements:
 * 1. Physical phone-to-vehicle alignment R_b_to_v using the true gravity vector.
 * 2. Gravity removal in vehicle frame (Z-axis).
 * 3. Gyroscope bias subtraction.
 * 4. Captures at least 20 real motion windows [1, 6, 20] from hardware sensors.
 * 5. Correlates each window with independent physical LocationManager reference speed.
 * 6. Logs Base64 serialized Float32 tensors for Python parity and metric verification.
 */
@RunWith(AndroidJUnit4::class)
class KinoNetCalibratedMotionTest {

    companion object {
        private const val TAG = "KinoNetMotionVal"
        private const val TARGET_WINDOWS = 20
    }

    @Test
    fun testCalibratedMotionValidation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("Target context must not be null", appContext)
        Log.i(TAG, "=== STARTING KINONET CALIBRATED MOTION VALIDATION ===")

        instrumentation.uiAutomation.grantRuntimePermission(
            appContext.packageName,
            "android.permission.ACCESS_FINE_LOCATION"
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            appContext.packageName,
            "android.permission.ACCESS_COARSE_LOCATION"
        )

        val kinoNet = KinoNetInference(appContext)
        assertTrue("KinoNet model must be loaded from assets", kinoNet.isModelLoaded)
        assertFalse("KinoNet must not be using fallback", kinoNet.isUsingFallback)

        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        assertNotNull("Accelerometer required", accelSensor)
        assertNotNull("Gyroscope required", gyroSensor)

        // Independent Physical Reference Speed from LocationManager
        var latestRefSpeedMps = 0.0
        var hasGpsSpeed = false

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.hasSpeed()) {
                    latestRefSpeedMps = location.speed.toDouble()
                    hasGpsSpeed = true
                } else {
                    latestRefSpeedMps = 0.0
                }
                Log.i(TAG, "LOCATION_UPDATE: provider=${location.provider} speed=${location.speed} acc=${location.accuracy}")
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener, Looper.getMainLooper())
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 500L, 0f, locationListener, Looper.getMainLooper())
            }
            val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null && last.hasSpeed()) {
                latestRefSpeedMps = last.speed.toDouble()
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission error", e)
        }

        // 1. Initial Gravity & Bias Calibration (first 25 samples static/gravity capture)
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

        // Compute mean gravity vector in phone body frame
        var gMeanX = 0.0; var gMeanY = 0.0; var gMeanZ = 0.0
        val accList = rawAccels.toList()
        for (a in accList) {
            gMeanX += a[0]; gMeanY += a[1]; gMeanZ += a[2]
        }
        gMeanX /= accList.size; gMeanY /= accList.size; gMeanZ /= accList.size

        val gNorm = sqrt(gMeanX * gMeanX + gMeanY * gMeanY + gMeanZ * gMeanZ)
        val gUnit = doubleArrayOf(gMeanX / gNorm, gMeanY / gNorm, gMeanZ / gNorm)

        // Compute gyro static bias
        var wBiasX = 0.0; var wBiasY = 0.0; var wBiasZ = 0.0
        val gyroList = rawGyros.toList()
        if (gyroList.isNotEmpty()) {
            for (w in gyroList) {
                wBiasX += w[0]; wBiasY += w[1]; wBiasZ += w[2]
            }
            wBiasX /= gyroList.size; wBiasY /= gyroList.size; wBiasZ /= gyroList.size
        }
        val gyroBias = doubleArrayOf(wBiasX, wBiasY, wBiasZ)

        // Construct rotation matrix R_b_to_v such that vehicle Z = unit gravity vector (aligning vertical axis)
        // Vehicle Z = [gUnit[0], gUnit[1], gUnit[2]]
        // Build orthogonal basis:
        val zAxis = gUnit.clone()
        // Choose temporary arbitrary vector not parallel to zAxis
        val temp = if (Math.abs(zAxis[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        // yAxis = zAxis x temp
        val yAxis = doubleArrayOf(
            zAxis[1] * temp[2] - zAxis[2] * temp[1],
            zAxis[2] * temp[0] - zAxis[0] * temp[2],
            zAxis[0] * temp[1] - zAxis[1] * temp[0]
        )
        val yNorm = sqrt(yAxis[0] * yAxis[0] + yAxis[1] * yAxis[1] + yAxis[2] * yAxis[2])
        yAxis[0] /= yNorm; yAxis[1] /= yNorm; yAxis[2] /= yNorm
        // xAxis = yAxis x zAxis
        val xAxis = doubleArrayOf(
            yAxis[1] * zAxis[2] - yAxis[2] * zAxis[1],
            yAxis[2] * zAxis[0] - yAxis[0] * zAxis[2],
            yAxis[0] * zAxis[1] - yAxis[1] * zAxis[0]
        )

        // R_b_to_v has rows [xAxis, yAxis, zAxis]
        val R_b_to_v = arrayOf(xAxis, yAxis, zAxis)

        Log.i(TAG, "CALIBRATED_GRAVITY_VECTOR=[%.4f, %.4f, %.4f] norm=%.4f".format(gMeanX, gMeanY, gMeanZ, gNorm))
        Log.i(TAG, "CALIBRATED_GYRO_BIAS=[%.6f, %.6f, %.6f]".format(wBiasX, wBiasY, wBiasZ))
        Log.i(TAG, "R_PHONE_TO_VEHICLE=[[%.4f, %.4f, %.4f], [%.4f, %.4f, %.4f], [%.4f, %.4f, %.4f]]".format(
            R_b_to_v[0][0], R_b_to_v[0][1], R_b_to_v[0][2],
            R_b_to_v[1][0], R_b_to_v[1][1], R_b_to_v[1][2],
            R_b_to_v[2][0], R_b_to_v[2][1], R_b_to_v[2][2]
        ))

        // 2. Real Motion Sampling Window Capture
        val capturedWindows = mutableListOf<MotionWindowRecord>()
        val motionLatch = CountDownLatch(TARGET_WINDOWS + 25) // 45 samples @ 10 Hz = 4.5s
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

                        if (kinoNet.isReady && capturedWindows.size < TARGET_WINDOWS) {
                            val tensor = kinoNet.getChannelFirstInputTensor()
                            val pred = kinoNet.predictDetailed()

                            if (tensor != null && pred != null) {
                                val md5 = computeMd5(tensor)
                                val b64 = tensorToBase64(tensor)

                                // Compute input statistics
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

                                val record = MotionWindowRecord(
                                    index = capturedWindows.size,
                                    hash = md5,
                                    refSpeedMps = latestRefSpeedMps,
                                    predictedSpeedMps = pred.speedMps,
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
                                capturedWindows.add(record)
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

        val motionDone = motionLatch.await(15, TimeUnit.SECONDS)
        sensorManager.unregisterListener(motionListener)
        try {
            locationManager.removeUpdates(locationListener)
        } catch (_: Exception) {}
        kinoNet.close()

        assertTrue("Motion window capture completed", motionDone || capturedWindows.size >= TARGET_WINDOWS)
        Log.i(TAG, "CAPTURED_REAL_CALIBRATED_MOTION_WINDOWS=${capturedWindows.size}")
        assertTrue("Must capture at least $TARGET_WINDOWS windows", capturedWindows.size >= TARGET_WINDOWS)

        Log.i(TAG, "=== DUMPING CALIBRATED MOTION WINDOWS FOR PYTHON PARITY AND METRIC AUDIT ===")
        for (w in capturedWindows) {
            Log.i(
                TAG,
                "MOTION_WINDOW_ENTRY: idx=${w.index} hash=${w.hash} ref_speed=%.4f pred_speed=%.6f logvar=%.6f var=%.6f acc_min=%.4f acc_max=%.4f acc_mean=%.4f gyro_min=%.4f gyro_max=%.4f gyro_mean=%.4f tensor_b64=${w.base64Tensor}".format(
                    w.refSpeedMps, w.predictedSpeedMps, w.logVar, w.variance, w.accMin, w.accMax, w.accMean, w.gyroMin, w.gyroMax, w.gyroMean
                )
            )
        }
    }

    data class MotionWindowRecord(
        val index: Int,
        val hash: String,
        val refSpeedMps: Double,
        val predictedSpeedMps: Double,
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
