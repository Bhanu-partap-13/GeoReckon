package com.sleepingheads.sihpro.data.model

/**
 * Android GNSS fix measurement.
 * Units: Lat/Lon in degrees, speed in m/s, accuracy in meters.
 */
data class GnssSample(
    val timestampNs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double?,
    val accuracyM: Double?,
    val valid: Boolean
)
