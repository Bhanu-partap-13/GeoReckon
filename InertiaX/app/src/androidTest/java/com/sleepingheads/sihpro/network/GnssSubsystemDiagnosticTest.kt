package com.sleepingheads.sihpro.network

import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Standalone Diagnostic Test for Android GNSS Subsystem on Samsung Galaxy A15 (SM-A156E)
 *
 * Diagnoses:
 * 1. Runtime location permissions (FINE & COARSE)
 * 2. LocationManager service and provider enablement
 * 3. GnssStatus callback: satellites in view, satellites used in fix, C/N0 SNR
 * 4. LocationListener callback delivery on GPS_PROVIDER and other providers
 */
@RunWith(AndroidJUnit4::class)
class GnssSubsystemDiagnosticTest {

    companion object {
        private const val TAG = "GnssDiagnosticTest"
    }

    @Test
    fun diagnoseLocationSubsystem() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("App context must not be null", appContext)
        Log.i(TAG, "=== STARTING ANDROID GNSS SUBSYSTEM DIAGNOSTIC ===")

        // 1. Check & Grant Runtime Permissions
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

        Log.i(TAG, "DIAG_PERMISSION_FINE: $fineGranted")
        Log.i(TAG, "DIAG_PERMISSION_COARSE: $coarseGranted")
        assertTrue("ACCESS_FINE_LOCATION required for GNSS diagnosis", fineGranted)

        // 2. Check LocationManager & Providers
        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        assertNotNull("LocationManager must be non-null", locationManager)
        val lm = locationManager!!

        val allProviders = lm.allProviders
        val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val fusedEnabled = lm.isProviderEnabled("fused")
        val passiveEnabled = lm.isProviderEnabled("passive")

        Log.i(TAG, "DIAG_ALL_PROVIDERS: $allProviders")
        Log.i(TAG, "DIAG_GPS_ENABLED: $gpsEnabled")
        Log.i(TAG, "DIAG_NETWORK_ENABLED: $networkEnabled")
        Log.i(TAG, "DIAG_FUSED_ENABLED: $fusedEnabled")
        Log.i(TAG, "DIAG_PASSIVE_ENABLED: $passiveEnabled")

        // 3. Register GnssStatus.Callback to inspect satellite tracking
        val gnssStatusReceived = AtomicBoolean(false)
        val satellitesInView = AtomicInteger(0)
        val satellitesUsedInFix = AtomicInteger(0)
        val maxCn0 = AtomicInteger(0)
        val gnssStarted = AtomicBoolean(false)

        val gnssCallback = object : GnssStatus.Callback() {
            override fun onStarted() {
                gnssStarted.set(true)
                Log.i(TAG, "GNSS_STATUS_EVENT: onStarted() - GNSS engine hardware started")
            }

            override fun onStopped() {
                Log.i(TAG, "GNSS_STATUS_EVENT: onStopped() - GNSS engine hardware stopped")
            }

            override fun onFirstFix(ttffMillis: Int) {
                Log.i(TAG, "GNSS_STATUS_EVENT: onFirstFix() - TTFF = $ttffMillis ms")
            }

            override fun onSatelliteStatusChanged(status: GnssStatus) {
                gnssStatusReceived.set(true)
                val count = status.satelliteCount
                satellitesInView.set(count)
                var usedCount = 0
                var highestCn0 = 0f

                val satDetails = StringBuilder()
                for (i in 0 until count) {
                    val used = status.usedInFix(i)
                    if (used) usedCount++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 > highestCn0) highestCn0 = cn0
                    val constType = when (status.getConstellationType(i)) {
                        GnssStatus.CONSTELLATION_GPS -> "GPS"
                        GnssStatus.CONSTELLATION_GLONASS -> "GLO"
                        GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
                        GnssStatus.CONSTELLATION_GALILEO -> "GAL"
                        GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                        GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                        else -> "UNK"
                    }
                    if (i < 10 || used) {
                        satDetails.append("[$constType#${status.getSvid(i)}:cn0=${cn0.toInt()}dBHz,used=$used] ")
                    }
                }
                satellitesUsedInFix.set(usedCount)
                maxCn0.set(highestCn0.toInt())

                Log.i(TAG, "GNSS_SATELLITE_STATUS: inView=$count, usedInFix=$usedCount, maxCn0=${highestCn0.toInt()}dBHz | $satDetails")
            }
        }

        // 4. Register LocationListener for GPS_PROVIDER and FUSED/NETWORK
        val gpsCallbacks = CopyOnWriteArrayList<Location>()
        val gpsCallbackCount = AtomicInteger(0)
        val listenerRegistered = AtomicBoolean(false)

        val locationListener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val c = gpsCallbackCount.incrementAndGet()
                gpsCallbacks.add(loc)
                Log.i(TAG, "DIAG_GENUINE_LOCATION #$c: provider=${loc.provider}, lat=${loc.latitude}, lon=${loc.longitude}, alt=${loc.altitude}m, acc=${loc.accuracy}m, speed=${loc.speed}m/s, time=${loc.time}, elapsedNs=${loc.elapsedRealtimeNanos}")
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                Log.i(TAG, "DIAG_PROVIDER_STATUS_CHANGED: provider=$provider status=$status")
            }

            override fun onProviderEnabled(provider: String) {
                Log.i(TAG, "DIAG_PROVIDER_ENABLED: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.i(TAG, "DIAG_PROVIDER_DISABLED: $provider")
            }
        }

        val handler = Handler(Looper.getMainLooper())

        instrumentation.runOnMainSync {
            try {
                val gnssRegistered = lm.registerGnssStatusCallback(gnssCallback, handler)
                Log.i(TAG, "DIAG_GNSS_STATUS_CALLBACK_REGISTERED: $gnssRegistered")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException registering GnssStatus", e)
            }

            if (gpsEnabled) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L,
                        0f,
                        locationListener,
                        Looper.getMainLooper()
                    )
                    listenerRegistered.set(true)
                    Log.i(TAG, "DIAG_GPS_LISTENER_REGISTERED: true")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException registering GPS listener", e)
                }
            }

            if (networkEnabled) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        500L,
                        0f,
                        locationListener,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) {}
            }

            if (fusedEnabled) {
                try {
                    lm.requestLocationUpdates(
                        "fused",
                        500L,
                        0f,
                        locationListener,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) {}
            }
        }

        // 5. Run diagnostic observation window (30 seconds)
        Log.i(TAG, "Running diagnostic observation window (30s)...")
        val deadline = System.currentTimeMillis() + 30000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            if (gpsCallbackCount.get() > 0) {
                Log.i(TAG, "Diagnostic received first location callback! Continuing observation...")
            }
        }

        // 6. Cleanup
        instrumentation.runOnMainSync {
            lm.unregisterGnssStatusCallback(gnssCallback)
            lm.removeUpdates(locationListener)
        }

        val finalCount = gpsCallbackCount.get()
        val latestFix = gpsCallbacks.lastOrNull()
        val inView = satellitesInView.get()
        val used = satellitesUsedInFix.get()
        val statusAvailable = gnssStatusReceived.get()

        Log.i(TAG, "=== DIAGNOSTIC SUMMARY ===")
        Log.i(TAG, "LOCATION_PERMISSION=${fineGranted && coarseGranted}")
        Log.i(TAG, "GPS_PROVIDER_ENABLED=$gpsEnabled")
        Log.i(TAG, "GPS_LISTENER_REGISTERED=${listenerRegistered.get()}")
        Log.i(TAG, "REAL_GPS_CALLBACK_COUNT=$finalCount")
        Log.i(TAG, "REAL_GPS_PROVIDER=${latestFix?.provider}")
        Log.i(TAG, "REAL_GPS_ACCURACY_M=${latestFix?.accuracy}")
        Log.i(TAG, "GNSS_STATUS_AVAILABLE=$statusAvailable")
        Log.i(TAG, "GNSS_SATELLITES_IN_VIEW=$inView")
        Log.i(TAG, "GNSS_SATELLITES_USED=$used")
        Log.i(TAG, "GNSS_MAX_CN0_DBHZ=${maxCn0.get()}")
        Log.i(TAG, "GNSS_ENGINE_STARTED=${gnssStarted.get()}")
    }
}
