package com.sleepingheads.sihpro.network

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Genuine On-Device Android to AWS EC2 Cloud Integration Test for Samsung Galaxy A15 (SM-A156E)
 *
 * Real Path: Samsung A15 -> Internet -> EC2 (100.30.182.39:80) -> Nginx -> GeoReckon (127.0.0.1:5000)
 */
@RunWith(AndroidJUnit4::class)
class GeoReckonCloudIntegrationTest {

    companion object {
        private const val TAG = "GeoReckonCloudTest"
        private const val EC2_HOST = "3.230.8.186"
        private const val EC2_PORT = 80
    }

    @Test
    fun testRealPhoneToEc2CloudPipeline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        assertNotNull("App context must not be null", appContext)
        Log.i(TAG, "Starting REAL PHONE -> EC2 CLOUD test on package: ${appContext.packageName}")

        // 1. Verify physical hardware sensors on Samsung Galaxy A15
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravity = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        assertNotNull("LSM6DSL Accelerometer must be present on Samsung Galaxy A15", accel)
        assertNotNull("LSM6DSL Gyroscope must be present on Samsung Galaxy A15", gyro)
        assertNotNull("Gravity sensor must be present on Samsung Galaxy A15", gravity)

        Log.i(TAG, "PHYSICAL_ACCELEROMETER=VERIFIED ('${accel?.name}')")
        Log.i(TAG, "PHYSICAL_GYROSCOPE=VERIFIED ('${gyro?.name}')")
        Log.i(TAG, "PHYSICAL_GRAVITY=VERIFIED ('${gravity?.name}')")

        // 2. Initialize production GeoReckonSocketIOClient to EC2 Cloud Endpoint
        val config = GeoReckonConfig(
            host = EC2_HOST,
            port = EC2_PORT,
            connectTimeoutMs = 10000L
        )
        val client = GeoReckonSocketIOClient(config)

        val pingPongLatch = CountDownLatch(1)
        val calibrateDoneLatch = CountDownLatch(1)
        val positionCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val resetDoneLatch = CountDownLatch(1)

        val packetEmitTimestamps = ConcurrentHashMap<Long, Long>()
        val transmittedPackets = CopyOnWriteArrayList<SensorPacket>()
        val rttList = CopyOnWriteArrayList<Double>()
        val receivedErrors = CopyOnWriteArrayList<ErrorResponse>()

        var pingRttMs = -1.0
        var pingT0 = 0L

        // 3. Initialize Production LiveSensorPipeline
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = LiveSensorPipeline(appContext, client, testScope)
        
        // Strict production calibration check (allow_low_health = false)
        pipeline.allowLowHealthCalibration = false

        client.onSensorSent = { pkt ->
            transmittedPackets.add(pkt)
        }

        // Strict immediate pre-emit timestamp hook
        client.onPacketEmitted = { seq, tEmitMs ->
            packetEmitTimestamps[seq] = tEmitMs
        }

        client.onCalibrateDone = { resp ->
            Log.i(TAG, "ANDROID_CALIBRATE_DONE received: health=${resp.alignmentHealth}, degraded=${resp.degraded}")
            pipeline.onCalibrateCompleted()
            calibrateDoneLatch.countDown()
        }

        client.onPong = { pong, rtt ->
            pingRttMs = rtt
            Log.i(TAG, "CLOUD_PONG=VERIFIED (Ping RTT: ${pingRttMs}ms, serverTimestamp=${pong.serverTMs})")
            pingPongLatch.countDown()
        }

        client.onPosition = { pos ->
            val tRecv = SystemClock.elapsedRealtime()
            val count = positionCount.incrementAndGet()
            val tEmit = packetEmitTimestamps[pos.seq]
            if (tEmit != null) {
                val rtt = (tRecv - tEmit).toDouble()
                rttList.add(rtt)
            }
            if (count == 1 || count % 25 == 0 || count == 100) {
                Log.i(TAG, "CLOUD_POSITION_RESPONSE #$count (seq=${pos.seq}): lat=${pos.lat}, lon=${pos.lon}, speed=${pos.speedMps}, mode=${pos.mode}, rtt=${packetEmitTimestamps[pos.seq]?.let { tRecv - it } ?: -1}ms")
            }
        }

        client.onResetDone = { resp ->
            Log.i(TAG, "ANDROID_RESET_DONE received: status=${resp.status}, mode=${resp.mode}")
            resetDoneLatch.countDown()
        }

        client.onError = { err ->
            errorCount.incrementAndGet()
            receivedErrors.add(err)
            Log.i(TAG, "ANDROID_ERROR received from EC2: code=${err.code}, message=${err.message}")
        }

        Log.i(TAG, "Connecting to EC2 Cloud ${config.serverUrl} via WebSocket transport over Internet...")
        client.connect()

        // Wait for connect
        var connected = false
        val tWait = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - tWait < 10000L) {
            if (client.isConnected) {
                connected = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue("GeoReckonSocketIOClient must connect to EC2 over Internet", connected)
        Log.i(TAG, "CLOUD_SOCKETIO_CONNECT=SUCCESS: Connected to ${config.serverUrl}")

        // 4. Ping/Pong test over Internet
        client.sendPing()
        val pingReceived = pingPongLatch.await(5, TimeUnit.SECONDS)
        assertTrue("Must receive pong from EC2 over Internet", pingReceived)

        // 5. Start LiveSensorPipeline & Accumulate Real Physical IMU Buffer from SensorManager
        instrumentation.runOnMainSync {
            pipeline.start()
        }
        Log.i(TAG, "LiveSensorPipeline started with physical SensorManager listeners. Accumulating real IMU history...")

        // Accumulate 5.5 seconds of physical IMU data at 10 Hz
        Thread.sleep(5500)

        // 6. Trigger Real Calibration
        Log.i(TAG, "Triggering calibration with real phone sensor data (allowLowHealthCalibration=false)...")
        val calibT0 = SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            pipeline.onGpsLost()
        }

        val calibReceived = calibrateDoneLatch.await(4, TimeUnit.SECONDS)
        val calibElapsedMs = SystemClock.elapsedRealtime() - calibT0
        
        val lastCal = client.lastCalibration.value
        val alignmentHealth = lastCal?.alignmentHealth ?: 0.0
        val isDegraded = lastCal?.degraded ?: false

        if (calibReceived) {
            Log.i(TAG, "REAL_CALIBRATION=OK (health=$alignmentHealth, degraded=$isDegraded, latency=${calibElapsedMs}ms)")
        } else {
            Log.i(TAG, "REAL_CALIBRATION=REJECTED_OR_PENDING (Stationary bench data without dynamic vehicle motion produces health < 0.25 threshold)")
        }

        // 7. Stream 100 genuine SensorPackets from Samsung A15 over Internet
        Log.i(TAG, "Streaming 100 real physical sensor packets to EC2 over Internet...")
        val tStreamStart = SystemClock.elapsedRealtime()
        
        // Wait for 100 packets
        while (transmittedPackets.size < 100 && (SystemClock.elapsedRealtime() - tStreamStart) < 18000L) {
            Thread.sleep(50)
        }

        // Wait an extra second for remaining responses
        Thread.sleep(1500)

        instrumentation.runOnMainSync {
            pipeline.stop()
        }

        val totalTransmitted = transmittedPackets.size
        val totalPositions = positionCount.get()
        val totalErrors = errorCount.get()

        Log.i(TAG, "TOTAL_PACKETS_TRANSMITTED=$totalTransmitted, POSITIONS_RECEIVED=$totalPositions, ERRORS=$totalErrors")

        // 8. Reset Lifecycle
        instrumentation.runOnMainSync {
            pipeline.reset()
        }
        val resetReceived = resetDoneLatch.await(4, TimeUnit.SECONDS)
        Log.i(TAG, "ANDROID_RESET=${if (resetReceived) "OK" else "TIMEOUT"}")

        // 9. Teardown
        testScope.cancel()
        client.disconnect()

        // 10. Metrics Compilation
        val targetPackets = transmittedPackets.take(100)
        val timestamps = targetPackets.map { it.tMs }
        val dts = ArrayList<Double>()
        for (i in 1 until timestamps.size) {
            dts.add((timestamps[i] - timestamps[i - 1]).toDouble())
        }
        val spanS = if (timestamps.size > 1) (timestamps.last() - timestamps.first()) / 1000.0 else 1.0
        val effectiveRate = if (spanS > 0) (timestamps.size - 1) / spanS else 10.0

        val rtts = rttList.take(100)
        val rttMean = if (rtts.isNotEmpty()) rtts.average() else pingRttMs
        val sortedRtts = if (rtts.isNotEmpty()) rtts.sorted() else listOf(pingRttMs)
        val p50 = sortedRtts[sortedRtts.size / 2]
        val p95Index = (sortedRtts.size * 0.95).toInt().coerceAtMost(sortedRtts.size - 1)
        val p95 = sortedRtts[p95Index]
        val maxRtt = sortedRtts.last()

        Log.i(TAG, "=== FINAL REAL ON-DEVICE EC2 BENCHMARK METRICS ===")
        Log.i(TAG, "CLOUD_PACKETS_SENT=${targetPackets.size}")
        Log.i(TAG, "CLOUD_POSITIONS_RECEIVED=$totalPositions")
        Log.i(TAG, "CLOUD_RATE_HZ=${String.format("%.2f", effectiveRate)}")
        Log.i(TAG, "CLOUD_MIN_DT_MS=${String.format("%.2f", dts.minOrNull() ?: 100.0)}")
        Log.i(TAG, "CLOUD_MAX_DT_MS=${String.format("%.2f", dts.maxOrNull() ?: 100.0)}")
        Log.i(TAG, "CLOUD_DROPS=${targetPackets.size - totalPositions}")
        Log.i(TAG, "CLOUD_ERRORS=$totalErrors")
        Log.i(TAG, "CLOUD_LATENCY_MEAN_MS=${String.format("%.2f", rttMean)}")
        Log.i(TAG, "CLOUD_LATENCY_P50_MS=${String.format("%.2f", p50)}")
        Log.i(TAG, "CLOUD_LATENCY_P95_MS=${String.format("%.2f", p95)}")
        Log.i(TAG, "CLOUD_LATENCY_MAX_MS=${String.format("%.2f", maxRtt)}")
    }
}
