package com.sleepingheads.sihpro.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Tests for GeoReckon Protocol v3.2 Client Serialization and Logic
 */
class GeoReckonProtocolTest {

    @Test
    fun testMonotonicTimestamp() {
        val t1 = GeoReckonSocketIOClient.nowMonotonicMs()
        Thread.sleep(10)
        val t2 = GeoReckonSocketIOClient.nowMonotonicMs()
        assertTrue("Monotonic timestamp must increase", t2 >= t1)
    }

    @Test
    fun testSequenceProgression() {
        val client = GeoReckonSocketIOClient()
        client.resetSequence(10L)
        assertEquals(11L, client.nextSeq())
        assertEquals(12L, client.nextSeq())
        assertEquals(13L, client.nextSeq())
    }

    @Test
    fun testCalibrateRequestSerialization() {
        val lastGps = GpsPoint(
            lat = 12.9715987,
            lon = 77.5945627,
            altM = 920.5,
            speedMps = 18.5,
            bearingDeg = 45.0,
            accuracyM = 2.1
        )
        val gpsHistory = listOf(
            GpsHistoryPoint(1000L, 17.8, 44.8),
            GpsHistoryPoint(2000L, 18.2, 45.0),
            GpsHistoryPoint(3000L, 18.5, 45.0)
        )
        val imuHistory = (1..50).map { i ->
            ImuHistoryPoint(
                tMs = 1000L + i * 100L,
                accel = listOf(0.12, 0.05, 9.80665),
                gyro = listOf(0.001, -0.002, 0.005),
                gravity = listOf(0.0, 0.0, 9.80665)
            )
        }

        val request = CalibrateRequest(
            sessionId = "sess_unit_test",
            seq = 1L,
            tMs = 3000L,
            sampleRateHz = 10.0,
            allowLowHealth = false,
            lastGps = lastGps,
            gpsHistory = gpsHistory,
            imuHistory = imuHistory
        )

        val jsonString = GeoReckonJson.toJson(request)
        val json = JSONObject(jsonString)

        assertEquals("sess_unit_test", json.getString("session_id"))
        assertEquals(1L, json.getLong("seq"))
        assertEquals(10.0, json.getDouble("sample_rate_hz"), 1e-6)
        assertEquals(false, json.getBoolean("allow_low_health"))
        assertEquals(50, json.getJSONArray("imu_history").length())
        assertEquals(3, json.getJSONArray("gps_history").length())

        val parsedGps = json.getJSONObject("last_gps")
        assertEquals(12.9715987, parsedGps.getDouble("lat"), 1e-6)
        assertEquals(77.5945627, parsedGps.getDouble("lon"), 1e-6)
        assertEquals(18.5, parsedGps.getDouble("speed_mps"), 1e-6)
    }

    @Test
    fun testSensorPacketSerialization() {
        val packet = SensorPacket(
            sessionId = "sess_001",
            seq = 42L,
            tMs = 1727632414200L,
            accel = listOf(0.45, -0.08, 9.78),
            gyro = listOf(0.003, 0.001, -0.012),
            gravity = listOf(0.02, 0.01, 9.805)
        )

        val jsonString = GeoReckonJson.toJson(packet)
        val json = JSONObject(jsonString)

        assertEquals("sess_001", json.getString("session_id"))
        assertEquals(42L, json.getLong("seq"))
        assertEquals(1727632414200L, json.getLong("t_ms"))
        assertEquals(3, json.getJSONArray("accel").length())
        assertEquals(3, json.getJSONArray("gyro").length())
        assertEquals(3, json.getJSONArray("gravity").length())
        assertEquals(0.45, json.getJSONArray("accel").getDouble(0), 1e-6)
    }

    @Test
    fun testPositionResponseDeserialization() {
        val jsonString = """
        {
          "session_id": "sess_001",
          "seq": 142,
          "t_ms": 1727632414200,
          "lat": 12.9723456,
          "lon": 77.5952341,
          "alt_m": 920.5,
          "enu_x_m": 72.85,
          "enu_y_m": 82.71,
          "enu_z_m": 0.0,
          "speed_mps": 18.42,
          "bearing_deg": 45.3,
          "mode": "DEAD_RECKONING",
          "degraded": false,
          "confidence": 0.88,
          "is_stopped": false,
          "shock_detected": false,
          "stale": false,
          "gap_s": 0.0,
          "kinonet_speed_mps": 18.35,
          "kinonet_var": 0.12,
          "inekf_e_m": 72.91,
          "inekf_n_m": 82.65,
          "dr_lat": null,
          "dr_lon": null,
          "dr_error_m": null,
          "dr_distance_m": 850.4,
          "latency_ms": 3.8
        }
        """.trimIndent()

        val resp = GeoReckonJson.fromJson(jsonString, PositionResponse::class.java)

        assertEquals("sess_001", resp.sessionId)
        assertEquals(142L, resp.seq)
        assertEquals(12.9723456, resp.lat, 1e-6)
        assertEquals(77.5952341, resp.lon, 1e-6)
        assertEquals(72.85, resp.enuXM, 1e-6)
        assertEquals(82.71, resp.enuYM, 1e-6)
        assertEquals(18.42, resp.speedMps, 1e-6)
        assertEquals(45.3, resp.bearingDeg, 1e-6)
        assertEquals("DEAD_RECKONING", resp.mode)
        assertEquals(false, resp.degraded)
        assertEquals(0.88, resp.confidence, 1e-6)
        assertEquals(false, resp.stale)
        assertEquals(18.35, resp.kinonetSpeedMps!!, 1e-6)
        assertEquals(0.12, resp.kinonetVar!!, 1e-6)
        assertEquals(850.4, resp.drDistanceM!!, 1e-6)
        assertEquals(3.8, resp.latencyMs, 1e-6)
    }

    @Test
    fun testGpsRestoredAndPositionReentryDeserialization() {
        val jsonString = """
        {
          "session_id": "sess_001",
          "seq": 601,
          "t_ms": 1727632460100,
          "lat": 12.9789012,
          "lon": 77.6012345,
          "alt_m": 921.0,
          "enu_x_m": 720.50,
          "enu_y_m": 810.30,
          "enu_z_m": 0.0,
          "speed_mps": 20.1,
          "bearing_deg": 46.2,
          "mode": "IDLE",
          "degraded": false,
          "confidence": 1.0,
          "is_stopped": false,
          "shock_detected": false,
          "stale": false,
          "gap_s": 0.0,
          "kinonet_speed_mps": 20.05,
          "kinonet_var": 0.08,
          "inekf_e_m": 721.10,
          "inekf_n_m": 809.85,
          "dr_lat": 12.9788500,
          "dr_lon": 77.6011800,
          "dr_error_m": 8.12,
          "dr_distance_m": 1120.4,
          "latency_ms": 1.9
        }
        """.trimIndent()

        val resp = GeoReckonJson.fromJson(jsonString, PositionResponse::class.java)

        assertEquals("IDLE", resp.mode)
        assertEquals(12.9788500, resp.drLat!!, 1e-6)
        assertEquals(77.6011800, resp.drLon!!, 1e-6)
        assertEquals(8.12, resp.drErrorM!!, 1e-6)
        assertEquals(1120.4, resp.drDistanceM!!, 1e-6)
    }

    @Test
    fun testErrorCodeMapping() {
        assertEquals(GeoReckonErrorCode.NOT_CALIBRATED, GeoReckonErrorCode.fromCode("NOT_CALIBRATED"))
        assertEquals(GeoReckonErrorCode.INVALID_SCHEMA, GeoReckonErrorCode.fromCode("INVALID_SCHEMA"))
        assertEquals(GeoReckonErrorCode.NAN_OR_INF, GeoReckonErrorCode.fromCode("NAN_OR_INF"))
        assertEquals(GeoReckonErrorCode.OUT_OF_ORDER_SEQ, GeoReckonErrorCode.fromCode("OUT_OF_ORDER_SEQ"))
        assertEquals(GeoReckonErrorCode.INVALID_RATE, GeoReckonErrorCode.fromCode("INVALID_RATE"))
        assertEquals(GeoReckonErrorCode.HISTORY_TOO_SHORT, GeoReckonErrorCode.fromCode("HISTORY_TOO_SHORT"))
        assertEquals(GeoReckonErrorCode.HISTORY_TOO_LONG, GeoReckonErrorCode.fromCode("HISTORY_TOO_LONG"))
        assertEquals(GeoReckonErrorCode.ALIGNMENT_HEALTH_LOW, GeoReckonErrorCode.fromCode("ALIGNMENT_HEALTH_LOW"))
        assertEquals(GeoReckonErrorCode.BEARING_INVALID, GeoReckonErrorCode.fromCode("BEARING_INVALID"))
        assertEquals(GeoReckonErrorCode.INTERNAL_ERROR, GeoReckonErrorCode.fromCode("INTERNAL_ERROR"))
        assertEquals(GeoReckonErrorCode.UNKNOWN, GeoReckonErrorCode.fromCode("UNRECOGNIZED_CODE"))
    }
}
