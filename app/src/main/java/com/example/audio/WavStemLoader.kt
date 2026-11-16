package com.example.audio

import android.content.Context
import androidx.annotation.RawRes
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioStem(
    val leftSamples: FloatArray,
    val rightSamples: FloatArray,
    val sampleRate: Int
) {
    val frameCount: Int
        get() = leftSamples.size
}

fun interface StemLoader {
    fun loadStem(@RawRes rawResId: Int, targetSampleRate: Int): AudioStem
}

class WavStemLoader(
    private val context: Context
) : StemLoader {

    override fun loadStem(@RawRes rawResId: Int, targetSampleRate: Int): AudioStem {
        val bytes = context.resources.openRawResource(rawResId).use { input ->
            readAllBytes(input)
        }
        return decodeWavBytes(bytes, targetSampleRate)
    }

    companion object {
        fun decodeWavBytes(bytes: ByteArray, targetSampleRate: Int): AudioStem {
            require(bytes.size >= 44) { "WAV stream too small (${bytes.size} bytes)" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val riff = ByteArray(4).also { buf.get(it) }
            require(String(riff) == "RIFF") { "Invalid RIFF header" }
            buf.int // chunk size
            val wave = ByteArray(4).also { buf.get(it) }
            require(String(wave) == "WAVE") { "Invalid WAVE header" }

            var numChannels = 1
            var sampleRate = targetSampleRate
            var bitsPerSample = 16
            var dataOffset = -1
            var dataLength = 0

            while (buf.remaining() >= 8) {
                val chunkIdBytes = ByteArray(4).also { buf.get(it) }
                val chunkId = String(chunkIdBytes)
                val chunkSize = buf.int
                val safeChunkSize = chunkSize.coerceAtLeast(0).coerceAtMost(buf.remaining())

                when (chunkId) {
                    "fmt " -> {
                        val fmtStart = buf.position()
                        if (safeChunkSize >= 16) {
                            val audioFormat = buf.short.toInt() and 0xFFFF
                            require(audioFormat == 1) { "Unsupported WAV audio format: $audioFormat (PCM=1 required)" }
                            numChannels = buf.short.toInt() and 0xFFFF
                            sampleRate = buf.int
                            buf.int // byteRate
                            buf.short // blockAlign
                            bitsPerSample = buf.short.toInt() and 0xFFFF
                        }
                        buf.position(fmtStart + safeChunkSize)
                    }
                    "data" -> {
                        dataOffset = buf.position()
                        dataLength = safeChunkSize
                        break
                    }
                    else -> {
                        buf.position(buf.position() + safeChunkSize)
                    }
                }
                if (chunkSize % 2 == 1 && buf.hasRemaining()) {
                    buf.get()
                }
            }

            require(dataOffset >= 0 && dataLength > 0) { "WAV data chunk missing" }
            require(bitsPerSample == 16) { "Only 16-bit PCM WAV supported (got $bitsPerSample)" }
            require(numChannels == 1 || numChannels == 2) { "Unsupported channel count: $numChannels" }

            val bytesPerFrame = numChannels * 2
            val frameCount = dataLength / bytesPerFrame
            val left = FloatArray(frameCount)
            val right = FloatArray(frameCount)

            val dataBuf = ByteBuffer.wrap(bytes, dataOffset, frameCount * bytesPerFrame)
                .order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until frameCount) {
                val l = dataBuf.short / 32768f
                val r = if (numChannels == 2) dataBuf.short / 32768f else l
                left[i] = l
                right[i] = r
            }

            if (sampleRate == targetSampleRate || frameCount == 0) {
                return AudioStem(left, right, sampleRate)
            }

            // Linear resampling if stem sample rate differs from engine rate
            val ratio = sampleRate.toDouble() / targetSampleRate.toDouble()
            val outFrames = ((frameCount / ratio).toInt()).coerceAtLeast(1)
            val outLeft = FloatArray(outFrames)
            val outRight = FloatArray(outFrames)
            for (i in 0 until outFrames) {
                val srcPos = i * ratio
                val idx0 = srcPos.toInt().coerceIn(0, frameCount - 1)
                val idx1 = (idx0 + 1) % frameCount
                val frac = (srcPos - idx0).toFloat()
                outLeft[i] = left[idx0] + (left[idx1] - left[idx0]) * frac
                outRight[i] = right[idx0] + (right[idx1] - right[idx0]) * frac
            }
            return AudioStem(outLeft, outRight, targetSampleRate)
        }

        private fun readAllBytes(input: InputStream): ByteArray {
            val buffer = ByteArray(16 * 1024)
            val out = ByteArrayOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}
