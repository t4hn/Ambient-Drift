package com.example

import android.media.AudioTrack
import com.example.audio.AudioOutputSink
import com.example.audio.AudioStem
import com.example.audio.DriftAudioMixerEngine
import com.example.audio.StemLoader
import com.example.model.ChannelState
import com.example.model.SceneCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DriftAudioMixerEngineTest {

    private fun constantStem(value: Float, frames: Int = 1000): AudioStem {
        return AudioStem(
            leftSamples = FloatArray(frames) { value },
            rightSamples = FloatArray(frames) { value },
            sampleRate = DriftAudioMixerEngine.SAMPLE_RATE
        )
    }

    private fun sineStem(freqHz: Double, frames: Int = 4410): AudioStem {
        val arr = FloatArray(frames) { idx ->
            (0.4 * sin(2.0 * PI * freqHz * idx / DriftAudioMixerEngine.SAMPLE_RATE)).toFloat()
        }
        return AudioStem(arr, arr.copyOf(), DriftAudioMixerEngine.SAMPLE_RATE)
    }

    @Test
    fun `simultaneous channels mix and mute independently without clicks`() {
        val scene = SceneCatalog.scenes[0] // Storm (4 channels)
        val stemLoader = StemLoader { rawResId, _ ->
            when (rawResId) {
                scene.channels[0].rawResId -> constantStem(0.20f)
                scene.channels[1].rawResId -> constantStem(0.20f)
                else -> constantStem(0f)
            }
        }
        val engine = DriftAudioMixerEngine(stemLoader)
        val channels = scene.channels.mapIndexed { idx, spec ->
            ChannelState(
                spec = spec,
                volume = if (idx < 2) 1.0f else 0.0f,
                isMuted = false,
                isPlaying = true
            )
        }

        engine.loadScene(
            scene = scene,
            channelStates = channels,
            masterVolume = 1.0f,
            crossfade = false,
            isCurrentlyPlaying = false
        )
        // Start playback gate (without spawning hardware thread, we drive renderFrames directly)
        engine.updateChannelStates(channels)
        // Use beginSleepFadeOut=false equivalent by testing startFade via reflection-free path:
        // Let's call startPlayback with a no-op sink!
        val noOpEngine = createTestEngine(stemLoader)
        noOpEngine.loadScene(scene, channels, 1.0f, crossfade = false, isCurrentlyPlaying = true)
        noOpEngine.startPlayback()

        val buf = ShortArray(2048 * 2)
        // Render 40,000 frames (~0.9s) so fade-in settles completely
        repeat(20) {
            noOpEngine.renderFrames(buf, 2048)
        }
        val rmsBoth = rms(buf)
        assertTrue("Expected audible mix from two channels, rms=$rmsBoth", rmsBoth > 5000.0)

        // Mute channel 0 -> output should drop roughly in half, not zero
        val mutedCh0 = channels.mapIndexed { idx, ch ->
            if (idx == 0) ch.copy(isMuted = true) else ch
        }
        noOpEngine.updateChannelStates(mutedCh0)
        repeat(5) {
            noOpEngine.renderFrames(buf, 2048)
        }
        val rmsOne = rms(buf)
        assertTrue("Expected ch1 to keep playing when ch0 muted (rmsOne=$rmsOne, rmsBoth=$rmsBoth)", rmsOne in 1500.0..(rmsBoth * 0.75))

        // Pause channel 1 independently -> mix should ramp cleanly to silence
        val bothOff = mutedCh0.mapIndexed { idx, ch ->
            if (idx == 1) ch.copy(isPlaying = false) else ch
        }
        noOpEngine.updateChannelStates(bothOff)
        repeat(5) {
            noOpEngine.renderFrames(buf, 2048)
        }
        val rmsZero = rms(buf)
        assertEquals(0.0, rmsZero, 5.0)
        noOpEngine.release()
        engine.release()
    }

    @Test
    fun `loop wraps seamlessly across stem boundary with no dropout`() {
        val scene = SceneCatalog.scenes[1] // Deep Space
        val stemLoader = StemLoader { rawResId, _ ->
            if (rawResId == scene.channels[0].rawResId) {
                sineStem(100.0, frames = 441) // exact 1 cycle = 10ms = 441 samples
            } else {
                constantStem(0f, frames = 441)
            }
        }
        val engine = createTestEngine(stemLoader)
        val channels = scene.channels.mapIndexed { idx, spec ->
            ChannelState(spec, volume = if (idx == 0) 1.0f else 0f, isMuted = false, isPlaying = true)
        }
        engine.loadScene(scene, channels, 1.0f, crossfade = false, isCurrentlyPlaying = true)
        engine.startPlayback()

        val buf = ShortArray(2048 * 2)
        // Warm up fade-in
        repeat(20) { engine.renderFrames(buf, 2048) }

        // Render across multiple 441-sample loop boundaries and verify smoothness
        engine.renderFrames(buf, 2048)
        var maxDelta = 0
        for (i in 1 until 2048) {
            val prev = buf[(i - 1) * 2].toInt()
            val curr = buf[i * 2].toInt()
            val delta = abs(curr - prev)
            if (delta > maxDelta) maxDelta = delta
        }
        // A 100 Hz sine wave at 44.1 kHz has max step ~0.014 * amplitude (~200 PCM units).
        // Any click/dropout at the 441-sample loop wrap would jump by thousands of units!
        assertTrue("Expected smooth loop wrap without clicks, maxDelta=$maxDelta", maxDelta < 500)
        engine.release()
    }

    @Test
    fun `scene crossfade and sleep fade out transition smoothly to completion`() {
        val storm = SceneCatalog.scenes[0]
        val abyss = SceneCatalog.scenes[2]
        val stemLoader = StemLoader { _, _ -> constantStem(0.25f, frames = 512) }
        val engine = createTestEngine(stemLoader)

        val stormCh = storm.channels.map { ChannelState(it, 0.8f, isMuted = false, isPlaying = true) }
        val abyssCh = abyss.channels.map { ChannelState(it, 0.8f, isMuted = false, isPlaying = true) }

        engine.loadScene(storm, stormCh, 1.0f, crossfade = false, isCurrentlyPlaying = true)
        engine.startPlayback()

        val buf = ShortArray(2048 * 2)
        repeat(20) { engine.renderFrames(buf, 2048) }
        val stormRms = rms(buf)
        assertTrue(stormRms > 4000.0)

        // Crossfade to Abyss
        engine.loadScene(abyss, abyssCh, 1.0f, crossfade = true, isCurrentlyPlaying = true)
        engine.renderFrames(buf, 2048)
        assertTrue("Crossfade should stay audible without dropping to zero", rms(buf) > 2000.0)

        // Trigger short 0.5s sleep fade out and verify completion callback fires
        var completed = false
        engine.beginSleepFadeOut(durationSeconds = 0.5f) {
            completed = true
        }
        repeat(15) {
            engine.renderFrames(buf, 2048)
        }
        assertTrue("Sleep fade-out should invoke completion callback", completed)
        assertEquals(0.0, rms(buf), 20.0)
        engine.release()
    }

    @Test
    fun `softLimit is continuous, monotonic, and passes through below threshold across -2 to 2`() {
        val engine = createTestEngine { _, _ -> constantStem(0f, frames = 64) }
        val step = 0.001f
        val epsilon = 0.002f
        val threshold = 0.85f

        var x = -2.0f
        var prev = engine.softLimit(x)
        assertTrue("Expected softLimit(-2) > -1.0f, was $prev", prev > -1.0f)

        val steps = 4000
        for (i in 1..steps) {
            x = -2.0f + i * step
            val curr = engine.softLimit(x)
            val jump = abs(curr - prev)

            assertTrue("Expected output within (-1, 1) at x=$x, got $curr", curr > -1.0f && curr < 1.0f)
            assertTrue("Expected monotonic output at x=$x: prev=$prev, curr=$curr", curr >= prev)
            assertTrue("Discontinuity detected at x=$x: prev=$prev, curr=$curr, jump=$jump", jump < epsilon)

            if (abs(x) <= threshold) {
                assertEquals("Expected pass-through inside [-0.85, 0.85] at x=$x", x, curr, 1e-6f)
            }
            prev = curr
        }
        engine.release()
    }

    @Test
    fun `dead object write error recreates sink once and recovers without stopping playback`() {
        val scene = SceneCatalog.scenes[0]
        val channels = scene.channels.map { ChannelState(it, 0.5f, isMuted = false, isPlaying = true) }
        val sinkCount = AtomicInteger(0)
        val releaseCount = AtomicInteger(0)
        val healthyWrites = CountDownLatch(3)
        val errorTriggered = AtomicInteger(0)

        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ -> constantStem(0.1f, frames = 512) },
            sinkFactory = {
                val id = sinkCount.incrementAndGet()
                val writesOnThisSink = AtomicInteger(0)
                object : AudioOutputSink {
                    override fun start() {}
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int {
                        val n = writesOnThisSink.incrementAndGet()
                        if (id == 1 && n == 1) {
                            return AudioTrack.ERROR_DEAD_OBJECT
                        }
                        healthyWrites.countDown()
                        Thread.sleep(5)
                        return shortCount
                    }
                    override fun pauseAndFlush() {}
                    override fun release() {
                        releaseCount.incrementAndGet()
                    }
                }
            }
        )
        engine.setOnPlaybackErrorListener { errorTriggered.incrementAndGet() }
        engine.loadScene(scene, channels, 1.0f, crossfade = false, isCurrentlyPlaying = false)
        engine.startPlayback()

        assertTrue("Expected recovered sink to continue writing", healthyWrites.await(2, TimeUnit.SECONDS))
        assertEquals(2, sinkCount.get())
        assertEquals(1, releaseCount.get())
        assertEquals(0, errorTriggered.get())
        assertTrue(engine.isMixerThreadRunning())
        engine.release()
    }

    @Test
    fun `persistent negative write errors stop render thread cleanly without spinning and notify listener`() {
        val scene = SceneCatalog.scenes[0]
        val channels = scene.channels.map { ChannelState(it, 0.5f, isMuted = false, isPlaying = true) }
        val sinkCreated = AtomicInteger(0)
        val writeCalls = AtomicInteger(0)
        val errorLatch = CountDownLatch(1)

        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ -> constantStem(0.1f, frames = 512) },
            sinkFactory = {
                sinkCreated.incrementAndGet()
                object : AudioOutputSink {
                    override fun start() {}
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int {
                        writeCalls.incrementAndGet()
                        return AudioTrack.ERROR_DEAD_OBJECT
                    }
                    override fun pauseAndFlush() {}
                    override fun release() {}
                }
            }
        )
        engine.setOnPlaybackErrorListener { errorLatch.countDown() }
        engine.loadScene(scene, channels, 1.0f, crossfade = false, isCurrentlyPlaying = false)
        engine.startPlayback()

        assertTrue("Expected playback error listener to fire", errorLatch.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        val callsAfterStop = writeCalls.get()
        Thread.sleep(50)
        assertEquals("Render thread must stop cleanly instead of spinning", callsAfterStop, writeCalls.get())
        assertFalse(engine.isMixerThreadRunning())
        // Initial sink + 3 bounded retries = 4 total sinks created
        assertEquals(4, sinkCreated.get())
        engine.release()
    }

    @Test
    fun `repeated zero writes sleep briefly instead of busy spinning`() {
        val scene = SceneCatalog.scenes[0]
        val channels = scene.channels.map { ChannelState(it, 0.5f, isMuted = false, isPlaying = true) }
        val writeCalls = AtomicInteger(0)

        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ -> constantStem(0.1f, frames = 512) },
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
        engine.loadScene(scene, channels, 1.0f, crossfade = false, isCurrentlyPlaying = false)
        engine.startPlayback()

        Thread.sleep(100)
        val totalCalls = writeCalls.get()
        engine.release()

        // Without sleep backoff, 100ms in tight loop yields tens of thousands of calls.
        assertTrue("Expected bounded write calls under zero-write backoff, got $totalCalls", totalCalls in 3..40)
    }

    @Test
    fun `pauseOrStopPlayback and startPlayback preserve all channel cursors`() {
        val scene = SceneCatalog.scenes[0]
        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ -> constantStem(0.2f, frames = 200_000) },
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
        val channels = scene.channels.map { ChannelState(it, 0.8f, isMuted = false, isPlaying = true) }

        engine.loadScene(scene, channels, 1.0f, crossfade = false, isCurrentlyPlaying = false)
        engine.startPlayback()

        val buf = ShortArray(2048 * 2)
        repeat(4) { engine.renderFrames(buf, 2048) }
        val cursorsBeforePause = engine.getChannelCursors()
        assertEquals(4, cursorsBeforePause.size)
        cursorsBeforePause.values.forEach { cursor ->
            assertTrue("Expected cursor > 0 after rendering, was $cursor", cursor >= 8192)
        }

        engine.pauseOrStopPlayback()
        // Render through pause fade-out; cursors remain preserved (>= before pause)
        repeat(15) { engine.renderFrames(buf, 2048) }
        val cursorsWhilePaused = engine.getChannelCursors()
        scene.channels.forEach { spec ->
            assertTrue(
                "Expected channel ${spec.id} cursor preserved across pause",
                (cursorsWhilePaused[spec.id] ?: 0) >= (cursorsBeforePause[spec.id] ?: Int.MAX_VALUE)
            )
        }

        engine.startPlayback()
        val cursorsOnResume = engine.getChannelCursors()
        scene.channels.forEach { spec ->
            assertTrue(
                "Expected channel ${spec.id} cursor not reset on resume",
                (cursorsOnResume[spec.id] ?: 0) >= (cursorsWhilePaused[spec.id] ?: Int.MAX_VALUE)
            )
        }
        engine.renderFrames(buf, 2048)
        val cursorsAfterResume = engine.getChannelCursors()
        scene.channels.forEach { spec ->
            assertTrue(
                "Expected channel ${spec.id} to advance from preserved cursor",
                (cursorsAfterResume[spec.id] ?: 0) > (cursorsOnResume[spec.id] ?: 0)
            )
        }
        engine.release()
    }

    @Test
    fun `ducking attenuates smoothly and restores cleanly without affecting volume settings`() {
        val scene = SceneCatalog.scenes[0]
        val engine = createTestEngine { _, _ -> constantStem(0.25f, frames = 4096) }
        val channels = scene.channels.mapIndexed { idx, spec ->
            ChannelState(spec, volume = if (idx == 0) 0.9f else 0f, isMuted = false, isPlaying = true)
        }

        engine.loadScene(scene, channels, 0.9f, crossfade = false, isCurrentlyPlaying = true)
        engine.startPlayback()

        val buf = ShortArray(2048 * 2)
        repeat(15) { engine.renderFrames(buf, 2048) }
        val normalRms = rms(buf)
        assertTrue(normalRms > 4000.0)

        engine.setDucking(true)
        assertEquals(0.25f, engine.getDuckTarget(), 0.001f)
        repeat(10) { engine.renderFrames(buf, 2048) }
        val duckedRms = rms(buf)
        assertEquals(normalRms * 0.25, duckedRms, normalRms * 0.05)

        engine.setDucking(false)
        assertEquals(1.0f, engine.getDuckTarget(), 0.001f)
        repeat(10) { engine.renderFrames(buf, 2048) }
        val restoredRms = rms(buf)
        assertEquals(normalRms, restoredRms, normalRms * 0.05)
        engine.release()
    }

    @Test
    fun `cancelSleepFadeOut while running restores sleep fade gain smoothly without snapping`() {
        val scene = SceneCatalog.scenes[0]
        val engine = DriftAudioMixerEngine(
            stemLoader = { _, _ -> constantStem(0.2f, frames = 512) },
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
        val channels = scene.channels.map { ChannelState(it, 0.8f, isMuted = false, isPlaying = true) }
        engine.loadScene(scene, channels, 1.0f, crossfade = false, isCurrentlyPlaying = true)
        engine.startPlayback()
        Thread.sleep(40)

        var completed = false
        engine.beginSleepFadeOut(durationSeconds = 0.5f) { completed = true }
        val buf = ShortArray(2048 * 2)
        repeat(4) { engine.renderFrames(buf, 2048) }
        val midFadeGain = engine.getSleepFadeCurrent()
        assertTrue("Expected fade-out to be partially faded, was $midFadeGain", midFadeGain in 0.1f..0.9f)

        engine.cancelSleepFadeOut()
        assertEquals(1.0f, engine.getSleepFadeTarget(), 0.001f)
        // Smooth restoration means current gain does not snap instantaneously to 1.0f
        assertTrue("Expected smooth recovery from $midFadeGain", engine.getSleepFadeCurrent() < 0.99f)

        repeat(10) { engine.renderFrames(buf, 2048) }
        assertEquals(1.0f, engine.getSleepFadeCurrent(), 0.001f)
        assertFalse("Cancelled sleep fade-out must not fire completion callback", completed)
        engine.release()
    }

    private fun createTestEngine(stemLoader: StemLoader): DriftAudioMixerEngine {
        return DriftAudioMixerEngine(
            stemLoader = stemLoader,
            sinkFactory = {
                object : com.example.audio.AudioOutputSink {
                    override fun start() {}
                    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int = shortCount
                    override fun pauseAndFlush() {}
                    override fun release() {}
                }
            }
        )
    }

    private fun rms(pcm: ShortArray): Double {
        var sumSq = 0.0
        for (s in pcm) {
            sumSq += s.toDouble() * s.toDouble()
        }
        return kotlin.math.sqrt(sumSq / pcm.size)
    }
}
