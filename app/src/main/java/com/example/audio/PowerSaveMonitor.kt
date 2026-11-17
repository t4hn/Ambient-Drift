package com.example.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PowerSaveMonitor {
    val isPowerSaveMode: StateFlow<Boolean>
    fun start()
    fun stop()
}

class AndroidPowerSaveMonitor(
    context: Context
) : PowerSaveMonitor {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val _isPowerSaveMode = MutableStateFlow(readPowerSaveState())
    override val isPowerSaveMode: StateFlow<Boolean> = _isPowerSaveMode.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                _isPowerSaveMode.value = readPowerSaveState()
            }
        }
    }

    @Synchronized
    override fun start() {
        _isPowerSaveMode.value = readPowerSaveState()
        if (!registered) {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            registered = true
        }
    }

    @Synchronized
    override fun stop() {
        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun readPowerSaveState(): Boolean {
        return runCatching { powerManager?.isPowerSaveMode == true }.getOrDefault(false)
    }
}
