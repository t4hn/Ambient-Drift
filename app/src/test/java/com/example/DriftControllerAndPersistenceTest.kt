package com.example

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioOutputSink
import com.example.audio.DriftAudioMixerEngine
import com.example.audio.WavStemLoader
import com.example.data.DriftPreferencesRepository
import com.example.model.AudioSourceOrigin
import com.example.model.PlaybackStatus
import com.example.model.SceneCatalog
import com.example.service.DriftPlaybackController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DriftControllerAndPersistenceTest {

    private suspend fun awaitUntil(timeoutMs: Long = 3000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            delay(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `all 12 scene stems exist and decode cleanly from res raw`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = WavStemLoader(context)

        assertEquals(3, SceneCatalog.scenes.size)
        for (scene in SceneCatalog.scenes) {
            assertEquals(4, scene.channels.size)
            for (channel in scene.channels) {
                assertEquals(AudioSourceOrigin.SYNTHESIZED_TEST, channel.sourceOrigin)
                val stem = loader.loadStem(channel.rawResId, DriftAudioMixerEngine.SAMPLE_RATE)
                assertTrue("Stem ${channel.id} should have audio frames", stem.frameCount > 44100)
                val maxAmp = stem.leftSamples.maxOf { kotlin.math.abs(it) }
                assertTrue("Stem ${channel.id} should be non-silent (maxAmp=$maxAmp)", maxAmp > 0.01f)
            }
        }
    }

    @Test
    fun `preferences persist and restore scene, channel volumes, mutes, and master volume`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo1 = DriftPreferencesRepository(context)

        repo1.setLastSceneId(SceneCatalog.SCENE_ABYSS_ID)
        repo1.setMasterVolume(0.42f)
        repo1.setChannelVolume("abyss_whale_calls", 0.91f)
        repo1.setChannelMuted("abyss_currents", true)
        repo1.setChannelPlaying("abyss_resonant_drone", false)
        repo1.setAutoPlayOnSceneSwitch(false)
        repo1.setSmoothCrossfadeEnabled(false)

        // New instance simulates app restart
        val repo2 = DriftPreferencesRepository(context)
        val restored = repo2.readInitialPreferences()

        assertEquals(SceneCatalog.SCENE_ABYSS_ID, restored.lastSceneId)
        assertEquals(0.42f, restored.masterVolume, 0.001f)
        assertEquals(0.91f, restored.channelVolumes["abyss_whale_calls"] ?: 0f, 0.001f)
        assertTrue(restored.channelMutes["abyss_currents"] == true)
        assertFalse(restored.channelPlaybackStates["abyss_resonant_drone"] ?: true)
        assertFalse(restored.autoPlayOnSceneSwitch)
        assertFalse(restored.smoothCrossfadeEnabled)
    }

    @Test
    fun `controller handles audio focus interruptions, noisy disconnect, and sleep timer cancellation`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = DriftPreferencesRepository(context)
        val noopEngine = DriftAudioMixerEngine(
            stemLoader = WavStemLoader(context),
            sinkFactory = {
                object : AudioOutputSink {
                    override fun start() {}
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int = shortCount
                    override fun pauseAndFlush() {}
                    override fun release() {}
                }
            }
        )

        val controller = DriftPlaybackController(
            appContext = context,
            preferences = repo,
            mixerEngine = noopEngine,
            enableAndroidServiceBridge = false
        )
        awaitUntil { controller.uiState.value.activeScene != null }

        controller.selectScene(SceneCatalog.SCENE_DEEP_SPACE_ID, autoStartIfConfigured = true)
        awaitUntil {
            controller.uiState.value.activeScene?.id == SceneCatalog.SCENE_DEEP_SPACE_ID &&
                controller.uiState.value.playbackStatus == PlaybackStatus.PLAYING
        }
        assertEquals(SceneCatalog.SCENE_DEEP_SPACE_ID, controller.uiState.value.activeScene?.id)
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)

        // Transient audio focus loss -> pauses, then focus gain -> resumes
        controller.onAudioFocusLost(transient = true)
        awaitUntil { controller.uiState.value.playbackStatus == PlaybackStatus.PAUSED }
        assertEquals(PlaybackStatus.PAUSED, controller.uiState.value.playbackStatus)

        controller.onAudioFocusGained()
        awaitUntil { controller.uiState.value.playbackStatus == PlaybackStatus.PLAYING }
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)

        // Headphone unplug (becoming noisy) -> pauses
        controller.onBecomingNoisy()
        awaitUntil { controller.uiState.value.playbackStatus == PlaybackStatus.PAUSED }
        assertEquals(PlaybackStatus.PAUSED, controller.uiState.value.playbackStatus)

        // Sleep timer starts playback and can be cancelled cleanly
        controller.startSleepTimerSeconds(totalSeconds = 120)
        awaitUntil {
            controller.uiState.value.playbackStatus == PlaybackStatus.PLAYING &&
                controller.uiState.value.sleepTimer.isActive
        }
        assertEquals(PlaybackStatus.PLAYING, controller.uiState.value.playbackStatus)
        assertTrue(controller.uiState.value.sleepTimer.isActive)
        assertTrue(controller.uiState.value.sleepTimer.remainingSeconds in 118..120)

        controller.cancelSleepTimer()
        awaitUntil { !controller.uiState.value.sleepTimer.isActive }
        assertFalse(controller.uiState.value.sleepTimer.isActive)

        controller.stopAll()
        awaitUntil { controller.uiState.value.playbackStatus == PlaybackStatus.STOPPED }
        assertEquals(PlaybackStatus.STOPPED, controller.uiState.value.playbackStatus)
        controller.release()
    }
}
