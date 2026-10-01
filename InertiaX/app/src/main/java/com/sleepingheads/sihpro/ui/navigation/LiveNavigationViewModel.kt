package com.sleepingheads.sihpro.ui.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sleepingheads.sihpro.engine.GeoReckonEngine
import com.sleepingheads.sihpro.engine.EngineOutput
import com.sleepingheads.sihpro.engine.SensorBridge
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for LIVE sensor mode — uses the real GeoReckon pipeline
 * running on the phone's IMU and GPS sensors.
 *
 * The UI layer (NavigationScreen, TrajectoryView) can bind to either
 * [NavigationViewModel] (demo replay) or [LiveNavigationViewModel] (live engine)
 * by switching the ViewModel provided to the Composable.
 *
 * [engineOutput] is a [StateFlow<EngineOutput>] that ticks at ~10 Hz as
 * long as [SensorBridge] is active.
 *
 * Usage in AppNavGraph:
 *   val liveVm: LiveNavigationViewModel = viewModel()
 *   // pass liveVm.engineOutput to your screen composable
 */
class LiveNavigationViewModel(application: Application) : AndroidViewModel(application) {

    val engine = GeoReckonEngine(application)
    val engineOutput: StateFlow<EngineOutput> = engine.output

    private val sensorBridge = SensorBridge(application, engine, viewModelScope)

    init {
        sensorBridge.start()
    }

    fun resetEngine() {
        engine.reset()
        sensorBridge.stop()
        sensorBridge.start()
    }

    override fun onCleared() {
        super.onCleared()
        sensorBridge.stop()
        engine.close()
    }
}
