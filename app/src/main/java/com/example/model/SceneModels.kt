package com.example.model

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes

enum class AudioSourceOrigin {
    SYNTHESIZED_TEST,
    MASTERED_AUDIO
}

data class AudioChannelSpec(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:RawRes val rawResId: Int,
    val defaultVolume: Float,
    val pan: Float = 0f,
    val sourceOrigin: AudioSourceOrigin = AudioSourceOrigin.SYNTHESIZED_TEST
)

data class SceneSpec(
    val id: String,
    val indexLabel: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val subtitleRes: Int,
    @param:DrawableRes val artworkResId: Int,
    val accentHex: Long,
    val channels: List<AudioChannelSpec>
)

data class ChannelState(
    val spec: AudioChannelSpec,
    val volume: Float,
    val isMuted: Boolean,
    val isPlaying: Boolean
) {
    val isAudible: Boolean
        get() = isPlaying && !isMuted && volume > 0.001f
}

enum class PlaybackStatus {
    STOPPED,
    PLAYING,
    PAUSED,
    FADING_OUT
}

enum class SleepTimerPreset(val minutes: Int) {
    FIFTEEN(15),
    THIRTY(30),
    SIXTY(60),
    NINETY(90);

    val totalSeconds: Int
        get() = minutes * 60
}

data class SleepTimerState(
    val isActive: Boolean = false,
    val selectedPreset: SleepTimerPreset? = null,
    val totalSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val isFadingOut: Boolean = false
)

data class UserPreferences(
    val lastSceneId: String,
    val masterVolume: Float,
    val autoPlayOnSceneSwitch: Boolean,
    val smoothCrossfadeEnabled: Boolean,
    val channelVolumes: Map<String, Float>,
    val channelMutes: Map<String, Boolean>,
    val channelPlaybackStates: Map<String, Boolean>
)

data class DriftPlaybackUiState(
    val scenes: List<SceneSpec> = emptyList(),
    val activeScene: SceneSpec? = null,
    val channels: List<ChannelState> = emptyList(),
    val masterVolume: Float = 0.85f,
    val playbackStatus: PlaybackStatus = PlaybackStatus.STOPPED,
    val isTransitioning: Boolean = false,
    val sleepTimer: SleepTimerState = SleepTimerState(),
    val autoPlayOnSceneSwitch: Boolean = true,
    val smoothCrossfadeEnabled: Boolean = true,
    val hasTestAudioStems: Boolean = true
) {
    val isPlayingOrFading: Boolean
        get() = playbackStatus == PlaybackStatus.PLAYING || playbackStatus == PlaybackStatus.FADING_OUT
}
