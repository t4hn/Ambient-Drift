package com.example

import android.app.Application
import com.example.data.DriftPreferencesRepository
import com.example.service.DriftPlaybackController

class DriftApplication : Application() {

    lateinit var preferencesRepository: DriftPreferencesRepository
        private set

    lateinit var playbackController: DriftPlaybackController
        private set

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = DriftPreferencesRepository(this)
        playbackController = DriftPlaybackController(
            appContext = this,
            preferences = preferencesRepository
        )
    }

    override fun onTerminate() {
        playbackController.release()
        super.onTerminate()
    }
}
