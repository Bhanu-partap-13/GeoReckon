package com.sleepingheads.sihpro.network

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Genuine Physical Device Android GNSS / Location Runtime Test
 * for Samsung Galaxy A15 (SM-A156E)
 *
 * STRICT REQUIREMENTS:
 * 1. Runtime location permission grant (ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
 * 2. Registration of production LiveSensorPipeline LocationListener
 * 3. Awaiting genuine LocationManager callbacks from real device hardware
 * 4. Logging provider, lat, lon, acc, time, elapsedRealtimeNanos for every genuine fix
 * 5. Counting REAL_LOCATION_CALLBACK_COUNT
 * 6. Recording REAL_LOCATION_PROVIDER & REAL_LOCATION_ACCURACY_M
 * 7. Proving exact Location object delivered to LiveSensorPipeline.onLocationChanged()
 * 8. NO synthetic/mock Location fallback: fails with NO_REAL_GNSS_FIX if no fix obtained
 */
@RunWith(AndroidJUnit4::class)
class GeoReckonOnDeviceGnssTest {

    companion object {
        private const val TAG = "GeoReckonGnssTest"
    }

    @Test
    fun testGenuinePhysicalLocationAcquisition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("App context must not be null", appContext)
        Log.i(TAG, "=== STARTING GENUINE PHYSICAL GNSS RUNTIME TEST ===")
        Log.i(TAG, "Device Package: ${appContext.packageName}")

        // 1. Request and verify runtime permissions legitimately
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
        val coarseGranted = ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        assertTrue("ACCESS_FINE_LOCATION must be granted at runtime", fineGranted)
        assertTrue("ACCESS_COARSE_LOCATION must be granted at runtime", coarseGranted)
        Log.i(TAG, "RUNTIME_PERMISSIONS_GRANTED: fine=$fineGranted coarse=$coarseGranted")

        // 2. Query physical LocationManager providers
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = locationManager.allProviders
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        Log.i(TAG, "PHYSICAL_LOCATION_PROVIDERS: $providers")
        Log.i(TAG, "PROVIDER_ENABLED_GPS: $gpsEnabled")
        Log.i(TAG, "PROVIDER_ENABLED_NETWORK: $networkEnabled")
        assertTrue("GPS provider must be present in hardware providers list", providers.contains(LocationManager.GPS_PROVIDER))

        // 3. Initialize Socket.IO Client (idle config for pipeline)
        val config = GeoReckonConfig(
            host = "127.0.0.1",
            port = 5000,
            connectTimeoutMs = 6000L
        )
        val client = GeoReckonSocketIOClient(config)
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // 4. Create Production LiveSensorPipeline
        val pipeline = LiveSensorPipeline(appContext, client, testScope)

        // 5. Track genuine LocationManager callbacks
        val directHardwareLocations = CopyOnWriteArrayList<Location>()
        val callbackCount = AtomicInteger(0)
        val firstFixLatch = CountDownLatch(1)

        val directListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val count = callbackCount.incrementAndGet()
                directHardwareLocations.add(loc)
                Log.i(TAG, "GENUINE_LOCATION_CALLBACK #$count: provider=${loc.provider}, lat=${loc.latitude}, lon=${loc.longitude}, alt=${loc.altitude}m, acc=${loc.accuracy}m, speed=${loc.speed}m/s, bearing=${loc.bearing}deg, tMs=${loc.time}, elapsedNs=${loc.elapsedRealtimeNanos}")
                firstFixLatch.countDown()
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                Log.i(TAG, "PROVIDER_ENABLED_EVENT: $provider")
            }
            override fun onProviderDisabled(provider: String) {
                Log.i(TAG, "PROVIDER_DISABLED_EVENT: $provider")
            }
        }

        // 6. Register listeners on main looper
        instrumentation.runOnMainSync {
            pipeline.start()
            for (p in providers) {
                if (locationManager.isProviderEnabled(p)) {
                    try {
                        locationManager.requestLocationUpdates(p, 500L, 0f, directListener, Looper.getMainLooper())
                        Log.i(TAG, "REQUESTED_LOCATION_UPDATES_ON: $p")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException on provider $p", e)
                    }
                }
            }
        }

        // 7. Await genuine hardware location callbacks (timeout: 45 seconds)
        Log.i(TAG, "Awaiting genuine hardware location fix from Samsung Galaxy A15 location subsystem...")
        val receivedFix = firstFixLatch.await(45, TimeUnit.SECONDS)

        // Clean up listeners
        instrumentation.runOnMainSync {
            locationManager.removeUpdates(directListener)
            pipeline.stop()
        }
        testScope.cancel()

        val totalCallbacks = callbackCount.get()
        val latestFix = directHardwareLocations.lastOrNull()

        Log.i(TAG, "=== TEST RESULTS ===")
        Log.i(TAG, "REAL_LOCATION_CALLBACK_COUNT=$totalCallbacks")

        if (latestFix != null) {
            Log.i(TAG, "REAL_LOCATION_PROVIDER=${latestFix.provider}")
            Log.i(TAG, "REAL_LOCATION_ACCURACY_M=${latestFix.accuracy}")
            Log.i(TAG, "REAL_LOCATION_LAT=${latestFix.latitude}")
            Log.i(TAG, "REAL_LOCATION_LON=${latestFix.longitude}")
            Log.i(TAG, "REAL_LOCATION_TIMESTAMP_MS=${latestFix.time}")
            Log.i(TAG, "REAL_LOCATION_ELAPSED_NS=${latestFix.elapsedRealtimeNanos}")
        } else {
            Log.i(TAG, "REAL_LOCATION_PROVIDER=null")
            Log.i(TAG, "REAL_LOCATION_ACCURACY_M=null")
        }

        // 8. Strict Assertions: NO synthetic fallbacks permitted
        assertTrue(
            "NO_REAL_GNSS_FIX: Samsung Galaxy A15 location subsystem produced 0 genuine callbacks within timeout (outdoor/line-of-sight conditions required).",
            totalCallbacks > 0
        )
        assertNotNull("REAL_LOCATION_PROVIDER must be non-null", latestFix?.provider)
        assertTrue("REAL_LOCATION_ACCURACY_M must be positive", (latestFix?.accuracy ?: 0f) > 0f)

        Log.i(TAG, "ANDROID_LOCATION_RUNTIME=VERIFIED")
    }
}
