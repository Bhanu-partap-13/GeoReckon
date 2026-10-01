package com.sleepingheads.sihpro.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sleepingheads.sihpro.network.GeoReckonSocketIOClient
import com.sleepingheads.sihpro.network.LiveSensorPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android KinoNet Shadow Integration & Python Parity Test
 *
 * Verifies:
 * 1. KinoNet running in parallel shadow mode inside LiveSensorPipeline.
 * 2. Captures 20 real physical IMU windows [1, 6, 20] from Samsung Galaxy A15 hardware.
 * 3. Logs MD5 hash, input stats (ACC_MIN/MAX/MEAN, GYRO_MIN/MAX/MEAN), Android predictions, and serialized tensors.
 * 4. Enables Python server parity verification on the EXACT same physical tensors.
 */
@RunWith(AndroidJUnit4::class)
class KinoNetShadowParityTest {

    companion object {
        private const val TAG = "KinoNetShadowParity"
        private const val TARGET_WINDOWS = 20
    }

    @Test
    fun testKinoNetShadowIntegrationAndParity() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull("Target context must not be null", appContext)
        Log.i(TAG, "=== STARTING KINONET SHADOW INTEGRATION & PARITY TEST ===")

        val testScope = CoroutineScope(Dispatchers.Default + Job())
        val dummyClient = GeoReckonSocketIOClient()
        val pipeline = LiveSensorPipeline(appContext, dummyClient, testScope)

        val shadowKinoNet = pipeline.shadowKinoNet
        assertTrue("Shadow KinoNet model must be loaded", shadowKinoNet.isModelLoaded)
        assertFalse("Shadow KinoNet must NOT be using fallback", shadowKinoNet.isUsingFallback)

        // Set default vehicle alignment (Identity R, 0 fwd bias, 0 gyro bias)
        pipeline.calibratedRPhoneToVehicle = arrayOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0)
        )
        pipeline.calibratedFwdAccelBias = 0.0
        pipeline.calibratedGyroBias = doubleArrayOf(0.0, 0.0, 0.0)

        // Capture physical IMU windows
        val capturedWindows = mutableListOf<WindowRecord>()
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        assertNotNull("Accelerometer required", accelSensor)
        assertNotNull("Gyroscope required", gyroSensor)

        var latestGravity = doubleArrayOf(0.0, 0.0, 9.80665)
        var latestGyro = doubleArrayOf(0.0, 0.0, 0.0)
        val latch = CountDownLatch(TARGET_WINDOWS + 25) // 45 samples @ 10 Hz = 4.5s

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> {
                        latestGravity = doubleArrayOf(
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble()
                        )
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        latestGyro = doubleArrayOf(
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble()
                        )
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val rawA = doubleArrayOf(
                            event.values[0].toDouble(),
                            event.values[1].toDouble(),
                            event.values[2].toDouble()
                        )
                        val rawW = latestGyro.clone()

                        shadowKinoNet.feedRawSample(
                            rawAccel = rawA,
                            rawGyro = rawW,
                            rPhoneToVehicle = pipeline.calibratedRPhoneToVehicle,
                            fwdAccelBias = pipeline.calibratedFwdAccelBias,
                            gyroBias = pipeline.calibratedGyroBias
                        )

                        if (shadowKinoNet.isReady && capturedWindows.size < TARGET_WINDOWS) {
                            val tensor = shadowKinoNet.getChannelFirstInputTensor()
                            val pred = shadowKinoNet.predictDetailed()

                            if (tensor != null && pred != null) {
                                val md5 = computeMd5(tensor)
                                val b64 = tensorToBase64(tensor)

                                // Compute stats
                                var accMin = Float.MAX_VALUE
                                var accMax = -Float.MAX_VALUE
                                var accSum = 0.0
                                var gyroMin = Float.MAX_VALUE
                                var gyroMax = -Float.MAX_VALUE
                                var gyroSum = 0.0

                                // 6 channels x 20 steps
                                // channels 0..2 = Acc, channels 3..5 = Gyro
                                for (c in 0 until 6) {
                                    for (t in 0 until 20) {
                                        val v = tensor[c * 20 + t]
                                        if (c < 3) {
                                            if (v < accMin) accMin = v
                                            if (v > accMax) accMax = v
                                            accSum += v
                                        } else {
                                            if (v < gyroMin) gyroMin = v
                                            if (v > gyroMax) gyroMax = v
                                            gyroSum += v
                                        }
                                    }
                                }

                                val accMean = accSum / 60.0
                                val gyroMean = gyroSum / 60.0

                                val record = WindowRecord(
                                    index = capturedWindows.size,
                                    hash = md5,
                                    speedMps = pred.speedMps,
                                    logVar = pred.logVar,
                                    variance = pred.variance,
                                    accMin = accMin,
                                    accMax = accMax,
                                    accMean = accMean,
                                    gyroMin = gyroMin,
                                    gyroMax = gyroMax,
                                    gyroMean = gyroMean,
                                    base64Tensor = b64
                                )
                                capturedWindows.add(record)
                            }
                        }
                        latch.countDown()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        if (gravitySensor != null) {
            sensorManager.registerListener(listener, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        }

        val completed = latch.await(15, TimeUnit.SECONDS)
        sensorManager.unregisterListener(listener)
        pipeline.stop()

        assertTrue("Sensor sampling completed", completed || capturedWindows.size >= TARGET_WINDOWS)
        Log.i(TAG, "CAPTURED_REAL_PHYSICAL_WINDOWS=${capturedWindows.size}")
        assertTrue("Must capture at least $TARGET_WINDOWS windows", capturedWindows.size >= TARGET_WINDOWS)

        Log.i(TAG, "=== DUMPING CAPTURED PHYSICAL IMU WINDOWS FOR PARITY VERIFICATION ===")
        for (w in capturedWindows) {
            Log.i(
                TAG,
                "WINDOW_ENTRY: idx=${w.index} hash=${w.hash} speed=%.6f logvar=%.6f var=%.6f acc_min=%.4f acc_max=%.4f acc_mean=%.4f gyro_min=%.4f gyro_max=%.4f gyro_mean=%.4f tensor_b64=${w.base64Tensor}".format(
                    w.speedMps, w.logVar, w.variance, w.accMin, w.accMax, w.accMean, w.gyroMin, w.gyroMax, w.gyroMean
                )
            )
        }

        Log.i(TAG, "ANDROID_KINONET_SHADOW_INTEGRATION=VERIFIED")
    }

    data class WindowRecord(
        val index: Int,
        val hash: String,
        val speedMps: Double,
        val logVar: Double,
        val variance: Double,
        val accMin: Float,
        val accMax: Float,
        val accMean: Double,
        val gyroMin: Float,
        val gyroMax: Float,
        val gyroMean: Double,
        val base64Tensor: String
    )

    private fun computeMd5(floats: FloatArray): String {
        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) bytes.putFloat(f)
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes.array())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun tensorToBase64(floats: FloatArray): String {
        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) bytes.putFloat(f)
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
    }
}
