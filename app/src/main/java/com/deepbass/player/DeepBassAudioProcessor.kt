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
 * Local real-time PCM processor:
 * 10-band peaking EQ, sub-bass, punch, vocal presence, preamp and a
 * 3 ms look-ahead peak limiter with a smooth release.
 */
class DeepBassAudioProcessor : BaseAudioProcessor() {
    @Volatile var bassAmount: Float = 0.34f
    @Volatile var punchAmount: Float = 0.28f
    @Volatile var vocalAmount: Float = 0.26f
    @Volatile var preampDb: Float = -5.0f

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
        1.5f, 2.5f, 1.5f, 0f, -0.5f, 0f, 0.5f, 1f, 1f, 0.5f
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

    // Circular look-ahead audio buffer. Each frame stores all channels together.
    private var limiterBuffer = Array(2) { FloatArray(channels) }
    private var limiterWriteIndex = 0
    private var limiterGain = 1.0f
    private var limiterReleaseCoefficient = 0.0f
    private var limiterLookAheadFrames = 1
    private var limiterFramesProcessed = 0L
    private var peakDequeIndices = LongArray(4)
    private var peakDequeValues = FloatArray(4)
    private var peakDequeHead = 0
    private var peakDequeCount = 0
    private val limiterThreshold = 0.90f

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
        rebuildEqualizer()
        prepareLimiter()
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

        val bassGain = bassAmount.coerceIn(0f, 1f) * 0.88f
        val punchGain = punchAmount.coerceIn(0f, 1f) * 0.38f
        val vocalGain = vocalAmount.coerceIn(0f, 1f) * 0.10f
        val preampGain = 10.0.pow(preampDb.coerceIn(-12f, 0f) / 20.0).toFloat()
        val coefficients = equalizerCoefficients

        repeat(frames) {
            var framePeak = 0f

            // Decode and shape the frame, then store it in the look-ahead audio ring.
            repeat(channels) { channel ->
                val decoded = if (encoding == C.ENCODING_PCM_FLOAT) {
                    input.float
                } else {
                    input.short / 32768.0f
                }

                var shaped = decoded * preampGain

                // Ten peaking filters; their coefficients are prepared outside this audio loop.
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
                val safeSample = if (mixed.isFinite()) mixed else 0f
                limiterBuffer[limiterWriteIndex][channel] = safeSample
                framePeak = maxOf(framePeak, abs(safeSample))
            }

            // Maintain a monotonic maximum queue. This replaces scanning every
            // sample in the 3 ms look-ahead window (which was too expensive on mobile).
            val currentFrameIndex = limiterFramesProcessed
            while (peakDequeCount > 0) {
                val tailPosition = (peakDequeHead + peakDequeCount - 1) % peakDequeIndices.size
                if (peakDequeValues[tailPosition] > framePeak) break
                peakDequeCount--
            }
            val newTail = (peakDequeHead + peakDequeCount) % peakDequeIndices.size
            peakDequeIndices[newTail] = currentFrameIndex
            peakDequeValues[newTail] = framePeak
            peakDequeCount++

            val oldestFrameIndex = currentFrameIndex - limiterLookAheadFrames
            while (peakDequeCount > 0 && peakDequeIndices[peakDequeHead] < oldestFrameIndex) {
                peakDequeHead = (peakDequeHead + 1) % peakDequeIndices.size
                peakDequeCount--
            }

            // After the pointer advances, it points to the oldest delayed audio frame.
            limiterWriteIndex = (limiterWriteIndex + 1) % limiterBuffer.size

            if (currentFrameIndex < limiterLookAheadFrames) {
                repeat(channels) {
                    if (encoding == C.ENCODING_PCM_FLOAT) output.putFloat(0f)
                    else output.putShort(0)
                }
            } else {
                // The deque head is the maximum peak across the complete look-ahead window.
                val windowPeak = if (peakDequeCount > 0) peakDequeValues[peakDequeHead] else 0f
                val targetGain = if (windowPeak > limiterThreshold) {
                    limiterThreshold / windowPeak
                } else {
                    1f
                }

                // Immediate attack and smooth release keep transient control without per-frame scans.
                limiterGain = if (targetGain < limiterGain) {
                    targetGain
                } else {
                    limiterGain + (targetGain - limiterGain) * limiterReleaseCoefficient
                }

                val delayedFrame = limiterBuffer[limiterWriteIndex]
                repeat(channels) { channel ->
                    val limited = (delayedFrame[channel] * limiterGain)
                        .coerceIn(-limiterThreshold, limiterThreshold)
                    if (encoding == C.ENCODING_PCM_FLOAT) {
                        output.putFloat(limited)
                    } else {
                        output.putShort((limited * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                    }
                }
            }

            limiterFramesProcessed++
        }

        input.position(input.position() + (availableBytes - byteCount))
        output.flip()
    }

    private fun prepareLimiter() {
        // 3 ms of look-ahead is a practical compromise for mobile CPU and transient control.
        limiterLookAheadFrames = (sampleRate * 3 / 1000).coerceAtLeast(1)
        limiterBuffer = Array(limiterLookAheadFrames + 1) { FloatArray(channels) }
        // A monotonic deque stores only candidate peaks: total work is O(1) amortized per frame.
        val dequeCapacity = limiterLookAheadFrames + 2
        peakDequeIndices = LongArray(dequeCapacity)
        peakDequeValues = FloatArray(dequeCapacity)
        limiterReleaseCoefficient = (1.0 - exp(-1.0 / (sampleRate * 0.075))).toFloat()
    }

    private fun rebuildEqualizer() {
        val gains = equalizerGainsDb
        val nyquistLimit = (sampleRate * 0.45f).coerceAtLeast(20f)
        equalizerCoefficients = Array(bandFrequencies.size) { index ->
            val frequency = bandFrequencies[index].coerceIn(20f, nyquistLimit)
            createPeakingBiquad(frequency, gains.getOrElse(index) { 0f }.coerceIn(-12f, 12f), 1.05f)
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
        for (frame in limiterBuffer) frame.fill(0f)
        limiterWriteIndex = 0
        limiterGain = 1f
        limiterFramesProcessed = 0L
        peakDequeHead = 0
        peakDequeCount = 0
        peakDequeIndices.fill(0L)
        peakDequeValues.fill(0f)
    }
}
