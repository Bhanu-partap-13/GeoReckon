package com.sleepingheads.sihpro.session

import com.sleepingheads.sihpro.data.model.DemoPackage
import com.sleepingheads.sihpro.data.model.DemoSample
import com.sleepingheads.sihpro.data.model.TripMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentSampleIndex: Int = 0,
    val currentSample: DemoSample? = null,
    val progress: Float = 0f,
    val elapsedSec: Float = 0f,
    val speedMultiplier: Float = 1.0f,
    val isManualBlackoutForced: Boolean = false,
    val isCompleted: Boolean = false,
    val metadata: TripMetadata? = null,
    val allSamples: List<DemoSample> = emptyList()
)

/**
 * Deterministic real-time playback controller for GeoReckon demonstration.
 * Replays samples at 10 Hz with speed scaling, pause, resume, seek, and manual blackout toggling.
 */
class DemoPlaybackController(
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var playbackJob: Job? = null

    fun loadPackage(demoPackage: DemoPackage) {
        val samples = demoPackage.samples
        _state.update {
            it.copy(
                allSamples = samples,
                metadata = demoPackage.metadata,
                currentSampleIndex = 0,
                currentSample = samples.firstOrNull(),
                progress = 0f,
                elapsedSec = 0f,
                isCompleted = false
            )
        }
    }

    fun start() {
        if (_state.value.isPlaying) return
        if (_state.value.isCompleted) {
            seekTo(0f)
        }
        _state.update { it.copy(isPlaying = true) }
        startLoop()
    }

    fun pause() {
        _state.update { it.copy(isPlaying = false) }
        playbackJob?.cancel()
    }

    fun resume() {
        start()
    }

    fun restart() {
        pause()
        seekTo(0f)
        start()
    }

    fun seekTo(fraction: Float) {
        val samples = _state.value.allSamples
        if (samples.isEmpty()) return
        val targetIdx = (fraction * (samples.size - 1)).toInt().coerceIn(0, samples.size - 1)
        val sample = samples[targetIdx]
        _state.update {
            it.copy(
                currentSampleIndex = targetIdx,
                currentSample = sample,
                progress = targetIdx.toFloat() / (samples.size - 1),
                elapsedSec = sample.timestampMs / 1000f,
                isCompleted = targetIdx >= samples.size - 1
            )
        }
    }

    fun setSpeedMultiplier(multiplier: Float) {
        _state.update { it.copy(speedMultiplier = multiplier) }
        if (_state.value.isPlaying) {
            playbackJob?.cancel()
            startLoop()
        }
    }

    fun toggleManualBlackout() {
        _state.update { it.copy(isManualBlackoutForced = !it.isManualBlackoutForced) }
    }

    private fun startLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.Default) {
            val baseIntervalMs = 100L // 10 Hz sampling rate
            while (isActive && _state.value.isPlaying) {
                val currentIdx = _state.value.currentSampleIndex
                val samples = _state.value.allSamples
                if (samples.isEmpty()) break

                if (currentIdx >= samples.size - 1) {
                    _state.update { it.copy(isPlaying = false, isCompleted = true) }
                    break
                }

                val nextIdx = currentIdx + 1
                val sample = samples[nextIdx]

                // Modify sample if manual blackout is forced
                val effectiveSample = if (_state.value.isManualBlackoutForced && sample.gnssAvailable) {
                    sample.copy(
                        gnssAvailable = false,
                        gpsLat = null,
                        gpsLon = null,
                        mode = "DEAD_RECKONING",
                        health = "ADAPTIVE",
                        uncertaintyM = minOf(15.0, sample.uncertaintyM + 4.0)
                    )
                } else {
                    sample
                }

                _state.update {
                    it.copy(
                        currentSampleIndex = nextIdx,
                        currentSample = effectiveSample,
                        progress = nextIdx.toFloat() / (samples.size - 1),
                        elapsedSec = effectiveSample.timestampMs / 1000f
                    )
                }

                val delayMs = (baseIntervalMs / _state.value.speedMultiplier).toLong().coerceAtLeast(10L)
                delay(delayMs)
            }
        }
    }
}
