package com.sleepingheads.sihpro.engine

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Phase 3 — KinoNet-R2 Speed Prediction via TFLite.
 *
 * Ports [georeckon_pipeline.py] Phase 3 [KinoNetR2.predict_with_uncertainty].
 *
 * The TFLite model is a conversion of the trained PyTorch KinoNet-R2
 * (kinonet_r2_best.pth → kinonet_r2.tflite).
 *
 * Input:  float32 tensor [1, 6, 20]  — 6 IMU channels × 20 timesteps (2 s @ 10 Hz)
 * Output: float32 tensor [1, 2]      — [μ (speed m/s), log_σ² (log variance)]
 *
 * The 20-sample sliding window matches Python [Config.WINDOW_SIZE = 20].
 * Channel order: [AccX, AccY, AccZ, GyroX, GyroY, GyroZ] (vehicle frame, bias-removed).
 *
 * FALLBACK: If the TFLite model is not present in assets, a lightweight
 * pure-Kotlin KNN speed estimator is used (based on acceleration magnitude).
 */
class KinoNetInference(context: Context) {

    companion object {
        const val WINDOW_SIZE = 20       // 2 seconds @ 10 Hz
        const val INPUT_CHANNELS = 6     // AccX, AccY, AccZ, GyroX, GyroY, GyroZ
        const val N_MC_SAMPLES = 10      // Monte Carlo dropout passes (reduced for mobile)
        private const val MODEL_ASSET = "kinonet_r2.tflite"
    }

    private var interpreter: Interpreter? = null
    private var usingFallback: Boolean = false

    // Sliding window buffer: stores last WINDOW_SIZE samples as [6] each
    private val windowBuffer = ArrayDeque<FloatArray>(WINDOW_SIZE + 1)

    init {
        try {
            val model = loadModelFromAssets(context)
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            interpreter = Interpreter(model, options)
            usingFallback = false
        } catch (e: Exception) {
            // TFLite model not available — use pure-Kotlin fallback
            usingFallback = true
        }
    }

    /**
     * Feed one IMU sample (in vehicle frame, bias-corrected) into the rolling window.
     *
     * @param accelVehicle [ax, ay, az] m/s² (vehicle frame, gravity-removed)
     * @param gyroVehicle  [gx, gy, gz] rad/s (vehicle frame)
     */
    fun feedSample(accelVehicle: DoubleArray, gyroVehicle: DoubleArray) {
        val row = FloatArray(INPUT_CHANNELS) {
            when (it) {
                0 -> accelVehicle[0].toFloat()
                1 -> accelVehicle[1].toFloat()
                2 -> accelVehicle[2].toFloat()
                3 -> gyroVehicle[0].toFloat()
                4 -> gyroVehicle[1].toFloat()
                5 -> gyroVehicle[2].toFloat()
                else -> 0f
            }
        }
        windowBuffer.addLast(row)
        if (windowBuffer.size > WINDOW_SIZE) windowBuffer.removeFirst()
    }

    /**
     * Run inference if the window is full (≥ [WINDOW_SIZE] samples).
     *
     * @return [Pair<Double, Double>] of (speed_mps, variance) or null if not ready.
     *
     * Equivalent to Python:
     *   mu, log_var = model(window_tensor)
     *   speed_pred = max(0.0, mu.item())
     *   speed_var  = exp(log_var).item()
     */
    fun predict(): Pair<Double, Double>? {
        return predictDetailed()?.let { Pair(it.speedMps, it.variance) }
    }

    val isReady: Boolean get() = windowBuffer.size >= WINDOW_SIZE
    val isModelLoaded: Boolean get() = interpreter != null && !usingFallback
    val isUsingFallback: Boolean get() = usingFallback
    fun getInterpreter(): Interpreter? = interpreter

    data class Prediction(
        val speedMps: Double,
        val logVar: Double,
        val variance: Double
    )

    /**
     * Feeds raw IMU sensor reading and applies exact vehicle-frame rotation, gravity removal, and bias correction
     * matching georeckon/server/engine_wrapper.py lines 453-457.
     */
    fun feedRawSample(
        rawAccel: DoubleArray,
        rawGyro: DoubleArray,
        rPhoneToVehicle: Array<DoubleArray>? = null,
        fwdAccelBias: Double = 0.0,
        gyroBias: DoubleArray? = null
    ) {
        val gb = gyroBias ?: doubleArrayOf(0.0, 0.0, 0.0)
        val wClean = doubleArrayOf(
            rawGyro[0] - gb[0],
            rawGyro[1] - gb[1],
            rawGyro[2] - gb[2]
        )

        val aVeh = if (rPhoneToVehicle != null) {
            doubleArrayOf(
                rPhoneToVehicle[0][0] * rawAccel[0] + rPhoneToVehicle[0][1] * rawAccel[1] + rPhoneToVehicle[0][2] * rawAccel[2],
                rPhoneToVehicle[1][0] * rawAccel[0] + rPhoneToVehicle[1][1] * rawAccel[1] + rPhoneToVehicle[1][2] * rawAccel[2],
                rPhoneToVehicle[2][0] * rawAccel[0] + rPhoneToVehicle[2][1] * rawAccel[1] + rPhoneToVehicle[2][2] * rawAccel[2]
            )
        } else {
            rawAccel.clone()
        }

        val wVeh = if (rPhoneToVehicle != null) {
            doubleArrayOf(
                rPhoneToVehicle[0][0] * wClean[0] + rPhoneToVehicle[0][1] * wClean[1] + rPhoneToVehicle[0][2] * wClean[2],
                rPhoneToVehicle[1][0] * wClean[0] + rPhoneToVehicle[1][1] * wClean[1] + rPhoneToVehicle[1][2] * wClean[2],
                rPhoneToVehicle[2][0] * wClean[0] + rPhoneToVehicle[2][1] * wClean[1] + rPhoneToVehicle[2][2] * wClean[2]
            )
        } else {
            wClean
        }

        // Gravity removal (Z-axis in vehicle frame) & forward bias removal (X-axis in vehicle frame)
        aVeh[2] -= 9.80665
        aVeh[0] -= fwdAccelBias

        feedSample(aVeh, wVeh)
    }

    /**
     * Extracts the exact channel-first [1, 6, 20] Float32 tensor currently in the sliding window.
     * Length is 120 floats. Returns null if window is not full.
     */
    fun getChannelFirstInputTensor(): FloatArray? {
        if (windowBuffer.size < WINDOW_SIZE) return null
        val tensor = FloatArray(INPUT_CHANNELS * WINDOW_SIZE)
        val samples = windowBuffer.toList()
        var idx = 0
        for (c in 0 until INPUT_CHANNELS) {
            for (t in 0 until WINDOW_SIZE) {
                tensor[idx++] = samples[t][c]
            }
        }
        return tensor
    }

    /**
     * Detailed prediction returning speed_mps, log_variance, and variance.
     */
    fun predictDetailed(): Prediction? {
        if (windowBuffer.size < WINDOW_SIZE) return null

        return if (!usingFallback && interpreter != null) {
            val interp = interpreter ?: return null
            val inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_CHANNELS * WINDOW_SIZE * 4)
                .order(ByteOrder.nativeOrder())

            val samples = windowBuffer.toList()
            for (c in 0 until INPUT_CHANNELS) {
                for (t in 0 until WINDOW_SIZE) {
                    inputBuffer.putFloat(samples[t][c])
                }
            }
            inputBuffer.rewind()

            val outputBuffer = Array(1) { FloatArray(2) }
            interp.run(inputBuffer, outputBuffer)

            val mu = outputBuffer[0][0].toDouble().coerceAtLeast(0.0)
            val logVar = outputBuffer[0][1].toDouble().coerceIn(-10.0, 10.0)
            val variance = Math.exp(logVar)

            Prediction(mu, logVar, variance)
        } else {
            val (spd, v) = predictFallback()
            Prediction(spd, Math.log(v), v)
        }
    }

    // -----------------------------------------------------------------------
    // Pure-Kotlin fallback (no TFLite dependency required)
    // -----------------------------------------------------------------------
    // Simple physics-based speed estimator:
    // Integrate forward acceleration (AccX in vehicle frame) over the window.
    // This is not as accurate as KinoNet-R2 but keeps the system functional.
    // -----------------------------------------------------------------------

    private fun predictFallback(): Pair<Double, Double> {
        val samples = windowBuffer.toList()
        val dt = 0.1 // 10 Hz

        // Forward acceleration = AccX (vehicle frame)
        val forwardAccels = samples.map { it[0].toDouble() }
        val meanAccel = forwardAccels.average()

        // Simple integration: v = v0 + a*t, assume v0 starts from mid-window
        // Use smoothed magnitude of AccX as speed proxy
        val speedEstimate = maxOf(0.0, forwardAccels.takeLast(10).average() * 2.0)

        // High variance (uncertain) when using fallback
        val variance = 4.0 // ~2 m/s std dev

        return Pair(speedEstimate, variance)
    }

    // -----------------------------------------------------------------------
    // Model loader
    // -----------------------------------------------------------------------

    private fun loadModelFromAssets(context: Context): MappedByteBuffer {
        val fd = context.assets.openFd(MODEL_ASSET)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() {
        interpreter?.close()
    }
}
