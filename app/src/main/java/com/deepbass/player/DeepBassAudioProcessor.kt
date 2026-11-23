package com.deepbass.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Lightweight, local PCM processor. Adds controlled sub-bass and punch while
 * keeping a separate presence band for vocals. A soft ceiling helps avoid
 * harsh digital clipping; it is not a replacement for a studio limiter.
 */
class DeepBassAudioProcessor : BaseAudioProcessor() {
    @Volatile var bassAmount: Float = 0.35f
    @Volatile var punchAmount: Float = 0.25f
    @Volatile var vocalAmount: Float = 0.20f

    private var sampleRate = 48000
    private var channels = 2
    private var subState = FloatArray(2)
    private var lowState = FloatArray(2)
    private var presenceHighState = FloatArray(2)
    private var presenceLowState = FloatArray(2)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        resetStates()
        return inputAudioFormat
    }

    override fun onFlush() {
        resetStates()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val encoding = inputAudioFormat.encoding
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = channels * bytesPerSample
        val byteCount = input.remaining() - (input.remaining() % frameBytes)
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        val frames = byteCount / frameBytes

        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val original = if (encoding == C.ENCODING_PCM_FLOAT) {
                    input.float
                } else {
                    input.short / 32768.0f
                }
                val sub = lowPass(original, channel, 48f, subState)
                val low = lowPass(original, channel, 145f, lowState)
                val punchBand = (low - sub * 0.72f)
                val presence = highPassThenLowPass(original, channel, 950f, 4200f)

                var sample = original +
                    sub * (bassAmount.coerceIn(0f, 1f) * 0.95f) +
                    punchBand * (punchAmount.coerceIn(0f, 1f) * 0.42f) +
                    presence * (vocalAmount.coerceIn(0f, 1f) * 0.12f)

                sample = softCeiling(sample)
                if (encoding == C.ENCODING_PCM_FLOAT) {
                    output.putFloat(sample)
                } else {
                    output.putShort((sample * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }
        input.position(input.position() + (input.remaining() % frameBytes))
        output.flip()
    }

    private fun lowPass(input: Float, channel: Int, cutoff: Float, state: FloatArray): Float {
        val alpha = 1f - exp((-2.0 * Math.PI * cutoff / sampleRate).toFloat())
        state[channel] += alpha * (input - state[channel])
        return state[channel]
    }

    private fun highPassThenLowPass(input: Float, channel: Int, highCut: Float, lowCut: Float): Float {
        val highPassed = input - lowPass(input, channel, highCut, presenceHighState)
        return lowPass(highPassed, channel, lowCut, presenceLowState)
    }

    private fun softCeiling(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= 0.92f) return value
        val excess = (magnitude - 0.92f).coerceAtLeast(0f)
        return sign(value) * (0.92f + 0.075f * (1f - exp(-excess / 0.075f)))
    }

    private fun resetStates() {
        subState = FloatArray(channels)
        lowState = FloatArray(channels)
        presenceHighState = FloatArray(channels)
        presenceLowState = FloatArray(channels)
    }
}
