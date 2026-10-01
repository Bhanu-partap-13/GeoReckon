package com.sleepingheads.sihpro.engine

import kotlin.math.*

/**
 * Phase 2 — Phone-to-Vehicle Frame Alignment & Bias Calibration.
 *
 * Ports [georeckon_pipeline.py] Phase 2:
 *   Step A: gravity leveling → pitch & roll rotation matrix (R_level)
 *   Step B: first-motion heading → yaw rotation matrix (R_yaw)
 *   Step C: IMU bias estimation during static segments
 *   Result: R_phone_to_vehicle (3×3), accel_bias (3), gyro_bias (3)
 *
 * Matrices are stored in row-major flat DoubleArrays of size 9.
 * mat[row][col] = mat[row*3 + col]
 */
class PhoneAligner {

    // ---- Configuration (mirrors Python Config) ----
    private val GRAVITY = 9.80665
    private val STATIC_THRESHOLD = 0.15  // m/s² variance threshold
    private val ALIGN_WINDOW = 80        // 8 seconds × 10 Hz = 80 samples

    // ---- Calibration results ----
    var rPhoneToVehicle: DoubleArray = identity3()   // 3×3 flat
        private set
    var accelBias: DoubleArray = DoubleArray(3)       // [bx, by, bz] m/s²
        private set
    var gyroBias: DoubleArray = DoubleArray(3)        // [wx, wy, wz] rad/s
        private set
    var isCalibrated: Boolean = false
        private set

    // Rolling buffer for static detection
    private val accelBuffer = ArrayDeque<DoubleArray>(150)
    private val gyroBuffer = ArrayDeque<DoubleArray>(150)
    private var staticSegmentStart = -1
    private var firstMoveDetected = false

    /**
     * Feed one sample into the aligner. Call repeatedly at ~10Hz until
     * [isCalibrated] becomes true.
     *
     * @param accel raw accelerometer [ax, ay, az] in m/s² (phone frame)
     * @param gyro  raw gyroscope [gx, gy, gz] in rad/s (phone frame)
     */
    fun processSample(accel: DoubleArray, gyro: DoubleArray) {
        if (isCalibrated) return

        accelBuffer.addLast(accel.copyOf())
        gyroBuffer.addLast(gyro.copyOf())
        if (accelBuffer.size > 150) {
            accelBuffer.removeFirst()
            gyroBuffer.removeFirst()
        }

        val n = accelBuffer.size
        if (n < 20) return

        // --- Static detection (10-sample window around current) ---
        val windowStart = maxOf(0, n - 20)
        val window = accelBuffer.drop(windowStart)
        val mags = window.map { a -> sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) }
        val variance = variance(mags.toDoubleArray())

        val isStatic = variance < STATIC_THRESHOLD

        if (isStatic && staticSegmentStart < 0) {
            staticSegmentStart = n - 20
        }

        // Only calibrate once we have enough static data
        val staticDuration = if (staticSegmentStart >= 0) n - staticSegmentStart else 0
        if (staticDuration >= ALIGN_WINDOW && !firstMoveDetected) {
            calibrateFromStaticSegment()
        }
    }

    /**
     * Force calibration with a static snapshot (e.g., from demo data).
     * Used when running in demo/replay mode to initialize the filter properly.
     *
     * @param staticAccels list of [ax,ay,az] during a static period (car stopped)
     * @param staticGyros  list of [gx,gy,gz] during same static period
     * @param firstMoveAccels list of [ax,ay,az] during first forward acceleration
     */
    fun calibrateFromSnapshot(
        staticAccels: List<DoubleArray>,
        staticGyros: List<DoubleArray>,
        firstMoveAccels: List<DoubleArray>
    ) {
        // Step 1: Pitch & roll from gravity
        val fMean = columnMeans(staticAccels)
        val rLevel = gravityLeveling(fMean)

        // Step 2: Yaw from first motion
        val rFull = headingAlignment(rLevel, firstMoveAccels)
        rPhoneToVehicle = rFull

        // Step 3: Bias calibration
        val accelVehicle = staticAccels.map { a -> matMul3(rFull, a) }
        val gyroVehicle = staticGyros.map { g -> matMul3(rFull, g) }

        val meanAccelVehicle = columnMeans(accelVehicle)
        val expectedGravity = doubleArrayOf(0.0, 0.0, GRAVITY)
        accelBias = DoubleArray(3) { meanAccelVehicle[it] - expectedGravity[it] }
        gyroBias = columnMeans(gyroVehicle)

        isCalibrated = true
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private fun calibrateFromStaticSegment() {
        val start = maxOf(0, staticSegmentStart)
        val end = accelBuffer.size

        val staticAccels = accelBuffer.drop(start).take(end - start)
        val staticGyros = gyroBuffer.drop(start).take(end - start)

        if (staticAccels.isEmpty()) return

        val fMean = columnMeans(staticAccels)
        val rLevel = gravityLeveling(fMean)

        // For now use identity yaw until first motion detected
        rPhoneToVehicle = rLevel

        val accelVehicle = staticAccels.map { a -> matMul3(rLevel, a) }
        val gyroVehicle = staticGyros.map { g -> matMul3(rLevel, g) }

        val meanAccelVehicle = columnMeans(accelVehicle)
        val expectedGravity = doubleArrayOf(0.0, 0.0, GRAVITY)
        accelBias = DoubleArray(3) { meanAccelVehicle[it] - expectedGravity[it] }
        gyroBias = columnMeans(gyroVehicle)

        isCalibrated = true
    }

    /**
     * Compute pitch & roll rotation matrix from averaged gravity reading.
     * Python equivalent: [estimate_phone_orientation]
     */
    private fun gravityLeveling(fMean: DoubleArray): DoubleArray {
        val fx = fMean[0]; val fy = fMean[1]; val fz = fMean[2]
        val pitch = atan2(-fx, sqrt(fy * fy + fz * fz))
        val roll = atan2(fy, fz)
        // R_level = Ry(pitch) × Rx(roll)
        return matMul3x3(rotY(pitch), rotX(roll))
    }

    /**
     * Refine R_level with yaw from first-motion acceleration.
     * Python equivalent: [estimate_heading_alignment]
     */
    private fun headingAlignment(rLevel: DoubleArray, firstMoveAccels: List<DoubleArray>): DoubleArray {
        if (firstMoveAccels.isEmpty()) return rLevel

        val leveledAccels = firstMoveAccels.map { a ->
            val lev = matMul3(rLevel, a)
            doubleArrayOf(lev[0], lev[1], lev[2] - GRAVITY)
        }
        val meanAx = leveledAccels.map { it[0] }.average()
        val meanAy = leveledAccels.map { it[1] }.average()
        val yaw = atan2(meanAy, meanAx)
        return matMul3x3(rotZ(yaw), rLevel)
    }

    // -----------------------------------------------------------------------
    // 3×3 rotation matrix utilities (row-major, flat DoubleArray of size 9)
    // -----------------------------------------------------------------------

    internal fun identity3(): DoubleArray = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, 1.0, 0.0,
        0.0, 0.0, 1.0
    )

    private fun rotX(angle: Double): DoubleArray {
        val c = cos(angle); val s = sin(angle)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
    }

    private fun rotY(angle: Double): DoubleArray {
        val c = cos(angle); val s = sin(angle)
        return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
    }

    private fun rotZ(angle: Double): DoubleArray {
        val c = cos(angle); val s = sin(angle)
        return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
    }

    /** R (3×3) × v (3) → result (3) */
    internal fun matMul3(R: DoubleArray, v: DoubleArray): DoubleArray = DoubleArray(3) { i ->
        R[i * 3] * v[0] + R[i * 3 + 1] * v[1] + R[i * 3 + 2] * v[2]
    }

    /** A (3×3) × B (3×3) → result (3×3) */
    private fun matMul3x3(A: DoubleArray, B: DoubleArray): DoubleArray {
        val C = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) for (k in 0..2) {
            C[i * 3 + j] += A[i * 3 + k] * B[k * 3 + j]
        }
        return C
    }

    private fun columnMeans(data: List<DoubleArray>): DoubleArray {
        if (data.isEmpty()) return DoubleArray(3)
        val sum = DoubleArray(data[0].size)
        for (row in data) for (j in row.indices) sum[j] += row[j]
        return DoubleArray(sum.size) { sum[it] / data.size }
    }

    private fun variance(arr: DoubleArray): Double {
        val mean = arr.average()
        return arr.sumOf { (it - mean).pow(2) } / arr.size
    }
}
