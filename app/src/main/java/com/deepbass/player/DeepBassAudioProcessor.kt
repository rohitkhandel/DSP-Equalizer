package com.deepbass.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Low-latency PCM DSP for local playback.
 *
 * Processing is frame-based and allocation-free inside the sample loop:
 * 10-band peaking EQ, sub-bass, punch, vocal presence, smoothed controls,
 * and a linked stereo peak controller. There is no look-ahead audio queue,
 * so playback does not acquire an artificial delay or lose a delayed tail.
 */
class DeepBassAudioProcessor : BaseAudioProcessor() {
    @Volatile var bassAmount: Float = 0.55f
    @Volatile var punchAmount: Float = 0.42f
    @Volatile var vocalAmount: Float = 0.40f
    @Volatile var preampDb: Float = 0.0f

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

    @Volatile private var equalizerGainsDb = FloatArray(bandFrequencies.size)
    @Volatile private var equalizerCoefficients: Array<Biquad> = emptyArray()

    private var sampleRate = 48000
    private var channels = 2

    private var subAlpha = 0f
    private var lowAlpha = 0f
    private var presenceHighAlpha = 0f
    private var presenceLowAlpha = 0f
    private var controlSmoothing = 0f
    private var limiterRelease = 0f

    private var subState = FloatArray(channels)
    private var lowState = FloatArray(channels)
    private var presenceHighState = FloatArray(channels)
    private var presenceLowState = FloatArray(channels)
    private var eqZ1 = Array(bandFrequencies.size) { FloatArray(channels) }
    private var eqZ2 = Array(bandFrequencies.size) { FloatArray(channels) }

    private var smoothedBass = 0f
    private var smoothedPunch = 0f
    private var smoothedVocal = 0f
    private var smoothedPreampGain = 1f
    private var limiterEnvelope = 0f
    private var limiterGain = 1f

    private val limiterThreshold = 0.94f

    init {
        rebuildEqualizer()
    }

    fun setEqualizerGains(gainsDb: FloatArray) {
        equalizerGainsDb = FloatArray(bandFrequencies.size) { index ->
            gainsDb.getOrElse(index) { 0f }.coerceIn(-12f, 12f)
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
        controlSmoothing = (1.0 - exp(-1.0 / (sampleRate * 0.008))).toFloat()
        limiterRelease = (1.0 - exp(-1.0 / (sampleRate * 0.060))).toFloat()
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

        val targetBass = bassAmount.coerceIn(0f, 1f)
        val targetPunch = punchAmount.coerceIn(0f, 1f)
        val targetVocal = vocalAmount.coerceIn(0f, 1f)
        val targetPreampGain = 10.0.pow(preampDb.coerceIn(-12f, 0f) / 20.0).toFloat()
        val coefficients = equalizerCoefficients
        val frameSamples = FloatArray(channels)

        repeat(frames) {
            smoothedBass += (targetBass - smoothedBass) * controlSmoothing
            smoothedPunch += (targetPunch - smoothedPunch) * controlSmoothing
            smoothedVocal += (targetVocal - smoothedVocal) * controlSmoothing
            smoothedPreampGain +=
                (targetPreampGain - smoothedPreampGain) * controlSmoothing

            // Strong enough to be audible at normal slider positions, while
            // the linked limiter below protects against digital clipping.
            val bassGain = smoothedBass * 1.80f
            val punchGain = smoothedPunch * 0.72f
            val vocalGain = smoothedVocal * 0.18f

            var framePeak = 0f
            repeat(channels) { channel ->
                val decoded = if (encoding == C.ENCODING_PCM_FLOAT) {
                    input.float
                } else {
                    input.short / 32768.0f
                }

                var shaped = decoded * smoothedPreampGain

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

                val presenceLow = lowPass(
                    shaped, channel, presenceHighAlpha, presenceHighState
                )
                val presenceHigh = shaped - presenceLow
                val vocalBand = lowPass(
                    presenceHigh, channel, presenceLowAlpha, presenceLowState
                )

                val mixed = shaped +
                    (sub * bassGain) +
                    (punchBand * punchGain) +
                    (vocalBand * vocalGain)

                val safeSample = if (mixed.isFinite()) mixed else 0f
                frameSamples[channel] = safeSample
                framePeak = maxOf(framePeak, abs(safeSample))
            }

            // Linked-channel envelope control with instant attack and smooth
            // release. This avoids the old circular look-ahead buffer, which
            // added latency and could create start/seek transients.
            limiterEnvelope = if (framePeak > limiterEnvelope) {
                framePeak
            } else {
                limiterEnvelope + (framePeak - limiterEnvelope) * limiterRelease
            }

            val targetLimiterGain = if (limiterEnvelope > limiterThreshold) {
                limiterThreshold / limiterEnvelope
            } else {
                1f
            }
            limiterGain = if (targetLimiterGain < limiterGain) {
                targetLimiterGain
            } else {
                limiterGain + (targetLimiterGain - limiterGain) * limiterRelease
            }

            repeat(channels) { channel ->
                val limited = (frameSamples[channel] * limiterGain)
                    .coerceIn(-limiterThreshold, limiterThreshold)
                if (encoding == C.ENCODING_PCM_FLOAT) {
                    output.putFloat(limited)
                } else {
                    val pcm = (limited * 32768f)
                        .toInt().coerceIn(-32768, 32767)
                    output.putShort(pcm.toShort())
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
            createPeakingBiquad(
                frequency,
                gains.getOrElse(index) { 0f }.coerceIn(-12f, 12f),
                1.05f
            )
        }
    }

    private fun createPeakingBiquad(frequency: Float, gainDb: Float, q: Float): Biquad {
        val amplitude = 10.0.pow(gainDb / 40.0)
        val omega = 2.0 * PI * frequency / sampleRate
        val sine = sin(omega)
        val cosine = cos(omega)
        val alpha = sine / (2.0 * q)
        val a0 = 1.0 + alpha / amplitude

        return Biquad(
            b0 = ((1.0 + alpha * amplitude) / a0).toFloat(),
            b1 = ((-2.0 * cosine) / a0).toFloat(),
            b2 = ((1.0 - alpha * amplitude) / a0).toFloat(),
            a1 = ((-2.0 * cosine) / a0).toFloat(),
            a2 = ((1.0 - alpha / amplitude) / a0).toFloat()
        )
    }

    private fun calculateAlpha(cutoffHz: Float): Float {
        val safeCutoff = cutoffHz.coerceIn(10f, sampleRate * 0.45f)
        return (1.0 - exp(-2.0 * PI * safeCutoff / sampleRate)).toFloat()
    }

    private fun lowPass(input: Float, channel: Int, alpha: Float, state: FloatArray): Float {
        val updated = state[channel] + alpha * (input - state[channel])
        state[channel] = updated
        return updated
    }

    private fun resetStates() {
        subState = FloatArray(channels)
        lowState = FloatArray(channels)
        presenceHighState = FloatArray(channels)
        presenceLowState = FloatArray(channels)
        eqZ1 = Array(bandFrequencies.size) { FloatArray(channels) }
        eqZ2 = Array(bandFrequencies.size) { FloatArray(channels) }
        smoothedBass = bassAmount.coerceIn(0f, 1f)
        smoothedPunch = punchAmount.coerceIn(0f, 1f)
        smoothedVocal = vocalAmount.coerceIn(0f, 1f)
        smoothedPreampGain = 10.0.pow(preampDb.coerceIn(-12f, 0f) / 20.0).toFloat()
        limiterEnvelope = 0f
        limiterGain = 1f
    }
}
