package com.sleepingheads.sihpro.data.model

/**
 * GeoReckon Engine Health status.
 * Reflects sensor reliability and filter condition independently of positioning mode.
 */
enum class EngineHealth {
    NORMAL,
    ADAPTIVE,
    DEGRADED,
    RECOVERING
}
