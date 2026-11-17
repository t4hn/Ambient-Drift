package com.example.service

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.audio.AndroidPowerSaveMonitor
import com.example.audio.AudioFocusClient
import com.example.audio.AudioFocusHelper
import com.example.audio.AudioFocusListener
import com.example.audio.DriftAudioMixerEngine
import com.example.audio.FocusRequestOutcome
import com.example.audio.PowerSaveMonitor
import com.example.audio.WavStemLoader
import com.example.data.PreferencesStorage
import com.example.model.AudioSourceOrigin
import com.example.model.ChannelState
import com.example.model.DriftPlaybackUiState
import com.example.model.PlaybackStatus
import com.example.model.SceneCatalog
import com.example.model.SceneSpec
import com.example.model.SleepTimerPreset
import com.example.model.SleepTimerState
import com.example.model.UserPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DriftPlaybackController(
    private val appContext: Context,
    private val preferences: PreferencesStorage,
    private val mixerEngine: DriftAudioMixerEngine = DriftAudioMixerEngine(WavStemLoader(appContext)),
    private val enableAndroidServiceBridge: Boolean = true,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val powerSaveMonitor: PowerSaveMonitor? = if (enableAndroidServiceBridge) {
        AndroidPowerSaveMonitor(appContext)
    } else {
        null
    }
) : AudioFocusListener {

    companion object {
        private const val TAG = "DriftPlaybackController"
        const val SLEEP_FADE_DURATION_SECONDS = 8.0f
        private const val BATTERY_SAVER_SLEEP_TIMER_TICK_MS = 5_000L
        private const val BATTERY_SAVER_FINE_TIMER_WINDOW_MS = 60_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private var cachedPrefs: UserPreferences? = null
    @Volatile
    private var hasUserInteracted = false
    private var resumeOnAudioFocusGain = false
    private var sleepTimerJob: Job? = null
    private var sleepTimerGeneration = 0L
    private var timerRemainingMs: Long = 0L
    private var timerEndTimeMs: Long? = null
    private var activeTimerFadeDurationSeconds: Float = SLEEP_FADE_DURATION_SECONDS
    private var persistVolumeJob: Job? = null
    private var persistMasterVolumeJob: Job? = null
    private var sceneLoadJob: Job? = null
    private var transitionResetJob: Job? = null
    private var powerSaveJob: Job? = null
    private val sceneLoadMutex = Mutex()
    private var sceneGeneration = 0L
    private var stateChangeListener: (() -> Unit)? = null

    internal var audioFocusClient: AudioFocusClient? = if (enableAndroidServiceBridge) {
        AudioFocusHelper(appContext, this)
    } else {
        null
    }

    private val _uiState = MutableStateFlow(
        buildInitialState(
            UserPreferences(
                lastSceneId = SceneCatalog.defaultScene.id,
                masterVolume = 0.85f,
                autoPlayOnSceneSwitch = true,
                smoothCrossfadeEnabled = true,
                channelVolumes = emptyMap(),
                channelMutes = emptyMap(),
                channelPlaybackStates = emptyMap(),
                batterySaverOptimizationEnabled = true
            )
        ).copy(isDevicePowerSaveMode = powerSaveMonitor?.isPowerSaveMode?.value == true)
    )
    val uiState: StateFlow<DriftPlaybackUiState> = _uiState.asStateFlow()

    internal var serviceStarter: (Intent) -> Unit = { intent ->
        ContextCompat.startForegroundService(appContext, intent)
    }

    init {
        mixerEngine.setOnPlaybackErrorListener {
            scope.launch {
                stopAll()
            }
        }
        powerSaveMonitor?.let { monitor ->
            monitor.start()
            _uiState.update { it.copy(isDevicePowerSaveMode = monitor.isPowerSaveMode.value) }
            applyBatterySaverAudioMode()
            powerSaveJob = scope.launch {
                monitor.isPowerSaveMode.collect { isPowerSave ->
                    _uiState.update { it.copy(isDevicePowerSaveMode = isPowerSave) }
                    applyBatterySaverAudioMode()
                }
            }
        }
        scope.launch {
            val loaded = withContext(ioDispatcher) {
                preferences.readInitialPreferences()
            }
            cachedPrefs = loaded
            val scene = SceneCatalog.findScene(loaded.lastSceneId)
            val channels = buildChannelsForScene(scene, loaded)

            // Only apply restored preferences if user hasn't already interacted during startup
            if (!hasUserInteracted && _uiState.value.playbackStatus == PlaybackStatus.STOPPED) {
                _uiState.update {
                    it.copy(
                        scenes = SceneCatalog.scenes,
                        activeScene = scene,
                        channels = channels,
                        masterVolume = loaded.masterVolume,
                        autoPlayOnSceneSwitch = loaded.autoPlayOnSceneSwitch,
                        smoothCrossfadeEnabled = loaded.smoothCrossfadeEnabled,
                        batterySaverOptimizationEnabled = loaded.batterySaverOptimizationEnabled,
                        hasTestAudioStems = scene.channels.any { ch -> ch.sourceOrigin == AudioSourceOrigin.SYNTHESIZED_TEST }
                    )
                }
                applyBatterySaverAudioMode()
                withContext(ioDispatcher) {
                    mixerEngine.loadScene(
                        scene = scene,
                        channelStates = channels,
                        masterVolume = loaded.masterVolume,
                        crossfade = false,
                        isCurrentlyPlaying = false
                    )
                }
                notifyMediaSession()
            }
        }
    }

    fun setMediaSessionListener(listener: (() -> Unit)?) {
        stateChangeListener = listener
    }

    fun selectScene(sceneId: String, autoStartIfConfigured: Boolean = true) {
        hasUserInteracted = true
        val current = _uiState.value
        val targetScene = SceneCatalog.findScene(sceneId)
        if (current.activeScene?.id == targetScene.id) {
            if (autoStartIfConfigured && current.autoPlayOnSceneSwitch && current.playbackStatus == PlaybackStatus.STOPPED) {
                playAll()
            }
            return
        }

        val targetGeneration = ++sceneGeneration
        transitionResetJob?.cancel()

        val prefs = cachedPrefs
        val newChannels = buildChannelsForScene(targetScene, prefs)
        val wasPlaying = current.isPlayingOrFading
        val shouldPlay = wasPlaying || (autoStartIfConfigured && current.autoPlayOnSceneSwitch)
        val crossfade = wasPlaying && current.smoothCrossfadeEnabled

        _uiState.update {
            it.copy(
                activeScene = targetScene,
                channels = newChannels,
                isTransitioning = crossfade,
                hasTestAudioStems = targetScene.channels.any { ch -> ch.sourceOrigin == AudioSourceOrigin.SYNTHESIZED_TEST }
            )
        }

        sceneLoadJob?.cancel()
        sceneLoadJob = scope.launch {
            sceneLoadMutex.withLock {
                if (targetGeneration != sceneGeneration || !isActive) return@withLock
                withContext(ioDispatcher) {
                    preferences.setLastSceneId(targetScene.id)
                    mixerEngine.loadScene(
                        scene = targetScene,
                        channelStates = newChannels,
                        masterVolume = _uiState.value.masterVolume,
                        crossfade = crossfade,
                        isCurrentlyPlaying = wasPlaying
                    )
                }
            }

            if (targetGeneration != sceneGeneration || !isActive) return@launch

            if (shouldPlay && !wasPlaying) {
                playAll()
            } else {
                notifyMediaSession()
            }

            if (crossfade) {
                transitionResetJob = scope.launch {
                    delay(1250L)
                    if (targetGeneration == sceneGeneration) {
                        _uiState.update { it.copy(isTransitioning = false) }
                    }
                }
            }
        }
    }

    fun playAll() {
        playAllInternal()
    }

    private fun playAllInternal(): Boolean {
        hasUserInteracted = true
        val state = _uiState.value
        val activeScene = state.activeScene ?: SceneCatalog.defaultScene
        val wasStopped = state.playbackStatus == PlaybackStatus.STOPPED

        // Ensure at least channels are ready if all channels were individually paused
        val updatedChannels = if (state.channels.none { it.isPlaying }) {
            state.channels.map { it.copy(isPlaying = true) }
        } else {
            state.channels
        }

        when (audioFocusClient?.requestFocus() ?: FocusRequestOutcome.GRANTED) {
            FocusRequestOutcome.GRANTED -> {
                resumeOnAudioFocusGain = false
                mixerEngine.setDucking(false)
            }
            FocusRequestOutcome.DELAYED -> {
                resumeOnAudioFocusGain = true
                return false
            }
            FocusRequestOutcome.FAILED -> {
                return false
            }
        }

        // If we were fading out from a sleep timer, cancel the fade and reset timer if expired
        if (state.playbackStatus == PlaybackStatus.FADING_OUT) {
            sleepTimerGeneration++
            cancelSleepTimerInternal(clearFade = true)
            timerRemainingMs = 0L
            timerEndTimeMs = null
            _uiState.update {
                it.copy(
                    sleepTimer = SleepTimerState()
                )
            }
        }

        // Resume any paused sleep timer alongside playback
        val activeTimer = _uiState.value.sleepTimer
        var pendingExpiryTimerGen: Long? = null
        if (activeTimer.isActive && activeTimer.isPaused) {
            if (timerRemainingMs > 0L) {
                val timerGen = ++sleepTimerGeneration
                timerEndTimeMs = elapsedRealtimeMs() + timerRemainingMs
                _uiState.update {
                    it.copy(
                        channels = updatedChannels,
                        playbackStatus = PlaybackStatus.PLAYING,
                        sleepTimer = it.sleepTimer.copy(
                            isPaused = false,
                            isFadingOut = false
                        )
                    )
                }
                launchSleepTimerLoop(timerGen, activeTimerFadeDurationSeconds)
            } else {
                val timerGen = ++sleepTimerGeneration
                pendingExpiryTimerGen = timerGen
                timerRemainingMs = 0L
                timerEndTimeMs = null
                _uiState.update {
                    it.copy(
                        channels = updatedChannels,
                        playbackStatus = PlaybackStatus.FADING_OUT,
                        sleepTimer = it.sleepTimer.copy(
                            isActive = true,
                            remainingSeconds = 0,
                            isFadingOut = true,
                            isPaused = false
                        )
                    )
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    channels = updatedChannels,
                    playbackStatus = PlaybackStatus.PLAYING
                )
            }
        }

        if (updatedChannels !== state.channels) {
            scope.launch(ioDispatcher) {
                updatedChannels.forEach { ch -> preferences.setChannelPlaying(ch.spec.id, true) }
            }
        }

        val playGeneration = sceneGeneration
        scope.launch {
            var started = false
            sceneLoadMutex.withLock {
                val currentState = _uiState.value
                if (!currentState.isPlayingOrFading || playGeneration != sceneGeneration) {
                    return@withLock
                }
                val sceneToPlay = currentState.activeScene ?: activeScene
                val channelsToPlay = currentState.channels
                val volumeToPlay = currentState.masterVolume
                withContext(ioDispatcher) {
                    // Load only when the target scene isn't loaded yet or starting fresh from STOPPED
                    if (!mixerEngine.hasLoadedScene(sceneToPlay.id) || wasStopped) {
                        mixerEngine.loadScene(
                            scene = sceneToPlay,
                            channelStates = channelsToPlay,
                            masterVolume = volumeToPlay,
                            crossfade = false,
                            isCurrentlyPlaying = false
                        )
                    }
                    mixerEngine.updateChannelStates(channelsToPlay)
                    mixerEngine.setMasterVolume(volumeToPlay)
                    mixerEngine.startPlayback()
                }
                started = true
            }
            if (started) {
                if (startMediaServiceIfNeeded()) {
                    if (pendingExpiryTimerGen != null && pendingExpiryTimerGen == sleepTimerGeneration) {
                        triggerSleepTimerExpiryFade(pendingExpiryTimerGen, activeTimerFadeDurationSeconds)
                    } else {
                        notifyMediaSession()
                    }
                } else {
                    stopAll()
                }
            }
        }
        return true
    }

    fun pauseAll(fromAudioFocus: Boolean = false) {
        if (!fromAudioFocus) {
            resumeOnAudioFocusGain = false
            audioFocusClient?.abandonFocus()
            mixerEngine.setDucking(false)
        }
        val state = _uiState.value
        if (state.playbackStatus == PlaybackStatus.PAUSED || state.playbackStatus == PlaybackStatus.STOPPED) {
            return
        }

        var updatedTimer = state.sleepTimer
        if (state.sleepTimer.isActive) {
            sleepTimerGeneration++
            val wasFading = state.sleepTimer.isFadingOut || state.playbackStatus == PlaybackStatus.FADING_OUT
            if (wasFading) {
                cancelSleepTimerInternal(clearFade = true)
                timerRemainingMs = 0L
                timerEndTimeMs = null
                updatedTimer = state.sleepTimer.copy(
                    isPaused = true,
                    isFadingOut = false,
                    remainingSeconds = 0
                )
            } else {
                cancelSleepTimerInternal(clearFade = false)
                val remaining = timerEndTimeMs?.let { (it - elapsedRealtimeMs()).coerceAtLeast(0L) }
                    ?: timerRemainingMs
                timerRemainingMs = remaining
                timerEndTimeMs = null
                updatedTimer = state.sleepTimer.copy(
                    isPaused = true,
                    isFadingOut = false,
                    remainingSeconds = ((remaining + 999L) / 1000L).toInt()
                )
            }
        }

        _uiState.update {
            it.copy(
                playbackStatus = PlaybackStatus.PAUSED,
                sleepTimer = updatedTimer
            )
        }
        mixerEngine.pauseOrStopPlayback()
        notifyMediaSession()
    }

    fun stopAll() {
        resumeOnAudioFocusGain = false
        transitionResetJob?.cancel()
        sleepTimerGeneration++
        cancelSleepTimerInternal(clearFade = true)
        timerRemainingMs = 0L
        timerEndTimeMs = null
        audioFocusClient?.abandonFocus()
        mixerEngine.setDucking(false)

        _uiState.update {
            it.copy(
                playbackStatus = PlaybackStatus.STOPPED,
                isTransitioning = false,
                sleepTimer = SleepTimerState()
            )
        }
        mixerEngine.pauseOrStopPlayback()
        notifyMediaSession()
    }

    fun togglePlayPause() {
        when (_uiState.value.playbackStatus) {
            PlaybackStatus.PLAYING, PlaybackStatus.FADING_OUT -> pauseAll()
            PlaybackStatus.PAUSED, PlaybackStatus.STOPPED -> playAll()
        }
    }

    fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _uiState.update { it.copy(masterVolume = clamped) }
        mixerEngine.setMasterVolume(clamped)
        cachedPrefs = cachedPrefs?.copy(masterVolume = clamped)

        persistMasterVolumeJob?.cancel()
        persistMasterVolumeJob = scope.launch(ioDispatcher) {
            delay(150L)
            preferences.setMasterVolume(clamped)
        }
    }

    fun setChannelVolume(channelId: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        val updated = _uiState.value.channels.map { ch ->
            if (ch.spec.id == channelId) ch.copy(volume = clamped) else ch
        }
        _uiState.update { it.copy(channels = updated) }
        mixerEngine.updateChannelStates(updated)
        updateCachedPrefs(channelId, volume = clamped)

        persistVolumeJob?.cancel()
        persistVolumeJob = scope.launch(ioDispatcher) {
            delay(150L)
            preferences.setChannelVolume(channelId, clamped)
        }
    }

    fun toggleChannelMute(channelId: String) {
        var newMuted = false
        val updated = _uiState.value.channels.map { ch ->
            if (ch.spec.id == channelId) {
                newMuted = !ch.isMuted
                ch.copy(isMuted = newMuted)
            } else ch
        }
        _uiState.update { it.copy(channels = updated) }
        mixerEngine.updateChannelStates(updated)
        updateCachedPrefs(channelId, muted = newMuted)

        scope.launch(ioDispatcher) {
            preferences.setChannelMuted(channelId, newMuted)
        }
    }

    fun toggleChannelPlay(channelId: String) {
        var newPlaying = true
        val updated = _uiState.value.channels.map { ch ->
            if (ch.spec.id == channelId) {
                newPlaying = !ch.isPlaying
                ch.copy(isPlaying = newPlaying)
            } else ch
        }
        _uiState.update { it.copy(channels = updated) }
        mixerEngine.updateChannelStates(updated)
        updateCachedPrefs(channelId, playing = newPlaying)

        scope.launch(ioDispatcher) {
            preferences.setChannelPlaying(channelId, newPlaying)
        }

        // If user starts an individual channel while master was stopped/paused, start master playback
        if (newPlaying && !_uiState.value.isPlayingOrFading) {
            playAll()
        }
    }

    fun resetActiveSceneToDefaults() {
        val activeScene = _uiState.value.activeScene ?: return
        val resetChannels = activeScene.channels.map { spec ->
            ChannelState(
                spec = spec,
                volume = spec.defaultVolume,
                isMuted = false,
                isPlaying = true
            )
        }
        _uiState.update { it.copy(channels = resetChannels) }
        mixerEngine.updateChannelStates(resetChannels)

        resetChannels.forEach { ch ->
            updateCachedPrefs(ch.spec.id, volume = ch.volume, muted = false, playing = true)
        }

        scope.launch(ioDispatcher) {
            preferences.resetSceneDefaults(activeScene)
        }
    }

    fun resetAllToDefaults() {
        val activeScene = _uiState.value.activeScene ?: SceneCatalog.defaultScene
        val resetChannels = activeScene.channels.map { spec ->
            ChannelState(
                spec = spec,
                volume = spec.defaultVolume,
                isMuted = false,
                isPlaying = true
            )
        }
        _uiState.update {
            it.copy(
                masterVolume = 0.85f,
                channels = resetChannels
            )
        }
        mixerEngine.setMasterVolume(0.85f)
        mixerEngine.updateChannelStates(resetChannels)

        cachedPrefs = cachedPrefs?.copy(
            masterVolume = 0.85f,
            channelVolumes = SceneCatalog.scenes.flatMap { it.channels }.associate { it.id to it.defaultVolume },
            channelMutes = emptyMap(),
            channelPlaybackStates = emptyMap()
        )

        scope.launch(ioDispatcher) {
            preferences.resetAllDefaults(SceneCatalog.scenes)
        }
    }

    fun setAutoPlayOnSceneSwitch(enabled: Boolean) {
        _uiState.update { it.copy(autoPlayOnSceneSwitch = enabled) }
        cachedPrefs = cachedPrefs?.copy(autoPlayOnSceneSwitch = enabled)
        scope.launch(ioDispatcher) {
            preferences.setAutoPlayOnSceneSwitch(enabled)
        }
    }

    fun setSmoothCrossfadeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(smoothCrossfadeEnabled = enabled) }
        cachedPrefs = cachedPrefs?.copy(smoothCrossfadeEnabled = enabled)
        scope.launch(ioDispatcher) {
            preferences.setSmoothCrossfadeEnabled(enabled)
        }
    }

    fun setBatterySaverOptimizationEnabled(enabled: Boolean) {
        hasUserInteracted = true
        _uiState.update { it.copy(batterySaverOptimizationEnabled = enabled) }
        cachedPrefs = cachedPrefs?.copy(batterySaverOptimizationEnabled = enabled)
        applyBatterySaverAudioMode()
        scope.launch(ioDispatcher) {
            preferences.setBatterySaverOptimizationEnabled(enabled)
        }
    }

    private fun applyBatterySaverAudioMode() {
        mixerEngine.setLowPowerMode(_uiState.value.isBatterySaverOptimizationActive)
    }

    fun startSleepTimer(preset: SleepTimerPreset) {
        startSleepTimerSeconds(preset.totalSeconds, preset)
    }

    internal fun startSleepTimerSeconds(
        totalSeconds: Int,
        preset: SleepTimerPreset? = null,
        fadeDurationSeconds: Float = SLEEP_FADE_DURATION_SECONDS
    ) {
        val timerGen = ++sleepTimerGeneration
        val safeTotalSeconds = totalSeconds.coerceAtLeast(1)
        activeTimerFadeDurationSeconds = fadeDurationSeconds
        cancelSleepTimerInternal(clearFade = true)
        timerRemainingMs = 0L
        timerEndTimeMs = null

        val statusBeforeStart = _uiState.value.playbackStatus
        if (statusBeforeStart == PlaybackStatus.FADING_OUT) {
            _uiState.update {
                it.copy(
                    playbackStatus = PlaybackStatus.PLAYING,
                    sleepTimer = SleepTimerState()
                )
            }
        } else if (statusBeforeStart == PlaybackStatus.PAUSED || statusBeforeStart == PlaybackStatus.STOPPED) {
            _uiState.update { it.copy(sleepTimer = SleepTimerState()) }
            if (!playAllInternal()) {
                notifyMediaSession()
                return
            }
        }

        if (timerGen != sleepTimerGeneration || !_uiState.value.isPlayingOrFading) {
            return
        }

        timerRemainingMs = safeTotalSeconds * 1000L
        timerEndTimeMs = elapsedRealtimeMs() + timerRemainingMs
        _uiState.update {
            it.copy(
                playbackStatus = PlaybackStatus.PLAYING,
                sleepTimer = SleepTimerState(
                    isActive = true,
                    selectedPreset = preset,
                    totalSeconds = safeTotalSeconds,
                    remainingSeconds = safeTotalSeconds,
                    isFadingOut = false,
                    isPaused = false
                )
            )
        }
        notifyMediaSession()

        launchSleepTimerLoop(timerGen, fadeDurationSeconds)
    }

    private fun launchSleepTimerLoop(timerGen: Long, fadeDurationSeconds: Float) {
        sleepTimerJob?.cancel()
        sleepTimerJob = scope.launch {
            while (isActive && timerGen == sleepTimerGeneration) {
                val endMs = timerEndTimeMs ?: break
                val remainingMs = (endMs - elapsedRealtimeMs()).coerceAtLeast(0L)
                timerRemainingMs = remainingMs
                val remainingSec = ((remainingMs + 999L) / 1000L).toInt()

                _uiState.update {
                    if (!it.sleepTimer.isActive || it.sleepTimer.isPaused) {
                        it
                    } else {
                        it.copy(
                            sleepTimer = it.sleepTimer.copy(
                                isActive = true,
                                remainingSeconds = remainingSec
                            )
                        )
                    }
                }
                notifyMediaSession()

                if (remainingMs <= 0L) {
                    triggerSleepTimerExpiryFade(timerGen, fadeDurationSeconds)
                    break
                }
                val tickIntervalMs = if (
                    _uiState.value.isBatterySaverOptimizationActive &&
                    remainingMs > BATTERY_SAVER_FINE_TIMER_WINDOW_MS
                ) {
                    val excessAboveWindow = remainingMs - BATTERY_SAVER_FINE_TIMER_WINDOW_MS
                    minOf(BATTERY_SAVER_SLEEP_TIMER_TICK_MS, excessAboveWindow).coerceAtLeast(1000L)
                } else {
                    1000L
                }
                delay(tickIntervalMs)
            }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerGeneration++
        val wasFading = _uiState.value.sleepTimer.isFadingOut ||
            _uiState.value.playbackStatus == PlaybackStatus.FADING_OUT
        cancelSleepTimerInternal(clearFade = true)
        timerRemainingMs = 0L
        timerEndTimeMs = null
        _uiState.update {
            it.copy(
                playbackStatus = if (wasFading && it.playbackStatus == PlaybackStatus.FADING_OUT) {
                    PlaybackStatus.PLAYING
                } else {
                    it.playbackStatus
                },
                sleepTimer = SleepTimerState()
            )
        }
        notifyMediaSession()
    }

    private fun triggerSleepTimerExpiryFade(timerGen: Long, fadeDurationSeconds: Float) {
        if (timerGen != sleepTimerGeneration) return
        timerRemainingMs = 0L
        timerEndTimeMs = null
        _uiState.update {
            it.copy(
                playbackStatus = PlaybackStatus.FADING_OUT,
                sleepTimer = it.sleepTimer.copy(
                    isActive = true,
                    remainingSeconds = 0,
                    isFadingOut = true,
                    isPaused = false
                )
            )
        }
        notifyMediaSession()

        mixerEngine.beginSleepFadeOut(durationSeconds = fadeDurationSeconds) {
            scope.launch {
                if (timerGen == sleepTimerGeneration) {
                    stopAll()
                }
            }
        }
    }

    private fun cancelSleepTimerInternal(clearFade: Boolean) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (clearFade) {
            mixerEngine.cancelSleepFadeOut()
        }
    }

    fun onBecomingNoisy() {
        if (_uiState.value.isPlayingOrFading) {
            pauseAll(fromAudioFocus = false)
        }
    }

    override fun onAudioFocusLost(transient: Boolean) {
        if (_uiState.value.isPlayingOrFading) {
            resumeOnAudioFocusGain = transient
            if (!transient) {
                mixerEngine.setDucking(false)
            }
            pauseAll(fromAudioFocus = true)
        } else if (!transient) {
            resumeOnAudioFocusGain = false
            mixerEngine.setDucking(false)
        }
    }

    override fun onAudioFocusDuck(duck: Boolean) {
        mixerEngine.setDucking(duck)
    }

    override fun onAudioFocusGained() {
        mixerEngine.setDucking(false)
        val status = _uiState.value.playbackStatus
        if (resumeOnAudioFocusGain && (status == PlaybackStatus.PAUSED || status == PlaybackStatus.STOPPED)) {
            resumeOnAudioFocusGain = false
            playAll()
        }
    }

    fun release() {
        powerSaveJob?.cancel()
        powerSaveMonitor?.stop()
        sceneLoadJob?.cancel()
        transitionResetJob?.cancel()
        sleepTimerJob?.cancel()
        persistVolumeJob?.cancel()
        persistMasterVolumeJob?.cancel()
        audioFocusClient?.abandonFocus()
        mixerEngine.release()
    }

    private fun startMediaServiceIfNeeded(): Boolean {
        if (!enableAndroidServiceBridge) return true
        return try {
            val intent = Intent(appContext, DriftPlaybackService::class.java)
            serviceStarter(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground playback service", e)
            false
        }
    }

    private fun notifyMediaSession() {
        stateChangeListener?.invoke()
    }

    private fun buildInitialState(prefs: UserPreferences): DriftPlaybackUiState {
        val scene = SceneCatalog.findScene(prefs.lastSceneId)
        val channels = buildChannelsForScene(scene, prefs)
        return DriftPlaybackUiState(
            scenes = SceneCatalog.scenes,
            activeScene = scene,
            channels = channels,
            masterVolume = prefs.masterVolume,
            playbackStatus = PlaybackStatus.STOPPED,
            autoPlayOnSceneSwitch = prefs.autoPlayOnSceneSwitch,
            smoothCrossfadeEnabled = prefs.smoothCrossfadeEnabled,
            hasTestAudioStems = scene.channels.any { it.sourceOrigin == AudioSourceOrigin.SYNTHESIZED_TEST },
            batterySaverOptimizationEnabled = prefs.batterySaverOptimizationEnabled
        )
    }

    private fun buildChannelsForScene(
        scene: SceneSpec,
        prefs: UserPreferences?
    ): List<ChannelState> {
        return scene.channels.map { spec ->
            ChannelState(
                spec = spec,
                volume = prefs?.channelVolumes?.get(spec.id) ?: spec.defaultVolume,
                isMuted = prefs?.channelMutes?.get(spec.id) ?: false,
                isPlaying = prefs?.channelPlaybackStates?.get(spec.id) ?: true
            )
        }
    }

    private fun updateCachedPrefs(
        channelId: String,
        volume: Float? = null,
        muted: Boolean? = null,
        playing: Boolean? = null
    ) {
        val prev = cachedPrefs ?: return
        val newVols = if (volume != null) prev.channelVolumes + (channelId to volume) else prev.channelVolumes
        val newMutes = if (muted != null) prev.channelMutes + (channelId to muted) else prev.channelMutes
        val newPlays = if (playing != null) prev.channelPlaybackStates + (channelId to playing) else prev.channelPlaybackStates
        cachedPrefs = prev.copy(
            channelVolumes = newVols,
            channelMutes = newMutes,
            channelPlaybackStates = newPlays
        )
    }
}
