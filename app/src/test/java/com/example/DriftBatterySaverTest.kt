package com.example

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AndroidPowerSaveMonitor
import com.example.audio.AudioOutputSink
import com.example.audio.AudioStem
import com.example.audio.DriftAudioMixerEngine
import com.example.audio.PowerSaveMonitor
import com.example.data.DriftPreferencesRepository
import com.example.model.ChannelState
import com.example.model.PlaybackStatus
import com.example.model.SceneCatalog
import com.example.model.SleepTimerPreset
import com.example.service.DriftPlaybackController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DriftBatterySaverTest {

    private class FakePowerSaveMonitor(initial: Boolean = false) : PowerSaveMonitor {
        private val state = MutableStateFlow(initial)
        override val isPowerSaveMode: StateFlow<Boolean> = state
        var started = false
        var stopped = false

        override fun start() {
            started = true
        }

        override fun stop() {
            stopped = true
        }

        fun setPowerSave(enabled: Boolean) {
            state.value = enabled
        }
    }

    @Test
    fun `mixer engine lowers buffer write frequency 4x in low-power mode and switches cleanly`() {
        val scene = SceneCatalog.scenes[0]
        val channels = scene.channels.map { ChannelState(it, 0.7f, isMuted = false, isPlaying = true) }
        val writeSizes = CopyOnWriteArrayList<Int>()
        val sinkLowPowerEvents = CopyOnWriteArrayList<Boolean>()
        val standardLatch = CountDownLatch(2)
        val lowPowerLatch = CountDownLatch(2)
        val restoredStandardLatch = CountDownLatch(2)

        val phase = AtomicInteger(0)

        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ ->
                AudioStem(FloatArray(8192) { 0.15f }, FloatArray(8192) { 0.15f }, DriftAudioMixerEngine.SAMPLE_RATE)
            },
            sinkFactory = {
                object : AudioOutputSink {
                    override fun start() {}
                    override fun setLowPowerMode(enabled: Boolean) {
                        sinkLowPowerEvents.add(enabled)
                    }
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int {
                        writeSizes.add(shortCount)
                        when (phase.get()) {
                            0 -> if (shortCount == DriftAudioMixerEngine.FRAMES_PER_BUFFER * 2) standardLatch.countDown()
                            1 -> if (shortCount == DriftAudioMixerEngine.FRAMES_PER_BUFFER_LOW_POWER * 2) lowPowerLatch.countDown()
                            2 -> if (shortCount == DriftAudioMixerEngine.FRAMES_PER_BUFFER * 2) restoredStandardLatch.countDown()
                        }
                        Thread.sleep(5)
                        return shortCount
                    }
                    override fun pauseAndFlush() {}
                    override fun release() {}
                }
            }
        )

        engine.loadScene(scene, channels, 0.8f, crossfade = false, isCurrentlyPlaying = true)
        engine.startPlayback()
        assertTrue("Expected standard 1024-frame buffers initially", standardLatch.await(2, TimeUnit.SECONDS))
        assertEquals(DriftAudioMixerEngine.FRAMES_PER_BUFFER, engine.getFramesPerBuffer())

        phase.set(1)
        engine.setLowPowerMode(true)
        assertTrue("Expected 4096-frame low-power buffers after enabling", lowPowerLatch.await(2, TimeUnit.SECONDS))
        assertEquals(DriftAudioMixerEngine.FRAMES_PER_BUFFER_LOW_POWER, engine.getFramesPerBuffer())
        assertTrue(writeSizes.contains(DriftAudioMixerEngine.FRAMES_PER_BUFFER_LOW_POWER * 2))

        phase.set(2)
        engine.setLowPowerMode(false)
        assertTrue("Expected return to 1024-frame buffers when disabled", restoredStandardLatch.await(2, TimeUnit.SECONDS))
        assertEquals(DriftAudioMixerEngine.FRAMES_PER_BUFFER, engine.getFramesPerBuffer())
        assertTrue(sinkLowPowerEvents.contains(true))
        assertTrue(sinkLowPowerEvents.contains(false))

        engine.release()
    }

    @Test
    fun `zero-write backoff sleeps longer in low-power mode to reduce background thread spins`() {
        val scene = SceneCatalog.scenes[0]
        val channels = scene.channels.map { ChannelState(it, 0.5f, isMuted = false, isPlaying = true) }
        val writeCalls = AtomicInteger(0)

        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ ->
                AudioStem(FloatArray(512) { 0.1f }, FloatArray(512) { 0.1f }, DriftAudioMixerEngine.SAMPLE_RATE)
            },
            sinkFactory = {
                object : AudioOutputSink {
                    override fun start() {}
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int {
                        writeCalls.incrementAndGet()
                        return 0
                    }
                    override fun pauseAndFlush() {}
                    override fun release() {}
                }
            }
        )

        engine.setLowPowerMode(true)
        engine.loadScene(scene, channels, 0.8f, crossfade = false, isCurrentlyPlaying = false)
        engine.startPlayback()

        Thread.sleep(150)
        val totalCalls = writeCalls.get()
        engine.release()

        // 3 initial calls + ~30ms sleeps over 150ms should stay very small (<= 12)
        assertTrue("Expected <= 12 zero-write polls in 150ms low-power mode, got $totalCalls", totalCalls in 3..12)
    }

    @Test
    fun `AndroidPowerSaveMonitor reflects system PowerManager state and broadcasts`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val shadowPowerManager = shadowOf(powerManager)

        shadowPowerManager.setIsPowerSaveMode(false)
        val monitor = AndroidPowerSaveMonitor(context)
        monitor.start()
        assertFalse(monitor.isPowerSaveMode.value)

        shadowPowerManager.setIsPowerSaveMode(true)
        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(monitor.isPowerSaveMode.value)

        shadowPowerManager.setIsPowerSaveMode(false)
        context.sendBroadcast(Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(monitor.isPowerSaveMode.value)

        monitor.stop()
    }

    @Test
    fun `controller enables low-power audio and reduces sleep timer wakeups in battery saver mode`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DriftPreferencesRepository(context)
        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ ->
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
        val fakeMonitor = FakePowerSaveMonitor(initial = false)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = DriftPlaybackController(
            appContext = context,
            preferences = prefs,
            mixerEngine = engine,
            enableAndroidServiceBridge = false,
            elapsedRealtimeMs = { testScheduler.currentTime },
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            powerSaveMonitor = fakeMonitor
        )
        val mediaNotifications = AtomicInteger(0)
        controller.setMediaSessionListener { mediaNotifications.incrementAndGet() }
        runCurrent()

        assertTrue(fakeMonitor.started)
        assertFalse(controller.uiState.value.isBatterySaverOptimizationActive)
        assertFalse(engine.isLowPowerMode())

        // Entering battery saver activates low-power mode on mixer engine
        fakeMonitor.setPowerSave(true)
        runCurrent()
        assertTrue(controller.uiState.value.isDevicePowerSaveMode)
        assertTrue(controller.uiState.value.isBatterySaverOptimizationActive)
        assertTrue(engine.isLowPowerMode())
        assertEquals(DriftAudioMixerEngine.FRAMES_PER_BUFFER_LOW_POWER, engine.getFramesPerBuffer())

        // Start 15m sleep timer in battery saver mode and measure media session notifications over first 100s
        controller.startSleepTimer(SleepTimerPreset.FIFTEEN)
        runCurrent()
        val notificationsAtStart = mediaNotifications.get()

        advanceTimeBy(100_000L)
        runCurrent()
        val deltaNotifications = mediaNotifications.get() - notificationsAtStart
        // With 5s coarse ticks above 60s remaining, 100s triggers ~20 updates instead of 100
        assertTrue(
            "Expected significantly fewer timer wakeups in battery saver mode, got $deltaNotifications in 100s",
            deltaNotifications <= 25
        )
        assertEquals(800, controller.uiState.value.sleepTimer.remainingSeconds)

        // Advance remaining 800s -> expires cleanly at exact 15m mark into FADING_OUT
        advanceTimeBy(800_000L)
        runCurrent()
        assertEquals(PlaybackStatus.FADING_OUT, controller.uiState.value.playbackStatus)

        // User opt-out disables low-power buffering even while device battery saver stays on
        controller.cancelSleepTimer()
        controller.setBatterySaverOptimizationEnabled(false)
        runCurrent()
        assertTrue(controller.uiState.value.isDevicePowerSaveMode)
        assertFalse(controller.uiState.value.batterySaverOptimizationEnabled)
        assertFalse(controller.uiState.value.isBatterySaverOptimizationActive)
        assertFalse(engine.isLowPowerMode())

        controller.release()
        assertTrue(fakeMonitor.stopped)
    }
}
