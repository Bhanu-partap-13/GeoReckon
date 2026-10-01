package com.sleepingheads.sihpro.data.model

/**
 * GeoReckon Positioning Mode state contract.
 * Explicitly separates positioning algorithm mode from hardware sensor health.
 */
enum class PositioningMode {
    INITIALIZING,
    GNSS_AIDED,
    DEAD_RECKONING,
    REACQUIRING
}
