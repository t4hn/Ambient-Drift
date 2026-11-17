package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.DriftApplication
import com.example.model.DriftPlaybackUiState
import com.example.model.SleepTimerPreset
import com.example.service.DriftPlaybackController
import kotlinx.coroutines.flow.StateFlow

class DriftViewModel(application: Application) : AndroidViewModel(application) {

    private val controller: DriftPlaybackController =
        (application as DriftApplication).playbackController

    val uiState: StateFlow<DriftPlaybackUiState> = controller.uiState

    fun selectScene(sceneId: String, autoStartIfConfigured: Boolean = true) {
        controller.selectScene(sceneId, autoStartIfConfigured)
    }

    fun playAll() {
        controller.playAll()
    }

    fun pauseAll() {
        controller.pauseAll()
    }

    fun stopAll() {
        controller.stopAll()
    }

    fun togglePlayPause() {
        controller.togglePlayPause()
    }

    fun setMasterVolume(volume: Float) {
        controller.setMasterVolume(volume)
    }

    fun setChannelVolume(channelId: String, volume: Float) {
        controller.setChannelVolume(channelId, volume)
    }

    fun toggleChannelMute(channelId: String) {
        controller.toggleChannelMute(channelId)
    }

    fun toggleChannelPlay(channelId: String) {
        controller.toggleChannelPlay(channelId)
    }

    fun resetActiveSceneToDefaults() {
        controller.resetActiveSceneToDefaults()
    }

    fun resetAllToDefaults() {
        controller.resetAllToDefaults()
    }

    fun setAutoPlayOnSceneSwitch(enabled: Boolean) {
        controller.setAutoPlayOnSceneSwitch(enabled)
    }

    fun setSmoothCrossfadeEnabled(enabled: Boolean) {
        controller.setSmoothCrossfadeEnabled(enabled)
    }

    fun setBatterySaverOptimizationEnabled(enabled: Boolean) {
        controller.setBatterySaverOptimizationEnabled(enabled)
    }

    fun startSleepTimer(preset: SleepTimerPreset) {
        controller.startSleepTimer(preset)
    }

    fun cancelSleepTimer() {
        controller.cancelSleepTimer()
    }
}
