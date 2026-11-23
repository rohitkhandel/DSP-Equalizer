package com.deepbass.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sign

/**
 * Local real-time PCM tone processor.
 *
 * The three controls add a restrained sub region, upper-bass punch and vocal
 * presence. Filter coefficients are calculated when the format changes rather
 * than for every sample, keeping CPU cost lower on mobile devices.
 */
class DeepBassAudioProcessor : BaseAudioProcessor() {
    @Volatile var bassAmount: Float = 0.38f
    @Volatile var punchAmount: Float = 0.30f
    @Volatile var vocalAmount: Float = 0.24f

    private var sampleRate = 48000
    private var channels = 2

    private var subAlpha = 0.0f
    private var lowAlpha = 0.0f
    private var presenceHighAlpha = 0.0f
    private var presenceLowAlpha = 0.0f

    private var subState = FloatArray(channels)
    private var lowState = FloatArray(channels)
    private var presenceHighState = FloatArray(channels)
    private var presenceLowState = FloatArray(channels)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        subAlpha = calculateAlpha(48f)
        lowAlpha = calculateAlpha(145f)
        presenceHighAlpha = calculateAlpha(950f)
        presenceLowAlpha = calculateAlpha(4200f)
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
        val availableBytes = input.remaining()
        val byteCount = availableBytes - (availableBytes % frameBytes)
        val output = replaceOutputBuffer(byteCount).order(ByteOrder.nativeOrder())
        val frames = byteCount / frameBytes

        val bassGain = bassAmount.coerceIn(0f, 1f) * 0.95f
        val punchGain = punchAmount.coerceIn(0f, 1f) * 0.42f
        val vocalGain = vocalAmount.coerceIn(0f, 1f) * 0.12f

        repeat(frames) {
            repeat(channels) { channel ->
                val source = if (encoding == C.ENCODING_PCM_FLOAT) {
                    input.float
                } else {
                    input.short / 32768.0f
                }

                val sub = lowPass(source, channel, subAlpha, subState)
                val low = lowPass(source, channel, lowAlpha, lowState)
                val punchBand = low - (sub * 0.72f)
                val presenceLow = lowPass(source, channel, presenceHighAlpha, presenceHighState)
                val presenceHigh = source - presenceLow
                val vocalBand = lowPass(presenceHigh, channel, presenceLowAlpha, presenceLowState)

                val mixed = source +
                    (sub * bassGain) +
                    (punchBand * punchGain) +
                    (vocalBand * vocalGain)
                val limited = softCeiling(mixed)

                if (encoding == C.ENCODING_PCM_FLOAT) {
                    output.putFloat(limited)
                } else {
                    output.putShort((limited * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                }
            }
        }

        // Keep any incomplete trailing PCM frame for BaseAudioProcessor's caller.
        input.position(input.position() + (availableBytes - byteCount))
        output.flip()
    }

    private fun calculateAlpha(cutoffHz: Float): Float {
        val safeCutoff = cutoffHz.coerceIn(10f, sampleRate * 0.45f)
        return (1.0 - kotlin.math.exp(-2.0 * Math.PI * safeCutoff / sampleRate)).toFloat()
    }

    private fun lowPass(input: Float, channel: Int, alpha: Float, state: FloatArray): Float {
        val updated = state[channel] + alpha * (input - state[channel])
        state[channel] = updated
        return updated
    }

    /**
     * Smoothly rounds peaks near full scale. This is a soft clipper, not a
     * look-ahead brick-wall limiter; it avoids the abrupt hard clipping edge.
     */
    private fun softCeiling(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= 0.92f) return value
        val excess = magnitude - 0.92f
        val rounded = 0.92f + (0.075f * excess / (excess + 0.075f))
        return sign(value) * rounded.coerceAtMost(0.995f)
    }

    private fun resetStates() {
        subState = FloatArray(channels)
        lowState = FloatArray(channels)
        presenceHighState = FloatArray(channels)
        presenceLowState = FloatArray(channels)
    }
}
