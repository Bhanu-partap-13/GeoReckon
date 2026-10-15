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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App-wide Manager for GeoReckon Cloud Socket.IO Connectivity and Real Telemetry
 *
 * Provides real runtime data for:
 * - Socket.IO state, latency, packet counters, and live events
 * - Calibration responses (alignment health, degraded flag)
 * - Hardware IMU activity (Accel, Gyro, Gravity)
 * - Hardware GNSS Location fixes
 * - Dead reckoning state and position updates
 * - Live physical device pipeline session management
 */
object GeoReckonCloudManager : SensorEventListener, LocationListener {
    private const val TAG = "GeoReckonCloudManager"
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    val config = GeoReckonConfig(
        host = "3.230.8.186",
        port = 80
    )

    val client: GeoReckonSocketIOClient = GeoReckonSocketIOClient(config)

    val socketState: StateFlow<SocketConnectionState> = client.socketState
    val connectionState: StateFlow<GeoReckonConnectionState> = client.connectionState
    val lastRttMs: StateFlow<Double> = client.lastRttMs
    val lastPosition: StateFlow<PositionResponse?> = client.lastPosition
    val lastCalibration: StateFlow<CalibrateDoneResponse?> = client.lastCalibration
    val lastError: StateFlow<ErrorResponse?> = client.lastError

    // Real packet & network metrics
    private val _packetsSent = MutableStateFlow(0L)
    val packetsSent: StateFlow<Long> = _packetsSent.asStateFlow()

    private val _packetsReceived = MutableStateFlow(0L)
    val packetsReceived: StateFlow<Long> = _packetsReceived.asStateFlow()

    private val _dropsCount = MutableStateFlow(0L)
    val dropsCount: StateFlow<Long> = _dropsCount.asStateFlow()

    private val _errorsCount = MutableStateFlow(0L)
    val errorsCount: StateFlow<Long> = _errorsCount.asStateFlow()

    private val _connectedSinceMs = MutableStateFlow<Long?>(null)
    val connectedSinceMs: StateFlow<Long?> = _connectedSinceMs.asStateFlow()

    // Real Hardware Sensors & GNSS State
    private val _isAccelActive = MutableStateFlow(false)
    val isAccelActive: StateFlow<Boolean> = _isAccelActive.asStateFlow()

    private val _isGyroActive = MutableStateFlow(false)
    val isGyroActive: StateFlow<Boolean> = _isGyroActive.asStateFlow()

    private val _isGravityActive = MutableStateFlow(false)
    val isGravityActive: StateFlow<Boolean> = _isGravityActive.asStateFlow()

    private val _lastGnssLocation = MutableStateFlow<Location?>(null)
    val lastGnssLocation: StateFlow<Location?> = _lastGnssLocation.asStateFlow()

    private val _isGnssActive = MutableStateFlow(false)
    val isGnssActive: StateFlow<Boolean> = _isGnssActive.asStateFlow()

    private val _sensorRateHz = MutableStateFlow(10.0)
    val sensorRateHz: StateFlow<Double> = _sensorRateHz.asStateFlow()

    private val _packetIntervalMs = MutableStateFlow(100.0)
    val packetIntervalMs: StateFlow<Double> = _packetIntervalMs.asStateFlow()

    // Live Device Demo Session State
    private val _liveDemoState = MutableStateFlow("READY")
    val liveDemoState: StateFlow<String> = _liveDemoState.asStateFlow()

    private var livePipeline: LiveSensorPipeline? = null

    // Live Event Log (most recent 25 events)
    private val _liveEventLog = MutableStateFlow<List<String>>(listOf("System initialized"))
    val liveEventLog: StateFlow<List<String>> = _liveEventLog.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null
    private var isStarted = false
    private var lastSensorTimestamp = 0L

    fun logEvent(event: String) {
        val timeStr = timeFormat.format(Date())
        val entry = "[$timeStr] $event"
        val current = _liveEventLog.value.toMutableList()
        current.add(0, entry)
        if (current.size > 25) {
            _liveEventLog.value = current.take(25)
        } else {
            _liveEventLog.value = current
        }
    }

    fun init(context: Context? = null) {
        if (isStarted) return
        isStarted = true
        Log.i(TAG, "Initializing GeoReckonCloudManager targeting ${config.serverUrl}")
        logEvent("CloudManager initialized for ${config.serverUrl}")

        // Attach listeners
        client.onPong = { _, rtt ->
            logEvent("Pong received: RTT = ${String.format("%.1f", rtt)} ms")
        }

        client.onCalibrateDone = { resp ->
            logEvent("Calibration complete: health=${String.format("%.3f", resp.alignmentHealth)}, degraded=${resp.degraded}")
            livePipeline?.onCalibrateCompleted()
            _liveDemoState.value = "RUNNING"
        }

        client.onPosition = { pos ->
            _packetsReceived.value = _packetsReceived.value + 1L
            if (pos.mode.equals("DEAD_RECKONING", ignoreCase = true)) {
                _liveDemoState.value = "DEAD_RECKONING"
            } else if (pos.mode.equals("IDLE", ignoreCase = true)) {
                _liveDemoState.value = "GNSS_RESTORED"
            }
            if (_packetsReceived.value == 1L || _packetsReceived.value % 10L == 0L) {
                logEvent("Dead Reckoning update #${_packetsReceived.value}: speed=${String.format("%.1f", pos.speedMps)}m/s mode=${pos.mode}")
            }
        }

        client.onError = { err ->
            _errorsCount.value = _errorsCount.value + 1L
            _liveDemoState.value = "ERROR"
            logEvent("Server error: [${err.code}] ${err.message}")
        }

        client.onResetDone = { resp ->
            _liveDemoState.value = "COMPLETED"
            logEvent("Session reset complete: status=${resp.status}")
        }

        client.onSensorSent = {
            _packetsSent.value = _packetsSent.value + 1L
            val now = SystemClock.elapsedRealtime()
            if (lastSensorTimestamp > 0) {
                val dt = (now - lastSensorTimestamp).toDouble()
                _packetIntervalMs.value = dt
                if (dt > 0) {
                    _sensorRateHz.value = 1000.0 / dt
                }
            }
            lastSensorTimestamp = now
        }

        // Register hardware sensors if Context is provided
        context?.let { ctx ->
            try {
                val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                val grav = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)

                if (accel != null) {
                    sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
                    _isAccelActive.value = true
                }
                if (gyro != null) {
                    sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_UI)
                    _isGyroActive.value = true
                }
                if (grav != null) {
                    sm.registerListener(this, grav, SensorManager.SENSOR_DELAY_UI)
                    _isGravityActive.value = true
                }

                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    _isGnssActive.value = true
                    try {
                        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, this, Looper.getMainLooper())
                        val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        if (last != null) {
                            _lastGnssLocation.value = last
                        }
                    } catch (_: SecurityException) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed initializing hardware sensor monitors", e)
            }
        }

        connect()
        startPeriodicPing()
    }

    fun startLivePipeline(context: Context, scope: CoroutineScope) {
        if (!client.isConnected) {
            connect()
        }
        livePipeline?.stop()
        val pipeline = LiveSensorPipeline(context, client, scope)
        pipeline.allowLowHealthCalibration = false // STRICT PRODUCTION RULE
        livePipeline = pipeline
        pipeline.start()
        _liveDemoState.value = "CONNECTING"
        logEvent("LiveSensorPipeline started at 10 Hz. Accumulating baseline...")
        mainHandler.postDelayed({
            if (_liveDemoState.value == "CONNECTING") {
                _liveDemoState.value = "CALIBRATING"
                triggerLiveCalibration()
            }
        }, 5000L)
    }

    fun triggerLiveCalibration() {
        _liveDemoState.value = "CALIBRATING"
        logEvent("Emitting calibration request (allow_low_health=false)...")
        livePipeline?.onGpsLost()
    }

    fun stopAndResetLivePipeline() {
        logEvent("Stopping LiveSensorPipeline and resetting session...")
        livePipeline?.reset()
        livePipeline?.stop()
        livePipeline = null
        _liveDemoState.value = "READY"
    }

    fun connect() {
        if (!client.isConnected) {
            logEvent("Connecting to ${config.serverUrl} via WebSocket...")
            client.connect()
        }
    }

    fun disconnect() {
        stopPeriodicPing()
        logEvent("Disconnecting from Socket.IO")
        client.disconnect()
        _connectedSinceMs.value = null
        isStarted = false
    }

    private fun startPeriodicPing() {
        stopPeriodicPing()
        pingRunnable = object : Runnable {
            override fun run() {
                if (client.isConnected) {
                    if (_connectedSinceMs.value == null) {
                        _connectedSinceMs.value = SystemClock.elapsedRealtime()
                        logEvent("Socket.IO connected to ${config.serverUrl}")
                    }
                    client.sendPing()
                } else {
                    _connectedSinceMs.value = null
                    if (socketState.value == SocketConnectionState.DISCONNECTED || socketState.value == SocketConnectionState.ERROR) {
                        client.connect()
                    }
                }
                mainHandler.postDelayed(this, 5000L)
            }
        }
        mainHandler.postDelayed(pingRunnable!!, 3000L)
    }

    private fun stopPeriodicPing() {
        pingRunnable?.let {
            mainHandler.removeCallbacks(it)
            pingRunnable = null
        }
    }

    // --- SensorEventListener ---
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> _isAccelActive.value = true
                Sensor.TYPE_GYROSCOPE -> _isGyroActive.value = true
                Sensor.TYPE_GRAVITY -> _isGravityActive.value = true
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- LocationListener ---
    override fun onLocationChanged(location: Location) {
        _lastGnssLocation.value = location
        _isGnssActive.value = true
        logEvent("GNSS fix updated: lat=${String.format("%.5f", location.latitude)}, lon=${String.format("%.5f", location.longitude)}, acc=${location.accuracy}m")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            _isGnssActive.value = true
            logEvent("GPS Provider enabled in settings")
        }
    }
    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            _isGnssActive.value = false
            logEvent("GPS Provider disabled")
        }
    }
}
