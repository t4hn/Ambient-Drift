package com.example

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioFocusClient
import com.example.audio.AudioFocusHelper
import com.example.audio.AudioFocusListener
import com.example.audio.AudioOutputSink
import com.example.audio.AudioStem
import com.example.audio.DriftAudioMixerEngine
import com.example.audio.FocusRequestOutcome
import com.example.data.PreferencesStorage
import com.example.model.PlaybackStatus
import com.example.model.SceneCatalog
import com.example.model.SceneSpec
import com.example.model.SleepTimerPreset
import com.example.model.SleepTimerState
import com.example.model.UserPreferences
import com.example.service.DriftPlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DriftPhase2a1RegressionTest {

    private class FakePreferencesStorage : PreferencesStorage {
        var lastSceneId: String = SceneCatalog.SCENE_STORM_ID
        var masterVolume: Float = 0.85f
        var autoPlay: Boolean = true
        var crossfade: Boolean = true
        val channelVolumes = mutableMapOf<String, Float>()
        val channelMutes = mutableMapOf<String, Boolean>()
        val channelPlayStates = mutableMapOf<String, Boolean>()

        private val flow = MutableStateFlow(snapshot())
        override val preferencesFlow: Flow<UserPreferences> = flow

        private fun snapshot() = UserPreferences(
            lastSceneId = lastSceneId,
            masterVolume = masterVolume,
            autoPlayOnSceneSwitch = autoPlay,
            smoothCrossfadeEnabled = crossfade,
            channelVolumes = channelVolumes.toMap(),
            channelMutes = channelMutes.toMap(),
            channelPlaybackStates = channelPlayStates.toMap()
        )

        override suspend fun readInitialPreferences(): UserPreferences = snapshot()

        override suspend fun setLastSceneId(sceneId: String) {
            lastSceneId = sceneId
            flow.value = snapshot()
        }

        override suspend fun setMasterVolume(volume: Float) {
            masterVolume = volume
            flow.value = snapshot()
        }

        override suspend fun setChannelVolume(channelId: String, volume: Float) {
            channelVolumes[channelId] = volume
            flow.value = snapshot()
        }

        override suspend fun setChannelMuted(channelId: String, muted: Boolean) {
            channelMutes[channelId] = muted
            flow.value = snapshot()
        }

        override suspend fun setChannelPlaying(channelId: String, playing: Boolean) {
            channelPlayStates[channelId] = playing
            flow.value = snapshot()
        }

        override suspend fun setAutoPlayOnSceneSwitch(enabled: Boolean) {
            autoPlay = enabled
            flow.value = snapshot()
        }

        override suspend fun setSmoothCrossfadeEnabled(enabled: Boolean) {
            crossfade = enabled
            flow.value = snapshot()
        }

        override suspend fun resetSceneDefaults(scene: SceneSpec) {
            scene.channels.forEach {
                channelVolumes[it.id] = it.defaultVolume
                channelMutes[it.id] = false
                channelPlayStates[it.id] = true
            }
            flow.value = snapshot()
        }

        override suspend fun resetAllDefaults(scenes: List<SceneSpec>) {
            masterVolume = 0.85f
            scenes.forEach { resetSceneDefaults(it) }
        }
    }

    private fun createEngine(stemLoadCounter: AtomicInteger? = null): DriftAudioMixerEngine {
        return DriftAudioMixerEngine(
            stemLoader = { _, _ ->
                stemLoadCounter?.incrementAndGet()
                AudioStem(FloatArray(44_100) { 0.2f }, FloatArray(44_100) { 0.2f }, DriftAudioMixerEngine.SAMPLE_RATE)
            },
            sinkFactory = {
                object : AudioOutputSink {
                    override fun start() {}
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int {
                        Thread.sleep(5)
                        return shortCount
                    }
                    override fun pauseAndFlush() {}
                    override fun release() {}
                }
            }
        )
    }

    private fun TestScope.createController(
        engine: DriftAudioMixerEngine,
        prefs: PreferencesStorage = FakePreferencesStorage(),
        enableServiceBridge: Boolean = false
    ): DriftPlaybackController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DriftPlaybackController(
            appContext = context,
            preferences = prefs,
            mixerEngine = engine,
            enableAndroidServiceBridge = enableServiceBridge,
            elapsedRealtimeMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher
        )
    }

    // -------------------------------------------------------------------------
    // 1. Pause & Resume Behaviour
    // -------------------------------------------------------------------------

    @Test
    fun `pause and resume preserves all channel positions without reloading active scene`() = runTest {
        val stemLoads = AtomicInteger(0)
        val engine = createEngine(stemLoads)
        val controller = createController(engine)
        runCurrent()

        val initialStorm = SceneCatalog.scenes[0]
        assertEquals(initialStorm.id, controller.uiState.value.activeScene?.id)
        val loadsAfterInit = stemLoads.get()

        controller.playAll()
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)

        // Advance active channels deterministically via renderFrames
        val pcm = ShortArray(2048 * 2)
        repeat(5) { engine.renderFrames(pcm, 2048) }
        val cursorsBeforePause = engine.getChannelCursors()
        initialStorm.channels.forEach { ch ->
            assertTrue("Channel ${ch.id} should advance", (cursorsBeforePause[ch.id] ?: 0) >= 10_240)
        }

        controller.pauseAll()
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.uiState.value.playbackStatus)
        val cursorsWhilePaused = engine.getChannelCursors()
        initialStorm.channels.forEach { ch ->
            assertTrue((cursorsWhilePaused[ch.id] ?: 0) >= (cursorsBeforePause[ch.id] ?: 0))
        }

        // Resume: must NOT reload scene or reset cursors
        controller.playAll()
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
        val cursorsOnResume = engine.getChannelCursors()
        initialStorm.channels.forEach { ch ->
            assertTrue(
                "Channel ${ch.id} restarted to zero on resume!",
                (cursorsOnResume[ch.id] ?: 0) >= (cursorsWhilePaused[ch.id] ?: 0)
            )
        }
        assertEquals("Resuming must not reload stems", loadsAfterInit, stemLoads.get())

        // Switching to a genuinely different scene must load the new scene and reset its cursors to 0
        controller.selectScene(SceneCatalog.SCENE_DEEP_SPACE_ID, autoStartIfConfigured = true)
        runCurrent()
        assertEquals(SceneCatalog.SCENE_DEEP_SPACE_ID, controller.uiState.value.activeScene?.id)
        assertTrue(engine.hasLoadedScene(SceneCatalog.SCENE_DEEP_SPACE_ID))
        assertTrue("Switching to new scene should load its stems", stemLoads.get() > loadsAfterInit)

        controller.release()
    }

    // -------------------------------------------------------------------------
    // 2. Audio Focus Ducking Recovery
    // -------------------------------------------------------------------------

    @Test
    fun `ducking restores after AUDIOFOCUS_GAIN, manual resume, and abandon without altering volumes`() = runTest {
        val engine = createEngine()
        var focusOutcome = FocusRequestOutcome.GRANTED
        var abandonCount = 0
        val fakeFocus = object : AudioFocusClient {
            override fun requestFocus(): FocusRequestOutcome = focusOutcome
            override fun abandonFocus() {
                abandonCount++
            }
        }
        val controller = createController(engine).apply { audioFocusClient = fakeFocus }
        runCurrent()

        controller.setMasterVolume(0.72f)
        controller.setChannelVolume("storm_rainfall", 0.64f)
        controller.playAll()
        runCurrent()
        assertEquals(1.0f, engine.getDuckTarget(), 0.001f)

        // Transient ducking reduces duckTarget without altering user volume sliders
        controller.onAudioFocusDuck(true)
        assertEquals(0.25f, engine.getDuckTarget(), 0.001f)
        assertEquals(0.72f, controller.uiState.value.masterVolume, 0.001f)
        assertEquals(0.64f, controller.uiState.value.channels.first { it.spec.id == "storm_rainfall" }.volume, 0.001f)

        // Restoring focus via AUDIOFOCUS_GAIN restores normal duckTarget
        controller.onAudioFocusGained()
        runCurrent()
        assertEquals(1.0f, engine.getDuckTarget(), 0.001f)

        // Duck -> transient focus loss -> manual resume (no AUDIOFOCUS_GAIN dispatched) -> duck cleared
        controller.onAudioFocusDuck(true)
        assertEquals(0.25f, engine.getDuckTarget(), 0.001f)
        controller.onAudioFocusLost(transient = true)
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.uiState.value.playbackStatus)

        focusOutcome = FocusRequestOutcome.GRANTED
        controller.playAll()
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
        assertEquals(1.0f, engine.getDuckTarget(), 0.001f)

        // Duck -> stop/abandon focus -> fresh playback starts un-ducked, volumes untouched
        controller.onAudioFocusDuck(true)
        assertEquals(0.25f, engine.getDuckTarget(), 0.001f)
        controller.stopAll()
        runCurrent()
        assertTrue(abandonCount >= 1)
        assertEquals(1.0f, engine.getDuckTarget(), 0.001f)

        controller.playAll()
        runCurrent()
        assertEquals(1.0f, engine.getDuckTarget(), 0.001f)
        assertEquals(0.72f, controller.uiState.value.masterVolume, 0.001f)
        assertEquals(0.64f, controller.uiState.value.channels.first { it.spec.id == "storm_rainfall" }.volume, 0.001f)

        controller.release()
    }

    @Test
    fun `AudioFocusHelper clears ducking callback on abandonFocus and fresh requestFocus`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val shadowAudioManager = shadowOf(audioManager)
        val duckEvents = mutableListOf<Boolean>()

        val helper = AudioFocusHelper(
            context = context,
            listener = object : AudioFocusListener {
                override fun onAudioFocusLost(transient: Boolean) {}
                override fun onAudioFocusDuck(duck: Boolean) {
                    duckEvents.add(duck)
                }
                override fun onAudioFocusGained() {}
            }
        )

        shadowAudioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        assertEquals(FocusRequestOutcome.GRANTED, helper.requestFocus())

        helper.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(listOf(true), duckEvents)

        helper.abandonFocus()
        assertEquals(listOf(true, false), duckEvents)

        // Fresh grant also leaves ducking cleared even after transient duck + loss
        helper.requestFocus()
        helper.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        helper.focusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(listOf(true, false, true, false), duckEvents)
    }

    // -------------------------------------------------------------------------
    // 3. Sleep Timer State Management
    // -------------------------------------------------------------------------

    @Test
    fun `all sleep timer presets count down, enter FADING_OUT, and stop cleanly`() = runTest {
        for (preset in SleepTimerPreset.entries) {
            val engine = createEngine()
            val controller = createController(engine)
            runCurrent()

            controller.startSleepTimer(preset)
            runCurrent()
            val active = controller.uiState.value.sleepTimer
            assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
            assertTrue(active.isActive)
            assertFalse(active.isPaused)
            assertFalse(active.isFadingOut)
            assertEquals(preset, active.selectedPreset)
            assertEquals(preset.totalSeconds, active.totalSeconds)
            assertEquals(preset.totalSeconds, active.remainingSeconds)

            // Halfway check
            advanceTimeBy((preset.totalSeconds / 2) * 1000L)
            runCurrent()
            assertEquals(preset.totalSeconds / 2, controller.uiState.value.sleepTimer.remainingSeconds)
            assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)

            // Expire remaining duration -> enters FADING_OUT
            advanceTimeBy((preset.totalSeconds / 2) * 1000L)
            runCurrent()
            assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)
            assertTrue(controller.uiState.value.sleepTimer.isFadingOut)
            assertEquals(0, controller.uiState.value.sleepTimer.remainingSeconds)

            // Drive mixer fade-out to completion -> controller moves to STOPPED
            val pcm = ShortArray(2048 * 2)
            var guard = 0
            while (controller.uiState.value.playbackStatus != PlaybackStatus.STOPPED && guard < 200) {
                engine.renderFrames(pcm, 2048)
                runCurrent()
                guard++
            }
            assertEquals(PlaybackStatus.STOPPED, controller.uiState.value.playbackStatus)
            assertEquals(SleepTimerState(), controller.uiState.value.sleepTimer)
            controller.release()
        }
    }

    @Test
    fun `restarting or cancelling during FADING_OUT restores PLAYING and cancels fade`() = runTest {
        val engine = createEngine()
        val controller = createController(engine)
        runCurrent()

        controller.startSleepTimerSeconds(totalSeconds = 5, preset = SleepTimerPreset.FIFTEEN, fadeDurationSeconds = 1.0f)
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)
        assertTrue(controller.uiState.value.sleepTimer.isFadingOut)

        // Render partially through fade-out, then restart with 30m preset
        val pcm = ShortArray(2048 * 2)
        repeat(6) { engine.renderFrames(pcm, 2048) }
        controller.startSleepTimer(SleepTimerPreset.THIRTY)
        runCurrent()

        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
        assertTrue(controller.uiState.value.sleepTimer.isActive)
        assertFalse(controller.uiState.value.sleepTimer.isFadingOut)
        assertEquals(1800, controller.uiState.value.sleepTimer.remainingSeconds)
        assertEquals(1.0f, engine.getSleepFadeTarget(), 0.001f)

        // Render more frames; old fade-out must NOT stop playback
        repeat(20) { engine.renderFrames(pcm, 2048) }
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)

        // Trigger expiry on a short timer, then cancel during FADING_OUT -> stays PLAYING & timer clears
        controller.startSleepTimerSeconds(totalSeconds = 2, preset = SleepTimerPreset.FIFTEEN, fadeDurationSeconds = 1.0f)
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)

        repeat(6) { engine.renderFrames(pcm, 2048) }
        controller.cancelSleepTimer()
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
        assertEquals(SleepTimerState(), controller.uiState.value.sleepTimer)
        assertEquals(1.0f, engine.getSleepFadeTarget(), 0.001f)

        // Cancelling an active non-fading timer stops its countdown
        controller.startSleepTimer(SleepTimerPreset.FIFTEEN)
        runCurrent()
        advanceTimeBy(10_000L)
        runCurrent()
        assertEquals(890, controller.uiState.value.sleepTimer.remainingSeconds)
        controller.cancelSleepTimer()
        runCurrent()
        assertEquals(SleepTimerState(), controller.uiState.value.sleepTimer)
        advanceTimeBy(900_000L)
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)

        controller.release()
    }

    @Test
    fun `pausing freezes sleep timer and resuming continues remaining countdown`() = runTest {
        val engine = createEngine()
        val controller = createController(engine)
        runCurrent()

        controller.startSleepTimer(SleepTimerPreset.FIFTEEN)
        runCurrent()
        advanceTimeBy(300_000L) // 5 minutes elapsed -> 10m remaining
        runCurrent()
        assertEquals(600, controller.uiState.value.sleepTimer.remainingSeconds)

        // Pause playback -> timer freezes and advancing 2 hours does NOT drain or stop it
        controller.pauseAll()
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.uiState.value.playbackStatus)
        assertTrue(controller.uiState.value.sleepTimer.isActive)
        assertTrue(controller.uiState.value.sleepTimer.isPaused)
        assertEquals(600, controller.uiState.value.sleepTimer.remainingSeconds)

        advanceTimeBy(7_200_000L)
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.uiState.value.playbackStatus)
        assertEquals(600, controller.uiState.value.sleepTimer.remainingSeconds)

        // Resume -> countdown continues from 600s and finishes cleanly
        controller.playAll()
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
        assertFalse(controller.uiState.value.sleepTimer.isPaused)

        advanceTimeBy(599_000L)
        runCurrent()
        assertEquals(1, controller.uiState.value.sleepTimer.remainingSeconds)
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)

        controller.release()
    }

    @Test
    fun `sleep timer does not activate when playback fails or is delayed by focus or service`() = runTest {
        val engine = createEngine()
        var focusOutcome = FocusRequestOutcome.FAILED
        val fakeFocus = object : AudioFocusClient {
            override fun requestFocus(): FocusRequestOutcome = focusOutcome
            override fun abandonFocus() {}
        }

        // Case 1: FAILED audio focus
        val controller = createController(engine).apply { audioFocusClient = fakeFocus }
        runCurrent()
        controller.startSleepTimer(SleepTimerPreset.FIFTEEN)
        runCurrent()
        assertEquals(PlaybackStatus.STOPPED, controller.uiState.value.playbackStatus)
        assertFalse(controller.uiState.value.sleepTimer.isActive)
        assertNull(controller.uiState.value.sleepTimer.selectedPreset)

        // Case 2: DELAYED audio focus
        focusOutcome = FocusRequestOutcome.DELAYED
        controller.startSleepTimer(SleepTimerPreset.THIRTY)
        runCurrent()
        assertEquals(PlaybackStatus.STOPPED, controller.uiState.value.playbackStatus)
        assertFalse(controller.uiState.value.sleepTimer.isActive)
        controller.release()

        // Case 3: Foreground service failure on start
        val engine2 = createEngine()
        val controllerWithService = createController(engine2, enableServiceBridge = true).apply {
            serviceStarter = { throw IllegalStateException("FGS blocked") }
        }
        runCurrent()
        controllerWithService.startSleepTimer(SleepTimerPreset.SIXTY)
        runCurrent()
        assertEquals(PlaybackStatus.STOPPED, controllerWithService.uiState.value.playbackStatus)
        assertFalse(controllerWithService.uiState.value.sleepTimer.isActive)
        controllerWithService.release()
    }

    @Test
    fun `rapid sleep timer restarts cancel earlier jobs so only final timer fires`() = runTest {
        val engine = createEngine()
        val controller = createController(engine)
        runCurrent()

        controller.startSleepTimerSeconds(totalSeconds = 10, preset = SleepTimerPreset.FIFTEEN, fadeDurationSeconds = 0.5f)
        controller.startSleepTimerSeconds(totalSeconds = 20, preset = SleepTimerPreset.THIRTY, fadeDurationSeconds = 0.5f)
        controller.cancelSleepTimer()
        controller.startSleepTimerSeconds(totalSeconds = 45, preset = SleepTimerPreset.SIXTY, fadeDurationSeconds = 0.5f)
        runCurrent()

        // Advance past 10s and 20s marks: only the 45s timer should be active
        advanceTimeBy(25_000L)
        runCurrent()
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
        assertEquals(20, controller.uiState.value.sleepTimer.remainingSeconds)
        assertEquals(SleepTimerPreset.SIXTY, controller.uiState.value.sleepTimer.selectedPreset)

        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)

        controller.release()
    }

    @Test
    fun `pausing during FADING_OUT and resuming before render thread stops completes fade and stops once`() = runTest {
        val writeEntered = CountDownLatch(1)
        val allowWritesToProceed = CountDownLatch(1)
        val releaseSink = AtomicBoolean(false)

        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ ->
                AudioStem(FloatArray(44_100) { 0.2f }, FloatArray(44_100) { 0.2f }, DriftAudioMixerEngine.SAMPLE_RATE)
            },
            sinkFactory = {
                object : AudioOutputSink {
                    override fun start() {}
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int {
                        writeEntered.countDown()
                        // Hold the background render thread alive deterministically until released
                        if (!releaseSink.get()) {
                            allowWritesToProceed.await(5, TimeUnit.SECONDS)
                        }
                        return shortCount
                    }
                    override fun pauseAndFlush() {}
                    override fun release() {
                        releaseSink.set(true)
                        allowWritesToProceed.countDown()
                    }
                }
            }
        )
        val controller = createController(engine)
        val statusTransitions = mutableListOf<PlaybackStatus>()
        val collectJob = backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            controller.uiState.collect { state ->
                if (statusTransitions.lastOrNull() != state.playbackStatus) {
                    statusTransitions.add(state.playbackStatus)
                }
            }
        }
        runCurrent()

        controller.startSleepTimerSeconds(totalSeconds = 3, preset = SleepTimerPreset.FIFTEEN, fadeDurationSeconds = 0.5f)
        runCurrent()
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        assertTrue(engine.isMixerThreadRunning())

        // Reach FADING_OUT
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)
        assertTrue(controller.uiState.value.sleepTimer.isFadingOut)
        assertEquals(0.0f, engine.getSleepFadeTarget(), 0.001f)

        // Advance fade partially
        val pcm = ShortArray(2048 * 2)
        repeat(4) { engine.renderFrames(pcm, 2048) }
        assertTrue(engine.getSleepFadeCurrent() in 0.1f..0.95f)

        // Pause during FADING_OUT while render thread is still running
        controller.pauseAll()
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.uiState.value.playbackStatus)
        assertTrue(controller.uiState.value.sleepTimer.isActive)
        assertTrue(controller.uiState.value.sleepTimer.isPaused)
        assertFalse(controller.uiState.value.sleepTimer.isFadingOut)
        assertEquals(0, controller.uiState.value.sleepTimer.remainingSeconds)
        assertTrue("Render thread must still be running before resume", engine.isMixerThreadRunning())

        // Resume BEFORE the master pause fade completes / render thread stops
        controller.playAll()
        runCurrent()

        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)
        assertTrue(controller.uiState.value.sleepTimer.isActive)
        assertFalse(controller.uiState.value.sleepTimer.isPaused)
        assertTrue(controller.uiState.value.sleepTimer.isFadingOut)
        assertEquals(
            "startPlayback() must not cancel newly resumed expiry fade",
            0.0f,
            engine.getSleepFadeTarget(),
            0.001f
        )

        // Drive fade-out to completion -> stops exactly once
        var guard = 0
        while (controller.uiState.value.playbackStatus != PlaybackStatus.STOPPED && guard < 100) {
            engine.renderFrames(pcm, 2048)
            runCurrent()
            guard++
        }
        assertEquals(PlaybackStatus.STOPPED, controller.uiState.value.playbackStatus)
        assertEquals(SleepTimerState(), controller.uiState.value.sleepTimer)

        // Let the blocked render thread unblock and finish cleanly
        releaseSink.set(true)
        allowWritesToProceed.countDown()
        var stopWait = 0
        while (engine.isMixerThreadRunning() && stopWait < 100) {
            Thread.sleep(5)
            stopWait++
        }
        runCurrent()
        assertFalse(engine.isMixerThreadRunning())
        assertEquals(
            listOf(
                PlaybackStatus.STOPPED,
                PlaybackStatus.PLAYING,
                PlaybackStatus.FADING_OUT,
                PlaybackStatus.PAUSED,
                PlaybackStatus.FADING_OUT,
                PlaybackStatus.STOPPED
            ),
            statusTransitions
        )

        collectJob.cancel()
        controller.release()
    }

    @Test
    fun `resuming expired paused timer after render thread stops and cancelling resumed fade behave cleanly`() = runTest {
        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ ->
                AudioStem(FloatArray(44_100) { 0.2f }, FloatArray(44_100) { 0.2f }, DriftAudioMixerEngine.SAMPLE_RATE)
            },
            sinkFactory = {
                object : AudioOutputSink {
                    override fun start() {}
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int = shortCount
                    override fun pauseAndFlush() {}
                    override fun release() {}
                }
            }
        )
        val controller = createController(engine)
        runCurrent()

        // Case 1: Pause during FADING_OUT, wait for render thread to stop completely, then resume -> fades & stops
        controller.startSleepTimerSeconds(totalSeconds = 2, preset = SleepTimerPreset.FIFTEEN, fadeDurationSeconds = 0.5f)
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)

        controller.pauseAll()
        runCurrent()
        assertEquals(PlaybackStatus.PAUSED, controller.uiState.value.playbackStatus)

        var stopWait = 0
        while (engine.isMixerThreadRunning() && stopWait < 200) {
            Thread.sleep(5)
            stopWait++
        }
        assertFalse("Render thread should finish pause fade and stop", engine.isMixerThreadRunning())

        // Resume after render thread stopped: must start thread, enter FADING_OUT, fade down, and stop
        controller.playAll()
        runCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)
        assertTrue(controller.uiState.value.sleepTimer.isFadingOut)
        assertEquals(0.0f, engine.getSleepFadeTarget(), 0.001f)
        assertTrue(engine.isMixerThreadRunning())

        var fadeWait = 0
        while (controller.uiState.value.playbackStatus != PlaybackStatus.STOPPED && fadeWait < 200) {
            Thread.sleep(10)
            runCurrent()
            fadeWait++
        }
        assertEquals(PlaybackStatus.STOPPED, controller.uiState.value.playbackStatus)
        assertEquals(SleepTimerState(), controller.uiState.value.sleepTimer)

        // Case 2: Cancel timer immediately after resuming from paused FADING_OUT before async startup runs
        controller.startSleepTimerSeconds(totalSeconds = 2, preset = SleepTimerPreset.FIFTEEN, fadeDurationSeconds = 0.5f)
        runCurrent()
        advanceTimeBy(2_000L)
        runCurrent()
        controller.pauseAll()
        runCurrent()

        controller.playAll()
        controller.cancelSleepTimer()
        runCurrent()

        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
        assertEquals(SleepTimerState(), controller.uiState.value.sleepTimer)
        assertEquals(1.0f, engine.getSleepFadeTarget(), 0.001f)

        controller.release()
    }
}
