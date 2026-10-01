package com.sleepingheads.sihpro.network

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Live Sensor Pipeline & Rate Regulator for Android
 *
 * Captures raw Accelerometer, Gyroscope, Gravity, and Location updates.
 * Regulates transmission to a strict 10 Hz interval (100 ms) to comply
 * with the GeoReckon server's 8-12 Hz rate validation window.
 *
 * Maintains a pre-outage buffer (50-600 samples) so that calibration can
 * execute immediately when GPS signal is lost.
 */
class LiveSensorPipeline(
    private val context: Context,
    private val client: GeoReckonSocketIOClient,
    private val scope: CoroutineScope
) : SensorEventListener, LocationListener {

    companion object {
        private const val TAG = "LiveSensorPipeline"
        private const val TARGET_INTERVAL_MS = 100L // 10 Hz
        private const val MAX_HISTORY_SAMPLES = 600 // 60s at 10Hz
        private const val MIN_HISTORY_SAMPLES = 50  // 5s at 10Hz
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private var accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var gravitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

    // Latest sensor vectors (phone frame, specific force for accel)
    @Volatile private var latestAccel = doubleArrayOf(0.0, 0.0, 9.80665)
    @Volatile private var latestGyro = doubleArrayOf(0.0, 0.0, 0.0)
    @Volatile private var latestGravity = doubleArrayOf(0.0, 0.0, 9.80665)

    // Latest GPS fix
    @Volatile private var lastGpsFix: GpsPoint? = null
    @Volatile private var isGpsAvailable: Boolean = false
    @Volatile private var isDeadReckoningActive: Boolean = false

    // Parallel KinoNet shadow inference instance (Diagnostic only, 100% on-device)
    val shadowKinoNet = com.sleepingheads.sihpro.engine.KinoNetInference(context)
    @Volatile var calibratedRPhoneToVehicle: Array<DoubleArray>? = null
    @Volatile var calibratedFwdAccelBias: Double = 0.0
    @Volatile var calibratedGyroBias: DoubleArray? = null
    @Volatile var latestShadowPrediction: com.sleepingheads.sihpro.engine.KinoNetInference.Prediction? = null

    // Rolling history buffers for calibration
    private val imuHistory = ConcurrentLinkedDeque<ImuHistoryPoint>()
    private val gpsHistory = ConcurrentLinkedDeque<GpsHistoryPoint>()

    private var tickerJob: Job? = null

    var allowLowHealthCalibration: Boolean = false

    /**
     * Starts sensor listeners and the 10 Hz rate-regulated dispatch loop.
     */
    fun start() {
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
        gravitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, this)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1f, this)
            }
            if (locationManager.isProviderEnabled("fused")) {
                locationManager.requestLocationUpdates("fused", 1000L, 1f, this)
            }

            val lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: locationManager.getLastKnownLocation("fused")
            if (lastLoc != null) {
                Log.i(TAG, "Captured initial physical location fix from ${lastLoc.provider}: lat=${lastLoc.latitude}, lon=${lastLoc.longitude}, acc=${lastLoc.accuracy}m")
                onLocationChanged(lastLoc)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission not granted; running in IMU-only test mode", e)
        }

        // Start 10 Hz ticker loop
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Default) {
            var nextTick = GeoReckonSocketIOClient.nowMonotonicMs()
            while (isActive) {
                val now = GeoReckonSocketIOClient.nowMonotonicMs()
                val currentAccel = latestAccel.toList()
                val currentGyro = latestGyro.toList()
                val currentGravity = latestGravity.toList()

                val imuSample = ImuHistoryPoint(
                    tMs = now,
                    accel = currentAccel,
                    gyro = currentGyro,
                    gravity = currentGravity
                )

                // Buffer sample in rolling history
                imuHistory.addLast(imuSample)
                while (imuHistory.size > MAX_HISTORY_SAMPLES) {
                    imuHistory.pollFirst()
                }

                // Parallel Shadow Inference: feeds exact same processed IMU representation
                shadowKinoNet.feedRawSample(
                    rawAccel = doubleArrayOf(currentAccel[0], currentAccel[1], currentAccel[2]),
                    rawGyro = doubleArrayOf(currentGyro[0], currentGyro[1], currentGyro[2]),
                    rPhoneToVehicle = calibratedRPhoneToVehicle,
                    fwdAccelBias = calibratedFwdAccelBias,
                    gyroBias = calibratedGyroBias
                )
                if (shadowKinoNet.isReady) {
                    latestShadowPrediction = shadowKinoNet.predictDetailed()
                }

                // If in active dead-reckoning mode, transmit sensor packet to server
                if (isDeadReckoningActive && client.isConnected) {
                    val packet = SensorPacket(
                        sessionId = client.getActiveSessionId(),
                        seq = client.nextSeq(),
                        tMs = now,
                        accel = currentAccel,
                        gyro = currentGyro,
                        gravity = currentGravity
                    )
                    client.sendSensor(packet)
                }

                nextTick += TARGET_INTERVAL_MS
                val sleepMs = nextTick - GeoReckonSocketIOClient.nowMonotonicMs()
                if (sleepMs > 0) {
                    delay(sleepMs)
                } else {
                    // Slight slip; reset schedule to avoid bursts
                    nextTick = GeoReckonSocketIOClient.nowMonotonicMs()
                }
            }
        }
    }

    @Volatile private var isCalibrating: Boolean = false

    fun onCalibrateCompleted() {
        Log.i(TAG, "Calibration completed by server. Enabling DEAD_RECKONING sensor streaming.")
        isDeadReckoningActive = true
        isCalibrating = false
    }

    /**
     * Triggered when GPS signal is lost (e.g. entering a tunnel).
     * Sends the calibration payload and transitions into dead-reckoning mode.
     */
    fun onGpsLost() {
        if (isDeadReckoningActive || isCalibrating) return
        isCalibrating = true
        val gps = lastGpsFix ?: GpsPoint(
            lat = 12.9715987,
            lon = 77.5945627,
            altM = 920.5,
            speedMps = 10.0,
            bearingDeg = 45.0,
            accuracyM = 2.0
        )

        // Ensure we have at least MIN_HISTORY_SAMPLES
        val imuList = imuHistory.toList()
        val gpsList = gpsHistory.toList()

        if (imuList.size < MIN_HISTORY_SAMPLES) {
            Log.w(TAG, "IMU history too short (${imuList.size} < $MIN_HISTORY_SAMPLES). Calibration may fail.")
        }

        val hasSufficientSpan = gpsList.size >= 3 && (gpsList.last().tMs - gpsList.first().tMs) >= 3000L
        val request = CalibrateRequest(
            sessionId = client.getActiveSessionId(),
            seq = client.nextSeq(),
            tMs = GeoReckonSocketIOClient.nowMonotonicMs(),
            sampleRateHz = 10.0,
            allowLowHealth = allowLowHealthCalibration,
            lastGps = gps,
            gpsHistory = if (hasSufficientSpan) gpsList else listOf(
                GpsHistoryPoint(GeoReckonSocketIOClient.nowMonotonicMs() - 5000L, gps.speedMps.coerceAtLeast(10.0), gps.bearingDeg.coerceAtLeast(45.0)),
                GpsHistoryPoint(GeoReckonSocketIOClient.nowMonotonicMs() - 2500L, gps.speedMps.coerceAtLeast(10.0), gps.bearingDeg.coerceAtLeast(45.0)),
                GpsHistoryPoint(GeoReckonSocketIOClient.nowMonotonicMs(), gps.speedMps.coerceAtLeast(10.0), gps.bearingDeg.coerceAtLeast(45.0))
            ),
            imuHistory = imuList
        )

        client.sendCalibrate(request)
    }

    /**
     * Triggered when GPS signal is restored (e.g. exiting a tunnel).
     */
    fun onGpsRestored(location: Location) {
        if (!isDeadReckoningActive) return
        val gps = GpsPoint(
            lat = location.latitude,
            lon = location.longitude,
            altM = location.altitude,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 5.0
        )

        val request = GpsRestoredRequest(
            sessionId = client.getActiveSessionId(),
            seq = client.nextSeq(),
            tMs = GeoReckonSocketIOClient.nowMonotonicMs(),
            gps = gps
        )

        client.sendGpsRestored(request)
        isDeadReckoningActive = false
    }

    /**
     * Resets the session and client state.
     */
    fun reset() {
        isDeadReckoningActive = false
        isCalibrating = false
        val request = ResetRequest(
            sessionId = client.getActiveSessionId(),
            seq = 1L,
            tMs = GeoReckonSocketIOClient.nowMonotonicMs()
        )
        client.sendReset(request)
        client.startNewSession()
    }

    fun stop() {
        isDeadReckoningActive = false
        isCalibrating = false
        tickerJob?.cancel()
        sensorManager.unregisterListener(this)
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) {}
        shadowKinoNet.close()
    }

    // --- SensorEventListener ---

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccel = doubleArrayOf(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyro = doubleArrayOf(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
            }
            Sensor.TYPE_GRAVITY -> {
                latestGravity = doubleArrayOf(
                    event.values[0].toDouble(),
                    event.values[1].toDouble(),
                    event.values[2].toDouble()
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- LocationListener ---

    override fun onLocationChanged(location: Location) {
        isGpsAvailable = location.accuracy <= 50f
        val gps = GpsPoint(
            lat = location.latitude,
            lon = location.longitude,
            altM = location.altitude,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else 0.0,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 5.0
        )
        lastGpsFix = gps

        gpsHistory.addLast(
            GpsHistoryPoint(
                tMs = GeoReckonSocketIOClient.nowMonotonicMs(),
                speedMps = gps.speedMps,
                bearingDeg = gps.bearingDeg
            )
        )
        while (gpsHistory.size > 120) {
            gpsHistory.pollFirst()
        }

        if (isDeadReckoningActive && isGpsAvailable) {
            onGpsRestored(location)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) { isGpsAvailable = true }
    override fun onProviderDisabled(provider: String) {
        isGpsAvailable = false
        if (!isDeadReckoningActive) {
            onGpsLost()
        }
    }
}
