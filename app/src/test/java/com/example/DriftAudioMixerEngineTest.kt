package com.example

import com.example.audio.AudioStem
import com.example.audio.DriftAudioMixerEngine
import com.example.audio.StemLoader
import com.example.model.ChannelState
import com.example.model.SceneCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
