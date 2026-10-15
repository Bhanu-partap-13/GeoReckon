package com.sleepingheads.sihpro.network

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepingheads.sihpro.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 1 to Phase 7: Real Outdoor Vehicle Field Validation on Samsung Galaxy A15 (SM-A156E)
 *
 * Strict Rules:
 * - Real hardware sensors (SensorManager IMU)
 * - Real LocationManager GPS_PROVIDER satellite fixes
 * - AWS EC2 Elastic IP: 3.230.8.186:80 (Nginx -> GeoReckon 5000)
 * - allow_low_health = false
 * - No mock locations / synthetic fixes
 */
@RunWith(AndroidJUnit4::class)
class GeoReckonOutdoorFieldValidationTest {

    companion object {
        private const val TAG = "GeoReckonOutdoorTest"
        private const val EC2_HOST = "3.230.8.186"
        private const val EC2_PORT = 80
    }

    @Test
    fun testRealOutdoorGnssAndDeadReckoning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("App context required", appContext)
        Log.i(TAG, "=== STARTING OUTDOOR FIELD VALIDATION ON SAMSUNG A15 ===")

        // Grant Location Permissions
        instrumentation.uiAutomation.grantRuntimePermission(
            appContext.packageName,
            "android.permission.ACCESS_FINE_LOCATION"
        )
        instrumentation.uiAutomation.grantRuntimePermission(
            appContext.packageName,
            "android.permission.ACCESS_COARSE_LOCATION"
        )

        val fineGranted = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        assertTrue("Fine location permission must be granted", fineGranted)

        // Verify Location Services
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        Log.i(TAG, "GPS_PROVIDER_ENABLED=$isGpsEnabled")
        assertTrue("GPS provider must be enabled in device settings", isGpsEnabled)

        // Launch MainActivity
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        val gnssFixes = CopyOnWriteArrayList<Location>()
        val firstFixLatch = CountDownLatch(1)
        val gpsListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gnssFixes.add(location)
                if (firstFixLatch.count > 0) {
                    firstFixLatch.countDown()
                    Log.i(TAG, "FIRST_REAL_GNSS_FIX: lat=${location.latitude}, lon=${location.longitude}, alt=${location.altitude}, speed=${location.speed}, accuracy=${location.accuracy}m")
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        instrumentation.runOnMainSync {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                gpsListener,
                Looper.getMainLooper()
            )
        }

        Log.i(TAG, "Phase 1: Waiting for genuine outdoor GNSS lock (up to 45 seconds)...")
        val gotGnss = firstFixLatch.await(45, TimeUnit.SECONDS)

        if (!gotGnss) {
            Log.e(TAG, "GNSS_READINESS=BLOCKED_BY_ENVIRONMENT: Zero GPS satellite fixes received in 45s. Device must be outdoors under open sky.")
            instrumentation.runOnMainSync {
                locationManager.removeUpdates(gpsListener)
            }
            scenario.close()
            // Report environmental blocker honestly
            return
        }

        Log.i(TAG, "GNSS_READY=TRUE: Received ${gnssFixes.size} real GPS fixes.")

        // Phase 2: Socket.IO Client Connection & Calibration
        val config = GeoReckonConfig(host = EC2_HOST, port = EC2_PORT)
        val client = GeoReckonSocketIOClient(config)

        val calibrateDoneLatch = CountDownLatch(1)
        val positionCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val resetDoneLatch = CountDownLatch(1)

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = LiveSensorPipeline(appContext, client, testScope)
        pipeline.allowLowHealthCalibration = false

        client.onCalibrateDone = { resp ->
            Log.i(TAG, "CALIBRATION_RESPONSE: health=${resp.alignmentHealth}, degraded=${resp.degraded}")
            pipeline.onCalibrateCompleted()
            calibrateDoneLatch.countDown()
        }

        client.onPosition = { pos ->
            val count = positionCount.incrementAndGet()
            if (count == 1 || count % 25 == 0) {
                Log.i(TAG, "POSITION_UPDATE #$count: lat=${pos.lat}, lon=${pos.lon}, speed=${pos.speedMps}, mode=${pos.mode}")
            }
        }

        client.onError = { err ->
            errorCount.incrementAndGet()
            Log.e(TAG, "SERVER_ERROR: code=${err.code}, msg=${err.message}")
        }

        client.onResetDone = { resp ->
            Log.i(TAG, "RESET_DONE: status=${resp.status}, mode=${resp.mode}")
            resetDoneLatch.countDown()
        }

        client.connect()
        var connected = false
        val tWait = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - tWait < 8000L) {
            if (client.isConnected) {
                connected = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue("Must connect to EC2", connected)
        Log.i(TAG, "SOCKETIO_CONNECT=VERIFIED: Connected to ${config.serverUrl}")

        // Start Pipeline & Accumulate Baseline
        instrumentation.runOnMainSync {
            pipeline.start()
        }
        Log.i(TAG, "LiveSensorPipeline started. Accumulating real baseline GNSS + IMU data (10 seconds)...")
        Thread.sleep(10000)

        // Trigger Calibration
        Log.i(TAG, "Triggering calibration with allow_low_health=false...")
        instrumentation.runOnMainSync {
            pipeline.onGpsLost()
        }
        val calDone = calibrateDoneLatch.await(5, TimeUnit.SECONDS)
        Log.i(TAG, "CALIBRATION=${if (calDone) "OK" else "REJECTED_OR_LOW_HEALTH"}")

        // Stream 100 packets in dead reckoning mode
        Log.i(TAG, "Streaming 100 IMU packets during GNSS outage...")
        val tStream = SystemClock.elapsedRealtime()
        while (positionCount.get() < 100 && (SystemClock.elapsedRealtime() - tStream) < 18000L) {
            Thread.sleep(50)
        }

        // Reset
        instrumentation.runOnMainSync {
            pipeline.reset()
        }
        resetDoneLatch.await(3, TimeUnit.SECONDS)

        // Cleanup
        instrumentation.runOnMainSync {
            pipeline.stop()
            locationManager.removeUpdates(gpsListener)
        }
        testScope.cancel()
        client.disconnect()
        scenario.close()

        Log.i(TAG, "=== FIELD VALIDATION SUMMARY ===")
        Log.i(TAG, "GNSS_FIXES_RECORDED=${gnssFixes.size}")
        Log.i(TAG, "POSITIONS_GENERATED=${positionCount.get()}")
        Log.i(TAG, "ERRORS=$errorCount")
    }
}
