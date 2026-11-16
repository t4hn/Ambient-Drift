package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.example.model.AudioChannelSpec
import com.example.model.ChannelState
import com.example.model.SceneSpec
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

interface AudioOutputSink {
    fun start()
    fun write(pcm16Stereo: ShortArray, shortCount: Int): Int
    fun pauseAndFlush()
    fun release()
}

class AndroidAudioTrackSink(
    private val sampleRate: Int = DriftAudioMixerEngine.SAMPLE_RATE
) : AudioOutputSink {

    private var audioTrack: AudioTrack? = null

    @Synchronized
    override fun start() {
        val track = audioTrack ?: createTrack().also { audioTrack = it }
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
    }

    @Synchronized
    override fun write(pcm16Stereo: ShortArray, shortCount: Int): Int {
        val track = audioTrack ?: return 0
        return track.write(pcm16Stereo, 0, shortCount, AudioTrack.WRITE_BLOCKING)
    }

    @Synchronized
    override fun pauseAndFlush() {
        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
            track.flush()
        }
    }

    @Synchronized
    override fun release() {
        audioTrack?.let { track ->
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.flush()
                track.release()
            }
        }
        audioTrack = null
    }

    private fun createTrack(): AudioTrack {
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate / 10 * 4)

        val bufferSizeBytes = (minBufBytes * 2).coerceAtLeast(16384)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}

class DriftAudioMixerEngine(
    private val stemLoader: StemLoader,
    private val sinkFactory: () -> AudioOutputSink = { AndroidAudioTrackSink() }
) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val FRAMES_PER_BUFFER = 1024

        // Per-sample smoothing rates (zero-click transitions)
        private const val CHANNEL_GAIN_STEP = 1f / (SAMPLE_RATE * 0.045f) // ~45ms click-free channel ramp
        private const val MASTER_PLAY_FADE_IN_STEP = 1f / (SAMPLE_RATE * 0.55f) // 550ms gentle fade-in
        private const val MASTER_PAUSE_FADE_OUT_STEP = 1f / (SAMPLE_RATE * 0.30f) // 300ms pause/stop fade
        private const val CROSSFADE_STEP = 1f / (SAMPLE_RATE * 1.20f) // 1.2s scene transition
        private const val DUCK_STEP = 1f / (SAMPLE_RATE * 0.25f) // 250ms duck transition
    }

    private class ChannelSlot(
        val spec: AudioChannelSpec,
        val stem: AudioStem,
        val panLeft: Float,
        val panRight: Float,
        var targetGain: Float,
        var currentGain: Float = 0f,
        var cursor: Int = 0
    )

    private class SceneBus(
        val sceneId: String,
        val slots: List<ChannelSlot>,
        var targetFade: Float,
        var currentFade: Float
    )

    private val lock = Any()
    private val stemCache = HashMap<Int, AudioStem>()

    private var activeBus: SceneBus? = null
    private val outgoingBuses = mutableListOf<SceneBus>()

    private var masterVolumeTarget: Float = 0.85f
    private var masterVolumeCurrent: Float = 0.85f

    private var masterGateTarget: Float = 0f
    private var masterGateCurrent: Float = 0f
    private var masterGateStep: Float = MASTER_PLAY_FADE_IN_STEP

    private var duckTarget: Float = 1f
    private var duckCurrent: Float = 1f

    // Sleep timer fade multiplier (1f normally, ramps smoothly to 0f over timer fade duration)
    private var sleepFadeTarget: Float = 1f
    private var sleepFadeCurrent: Float = 1f
    private var sleepFadeStep: Float = 1f / (SAMPLE_RATE * 8.0f)

    private val threadLifecycleLock = Any()
    private val isThreadRunning = AtomicBoolean(false)
    private var renderThread: Thread? = null
    private var sink: AudioOutputSink? = null

    @Volatile
    private var onFadeOutCompleted: (() -> Unit)? = null

    fun loadScene(
        scene: SceneSpec,
        channelStates: List<ChannelState>,
        masterVolume: Float,
        crossfade: Boolean,
        isCurrentlyPlaying: Boolean
    ) {
        val loadedStems = scene.channels.associate { ch ->
            ch.rawResId to (stemCache[ch.rawResId] ?: stemLoader.loadStem(ch.rawResId, SAMPLE_RATE)
                .also { stemCache[ch.rawResId] = it })
        }

        val stateMap = channelStates.associateBy { it.spec.id }

        val slots = scene.channels.map { spec ->
            val st = stateMap[spec.id]
            val targetGain = computeChannelGain(
                volume = st?.volume ?: spec.defaultVolume,
                isMuted = st?.isMuted ?: false,
                isPlaying = st?.isPlaying ?: true
            )
            val (panL, panR) = constantPowerPan(spec.pan)
            ChannelSlot(
                spec = spec,
                stem = loadedStems.getValue(spec.rawResId),
                panLeft = panL,
                panRight = panR,
                targetGain = targetGain,
                currentGain = if (isCurrentlyPlaying) targetGain else 0f,
                cursor = 0
            )
        }

        synchronized(lock) {
            masterVolumeTarget = perceptualCurve(masterVolume)
            if (!isCurrentlyPlaying) {
                masterVolumeCurrent = masterVolumeTarget
            }

            val prevBus = activeBus
            if (prevBus != null && prevBus.sceneId != scene.id && isCurrentlyPlaying && crossfade) {
                prevBus.targetFade = 0f
                outgoingBuses.add(prevBus)
                activeBus = SceneBus(
                    sceneId = scene.id,
                    slots = slots,
                    targetFade = 1f,
                    currentFade = 0f
                )
            } else {
                outgoingBuses.clear()
                activeBus = SceneBus(
                    sceneId = scene.id,
                    slots = slots,
                    targetFade = 1f,
                    currentFade = 1f
                )
            }
        }
    }

    fun updateChannelStates(channelStates: List<ChannelState>) {
        val map = channelStates.associateBy { it.spec.id }
        synchronized(lock) {
            activeBus?.slots?.forEach { slot ->
                map[slot.spec.id]?.let { state ->
                    slot.targetGain = computeChannelGain(
                        volume = state.volume,
                        isMuted = state.isMuted,
                        isPlaying = state.isPlaying
                    )
                }
            }
        }
    }

    fun setMasterVolume(volume: Float) {
        synchronized(lock) {
            masterVolumeTarget = perceptualCurve(volume)
        }
    }

    fun setDucking(duck: Boolean) {
        synchronized(lock) {
            duckTarget = if (duck) 0.25f else 1.0f
        }
    }

    fun startPlayback() {
        synchronized(lock) {
            onFadeOutCompleted = null
            sleepFadeTarget = 1f
            sleepFadeCurrent = 1f
            masterGateTarget = 1f
            masterGateStep = MASTER_PLAY_FADE_IN_STEP
            activeBus?.let { bus ->
                bus.targetFade = 1f
            }
        }
        ensureRenderThreadStarted()
    }

    fun pauseOrStopPlayback(onComplete: (() -> Unit)? = null) {
        synchronized(lock) {
            if (!isThreadRunning.get()) {
                masterGateCurrent = 0f
                masterGateTarget = 0f
                onComplete?.invoke()
                return
            }
            masterGateTarget = 0f
            masterGateStep = MASTER_PAUSE_FADE_OUT_STEP
            onFadeOutCompleted = onComplete
        }
    }

    fun beginSleepFadeOut(durationSeconds: Float = 8.0f, onComplete: () -> Unit) {
        synchronized(lock) {
            if (!isThreadRunning.get()) {
                onComplete()
                return
            }
            val safeSeconds = durationSeconds.coerceAtLeast(0.5f)
            sleepFadeStep = 1f / (SAMPLE_RATE * safeSeconds)
            sleepFadeTarget = 0f
            onFadeOutCompleted = onComplete
        }
    }

    fun cancelSleepFadeOut() {
        synchronized(lock) {
            onFadeOutCompleted = null
            sleepFadeTarget = 1f
            sleepFadeCurrent = 1f
        }
    }

    fun release() {
        stopRenderThreadImmediate()
        synchronized(lock) {
            activeBus = null
            outgoingBuses.clear()
            stemCache.clear()
            onFadeOutCompleted = null
        }
    }

    /**
     * Pure DSP render pass into [outStereoPcm16]. Exposed internally so JVM/Robolectric tests
     * can verify seamless looping, channel gain smoothing, mute, master volume, and crossfades
     * deterministically without requiring hardware audio output.
     */
    internal fun renderFrames(outStereoPcm16: ShortArray, frameCount: Int): Boolean {
        var fadeCompletedCallback: (() -> Unit)? = null
        var shouldContinue = true

        synchronized(lock) {
            val active = activeBus
            for (frame in 0 until frameCount) {
                masterVolumeCurrent = stepToward(masterVolumeCurrent, masterVolumeTarget, CHANNEL_GAIN_STEP)
                masterGateCurrent = stepToward(masterGateCurrent, masterGateTarget, masterGateStep)
                duckCurrent = stepToward(duckCurrent, duckTarget, DUCK_STEP)
                sleepFadeCurrent = stepToward(sleepFadeCurrent, sleepFadeTarget, sleepFadeStep)

                val globalGain = masterVolumeCurrent * masterGateCurrent * duckCurrent * sleepFadeCurrent

                var mixL = 0f
                var mixR = 0f

                if (active != null) {
                    active.currentFade = stepToward(active.currentFade, active.targetFade, CROSSFADE_STEP)
                    val busFade = active.currentFade
                    for (i in active.slots.indices) {
                        val slot = active.slots[i]
                        slot.currentGain = stepToward(slot.currentGain, slot.targetGain, CHANNEL_GAIN_STEP)
                        val g = slot.currentGain * busFade
                        val len = slot.stem.frameCount
                        if (len > 0) {
                            val idx = slot.cursor
                            if (g > 0.00001f) {
                                mixL += slot.stem.leftSamples[idx] * g * slot.panLeft
                                mixR += slot.stem.rightSamples[idx] * g * slot.panRight
                            }
                            slot.cursor = if (idx + 1 >= len) 0 else idx + 1
                        }
                    }
                }

                if (outgoingBuses.isNotEmpty()) {
                    val iter = outgoingBuses.iterator()
                    while (iter.hasNext()) {
                        val outBus = iter.next()
                        outBus.currentFade = stepToward(outBus.currentFade, 0f, CROSSFADE_STEP)
                        val busFade = outBus.currentFade
                        for (i in outBus.slots.indices) {
                            val slot = outBus.slots[i]
                            val g = slot.currentGain * busFade
                            val len = slot.stem.frameCount
                            if (len > 0) {
                                val idx = slot.cursor
                                if (g > 0.00001f) {
                                    mixL += slot.stem.leftSamples[idx] * g * slot.panLeft
                                    mixR += slot.stem.rightSamples[idx] * g * slot.panRight
                                }
                                slot.cursor = if (idx + 1 >= len) 0 else idx + 1
                            }
                        }
                        if (outBus.currentFade <= 0.00001f) {
                            iter.remove()
                        }
                    }
                }

                val scaledL = softLimit(mixL * globalGain)
                val scaledR = softLimit(mixR * globalGain)

                val outIdx = frame * 2
                outStereoPcm16[outIdx] = (scaledL * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                outStereoPcm16[outIdx + 1] = (scaledR * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }

            val finishedGateFade = masterGateTarget == 0f && masterGateCurrent <= 0.0001f
            val finishedSleepFade = sleepFadeTarget == 0f && sleepFadeCurrent <= 0.0001f
            if (finishedGateFade || finishedSleepFade) {
                shouldContinue = false
                if (onFadeOutCompleted != null) {
                    fadeCompletedCallback = onFadeOutCompleted
                    onFadeOutCompleted = null
                }
                masterGateTarget = 0f
                masterGateCurrent = 0f
            }
        }

        fadeCompletedCallback?.invoke()
        return shouldContinue
    }

    private fun ensureRenderThreadStarted() {
        synchronized(threadLifecycleLock) {
            if (isThreadRunning.get()) return
            isThreadRunning.set(true)
            val activeSink = sink ?: sinkFactory().also { sink = it }
            activeSink.start()
            val thread = Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                val pcmBuffer = ShortArray(FRAMES_PER_BUFFER * 2)
                while (isThreadRunning.get()) {
                    val keepGoing = renderFrames(pcmBuffer, FRAMES_PER_BUFFER)
                    activeSink.write(pcmBuffer, pcmBuffer.size)
                    if (!keepGoing) {
                        var shouldBreak = false
                        synchronized(threadLifecycleLock) {
                            synchronized(lock) {
                                val wantsResume = masterGateTarget > 0f && sleepFadeTarget > 0f
                                if (!wantsResume) {
                                    isThreadRunning.set(false)
                                    activeSink.pauseAndFlush()
                                    shouldBreak = true
                                }
                            }
                        }
                        if (shouldBreak) break
                    }
                }
            }, "DriftAudioMixerThread")
            renderThread = thread
            thread.start()
        }
    }

    private fun stopRenderThreadImmediate() {
        val threadToJoin: Thread?
        synchronized(threadLifecycleLock) {
            isThreadRunning.set(false)
            threadToJoin = renderThread
            renderThread = null
        }
        threadToJoin?.let { t ->
            if (Thread.currentThread() != t) {
                runCatching { t.join(500) }
            }
        }
        synchronized(threadLifecycleLock) {
            sink?.release()
            sink = null
        }
    }

    private fun computeChannelGain(volume: Float, isMuted: Boolean, isPlaying: Boolean): Float {
        if (isMuted || !isPlaying) return 0f
        return perceptualCurve(volume.coerceIn(0f, 1f))
    }

    private fun perceptualCurve(linear: Float): Float {
        val clamped = linear.coerceIn(0f, 1f)
        return clamped.pow(1.6f)
    }

    private fun constantPowerPan(pan: Float): Pair<Float, Float> {
        val clamped = pan.coerceIn(-1f, 1f)
        val angle = (clamped + 1f) * (PI / 4.0)
        return Pair(cos(angle).toFloat() * 1.15f, sin(angle).toFloat() * 1.15f)
    }

    private fun stepToward(current: Float, target: Float, maxDelta: Float): Float {
        val diff = target - current
        return when {
            diff > maxDelta -> current + maxDelta
            diff < -maxDelta -> current - maxDelta
            else -> target
        }
    }

    private fun softLimit(sample: Float): Float {
        return if (sample > 0.85f || sample < -0.85f) {
            tanh(sample.toDouble()).toFloat()
        } else {
            sample
        }
    }
}
