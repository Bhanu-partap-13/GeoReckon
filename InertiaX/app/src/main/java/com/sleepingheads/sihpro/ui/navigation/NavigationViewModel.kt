package com.sleepingheads.sihpro.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepingheads.sihpro.data.replay.DemoTripRepository
import com.sleepingheads.sihpro.session.DemoPlaybackController
import com.sleepingheads.sihpro.session.PlaybackState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = DemoTripRepository(application)
    val controller = DemoPlaybackController(viewModelScope)
    val playbackState: StateFlow<PlaybackState> = controller.state

    init {
        loadDemo()
    }

    fun loadDemo() {
        viewModelScope.launch {
            repository.loadDemoTrip().onSuccess { demoPkg ->
                controller.loadPackage(demoPkg)
                controller.start()
            }
        }
    }

    fun togglePlayPause() {
        if (playbackState.value.isPlaying) {
            controller.pause()
        } else {
            controller.resume()
        }
    }

    fun restart() = controller.restart()
    fun seekTo(fraction: Float) = controller.seekTo(fraction)
    fun setSpeedMultiplier(mult: Float) = controller.setSpeedMultiplier(mult)
    fun toggleManualBlackout() = controller.toggleManualBlackout()
}
