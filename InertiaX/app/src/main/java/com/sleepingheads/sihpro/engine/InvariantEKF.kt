package com.sleepingheads.sihpro.engine

import kotlin.math.*

/**
 * Phase 4 — Invariant Extended Kalman Filter (InEKF).
 *
 * Full port of [georeckon_pipeline.py] Phase 4 [InvariantEKF] class.
 *
 * State vector (15-DOF):
 *   [0:3]  = rotation error (so(3))
 *   [3:6]  = velocity (m/s, navigation frame)
 *   [6:9]  = position (m, navigation frame)
 *   [9:12] = accelerometer bias
 *   [12:15]= gyroscope bias
 *
 * All matrix ops use flat DoubleArray in row-major order.
 *
 * NOTE: The covariance matrix is named [cov] (not P) to avoid JVM name clash
 *       with the position vector [pos] — both would compile to getP()/setP().
 */
class InvariantEKF {

    // ---- Config (mirrors Python Config) ----
    private val GRAVITY = 9.80665
    private val GYRO_NOISE_STD = 0.01
    private val ACCEL_NOISE_STD = 0.5
    private val ACCEL_BIAS_STD = 0.01
    private val GYRO_BIAS_STD = 0.001
    private val NHC_LATERAL_STD = 0.1
    private val NHC_GRAVEL_RELAX = 10.0
    private val ZUPT_STD = 0.01
    private val GNSS_POSITION_STD = 2.5
    private val GNSS_VELOCITY_STD = 0.5
    private val MAHAL_GATE = 9.21

    // ---- State ----
    var R: DoubleArray = identity3()    // 3×3 rotation (row-major)
    var v: DoubleArray = DoubleArray(3) // velocity [vx, vy, vz]
    var pos: DoubleArray = DoubleArray(3) // position [px, py, pz]  ← renamed from 'p' to avoid JVM clash
    var bA: DoubleArray = DoubleArray(3)  // accel bias
    var bG: DoubleArray = DoubleArray(3)  // gyro bias
    var cov: DoubleArray = scale(identity15(), 0.01) // 15×15 covariance ← renamed from 'P'

    // Mode tracking
    var mode: String = "GNSS+INS"

    // Process noise Q (15×15)
    private val Q: DoubleArray = buildProcessNoise()

    // -----------------------------------------------------------------------
    // PUBLIC API
    // -----------------------------------------------------------------------

    /**
     * IMU prediction step (dead reckoning).
     * Python equivalent: [InvariantEKF.predict]
     */
    fun predict(accel: DoubleArray, gyro: DoubleArray, dt: Double) {
        val omega = vec3Subtract(gyro, bG)
        val aBody = vec3Subtract(accel, bA)
        val g = doubleArrayOf(0.0, 0.0, -GRAVITY)

        // Rotation update (Rodrigues)
        val angle = vec3Norm(omega) * dt
        if (angle > 1e-10) {
            val axis = vec3Scale(omega, 1.0 / vec3Norm(omega))
            val K = skew(axis)
            val sinA = sin(angle); val cosA = cos(angle)
            val Rdelta = mat3Add(
                mat3Add(identity3(), mat3Scale(K, sinA)),
                mat3Scale(mat3Mul3x3(K, K), 1.0 - cosA)
            )
            R = mat3Mul3x3(R, Rdelta)
        }

        val aNAV = vec3Add(mat3MulVec(R, aBody), g)
        val vOld = v.copyOf()
        v = vec3Add(v, vec3Scale(aNAV, dt))
        pos = vec3Add(pos, vec3Add(vec3Scale(vOld, dt), vec3Scale(aNAV, 0.5 * dt * dt)))

        // Covariance propagation
        val F = buildStateJacobian(omega, aBody)
        val Phi = mat15Add(identity15(), mat15Scale(F, dt))
        cov = mat15Add(mat15Mul(mat15Mul(Phi, cov), mat15Transpose(Phi)), mat15Scale(Q, dt))
    }

    /**
     * GNSS measurement update.
     * Python equivalent: [InvariantEKF.update_gnss]
     * Applies Mahalanobis innovation gate (Problem 9).
     */
    fun updateGnss(gpsPos: DoubleArray, gpsVel: DoubleArray? = null, forceUpdate: Boolean = false) {
        val Hpos = buildPosObservationMatrix()
        val Rpos = scaledIdentity3(GNSS_POSITION_STD * GNSS_POSITION_STD)
        val yPos = vec3Subtract(gpsPos, pos)

        val S = mat3Add(mat3SymQuad(Hpos, cov), Rpos)
        val Sinv = mat3Inv(S) ?: return

        // Innovation gate (Problem 9)
        val mahal = quadraticForm3(yPos, Sinv)
        if (mahal > MAHAL_GATE && !forceUpdate) return

        val K = mat15x3Mul(mat3Transpose15(Hpos, cov), Sinv)
        val delta = mat15x3MulVec3(K, yPos)

        applyDelta(delta)
        val IKH = buildIKH(K, Hpos)
        cov = mat15Add(
            mat15Mul(mat15Mul(IKH, cov), mat15Transpose(IKH)),
            mat15Mul(mat15Mul(K, Rpos3ToFull(Rpos)), mat15Transpose(K))
        )

        if (gpsVel != null) updateVelocity(gpsVel)
    }

    /**
     * Non-Holonomic Constraints (NHC).
     * Python equivalent: [InvariantEKF.apply_nhc]
     * Problems 6 (backward velocity clamp) & 7 (terrain adaptive).
     */
    fun applyNhc(isRoughTerrain: Boolean = false) {
        val Rt = mat3Transpose(R)
        val vBody = mat3MulVec(Rt, v)
        val zNhc = doubleArrayOf(vBody[1], vBody[2])
        val Hnhc = buildNhcObservationMatrix(Rt)

        val nhcNoise = if (isRoughTerrain) NHC_LATERAL_STD * NHC_GRAVEL_RELAX else NHC_LATERAL_STD
        val Rnhc = scaledIdentity2(nhcNoise * nhcNoise)
        val yNhc = doubleArrayOf(-zNhc[0], -zNhc[1])

        val S = mat2Add(mat2SymQuad(Hnhc, cov), Rnhc)
        val Sinv = mat2Inv(S) ?: return

        val K = mat15x2MulInv(Hnhc, Sinv)
        val delta = mat15x2MulVec2(K, yNhc)

        v = vec3Add(v, delta.copyOfRange(3, 6))
        pos = vec3Add(pos, delta.copyOfRange(6, 9))

        // Problem 6: forward velocity clamp
        val vBodyNew = mat3MulVec(mat3Transpose(R), v)
        if (vBodyNew[0] < 0.0) {
            vBodyNew[0] = 0.0
            v = mat3MulVec(R, vBodyNew)
        }

        val IKH = buildNhcIKH(K, Hnhc)
        cov = mat15Add(
            mat15Mul(mat15Mul(IKH, cov), mat15Transpose(IKH)),
            buildNhcKRKt(K, Rnhc)
        )
    }

    /**
     * Zero Velocity Update (ZUPT).
     * Python equivalent: [InvariantEKF.apply_zupt]
     */
    fun applyZupt() {
        val Hzupt = buildZuptObservationMatrix()
        val Rzupt = scaledIdentity3(ZUPT_STD * ZUPT_STD)
        val yZupt = vec3Negate(v)

        val S = mat3Add(mat3SymQuad(Hzupt, cov), Rzupt)
        val Sinv = mat3Inv(S) ?: return

        val K = mat15x3Mul(mat3Transpose15(Hzupt, cov), Sinv)
        val delta = mat15x3MulVec3(K, yZupt)

        v = vec3Add(v, delta.copyOfRange(3, 6))
        pos = vec3Add(pos, delta.copyOfRange(6, 9))

        val IKH = buildZuptIKH(K, Hzupt)
        cov = mat15Add(
            mat15Mul(mat15Mul(IKH, cov), mat15Transpose(IKH)),
            mat15Mul(mat15Mul(K, Rzupt3ToFull(Rzupt)), mat15Transpose(K))
        )
    }

    /**
     * AI Speed Measurement Update.
     * Python equivalent: [InvariantEKF.apply_ai_speed_update]
     */
    fun applyAiSpeedUpdate(speedPred: Double, speedVar: Double) {
        val Rt = mat3Transpose(R)
        val vBody = mat3MulVec(Rt, v)
        val vForward = vBody[0]

        val rVal = if (speedVar > 10.0 || speedVar.isNaN()) 0.2 else maxOf(speedVar, 0.01)
        val Hspeed = buildSpeedObservationMatrix(Rt)

        val sScalar = dot15x1WithCov(Hspeed) + rVal
        val K = buildSpeedKalmanGain(Hspeed, sScalar)

        val ySpeed = speedPred - vForward
        val delta = vec15Scale(K, ySpeed)

        v = vec3Add(v, delta.copyOfRange(3, 6))
        pos = vec3Add(pos, delta.copyOfRange(6, 9))

        val IKH = buildSpeedIKH(K, Hspeed)
        cov = mat15Mul(IKH, cov)
    }

    /** Tunnel wall clamp (Problem 8). */
    fun applyTunnelWall(roadWidthM: Double = 7.0) {
        val halfWidth = roadWidthM / 2.0
        if (abs(pos[1]) > halfWidth) pos[1] = sign(pos[1]) * halfWidth
    }

    fun isVehicleStopped(recentAccels: List<DoubleArray>, threshold: Double = 0.3): Boolean {
        if (recentAccels.size < 5) return false
        val mags = recentAccels.map { a -> sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) }
        val mean = mags.average()
        val variance = mags.sumOf { (it - mean) * (it - mean) } / mags.size
        return variance < threshold
    }

    fun isRoughTerrain(recentAccels: List<DoubleArray>, threshold: Double = 2.0): Boolean {
        if (recentAccels.size < 5) return false
        val zs = recentAccels.map { it[2] }
        val mean = zs.average()
        val std = sqrt(zs.sumOf { (it - mean) * (it - mean) } / zs.size)
        return std > threshold
    }

    // -----------------------------------------------------------------------
    // Private matrix building helpers
    // -----------------------------------------------------------------------

    private fun buildStateJacobian(omega: DoubleArray, aBody: DoubleArray): DoubleArray {
        val F = DoubleArray(225)
        val skewOmega = skew(omega)
        for (i in 0..2) for (j in 0..2) F[i * 15 + j] = -skewOmega[i * 3 + j]
        val skewA = skew(aBody)
        val RskewA = mat3Mul3x3(R, skewA)
        for (i in 0..2) for (j in 0..2) F[(i + 3) * 15 + j] = -RskewA[i * 3 + j]
        for (i in 0..2) F[(i + 6) * 15 + (i + 3)] = 1.0
        for (i in 0..2) for (j in 0..2) F[(i + 3) * 15 + (j + 9)] = -R[i * 3 + j]
        for (i in 0..2) F[i * 15 + (i + 12)] = -1.0
        return F
    }

    private fun buildProcessNoise(): DoubleArray {
        val Q = DoubleArray(225)
        for (i in 0..2) Q[i * 15 + i] = GYRO_NOISE_STD * GYRO_NOISE_STD
        for (i in 3..5) Q[i * 15 + i] = ACCEL_NOISE_STD * ACCEL_NOISE_STD
        for (i in 6..8) Q[i * 15 + i] = 0.001
        for (i in 9..11) Q[i * 15 + i] = ACCEL_BIAS_STD * ACCEL_BIAS_STD
        for (i in 12..14) Q[i * 15 + i] = GYRO_BIAS_STD * GYRO_BIAS_STD
        return Q
    }

    private fun buildPosObservationMatrix(): DoubleArray {
        val H = DoubleArray(45)
        for (i in 0..2) H[i * 15 + (i + 6)] = 1.0
        return H
    }

    private fun buildZuptObservationMatrix(): DoubleArray {
        val H = DoubleArray(45)
        for (i in 0..2) H[i * 15 + (i + 3)] = 1.0
        return H
    }

    private fun buildNhcObservationMatrix(Rt: DoubleArray): DoubleArray {
        val H = DoubleArray(30)
        for (j in 0..2) H[0 * 15 + (j + 3)] = Rt[1 * 3 + j]
        for (j in 0..2) H[1 * 15 + (j + 3)] = Rt[2 * 3 + j]
        return H
    }

    private fun buildSpeedObservationMatrix(Rt: DoubleArray): DoubleArray {
        val H = DoubleArray(15)
        for (j in 0..2) H[j + 3] = Rt[j]
        return H
    }

    private fun applyDelta(delta: DoubleArray) {
        val dTheta = delta.copyOfRange(0, 3)
        val angle = vec3Norm(dTheta)
        if (angle > 1e-10) {
            val axis = vec3Scale(dTheta, 1.0 / angle)
            val K = skew(axis)
            val Rcorr = mat3Add(
                mat3Add(identity3(), mat3Scale(K, sin(angle))),
                mat3Scale(mat3Mul3x3(K, K), 1.0 - cos(angle))
            )
            R = mat3Mul3x3(Rcorr, R)
        }
        v = vec3Add(v, delta.copyOfRange(3, 6))
        pos = vec3Add(pos, delta.copyOfRange(6, 9))
        bA = vec3Add(bA, delta.copyOfRange(9, 12))
        bG = vec3Add(bG, delta.copyOfRange(12, 15))
    }

    private fun updateVelocity(gpsVel: DoubleArray) {
        val Hvel = buildZuptObservationMatrix()
        val Rvel = scaledIdentity3(GNSS_VELOCITY_STD * GNSS_VELOCITY_STD)
        val yVel = vec3Subtract(gpsVel, v)
        val S = mat3Add(mat3SymQuad(Hvel, cov), Rvel)
        val Sinv = mat3Inv(S) ?: return
        val K = mat15x3Mul(mat3Transpose15(Hvel, cov), Sinv)
        val delta = mat15x3MulVec3(K, yVel)
        applyDelta(delta)
        val IKH = buildIKH(K, Hvel)
        cov = mat15Add(
            mat15Mul(mat15Mul(IKH, cov), mat15Transpose(IKH)),
            mat15Mul(mat15Mul(K, Rzupt3ToFull(Rvel)), mat15Transpose(K))
        )
    }

    // -----------------------------------------------------------------------
    // Linear algebra helpers (pure Kotlin)
    // -----------------------------------------------------------------------

    private fun identity3(): DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    private fun identity15(): DoubleArray { val I = DoubleArray(225); for (i in 0..14) I[i * 15 + i] = 1.0; return I }

    private fun skew(v: DoubleArray): DoubleArray = doubleArrayOf(0.0, -v[2], v[1], v[2], 0.0, -v[0], -v[1], v[0], 0.0)

    private fun vec3Add(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] + b[it] }
    private fun vec3Subtract(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
    private fun vec3Scale(v: DoubleArray, s: Double) = DoubleArray(3) { v[it] * s }
    private fun vec3Negate(v: DoubleArray) = DoubleArray(3) { -v[it] }
    private fun vec3Norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun mat3MulVec(M: DoubleArray, v: DoubleArray): DoubleArray = DoubleArray(3) { i ->
        M[i * 3] * v[0] + M[i * 3 + 1] * v[1] + M[i * 3 + 2] * v[2]
    }
    private fun mat3Mul3x3(A: DoubleArray, B: DoubleArray): DoubleArray {
        val C = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) for (k in 0..2) C[i * 3 + j] += A[i * 3 + k] * B[k * 3 + j]
        return C
    }
    private fun mat3Add(A: DoubleArray, B: DoubleArray) = DoubleArray(9) { A[it] + B[it] }
    private fun mat3Scale(M: DoubleArray, s: Double) = DoubleArray(9) { M[it] * s }
    private fun mat3Transpose(M: DoubleArray): DoubleArray = DoubleArray(9) { i -> M[(i % 3) * 3 + (i / 3)] }

    private fun scaledIdentity3(s: Double): DoubleArray { val I = DoubleArray(9); for (i in 0..2) I[i * 3 + i] = s; return I }
    private fun scaledIdentity2(s: Double): DoubleArray { val I = DoubleArray(4); I[0] = s; I[3] = s; return I }

    private fun mat3SymQuad(H: DoubleArray, C: DoubleArray): DoubleArray {
        val HP = DoubleArray(3 * 15)
        for (i in 0..2) for (j in 0..14) for (k in 0..14) HP[i * 15 + j] += H[i * 15 + k] * C[k * 15 + j]
        val HPHt = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) for (k in 0..14) HPHt[i * 3 + j] += HP[i * 15 + k] * H[j * 15 + k]
        return HPHt
    }

    private fun mat2SymQuad(H: DoubleArray, C: DoubleArray): DoubleArray {
        val HP = DoubleArray(2 * 15)
        for (i in 0..1) for (j in 0..14) for (k in 0..14) HP[i * 15 + j] += H[i * 15 + k] * C[k * 15 + j]
        val HPHt = DoubleArray(4)
        for (i in 0..1) for (j in 0..1) for (k in 0..14) HPHt[i * 2 + j] += HP[i * 15 + k] * H[j * 15 + k]
        return HPHt
    }

    private fun mat3Inv(M: DoubleArray): DoubleArray? {
        val det = M[0] * (M[4] * M[8] - M[5] * M[7]) -
                  M[1] * (M[3] * M[8] - M[5] * M[6]) +
                  M[2] * (M[3] * M[7] - M[4] * M[6])
        if (abs(det) < 1e-15) return null
        return doubleArrayOf(
            (M[4]*M[8]-M[5]*M[7])/det, -(M[1]*M[8]-M[2]*M[7])/det, (M[1]*M[5]-M[2]*M[4])/det,
            -(M[3]*M[8]-M[5]*M[6])/det, (M[0]*M[8]-M[2]*M[6])/det, -(M[0]*M[5]-M[2]*M[3])/det,
            (M[3]*M[7]-M[4]*M[6])/det, -(M[0]*M[7]-M[1]*M[6])/det, (M[0]*M[4]-M[1]*M[3])/det
        )
    }

    private fun mat2Inv(M: DoubleArray): DoubleArray? {
        val det = M[0] * M[3] - M[1] * M[2]
        if (abs(det) < 1e-15) return null
        return doubleArrayOf(M[3]/det, -M[1]/det, -M[2]/det, M[0]/det)
    }

    private fun quadraticForm3(y: DoubleArray, Sinv: DoubleArray): Double {
        val Sinvy = mat3MulVec(Sinv, y)
        return y[0]*Sinvy[0] + y[1]*Sinvy[1] + y[2]*Sinvy[2]
    }

    private fun mat3Transpose15(H: DoubleArray, C: DoubleArray): DoubleArray {
        val PHt = DoubleArray(45)
        for (i in 0..14) for (j in 0..2) for (k in 0..14) PHt[i * 3 + j] += C[i * 15 + k] * H[j * 15 + k]
        return PHt
    }

    private fun mat15x3Mul(PHt: DoubleArray, Sinv: DoubleArray): DoubleArray {
        val K = DoubleArray(45)
        for (i in 0..14) for (j in 0..2) for (k in 0..2) K[i * 3 + j] += PHt[i * 3 + k] * Sinv[k * 3 + j]
        return K
    }

    private fun mat15x3MulVec3(K: DoubleArray, y: DoubleArray): DoubleArray =
        DoubleArray(15) { i -> K[i*3]*y[0] + K[i*3+1]*y[1] + K[i*3+2]*y[2] }

    private fun buildIKH(K: DoubleArray, H: DoubleArray): DoubleArray {
        val KH = DoubleArray(225)
        for (i in 0..14) for (j in 0..14) for (k in 0..2) KH[i * 15 + j] += K[i * 3 + k] * H[k * 15 + j]
        val IKH = identity15()
        for (i in KH.indices) IKH[i] -= KH[i]
        return IKH
    }

    private fun buildNhcIKH(K: DoubleArray, H: DoubleArray): DoubleArray {
        val KH = DoubleArray(225)
        for (i in 0..14) for (j in 0..14) for (k in 0..1) KH[i * 15 + j] += K[i * 2 + k] * H[k * 15 + j]
        val IKH = identity15()
        for (i in KH.indices) IKH[i] -= KH[i]
        return IKH
    }

    private fun buildZuptIKH(K: DoubleArray, H: DoubleArray) = buildIKH(K, H)

    private fun buildNhcKRKt(K: DoubleArray, Rnhc: DoubleArray): DoubleArray {
        val KR = DoubleArray(30)
        for (i in 0..14) for (j in 0..1) for (k in 0..1) KR[i * 2 + j] += K[i * 2 + k] * Rnhc[k * 2 + j]
        val out = DoubleArray(225)
        for (i in 0..14) for (j in 0..14) for (k in 0..1) out[i * 15 + j] += KR[i * 2 + k] * K[j * 2 + k]
        return out
    }

    private fun mat15x2MulInv(H: DoubleArray, Sinv: DoubleArray): DoubleArray {
        val PHt = DoubleArray(30)
        for (i in 0..14) for (j in 0..1) for (k in 0..14) PHt[i * 2 + j] += cov[i * 15 + k] * H[j * 15 + k]
        val K = DoubleArray(30)
        for (i in 0..14) for (j in 0..1) for (k in 0..1) K[i * 2 + j] += PHt[i * 2 + k] * Sinv[k * 2 + j]
        return K
    }

    private fun mat15x2MulVec2(K: DoubleArray, y: DoubleArray): DoubleArray =
        DoubleArray(15) { i -> K[i * 2] * y[0] + K[i * 2 + 1] * y[1] }

    private fun mat2Add(A: DoubleArray, B: DoubleArray) = DoubleArray(4) { A[it] + B[it] }

    private fun dot15x1WithCov(H: DoubleArray): Double {
        val PHt = DoubleArray(15)
        for (i in 0..14) for (k in 0..14) PHt[i] += cov[i * 15 + k] * H[k]
        var s = 0.0; for (k in 0..14) s += H[k] * PHt[k]; return s
    }

    private fun buildSpeedKalmanGain(H: DoubleArray, sScalar: Double): DoubleArray {
        val PHt = DoubleArray(15)
        for (i in 0..14) for (k in 0..14) PHt[i] += cov[i * 15 + k] * H[k]
        return DoubleArray(15) { PHt[it] / sScalar }
    }

    private fun vec15Scale(v: DoubleArray, s: Double) = DoubleArray(15) { v[it] * s }

    private fun buildSpeedIKH(K: DoubleArray, H: DoubleArray): DoubleArray {
        val KH = DoubleArray(225)
        for (i in 0..14) for (j in 0..14) KH[i * 15 + j] = K[i] * H[j]
        val IKH = identity15()
        for (i in KH.indices) IKH[i] -= KH[i]
        return IKH
    }

    private fun mat15Mul(A: DoubleArray, B: DoubleArray): DoubleArray {
        val C = DoubleArray(225)
        for (i in 0..14) for (j in 0..14) for (k in 0..14) C[i * 15 + j] += A[i * 15 + k] * B[k * 15 + j]
        return C
    }
    private fun mat15Add(A: DoubleArray, B: DoubleArray) = DoubleArray(225) { A[it] + B[it] }
    private fun mat15Scale(M: DoubleArray, s: Double) = DoubleArray(225) { M[it] * s }
    private fun mat15Transpose(M: DoubleArray): DoubleArray = DoubleArray(225) { i -> M[(i % 15) * 15 + (i / 15)] }
    private fun scale(M: DoubleArray, s: Double) = DoubleArray(M.size) { M[it] * s }

    private fun Rpos3ToFull(R3: DoubleArray): DoubleArray {
        val F = DoubleArray(225)
        for (i in 0..2) for (j in 0..2) F[(i + 6) * 15 + (j + 6)] = R3[i * 3 + j]
        return F
    }
    private fun Rzupt3ToFull(R3: DoubleArray): DoubleArray {
        val F = DoubleArray(225)
        for (i in 0..2) for (j in 0..2) F[(i + 3) * 15 + (j + 3)] = R3[i * 3 + j]
        return F
    }
}
