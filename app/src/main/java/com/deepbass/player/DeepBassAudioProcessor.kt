package com.deepbass.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Local real-time PCM processor: 10-band peaking EQ plus sub-bass, punch,
 * vocal-presence and preamp/headroom controls.
 */
class DeepBassAudioProcessor : BaseAudioProcessor() {
    @Volatile var bassAmount: Float = 0.38f
    @Volatile var punchAmount: Float = 0.30f
    @Volatile var vocalAmount: Float = 0.24f
    @Volatile var preampDb: Float = -4.0f

    private val bandFrequencies = floatArrayOf(
        31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
    )

    private data class Biquad(
        val b0: Float,
        val b1: Float,
        val b2: Float,
        val a1: Float,
        val a2: Float
    )

    @Volatile private var equalizerGainsDb = floatArrayOf(
        2f, 3f, 2f, 0.5f, -0.5f, 0f, 0.5f, 1f, 1f, 0.5f
    )
    @Volatile private var equalizerCoefficients: Array<Biquad> = emptyArray()

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
    private var eqZ1 = Array(bandFrequencies.size) { FloatArray(channels) }
    private var eqZ2 = Array(bandFrequencies.size) { FloatArray(channels) }

    init {
        rebuildEqualizer()
    }

    fun setEqualizerGains(gainsDb: FloatArray) {
        equalizerGainsDb = FloatArray(bandFrequencies.size) { index ->
            (gainsDb.getOrElse(index) { 0f }).coerceIn(-12f, 12f)
        }
        rebuildEqualizer()
    }

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
        rebuildEqualizer()
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
        val preampGain = 10.0.pow(preampDb.coerceIn(-12f, 0f) / 20.0).toFloat()
        val coefficients = equalizerCoefficients

        repeat(frames) {
            repeat(channels) { channel ->
                val decoded = if (encoding == C.ENCODING_PCM_FLOAT) {
                    input.float
                } else {
                    input.short / 32768.0f
                }

                // Preamp attenuation creates headroom before EQ and bass boosts.
                var shaped = decoded * preampGain

                // Ten peaking filters, with coefficients prepared outside the audio loop.
                for (band in coefficients.indices) {
                    val c = coefficients[band]
                    val z1 = eqZ1[band]
                    val z2 = eqZ2[band]
                    val filtered = c.b0 * shaped + z1[channel]
                    z1[channel] = c.b1 * shaped - c.a1 * filtered + z2[channel]
                    z2[channel] = c.b2 * shaped - c.a2 * filtered
                    shaped = filtered
                }

                val sub = lowPass(shaped, channel, subAlpha, subState)
                val low = lowPass(shaped, channel, lowAlpha, lowState)
                val punchBand = low - (sub * 0.72f)
                val presenceLow = lowPass(shaped, channel, presenceHighAlpha, presenceHighState)
                val presenceHigh = shaped - presenceLow
                val vocalBand = lowPass(presenceHigh, channel, presenceLowAlpha, presenceLowState)

                val mixed = shaped +
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

        input.position(input.position() + (availableBytes - byteCount))
        output.flip()
    }

    private fun rebuildEqualizer() {
        val gains = equalizerGainsDb
        val nyquistLimit = (sampleRate * 0.45f).coerceAtLeast(20f)
        equalizerCoefficients = Array(bandFrequencies.size) { index ->
            val frequency = bandFrequencies[index].coerceIn(20f, nyquistLimit)
            val gain = gains.getOrElse(index) { 0f }.coerceIn(-12f, 12f)
            createPeakingBiquad(frequency, gain, 1.05f)
        }
    }

    private fun createPeakingBiquad(frequency: Float, gainDb: Float, q: Float): Biquad {
        val a = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)
        val a0 = 1.0 + alpha / a

        return Biquad(
            b0 = ((1.0 + alpha * a) / a0).toFloat(),
            b1 = ((-2.0 * cosOmega) / a0).toFloat(),
            b2 = ((1.0 - alpha * a) / a0).toFloat(),
            a1 = ((-2.0 * cosOmega) / a0).toFloat(),
            a2 = ((1.0 - alpha / a) / a0).toFloat()
        )
    }

    private fun calculateAlpha(cutoffHz: Float): Float {
        val safeCutoff = cutoffHz.coerceIn(10f, sampleRate * 0.45f)
        return (1.0 - kotlin.math.exp(-2.0 * PI * safeCutoff / sampleRate)).toFloat()
    }

    private fun lowPass(input: Float, channel: Int, alpha: Float, state: FloatArray): Float {
        val updated = state[channel] + alpha * (input - state[channel])
        state[channel] = updated
        return updated
    }

    /**
     * A smooth soft clipper that rounds peaks. It is not a look-ahead limiter.
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
        eqZ1 = Array(bandFrequencies.size) { FloatArray(channels) }
        eqZ2 = Array(bandFrequencies.size) { FloatArray(channels) }
    }
}
