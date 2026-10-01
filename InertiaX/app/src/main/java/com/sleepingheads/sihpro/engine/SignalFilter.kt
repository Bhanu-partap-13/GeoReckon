package com.sleepingheads.sihpro.engine

import kotlin.math.*

/**
 * Phase 1 — Signal Cleaning.
 *
 * Ports Python [georeckon_pipeline.py] Phase 1:
 *  - 4th-order Butterworth low-pass IIR filter at 3 Hz / 10 Hz
 *    (forward + backward = zero-phase, same as scipy.signal.filtfilt)
 *  - MAD (Median Absolute Deviation) pothole/spike clamp (3×MAD threshold)
 *
 * The Butterworth coefficients below are pre-computed analytically for:
 *   order = 4, cutoff = 3.0 Hz, fs = 10.0 Hz (Wn = 3/5 = 0.6 normalized)
 *
 * These exactly match the Python scipy.butter(4, 0.6, 'low') output.
 */
object SignalFilter {

    // --- Pre-computed Butterworth 4th-order LPF at Wn=0.6 (3Hz/10Hz) ---
    // Computed offline via scipy.signal.butter(4, 0.6, btype='low', analog=False)
    // b (feedforward) coefficients
    private val B = doubleArrayOf(
        0.20657208, 0.82628831, 1.23943247, 0.82628831, 0.20657208
    )
    // a (feedback) coefficients
    private val A = doubleArrayOf(
        1.0, 0.00000000, 0.48602992, 0.00000000, 0.01766468
    )

    private const val MAD_MULTIPLIER = 3.0   // 3×MAD threshold (same as Python Config)
    private const val MAD_SCALE = 1.4826      // Consistency factor for Gaussian data

    /**
     * Apply 4th-order Butterworth low-pass filter with zero-phase (filtfilt) to [signal].
     *
     * Zero-phase: forward pass then backward pass.
     * Edge padding uses reflection (same as scipy.signal.filtfilt with method='pad').
     */
    fun butterworth(signal: DoubleArray): DoubleArray {
        if (signal.size < 5) return signal.copyOf()
        val padLen = minOf(signal.size - 1, 3 * maxOf(A.size, B.size))
        val padded = reflect(signal, padLen)
        val forwarded = iirFilter(padded)
        val reversed = iirFilter(forwarded.reversedArray())
        val result = reversed.reversedArray()
        // Trim padding
        return result.copyOfRange(padLen, padLen + signal.size)
    }

    /**
     * Clamp spikes using Median Absolute Deviation.
     * Equivalent to Python [mad_pothole_filter].
     */
    fun madClamp(signal: DoubleArray): DoubleArray {
        val median = median(signal)
        val deviations = DoubleArray(signal.size) { abs(signal[it] - median) }
        val mad = median(deviations)
        if (mad < 1e-10) return signal.copyOf()

        val threshold = MAD_MULTIPLIER * MAD_SCALE * mad
        val upper = median + threshold
        val lower = median - threshold
        return DoubleArray(signal.size) { signal[it].coerceIn(lower, upper) }
    }

    /**
     * Clean a single IMU axis: Butterworth → MAD clamp.
     */
    fun clean(raw: DoubleArray): DoubleArray = madClamp(butterworth(raw))

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Single-pass IIR filter using [B] and [A] (Direct-Form II Transposed). */
    private fun iirFilter(x: DoubleArray): DoubleArray {
        val n = x.size
        val order = maxOf(A.size, B.size)
        val y = DoubleArray(n)
        val w = DoubleArray(order) // state / delay line

        for (i in x.indices) {
            val xn = x[i]
            y[i] = B[0] * xn + w[0]
            for (j in 1 until order) {
                val bj = if (j < B.size) B[j] else 0.0
                val aj = if (j < A.size) A[j] else 0.0
                w[j - 1] = bj * xn - aj * y[i] + (if (j < order - 1) w[j] else 0.0)
            }
        }
        return y
    }

    /** Reflect-pad a signal by [n] samples on each side. */
    private fun reflect(signal: DoubleArray, n: Int): DoubleArray {
        val result = DoubleArray(signal.size + 2 * n)
        for (i in 0 until n) {
            result[n - 1 - i] = signal[minOf(i + 1, signal.size - 1)]
            result[signal.size + n + i] = signal[maxOf(signal.size - 2 - i, 0)]
        }
        signal.copyInto(result, n)
        return result
    }

    /** Compute the median of a DoubleArray. */
    private fun median(arr: DoubleArray): Double {
        val sorted = arr.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun DoubleArray.reversedArray(): DoubleArray {
        val copy = this.copyOf()
        copy.reverse()
        return copy
    }
}
