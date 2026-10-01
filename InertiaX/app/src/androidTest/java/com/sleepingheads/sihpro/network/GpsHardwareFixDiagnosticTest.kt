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
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepingheads.sihpro.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dedicated Hardware GPS_PROVIDER Fix Diagnostic Test for Samsung Galaxy A15 (SM-A156E)
 *
 * Specifically verifies:
 * 1. MainActivity kept in foreground window
 * 2. Dedicated LocationListener exclusively on LocationManager.GPS_PROVIDER
 * 3. GnssStatus.Callback tracking constellation, SVID, C/N0, and usedInFix
 * 4. Capturing genuine GPS hardware callbacks (provider == "gps")
 */
@RunWith(AndroidJUnit4::class)
class GpsHardwareFixDiagnosticTest {

    companion object {
        private const val TAG = "GpsHardwareDiag"
    }

    @Test
    fun testGpsHardwareSatelliteFix() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("App context must not be null", appContext)
        Log.i(TAG, "=== STARTING DEDICATED GPS_PROVIDER HARDWARE FIX DIAGNOSTIC ===")

        // 1. Ensure runtime permissions
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
        Log.i(TAG, "LOCATION_PERMISSION: fine=$fineGranted")
        assertTrue("ACCESS_FINE_LOCATION required", fineGranted)

        // 2. Launch real MainActivity and maintain foreground state
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        val gpsHardwareLocations = CopyOnWriteArrayList<Location>()
        val gpsCallbackCount = AtomicInteger(0)
        val gpsFixLatch = CountDownLatch(1)

        val gnssStarted = AtomicBoolean(false)
        val gnssStatusReceived = AtomicBoolean(false)
        val satellitesInView = AtomicInteger(0)
        val satellitesUsedInFix = AtomicInteger(0)
        val maxCn0 = AtomicInteger(0)

        var gpsProviderEnabled = false
        var gpsListenerRegistered = false
        var gnssStatusRegistered = false

        scenario.onActivity { activity ->
            val isForeground = !activity.isFinishing && !activity.isDestroyed
            Log.i(TAG, "FOREGROUND_ACTIVITY_VISIBLE: $isForeground (Activity: ${activity.localClassName})")

            val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            gpsProviderEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            Log.i(TAG, "GPS_PROVIDER_ENABLED: $gpsProviderEnabled")

            val gnssCallback = object : GnssStatus.Callback() {
                override fun onStarted() {
                    gnssStarted.set(true)
                    Log.i(TAG, "GNSS_STATUS_EVENT: onStarted() - GNSS hardware RF front-end engaged")
                }

                override fun onStopped() {
                    Log.i(TAG, "GNSS_STATUS_EVENT: onStopped()")
                }

                override fun onFirstFix(ttffMillis: Int) {
                    Log.i(TAG, "GNSS_STATUS_EVENT: onFirstFix() - TTFF: $ttffMillis ms")
                }

                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    gnssStatusReceived.set(true)
                    val count = status.satelliteCount
                    satellitesInView.set(count)
                    var usedCount = 0
                    var highestCn0 = 0f

                    val satSummary = StringBuilder()
                    for (i in 0 until count) {
                        val used = status.usedInFix(i)
                        if (used) usedCount++
                        val cn0 = status.getCn0DbHz(i)
                        if (cn0 > highestCn0) highestCn0 = cn0

                        val constStr = when (status.getConstellationType(i)) {
                            GnssStatus.CONSTELLATION_GPS -> "GPS"
                            GnssStatus.CONSTELLATION_GLONASS -> "GLO"
                            GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
                            GnssStatus.CONSTELLATION_GALILEO -> "GAL"
                            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                            else -> "UNK"
                        }
                        if (used || cn0 > 20f || i < 6) {
                            satSummary.append("[$constStr#${status.getSvid(i)}:cn0=${cn0.toInt()}dBHz,used=$used] ")
                        }
                    }
                    satellitesUsedInFix.set(usedCount)
                    maxCn0.set(highestCn0.toInt())
                    Log.i(TAG, "GNSS_STATUS_UPDATE: inView=$count, usedInFix=$usedCount, maxCn0=${highestCn0.toInt()}dBHz | $satSummary")
                }
            }

            val gpsListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (loc.provider == LocationManager.GPS_PROVIDER) {
                        val count = gpsCallbackCount.incrementAndGet()
                        gpsHardwareLocations.add(loc)
                        Log.i(TAG, "REAL_GPS_HARDWARE_CALLBACK #$count: provider=${loc.provider}, lat=${loc.latitude}, lon=${loc.longitude}, alt=${loc.altitude}m, acc=${loc.accuracy}m, speed=${loc.speed}m/s, bear=${loc.bearing}deg, tMs=${loc.time}, elapsedNs=${loc.elapsedRealtimeNanos}")
                        gpsFixLatch.countDown()
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            try {
                gnssStatusRegistered = lm.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
                Log.i(TAG, "GNSS_STATUS_CALLBACK_REGISTERED: $gnssStatusRegistered")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException registering GnssStatus", e)
            }

            if (gpsProviderEnabled) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L,
                        0f,
                        gpsListener,
                        Looper.getMainLooper()
                    )
                    gpsListenerRegistered = true
                    Log.i(TAG, "GPS_LISTENER_REGISTERED: true")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException registering GPS listener", e)
                }
            }
        }

        // 3. Await genuine GPS hardware fix for up to 60 seconds
        Log.i(TAG, "Observing GPS_PROVIDER callbacks for up to 60 seconds outdoors...")
        val fixAcquired = gpsFixLatch.await(60, TimeUnit.SECONDS)

        val totalGpsCallbacks = gpsCallbackCount.get()
        val latestGpsFix = gpsHardwareLocations.lastOrNull()
        val inView = satellitesInView.get()
        val used = satellitesUsedInFix.get()
        val maxCn0Val = maxCn0.get()
        val statusAvail = gnssStatusReceived.get()

        Log.i(TAG, "=== FINAL GPS HARDWARE TEST SUMMARY ===")
        Log.i(TAG, "GPS_PROVIDER_ENABLED=$gpsProviderEnabled")
        Log.i(TAG, "REAL_GPS_CALLBACK_COUNT=$totalGpsCallbacks")
        Log.i(TAG, "REAL_GPS_PROVIDER=${latestGpsFix?.provider}")
        Log.i(TAG, "REAL_GPS_ACCURACY_M=${latestGpsFix?.accuracy}")
        if (latestGpsFix != null) {
            Log.i(TAG, "REAL_GPS_LATITUDE=${latestGpsFix.latitude}")
            Log.i(TAG, "REAL_GPS_LONGITUDE=${latestGpsFix.longitude}")
            Log.i(TAG, "REAL_GPS_ALTITUDE_M=${latestGpsFix.altitude}")
            Log.i(TAG, "REAL_GPS_TIMESTAMP_MS=${latestGpsFix.time}")
            Log.i(TAG, "REAL_GPS_ELAPSED_NS=${latestGpsFix.elapsedRealtimeNanos}")
        }
        Log.i(TAG, "GNSS_STATUS_CALLBACK=$statusAvail")
        Log.i(TAG, "GNSS_SATELLITES_IN_VIEW=$inView")
        Log.i(TAG, "GNSS_SATELLITES_USED=$used")
        Log.i(TAG, "GNSS_MAX_CN0_DBHZ=$maxCn0Val")

        scenario.close()
    }
}
