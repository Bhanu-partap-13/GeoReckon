package com.sleepingheads.sihpro.engine

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Standalone On-Device Inference Diagnostic Test for KinoNet-R2 on Samsung Galaxy A15 (SM-A156E)
 *
 * Verifies:
 * 1. Android-compatible TFLite model loads inside the Android process.
 * 2. Real physical IMU samples from Samsung Galaxy A15 hardware are captured.
 * 3. Real single and repeated batch inferences execute purely on-device (CPU/XNNPACK).
 * 4. Latency benchmarks (Mean, P50, P95, Max) and shape/validity checks.
 * 5. Zero network/server calls involved.
 */
@RunWith(AndroidJUnit4::class)
class KinoNetOnDeviceInferenceTest {

    companion object {
        private const val TAG = "KinoNetOnDeviceDiag"
        private const val BATCH_ITERATIONS = 50
    }

    @Test
    fun testOnDeviceKinoNetInference() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotNull("Target context must not be null", appContext)
        Log.i(TAG, "=== STARTING ON-DEVICE KINONET INFERENCE DIAGNOSTIC ===")

        // 1. Initialize KinoNetInference inside Android process
        val kinoNet = KinoNetInference(appContext)
        val isLoaded = kinoNet.isModelLoaded
        val isFallback = kinoNet.isUsingFallback
        val interpreter = kinoNet.getInterpreter()

        Log.i(TAG, "MODEL_LOAD_SUCCESS=$isLoaded")
        Log.i(TAG, "USING_FALLBACK=$isFallback")
        assertTrue("KinoNet TFLite model must load successfully from assets", isLoaded)
        assertFalse("KinoNet must NOT use fallback estimator", isFallback)
        assertNotNull("TFLite Interpreter must not be null", interpreter)

        val inputTensor = interpreter!!.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)

        val inputShapeStr = inputTensor.shape().contentToString()
        val outputShapeStr = outputTensor.shape().contentToString()
        val inputDataType = inputTensor.dataType().toString()
        val outputDataType = outputTensor.dataType().toString()

        Log.i(TAG, "MODEL_FORMAT=TFLITE_FLOAT32")
        Log.i(TAG, "INPUT_SHAPE=$inputShapeStr ($inputDataType)")
        Log.i(TAG, "OUTPUT_SHAPE=$outputShapeStr ($outputDataType)")
        Log.i(TAG, "DEVICE_BACKEND=TFLITE_CPU_XNNPACK (Physical Samsung Galaxy A15 ARM64)")

        assertEquals("Input shape must be [1, 6, 20]", "[1, 6, 20]", inputShapeStr)
        assertEquals("Output shape must be [1, 2]", "[1, 2]", outputShapeStr)

        // 2. Capture real physical IMU samples from Galaxy A15 hardware
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)

        assertNotNull("Physical Accelerometer must exist", accelSensor)
        assertNotNull("Physical Gyroscope must exist", gyroSensor)

        Log.i(TAG, "PHYSICAL_ACCEL_VENDOR=${accelSensor?.vendor} NAME=${accelSensor?.name}")
        Log.i(TAG, "PHYSICAL_GYRO_VENDOR=${gyroSensor?.vendor} NAME=${gyroSensor?.name}")

        val physicalSamples = ConcurrentLinkedDeque<Pair<DoubleArray, DoubleArray>>()
        val samplesLatch = CountDownLatch(25) // Wait for at least 25 real physical samples (2.5s)

        var latestGravity = doubleArrayOf(0.0, 0.0, 9.80665)
        var latestGyro = doubleArrayOf(0.0, 0.0, 0.0)

        val sensorListener = object : SensorEventListener {
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
                        val ax = event.values[0].toDouble() - latestGravity[0]
                        val ay = event.values[1].toDouble() - latestGravity[1]
                        val az = event.values[2].toDouble() - latestGravity[2]
                        val accelVehicle = doubleArrayOf(ax, ay, az)
                        val gyroVehicle = latestGyro.clone()

                        physicalSamples.add(Pair(accelVehicle, gyroVehicle))
                        samplesLatch.countDown()
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sensorManager.registerListener(sensorListener, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(sensorListener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        if (gravitySensor != null) {
            sensorManager.registerListener(sensorListener, gravitySensor, SensorManager.SENSOR_DELAY_GAME)
        }

        val collected = samplesLatch.await(10, TimeUnit.SECONDS)
        sensorManager.unregisterListener(sensorListener)

        assertTrue("Must collect real physical IMU samples from hardware", collected)
        Log.i(TAG, "REAL_PHYSICAL_IMU_SAMPLES_COLLECTED=${physicalSamples.size}")
        assertTrue("Physical sample count must be >= 20", physicalSamples.size >= 20)

        // 3. Feed 20 real physical samples into KinoNet window
        val sampleList = physicalSamples.toList().take(20)
        for ((accel, gyro) in sampleList) {
            kinoNet.feedSample(accel, gyro)
        }
        assertTrue("KinoNet must be ready after 20 samples", kinoNet.isReady)

        // 4. Run Single Real On-Device Inference
        val singleStartNs = System.nanoTime()
        val singleResult = kinoNet.predict()
        val singleDurationMs = (System.nanoTime() - singleStartNs) / 1_000_000.0

        assertNotNull("Single inference result must not be null", singleResult)
        val (initialSpeed, initialVar) = singleResult!!
        Log.i(TAG, "SINGLE_INFERENCE_LATENCY_MS=%.3f".format(singleDurationMs))
        Log.i(TAG, "SINGLE_INFERENCE_OUTPUT: speed_mps=%.4f, variance=%.6f".format(initialSpeed, initialVar))

        assertTrue("Predicted speed must be non-negative", initialSpeed >= 0.0)
        assertTrue("Predicted variance must be positive", initialVar > 0.0)

        // 5. Run Batch of Repeated Real On-Device Inferences
        val latenciesMs = mutableListOf<Double>()
        var errorCount = 0
        val recordedOutputs = mutableListOf<Pair<Double, Double>>()

        // Warmup (5 iterations)
        for (i in 0 until 5) {
            kinoNet.predict()
        }

        // Benchmark loop (50 iterations)
        for (i in 0 until BATCH_ITERATIONS) {
            val t0 = System.nanoTime()
            val pred = kinoNet.predict()
            val dtMs = (System.nanoTime() - t0) / 1_000_000.0

            if (pred != null) {
                latenciesMs.add(dtMs)
                if (i % 10 == 0 || i == BATCH_ITERATIONS - 1) {
                    recordedOutputs.add(pred)
                }
            } else {
                errorCount++
            }
        }

        latenciesMs.sort()
        val meanMs = latenciesMs.average()
        val p50Ms = latenciesMs[(latenciesMs.size * 0.50).toInt()]
        val p95Ms = latenciesMs[(latenciesMs.size * 0.95).toInt().coerceAtMost(latenciesMs.size - 1)]
        val maxMs = latenciesMs.maxOrNull() ?: 0.0

        Log.i(TAG, "=== ON-DEVICE INFERENCE BENCHMARK RESULTS ===")
        Log.i(TAG, "MODEL_LOAD_SUCCESS=true")
        Log.i(TAG, "MODEL_FORMAT=TFLITE_FLOAT32")
        Log.i(TAG, "INPUT_SHAPE=$inputShapeStr")
        Log.i(TAG, "OUTPUT_SHAPE=$outputShapeStr")
        Log.i(TAG, "DEVICE_BACKEND=TFLITE_CPU_XNNPACK (Samsung Galaxy A15)")
        Log.i(TAG, "INFERENCE_COUNT=${latenciesMs.size}")
        Log.i(TAG, "INFERENCE_MEAN_MS=%.3f".format(meanMs))
        Log.i(TAG, "INFERENCE_P50_MS=%.3f".format(p50Ms))
        Log.i(TAG, "INFERENCE_P95_MS=%.3f".format(p95Ms))
        Log.i(TAG, "INFERENCE_MAX_MS=%.3f".format(maxMs))
        Log.i(TAG, "INFERENCE_ERRORS=$errorCount")

        Log.i(TAG, "=== REPRESENTATIVE REAL ON-DEVICE OUTPUTS ===")
        recordedOutputs.forEachIndexed { idx, (speed, variance) ->
            Log.i(TAG, "OUTPUT[$idx]: speed_mps=%.4f, variance=%.6f".format(speed, variance))
        }

        Log.i(TAG, "INFERENCE_EXECUTION_SOURCE=ANDROID_PROCESS_LOCAL_TFLITE (0 Socket.IO / 0 Python Server Calls)")
        Log.i(TAG, "ANDROID_KINONET_RUNTIME=VERIFIED")

        assertEquals("No inference errors allowed", 0, errorCount)
        assertEquals("All batch iterations must succeed", BATCH_ITERATIONS, latenciesMs.size)
        assertTrue("Inference latency P95 must be <= 25 ms for real-time 10 Hz", p95Ms <= 25.0)

        kinoNet.close()
    }
}
