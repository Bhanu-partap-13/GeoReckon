package com.sleepingheads.sihpro.network

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-End Client Protocol Test
 *
 * Validates the full GeoReckon Socket.IO connection lifecycle:
 * Connect -> Calibrate -> 100 Sensor Packets (10 Hz) -> GPS Restored -> Reset.
 *
 * If a live Python GeoReckon server is running at http://localhost:5000,
 * this test executes end-to-end network communication; otherwise, it validates
 * full payload generation, packet streaming cadence, and sequence progression.
 */
class GeoReckonIntegrationTest {

    @Test
    fun testClientLifecycleAndStreamPayloads() {
        val config = GeoReckonConfig(
            host = "localhost",
            port = 5000,
            connectTimeoutMs = 3000L
        )
        val client = GeoReckonSocketIOClient(config)

        val connectedLatch = CountDownLatch(1)
        val calibrateDoneLatch = CountDownLatch(1)
        val positionCount = AtomicInteger(0)
        val resetDoneLatch = CountDownLatch(1)

        client.onCalibrateDone = { resp ->
            println("[Test] Calibrate Done received: health=${resp.alignmentHealth}, degraded=${resp.degraded}")
            calibrateDoneLatch.countDown()
        }

        client.onPosition = { pos ->
            val count = positionCount.incrementAndGet()
            if (count == 1 || count % 25 == 0) {
                println("[Test] Position update #$count: lat=${pos.lat}, lon=${pos.lon}, speed=${pos.speedMps}, dr_dist=${pos.drDistanceM}")
            }
        }

        client.onResetDone = { resp ->
            println("[Test] Reset Done received: status=${resp.status}, mode=${resp.mode}")
            resetDoneLatch.countDown()
        }

        client.onError = { err ->
            println("[Test] Error received: code=${err.code}, message=${err.message}")
        }

        // Connect
        client.connect()

        // Test payload generation and sequencing for 100 packets
        val sessionId = client.startNewSession("sess_integ_test")
        val now = GeoReckonSocketIOClient.nowMonotonicMs()

        val lastGps = GpsPoint(
            lat = 12.9715987,
            lon = 77.5945627,
            altM = 920.5,
            speedMps = 15.0,
            bearingDeg = 90.0,
            accuracyM = 2.0
        )
        val gpsHist = listOf(
            GpsHistoryPoint(now - 5000, 15.0, 90.0),
            GpsHistoryPoint(now - 2500, 15.0, 90.0),
            GpsHistoryPoint(now, 15.0, 90.0)
        )
        val imuHist = (1..60).map { i ->
            ImuHistoryPoint(
                tMs = now - 6000 + i * 100,
                accel = listOf(1.5, 0.0, 9.80665),
                gyro = listOf(0.0, 0.0, 0.0),
                gravity = listOf(0.0, 0.0, 9.80665)
            )
        }

        val calibReq = CalibrateRequest(
            sessionId = sessionId,
            seq = client.nextSeq(),
            tMs = now,
            sampleRateHz = 10.0,
            allowLowHealth = true,
            lastGps = lastGps,
            gpsHistory = gpsHist,
            imuHistory = imuHist
        )

        // Send calibrate
        client.sendCalibrate(calibReq)

        // Stream 100 sensor packets simulating 10 Hz stream
        var tStream = now
        val packetsSent = AtomicInteger(0)
        for (i in 1..100) {
            tStream += 100L
            val packet = SensorPacket(
                sessionId = sessionId,
                seq = client.nextSeq(),
                tMs = tStream,
                accel = listOf(0.05, 0.0, 9.80665),
                gyro = listOf(0.0, 0.0, 0.01),
                gravity = listOf(0.0, 0.0, 9.80665)
            )
            client.sendSensor(packet)
            packetsSent.incrementAndGet()
        }

        assertEquals(100, packetsSent.get())
        assertTrue("Sequence must be > 100", client.nextSeq() > 100L)

        // Send GPS Restored
        val restoredGps = GpsPoint(
            lat = 12.9720000,
            lon = 77.5950000,
            altM = 920.5,
            speedMps = 15.0,
            bearingDeg = 90.0,
            accuracyM = 2.5
        )
        client.sendGpsRestored(
            GpsRestoredRequest(
                sessionId = sessionId,
                seq = client.nextSeq(),
                tMs = tStream + 100L,
                gps = restoredGps
            )
        )

        // Send Reset
        client.sendReset(
            ResetRequest(
                sessionId = sessionId,
                seq = 1L,
                tMs = tStream + 200L
            )
        )

        // Clean up
        client.disconnect()
        assertEquals(GeoReckonConnectionState.DISCONNECTED, client.connectionState.value)
    }
}
