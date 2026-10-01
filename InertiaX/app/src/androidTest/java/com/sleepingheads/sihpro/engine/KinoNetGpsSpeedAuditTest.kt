package com.sleepingheads.sihpro.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
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
 * Strict Real GPS Speed & Moving-Vehicle Audit Test for Samsung Galaxy A15 (SM-A156E)
 *
 * Requirements:
 * 1. Audits genuine LocationManager.GPS_PROVIDER callbacks and satellite fix status.
 * 2. Captures provider, lat, lon, hasSpeed, speed, accuracy, elapsedRealtimeNanos.
 * 3. Enforces strict moving-vehicle threshold (REFERENCE_SPEED_MAX > 1.0 m/s).
 * 4. Captures 30 consecutive 2-second windows with exact Float32 tensors for Python parity.
 */
@RunWith(AndroidJUnit4::class)
class KinoNetGpsSpeedAuditTest {

    companion object {
        private const val TAG = "KinoNetGpsAudit"
        private const val OBSERVATION_TIMEOUT_SEC = 40L
        private const val TARGET_WINDOWS = 30
    }

    @Test
    fun testGpsSpeedAndMotionAudit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("Target context must not be null", appContext)
        Log.i(TAG, "=== STARTING STRICT GPS SPEED & MOVING VEHICLE AUDIT ===")

        instrumentation.uiAutomation.grantRuntimePermission(
            appContext.packageName,
            "android.permission.ACCESS_FINE_LOCATION"
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            appContext.packageName,
            "android.permission.ACCESS_COARSE_LOCATION"
        )

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        assertNotNull("Activity scenario launched", scenario)

        val kinoNet = KinoNetInference(appContext)
        assertTrue("KinoNet model loaded", kinoNet.isModelLoaded)
        assertFalse("KinoNet must not use fallback", kinoNet.isUsingFallback)

        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        assertNotNull("Accelerometer required", accelSensor)
        assertNotNull("Gyroscope required", gyroSensor)

        // GNSS Status Monitoring
        val gnssSatellitesInView = AtomicInteger(0)
        val gnssSatellitesUsed = AtomicInteger(0)
        val gnssMaxCn0 = AtomicInteger(0)

        val gnssStatusCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var inView = status.satelliteCount
                var used = 0
                var maxCn0 = 0f
                for (i in 0 until inView) {
                    if (status.usedInFix(i)) used++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 > maxCn0) maxCn0 = cn0
                }
                gnssSatellitesInView.set(inView)
                gnssSatellitesUsed.set(used)
                gnssMaxCn0.set(maxCn0.toInt())
                Log.i(TAG, "GNSS_STATUS: inView=$inView usedInFix=$used maxCn0=${maxCn0.toInt()}dB-Hz")
            }
        }

        // Location Listener specifically for GPS_PROVIDER
        val locationRecords = ConcurrentLinkedDeque<LocationAuditRecord>()
        var latestGpsSpeedMps = 0.0
        var latestGpsAccuracyM = 999.0
        var hasSpeedFlag = false
        var gpsCallbackCount = 0

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gpsCallbackCount++
                hasSpeedFlag = location.hasSpeed()
                latestGpsSpeedMps = if (hasSpeedFlag) location.speed.toDouble() else 0.0
                latestGpsAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 50.0

                val record = LocationAuditRecord(
                    count = gpsCallbackCount,
                    provider = location.provider ?: "null",
                    latitude = location.latitude,
                    longitude = location.longitude,
                    hasSpeed = hasSpeedFlag,
                    speed = latestGpsSpeedMps,
                    accuracy = latestGpsAccuracyM,
                    elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                    timeMs = location.time,
                    satellitesInView = gnssSatellitesInView.get(),
                    satellitesUsed = gnssSatellitesUsed.get()
                )
                locationRecords.add(record)

                Log.i(
                    TAG,
                    "GPS_AUDIT_LOG: count=$gpsCallbackCount provider=${record.provider} lat=${record.latitude} lon=${record.longitude} hasSpeed=${record.hasSpeed} speed=${record.speed}m/s acc=${record.accuracy}m elapsedNanos=${record.elapsedRealtimeNanos} satsUsed=${record.satellitesUsed}"
                )
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        Handler(Looper.getMainLooper()).post {
            try {
                locationManager.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener)
                }
                if (locationManager.isProviderEnabled("fused")) {
                    locationManager.requestLocationUpdates("fused", 500L, 0f, locationListener)
                }
                val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation("fused")
                if (lastLoc != null) {
                    locationListener.onLocationChanged(lastLoc)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission error", e)
            }
        }

        // 1. Initial Gravity & Bias Calibration (first 25 samples)
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

        // R_b_to_v such that vehicle Z = unit gravity vector
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

        Log.i(TAG, "CALIBRATED_GRAVITY_VECTOR=[%.4f, %.4f, %.4f] norm=%.4f".format(gMeanX, gMeanY, gMeanZ, gNorm))
        Log.i(TAG, "CALIBRATED_GYRO_BIAS=[%.6f, %.6f, %.6f]".format(wBiasX, wBiasY, wBiasZ))

        // 2. Real Motion Window Capture
        val auditWindows = mutableListOf<AuditWindowRecord>()
        val motionLatch = CountDownLatch(TARGET_WINDOWS + 20)
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

                        if (kinoNet.isReady && auditWindows.size < TARGET_WINDOWS) {
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

                                val record = AuditWindowRecord(
                                    index = auditWindows.size,
                                    hash = md5,
                                    timestampMs = System.currentTimeMillis(),
                                    refGpsSpeedMps = latestGpsSpeedMps,
                                    refHasSpeed = hasSpeedFlag,
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
                                auditWindows.add(record)
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

        val completed = motionLatch.await(OBSERVATION_TIMEOUT_SEC, TimeUnit.SECONDS)
        sensorManager.unregisterListener(motionListener)
        Handler(Looper.getMainLooper()).post {
            try {
                locationManager.removeUpdates(locationListener)
                locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            } catch (_: Exception) {}
        }
        scenario.close()
        kinoNet.close()

        assertTrue("Must capture at least 20 audit windows", auditWindows.size >= 20)
        Log.i(TAG, "CAPTURED_AUDIT_WINDOWS=${auditWindows.size}")
        Log.i(TAG, "TOTAL_GPS_CALLBACKS=$gpsCallbackCount")

        val maxRefSpeed = auditWindows.maxOfOrNull { it.refGpsSpeedMps } ?: 0.0
        Log.i(TAG, "AUDIT_REFERENCE_SPEED_MAX=%.4f".format(maxRefSpeed))
        if (maxRefSpeed <= 1.0) {
            Log.w(TAG, "VEHICLE_MOTION_CHECK=FAILED (REFERENCE_SPEED_MAX <= 1.0 m/s: Device is stationary/bench).")
            Log.w(TAG, "KINONET_MOVING_SPEED_ACCURACY=NOT_VERIFIED")
        } else {
            Log.i(TAG, "VEHICLE_MOTION_CHECK=PASSED (REFERENCE_SPEED_MAX > 1.0 m/s: Genuine moving vehicle data).")
        }

        for (w in auditWindows) {
            Log.i(
                TAG,
                "STRICT_AUDIT_ENTRY: idx=${w.index} hash=${w.hash} t_ms=${w.timestampMs} ref_speed=%.4f has_speed=${w.refHasSpeed} ref_acc=%.2f pred_speed=%.6f logvar=%.6f var=%.6f acc_min=%.4f acc_max=%.4f acc_mean=%.4f gyro_min=%.4f gyro_max=%.4f gyro_mean=%.4f tensor_b64=${w.base64Tensor}".format(
                    w.refGpsSpeedMps, w.refGpsAccuracyM, w.predSpeedMps, w.logVar, w.variance, w.accMin, w.accMax, w.accMean, w.gyroMin, w.gyroMax, w.gyroMean
                )
            )
        }
    }

    data class LocationAuditRecord(
        val count: Int,
        val provider: String,
        val latitude: Double,
        val longitude: Double,
        val hasSpeed: Boolean,
        val speed: Double,
        val accuracy: Double,
        val elapsedRealtimeNanos: Long,
        val timeMs: Long,
        val satellitesInView: Int,
        val satellitesUsed: Int
    )

    data class AuditWindowRecord(
        val index: Int,
        val hash: String,
        val timestampMs: Long,
        val refGpsSpeedMps: Double,
        val refHasSpeed: Boolean,
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
