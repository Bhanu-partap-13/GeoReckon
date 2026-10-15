package com.sleepingheads.sihpro.network

/**
 * GeoReckon Client State and Error Definitions
 */

enum class GeoReckonConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_IDLE,
    CALIBRATING,
    DEAD_RECKONING,
    GPS_RESTORED,
    ERROR
}

enum class SocketConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

fun GeoReckonConnectionState.toSocketState(): SocketConnectionState = when (this) {
    GeoReckonConnectionState.CONNECTING -> SocketConnectionState.CONNECTING
    GeoReckonConnectionState.CONNECTED_IDLE,
    GeoReckonConnectionState.CALIBRATING,
    GeoReckonConnectionState.DEAD_RECKONING,
    GeoReckonConnectionState.GPS_RESTORED -> SocketConnectionState.CONNECTED
    GeoReckonConnectionState.DISCONNECTED -> SocketConnectionState.DISCONNECTED
    GeoReckonConnectionState.ERROR -> SocketConnectionState.ERROR
}

enum class GeoReckonErrorCode(val description: String) {
    NOT_CALIBRATED("Sensor stream sent before calibration completed"),
    INVALID_SCHEMA("Packet schema is invalid or missing required fields"),
    NAN_OR_INF("Sensor payload contains NaN or non-finite values"),
    OUT_OF_ORDER_SEQ("Sequence number received out of order"),
    INVALID_RATE("Sensor stream rate is outside required 8-12 Hz window"),
    HISTORY_TOO_SHORT("Calibration IMU history < 50 or GPS history < 3 points"),
    HISTORY_TOO_LONG("Calibration IMU history contains > 600 samples"),
    ALIGNMENT_HEALTH_LOW("PCA variance ratio too low (< 0.25)"),
    BEARING_INVALID("Vehicle speed < 2.0 m/s with no moving historical bearing"),
    INTERNAL_ERROR("Server-side internal engine error"),
    AUTH_FAILED("WebSocket handshake authentication failed"),
    CONNECTION_LOST("Transport connection dropped"),
    TIMEOUT("Operation timed out"),
    UNKNOWN("Unknown error occurred");

    companion object {
        fun fromCode(code: String): GeoReckonErrorCode {
            return entries.firstOrNull { it.name.equals(code, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

data class GeoReckonConfig(
    val host: String = "3.230.8.186", // AWS EC2 Stable Elastic IP
    val port: Int = 80,
    val authToken: String? = null,
    val sampleRateHz: Double = 10.0,
    val connectTimeoutMs: Long = 10000L,
    val reconnectMaxRetries: Int = 5,
    val reconnectDelayMs: Long = 1000L
) {
    val serverUrl: String
        get() = if (port == 80) "http://$host" else "http://$host:$port"
}

