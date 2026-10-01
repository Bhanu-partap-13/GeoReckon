package com.sleepingheads.sihpro.network

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * GeoReckon WebSocket Protocol v3.2 Data Transfer Objects
 * Strictly matches PROTOCOL.md field names and schema constraints.
 */

data class GpsPoint(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lon") val lon: Double,
    @SerializedName("alt_m") val altM: Double = 0.0,
    @SerializedName("speed_mps") val speedMps: Double,
    @SerializedName("bearing_deg") val bearingDeg: Double,
    @SerializedName("accuracy_m") val accuracyM: Double = 1.0
)

data class GpsHistoryPoint(
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("speed_mps") val speedMps: Double,
    @SerializedName("bearing_deg") val bearingDeg: Double
)

data class ImuHistoryPoint(
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("accel") val accel: List<Double>,   // [ax, ay, az] in m/s^2 (specific force, gravity present)
    @SerializedName("gyro") val gyro: List<Double>,     // [wx, wy, wz] in rad/s
    @SerializedName("gravity") val gravity: List<Double> // [gx, gy, gz] in m/s^2
)

data class CalibrateRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("seq") val seq: Long,
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("sample_rate_hz") val sampleRateHz: Double = 10.0,
    @SerializedName("allow_low_health") val allowLowHealth: Boolean = false,
    @SerializedName("last_gps") val lastGps: GpsPoint,
    @SerializedName("gps_history") val gpsHistory: List<GpsHistoryPoint>,
    @SerializedName("imu_history") val imuHistory: List<ImuHistoryPoint>
)

data class SensorPacket(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("seq") val seq: Long,
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("accel") val accel: List<Double>,
    @SerializedName("gyro") val gyro: List<Double>,
    @SerializedName("gravity") val gravity: List<Double>
)

data class GpsRestoredRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("seq") val seq: Long,
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("gps") val gps: GpsPoint
)

data class ResetRequest(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("seq") val seq: Long = 1,
    @SerializedName("t_ms") val tMs: Long
)

data class PingPacket(
    @SerializedName("t_ms") val tMs: Long
)

data class PongPacket(
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("server_t_ms") val serverTMs: Long? = null
)

data class CalibrateDoneResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("seq") val seq: Long,
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("status") val status: String,
    @SerializedName("degraded") val degraded: Boolean = false,
    @SerializedName("r_phone_to_vehicle") val rPhoneToVehicle: List<List<Double>>? = null,
    @SerializedName("alignment_health") val alignmentHealth: Double = 0.0,
    @SerializedName("fwd_accel_bias_mps2") val fwdAccelBiasMps2: Double = 0.0,
    @SerializedName("gyro_bias_radps") val gyroBiasRadps: List<Double>? = null,
    @SerializedName("latency_ms") val latencyMs: Double = 0.0
)

data class PositionResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("seq") val seq: Long,
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("lat") val lat: Double,
    @SerializedName("lon") val lon: Double,
    @SerializedName("alt_m") val altM: Double = 0.0,
    @SerializedName("enu_x_m") val enuXM: Double = 0.0,
    @SerializedName("enu_y_m") val enuYM: Double = 0.0,
    @SerializedName("enu_z_m") val enuZM: Double = 0.0,
    @SerializedName("speed_mps") val speedMps: Double = 0.0,
    @SerializedName("bearing_deg") val bearingDeg: Double = 0.0,
    @SerializedName("mode") val mode: String = "DEAD_RECKONING",
    @SerializedName("degraded") val degraded: Boolean = false,
    @SerializedName("confidence") val confidence: Double = 1.0,
    @SerializedName("is_stopped") val isStopped: Boolean = false,
    @SerializedName("shock_detected") val shockDetected: Boolean = false,
    @SerializedName("stale") val stale: Boolean = false,
    @SerializedName("gap_s") val gapS: Double = 0.0,
    @SerializedName("kinonet_speed_mps") val kinonetSpeedMps: Double? = null,
    @SerializedName("kinonet_var") val kinonetVar: Double? = null,
    @SerializedName("inekf_e_m") val inekfEM: Double? = null,
    @SerializedName("inekf_n_m") val inekfNM: Double? = null,
    @SerializedName("dr_lat") val drLat: Double? = null,
    @SerializedName("dr_lon") val drLon: Double? = null,
    @SerializedName("dr_error_m") val drErrorM: Double? = null,
    @SerializedName("dr_distance_m") val drDistanceM: Double? = null,
    @SerializedName("latency_ms") val latencyMs: Double = 0.0
)

data class ResetDoneResponse(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("seq") val seq: Long,
    @SerializedName("t_ms") val tMs: Long,
    @SerializedName("status") val status: String,
    @SerializedName("mode") val mode: String = "IDLE",
    @SerializedName("latency_ms") val latencyMs: Double = 0.0
)

data class ErrorResponse(
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("seq") val seq: Long? = null,
    @SerializedName("t_ms") val tMs: Long? = null,
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
    @SerializedName("latency_ms") val latencyMs: Double = 0.0
)

object GeoReckonJson {
    val gson: Gson = Gson()

    fun <T> toJson(obj: T): String = gson.toJson(obj)
    fun <T> fromJson(json: String, clazz: Class<T>): T = gson.fromJson(json, clazz)
}
