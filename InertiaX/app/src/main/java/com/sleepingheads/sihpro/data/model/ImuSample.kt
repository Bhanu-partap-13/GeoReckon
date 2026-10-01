package com.sleepingheads.sihpro.data.model

/**
 * High-rate inertial measurement sample.
 * Accelerations in m/s^2, angular velocities in rad/s.
 * Hardware timestamps in nanoseconds must be preserved.
 */
data class ImuSample(
    val timestampNs: Long,
    val accelX: Double,
    val accelY: Double,
    val accelZ: Double,
    val gyroX: Double,
    val gyroY: Double,
    val gyroZ: Double,
    val frameId: String = "phone",
    val qualityFlags: Int = 0
)
