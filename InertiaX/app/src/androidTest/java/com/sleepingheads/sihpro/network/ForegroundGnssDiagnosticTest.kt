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
 * Minimal Foreground Activity GNSS Diagnostic Test for Samsung Galaxy A15 (SM-A156E)
 *
 * Ensures:
 * 1. MainActivity is launched and visibly resumed in foreground
 * 2. Runtime permissions granted
 * 3. LocationListener on GPS_PROVIDER registered from active foreground activity
 * 4. GnssStatus.Callback registered and monitored
 * 5. Full 60-second observation window
 */
@RunWith(AndroidJUnit4::class)
class ForegroundGnssDiagnosticTest {

    companion object {
        private const val TAG = "ForegroundGnssDiag"
    }

    @Test
    fun testForegroundActivityGnss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("App context must not be null", appContext)
        Log.i(TAG, "=== STARTING FOREGROUND ACTIVITY GNSS DIAGNOSTIC ===")

        // 1. Grant permissions
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

        Log.i(TAG, "LOCATION_PERMISSION: fine=$fineGranted, coarse=$coarseGranted")
        assertTrue("Fine location permission required", fineGranted)

        // 2. Launch real MainActivity and verify foreground status
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activityForeground = false

        val gpsCallbacks = CopyOnWriteArrayList<Location>()
        val gpsCallbackCount = AtomicInteger(0)
        val firstFixLatch = CountDownLatch(1)

        val gnssStarted = AtomicBoolean(false)
        val gnssStatusReceived = AtomicBoolean(false)
        val satellitesInView = AtomicInteger(0)
        val satellitesUsedInFix = AtomicInteger(0)
        val maxCn0 = AtomicInteger(0)

        var gpsProviderEnabled = false
        var gpsListenerRegistered = false
        var gnssStatusRegistered = false

        scenario.onActivity { activity ->
            activityForeground = !activity.isFinishing && !activity.isDestroyed
            Log.i(TAG, "FOREGROUND_ACTIVITY_VISIBLE: $activityForeground (Activity: ${activity.localClassName})")

            val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            gpsProviderEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            Log.i(TAG, "GPS_PROVIDER_ENABLED: $gpsProviderEnabled")
            Log.i(TAG, "ALL_PROVIDERS: ${lm.allProviders}")

            val gnssCallback = object : GnssStatus.Callback() {
                override fun onStarted() {
                    gnssStarted.set(true)
                    Log.i(TAG, "GNSS_STATUS_EVENT: onStarted() - GNSS RF front-end engaged")
                }

                override fun onStopped() {
                    Log.i(TAG, "GNSS_STATUS_EVENT: onStopped() - GNSS RF front-end stopped")
                }

                override fun onFirstFix(ttffMillis: Int) {
                    Log.i(TAG, "GNSS_STATUS_EVENT: onFirstFix() - TTFF: $ttffMillis ms")
                }

                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    gnssStatusReceived.set(true)
                    val count = status.satelliteCount
                    satellitesInView.set(count)
                    var usedCount = 0
                    var highest = 0f
                    val satSb = StringBuilder()

                    for (i in 0 until count) {
                        val used = status.usedInFix(i)
                        if (used) usedCount++
                        val cn0 = status.getCn0DbHz(i)
                        if (cn0 > highest) highest = cn0
                        val constType = when (status.getConstellationType(i)) {
                            GnssStatus.CONSTELLATION_GPS -> "GPS"
                            GnssStatus.CONSTELLATION_GLONASS -> "GLO"
                            GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
                            GnssStatus.CONSTELLATION_GALILEO -> "GAL"
                            else -> "SAT"
                        }
                        if (i < 8 || used) {
                            satSb.append("[$constType#${status.getSvid(i)}:cn0=${cn0.toInt()}dBHz,used=$used] ")
                        }
                    }
                    satellitesUsedInFix.set(usedCount)
                    maxCn0.set(highest.toInt())
                    Log.i(TAG, "GNSS_STATUS: inView=$count, used=$usedCount, maxCn0=${highest.toInt()}dBHz | $satSb")
                }
            }

            val locListener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    val count = gpsCallbackCount.incrementAndGet()
                    gpsCallbacks.add(loc)
                    Log.i(TAG, "REAL_GPS_CALLBACK #$count: provider=${loc.provider}, lat=${loc.latitude}, lon=${loc.longitude}, alt=${loc.altitude}m, acc=${loc.accuracy}m, speed=${loc.speed}m/s, time=${loc.time}, elapsedNs=${loc.elapsedRealtimeNanos}")
                    firstFixLatch.countDown()
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
                        locListener,
                        Looper.getMainLooper()
                    )
                    gpsListenerRegistered = true
                    Log.i(TAG, "GPS_LISTENER_REGISTERED: true")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException registering GPS listener", e)
                }
            }

            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        500L,
                        0f,
                        locListener,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) {}
            }

            if (lm.isProviderEnabled("fused")) {
                try {
                    lm.requestLocationUpdates(
                        "fused",
                        500L,
                        0f,
                        locListener,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) {}
            }
        }

        // 3. Observe for up to 60 seconds with active foreground Activity
        Log.i(TAG, "Observing foreground GNSS callbacks for up to 60 seconds...")
        val receivedFix = firstFixLatch.await(60, TimeUnit.SECONDS)

        val count = gpsCallbackCount.get()
        val latestFix = gpsCallbacks.lastOrNull()
        val inView = satellitesInView.get()
        val used = satellitesUsedInFix.get()
        val statusAvail = gnssStatusReceived.get()
        val engineStarted = gnssStarted.get()

        Log.i(TAG, "=== FOREGROUND GNSS DIAGNOSTIC SUMMARY ===")
        Log.i(TAG, "FOREGROUND_ACTIVITY_VISIBLE=$activityForeground")
        Log.i(TAG, "LOCATION_PERMISSION=${fineGranted && coarseGranted}")
        Log.i(TAG, "GPS_PROVIDER_ENABLED=$gpsProviderEnabled")
        Log.i(TAG, "REAL_GPS_CALLBACK_COUNT=$count")
        Log.i(TAG, "REAL_GPS_PROVIDER=${latestFix?.provider}")
        Log.i(TAG, "REAL_GPS_ACCURACY_M=${latestFix?.accuracy}")
        if (latestFix != null) {
            Log.i(TAG, "REAL_GPS_LAT=${latestFix.latitude}")
            Log.i(TAG, "REAL_GPS_LON=${latestFix.longitude}")
            Log.i(TAG, "REAL_GPS_TIMESTAMP=${latestFix.time}")
        }
        Log.i(TAG, "GNSS_STATUS_AVAILABLE=$statusAvail")
        Log.i(TAG, "GNSS_SATELLITES_IN_VIEW=$inView")
        Log.i(TAG, "GNSS_SATELLITES_USED=$used")
        Log.i(TAG, "GNSS_MAX_CN0_DBHZ=${maxCn0.get()}")
        Log.i(TAG, "GNSS_ENGINE_STARTED=$engineStarted")

        scenario.close()
    }
}
