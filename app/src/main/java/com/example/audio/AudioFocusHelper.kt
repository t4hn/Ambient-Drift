package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

enum class FocusRequestOutcome {
    GRANTED,
    DELAYED,
    FAILED
}

interface AudioFocusClient {
    fun requestFocus(): FocusRequestOutcome
    fun abandonFocus()
}

interface AudioFocusListener {
    fun onAudioFocusLost(transient: Boolean)
    fun onAudioFocusDuck(duck: Boolean)
    fun onAudioFocusGained()
}

class AudioFocusHelper(
    context: Context,
    private val listener: AudioFocusListener
) : AudioFocusClient {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    private val afChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                listener.onAudioFocusDuck(false)
                listener.onAudioFocusGained()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                listener.onAudioFocusLost(transient = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                listener.onAudioFocusLost(transient = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                listener.onAudioFocusDuck(true)
            }
        }
    }

    override fun requestFocus(): FocusRequestOutcome {
        if (hasFocus) return FocusRequestOutcome.GRANTED

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(afChangeListener)
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                afChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasFocus = true
                FocusRequestOutcome.GRANTED
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                hasFocus = false
                FocusRequestOutcome.DELAYED
            }
            else -> {
                hasFocus = false
                FocusRequestOutcome.FAILED
            }
        }
    }

    override fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(afChangeListener)
        }
        hasFocus = false
    }
}
