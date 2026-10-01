package com.sleepingheads.sihpro.engine

import android.content.Context
import com.sleepingheads.sihpro.data.model.GnssSample
import com.sleepingheads.sihpro.data.model.ImuSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

/**
 * GeoReckon Engine — Main Orchestrator
 *
 * Wires together all 5 phases of [georeckon_pipeline.py] into a single
 * class that accepts live Android sensor samples and emits [EngineOutput]
 * via StateFlow for the UI layer.
 *
 * Pipeline execution per IMU sample (10 Hz):
 *  1. Phase 1: SignalFilter — Butterworth + MAD on raw IMU axes
 *  2. Phase 2: PhoneAligner — estimate/apply phone-to-vehicle transform
 *  3. Phase 3: KinoNetInference — predict forward speed + uncertainty
 *  4. Phase 4: InvariantEKF.predict → apply AI speed → NHC → ZUPT → GNSS update
 *              → tunnel wall (when in blackout)
 *
 * All computation is synchronous and runs on whichever coroutine context the
 * caller uses.  Wrap in Dispatchers.Default for non-blocking UI.
 *
 * @param context Android context (needed for TFLite asset loading)
 */
class GeoReckonEngine(context: Context) {

    // ---- Config ----
    private val DT = 0.1               // 10 Hz timestep
    private val METERS_PER_DEG_LAT = 111320.0
    private val WINDOW_HISTORY = 12    // samples for terrain / stop detection

    // ---- Pipeline components (Phase 1-4) ----
    private val aligner = PhoneAligner()
    private val kinoNet = KinoNetInference(context)
    private val ekf = InvariantEKF()

    // ---- Signal filter buffers (one per axis) ----
    // We maintain a rolling raw-signal buffer then filter the last N samples.
    // For real-time use we apply a causal IIR approximation: process each
    // sample through the IIR state rather than batching (practical trade-off).
    private val accelRawBuf = Array(3) { ArrayDeque<Double>(50) }
    private val gyroRawBuf  = Array(3) { ArrayDeque<Double>(50) }
    private val accelFiltBuf= Array(3) { ArrayDeque<Double>(WINDOW_HISTORY + 1) }
    private val gyroFiltBuf = Array(3) { ArrayDeque<Double>(WINDOW_HISTORY + 1) }

    // Per-channel IIR state (Direct-Form II Transposed) — one per axis
    private val accelIirState = Array(3) { IirState() }
    private val gyroIirState  = Array(3) { IirState() }

    // ---- GPS origin ----
    private var lat0: Double = Double.NaN
    private var lon0: Double = Double.NaN
    private var metersPerDegLon: Double = 111320.0

    // ---- Calibration state ----
    private var isCalibrated = false
    private var calibStaticAccels = mutableListOf<DoubleArray>()
    private var calibStaticGyros = mutableListOf<DoubleArray>()
    private var calibFirstMoveAccels = mutableListOf<DoubleArray>()
    private var staticSampleCount = 0
    private var prevWasStatic = false

    // ---- Tracking history ----
    private val recentAccelVehicle = ArrayDeque<DoubleArray>(WINDOW_HISTORY + 1)

    // ---- GNSS state ----
    private var lastGnssPos: DoubleArray? = null
    private var prevGnssAvailable = true
    private var sampleCount = 0L

    // ---- Output StateFlow ----
    private val _output = MutableStateFlow(EngineOutput())
    val output: StateFlow<EngineOutput> = _output.asStateFlow()

    // -----------------------------------------------------------------------
    // PRIMARY ENTRY POINTS
    // -----------------------------------------------------------------------

    /**
     * Process one IMU sample. Call from a sensor listener at ~10-50 Hz.
     * The engine down-samples internally to 10 Hz for the filter + EKF.
     *
     * @param sample Raw IMU sample in phone frame (m/s², rad/s)
     */
    fun onImuSample(sample: ImuSample) {
        sampleCount++

        val rawAccel = doubleArrayOf(sample.accelX, sample.accelY, sample.accelZ)
        val rawGyro  = doubleArrayOf(sample.gyroX, sample.gyroY, sample.gyroZ)

        // ---- Phase 1: Apply causal Butterworth IIR filter per axis ----
        val filtAccel = DoubleArray(3) { i -> accelIirState[i].process(rawAccel[i]) }
        val filtGyro  = DoubleArray(3) { i -> gyroIirState[i].process(rawGyro[i]) }

        // ---- Phase 2: Collect calibration data or apply transform ----
        if (!isCalibrated) {
            handleCalibration(filtAccel, filtGyro)
            return
        }

        // Apply phone-to-vehicle rotation + bias subtraction
        val accelRotated = aligner.matMul3(aligner.rPhoneToVehicle, filtAccel)
        val gyroRotated  = aligner.matMul3(aligner.rPhoneToVehicle, filtGyro)
        val accelVehicle = DoubleArray(3) { accelRotated[it] - aligner.accelBias[it] }
        val gyroVehicle  = DoubleArray(3) { gyroRotated[it]  - aligner.gyroBias[it] }

        // Remove gravity from vertical (Z) axis
        val GRAVITY = 9.80665
        accelVehicle[2] -= GRAVITY

        // Track recent for terrain/stop detection
        recentAccelVehicle.addLast(accelVehicle.copyOf())
        if (recentAccelVehicle.size > WINDOW_HISTORY) recentAccelVehicle.removeFirst()

        // ---- Phase 3: Feed KinoNet window + predict speed ----
        kinoNet.feedSample(accelVehicle, gyroVehicle)

        // ---- Phase 4: EKF prediction step ----
        ekf.predict(accelVehicle, gyroVehicle, DT)

        // AI speed measurement update
        val (speedPred, speedVar) = kinoNet.predict() ?: Pair(0.0, 10.0)
        ekf.applyAiSpeedUpdate(maxOf(0.0, speedPred), speedVar)

        // NHC constraints (terrain-adaptive)
        val rough = ekf.isRoughTerrain(recentAccelVehicle.toList())
        ekf.applyNhc(rough)

        // ZUPT
        val stopped = ekf.isVehicleStopped(recentAccelVehicle.toList())
        if (stopped) ekf.applyZupt()

        // Tunnel wall (only in blackout)
        val gnssLost = lastGnssPos == null || ekf.mode == "DEAD_RECKONING"
        if (gnssLost) ekf.applyTunnelWall()

        // ---- Emit output ----
        emitOutput(speedPred, speedVar, stopped, rough)
    }

    /**
     * Process one GNSS fix. Call from LocationManager callback.
     *
     * @param sample GPS fix (lat/lon degrees, speed m/s, accuracy m)
     */
    fun onGnssSample(sample: GnssSample) {
        val gnssNowAvailable = sample.valid && !sample.latitude.isNaN() && !sample.longitude.isNaN()

        // Set origin on first fix
        if (lat0.isNaN() && gnssNowAvailable) {
            lat0 = sample.latitude
            lon0 = sample.longitude
            metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(lat0))
            // Initialize EKF position to origin (0, 0, 0)
            ekf.pos = doubleArrayOf(0.0, 0.0, 0.0)
        }

        if (lat0.isNaN()) return

        if (gnssNowAvailable) {
            val gpsX = (sample.longitude - lon0) * metersPerDegLon
            val gpsY = (sample.latitude  - lat0) * METERS_PER_DEG_LAT
            val gpsPos = doubleArrayOf(gpsX, gpsY, 0.0)

            val isReentry = !prevGnssAvailable && gnssNowAvailable  // tunnel exit

            if (isReentry) {
                // GNSS re-entry (Problem 9): snap EKF position to GPS, widen covariance
                ekf.pos = gpsPos.copyOf()
                val posVar = 6.25  // GNSS_POSITION_STD² = 2.5²
                for (i in 0..2) ekf.cov[(i + 6) * 15 + (i + 6)] = posVar
                ekf.mode = "GNSS+INS"
            }

            val gpsVel = if (sample.speedMps != null) {
                // Use GPS heading (unknown here) → supply null velocity, use position only
                null
            } else null

            ekf.updateGnss(gpsPos, gpsVel, forceUpdate = isReentry)
            lastGnssPos = gpsPos
            ekf.mode = "GNSS+INS"
        } else {
            if (prevGnssAvailable) {
                // GNSS lost → switch to dead reckoning
                ekf.mode = "DEAD_RECKONING"
                lastGnssPos = null
            }
        }

        prevGnssAvailable = gnssNowAvailable
    }

    /**
     * Force calibration with a static snapshot (used in demo/replay mode
     * to skip the real-time calibration phase).
     *
     * Pass the first ~8s of stopped IMU data from your demo dataset.
     */
    fun forceCalibrate(
        staticAccels: List<DoubleArray>,
        staticGyros: List<DoubleArray>,
        firstMoveAccels: List<DoubleArray> = emptyList()
    ) {
        aligner.calibrateFromSnapshot(staticAccels, staticGyros, firstMoveAccels)
        isCalibrated = true
    }

    /** Reset engine state (call on new trip or restart). */
    fun reset() {
        ekf.R = aligner.identity3()
        ekf.v = DoubleArray(3)
        ekf.pos = DoubleArray(3)
        ekf.bA = DoubleArray(3)
        ekf.bG = DoubleArray(3)
        for (i in ekf.cov.indices) ekf.cov[i] = if (i % 15 == i / 15) 0.01 else 0.0
        ekf.mode = "GNSS+INS"
        lat0 = Double.NaN; lon0 = Double.NaN
        lastGnssPos = null; prevGnssAvailable = true; sampleCount = 0L
        recentAccelVehicle.clear()
        for (s in accelIirState) s.reset()
        for (s in gyroIirState) s.reset()
        isCalibrated = false
        calibStaticAccels.clear(); calibStaticGyros.clear(); calibFirstMoveAccels.clear()
        staticSampleCount = 0; prevWasStatic = false
        _output.value = EngineOutput()
    }

    fun close() { kinoNet.close() }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun handleCalibration(filtAccel: DoubleArray, filtGyro: DoubleArray) {
        aligner.processSample(filtAccel, filtGyro)
        if (aligner.isCalibrated && !isCalibrated) {
            isCalibrated = true
        }
    }

    private fun emitOutput(speedPred: Double, speedVar: Double, stopped: Boolean, rough: Boolean) {
        val estLat = if (!lat0.isNaN()) lat0 + ekf.pos[1] / METERS_PER_DEG_LAT else 0.0
        val estLon = if (!lon0.isNaN()) lon0 + ekf.pos[0] / metersPerDegLon else 0.0

        val mode = ekf.mode
        val uncertainty = run {
            // Uncertainty from position covariance diagonal
            val covX = sqrt(maxOf(0.0, ekf.cov[6 * 15 + 6]))
            val covY = sqrt(maxOf(0.0, ekf.cov[7 * 15 + 7]))
            sqrt(covX * covX + covY * covY)
        }

        _output.value = EngineOutput(
            posX = ekf.pos[0],
            posY = ekf.pos[1],
            estLat = estLat,
            estLon = estLon,
            speedMps = maxOf(0.0, speedPred),
            speedKmh = maxOf(0.0, speedPred) * 3.6,
            aiSpeedMps = maxOf(0.0, speedPred),
            aiUncertainty = speedVar,
            uncertaintyM = uncertainty.coerceIn(0.5, 200.0),
            mode = mode,
            health = when {
                uncertainty > 50.0 -> "DEGRADED"
                uncertainty > 20.0 -> "ADAPTIVE"
                mode == "DEAD_RECKONING" -> "ADAPTIVE"
                else -> "NORMAL"
            },
            gnssAvailable = ekf.mode == "GNSS+INS",
            isRoughTerrain = rough,
            isStopped = stopped,
            sampleIndex = sampleCount
        )
    }

    // -----------------------------------------------------------------------
    // Causal Butterworth IIR state (Direct-Form II Transposed)
    // Matches the forward-pass of the Python filtfilt chain.
    // Coefficients match [SignalFilter] (4th order, Wn=0.6).
    // -----------------------------------------------------------------------
    private inner class IirState {
        private val B = doubleArrayOf(0.20657208, 0.82628831, 1.23943247, 0.82628831, 0.20657208)
        private val A = doubleArrayOf(1.0, 0.00000000, 0.48602992, 0.00000000, 0.01766468)
        private val w = DoubleArray(5)

        fun process(x: Double): Double {
            val y = B[0] * x + w[0]
            for (j in 1 until 5) {
                val bj = B[j]; val aj = if (j < A.size) A[j] else 0.0
                w[j - 1] = bj * x - aj * y + (if (j < 4) w[j] else 0.0)
            }
            return y
        }

        fun reset() { w.fill(0.0) }
    }
}

// -----------------------------------------------------------------------
// Data class for engine output (replaces the demo DemoSample for live mode)
// -----------------------------------------------------------------------

/**
 * One timestep of GeoReckon engine output.
 *
 * Maps to the same fields consumed by [NavigationScreen] / [TrajectoryView],
 * mirroring [DemoSample] so the UI needs zero changes.
 */
data class EngineOutput(
    val posX: Double = 0.0,
    val posY: Double = 0.0,
    val estLat: Double = 0.0,
    val estLon: Double = 0.0,
    val speedMps: Double = 0.0,
    val speedKmh: Double = 0.0,
    val aiSpeedMps: Double = 0.0,
    val aiUncertainty: Double = 0.0,
    val uncertaintyM: Double = 1.8,
    val mode: String = "INITIALIZING",       // "GNSS+INS" | "DEAD_RECKONING" | "REACQUIRING"
    val health: String = "NORMAL",            // "NORMAL" | "ADAPTIVE" | "DEGRADED"
    val gnssAvailable: Boolean = true,
    val isRoughTerrain: Boolean = false,
    val isStopped: Boolean = false,
    val sampleIndex: Long = 0L
)
