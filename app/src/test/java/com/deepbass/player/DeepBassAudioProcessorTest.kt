package com.deepbass.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepBassAudioProcessorTest {

    @Test
    fun pcmInputProducesImmediateCompleteFiniteOutput() {
        val processor = configuredProcessor(
            bass = 0f, punch = 0f, vocal = 0f, preamp = 0f, gains = FloatArray(10)
        )
        val input = makeToneBuffer(frameCount = 8192, frequencyHz = 440f)
        val inputBytes = input.remaining()

        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        assertEquals("DSP must preserve every PCM frame", inputBytes, output.remaining())
        var firstNonZeroSample = -1
        var sampleIndex = 0
        while (output.remaining() >= 2) {
            if (output.short.toInt() != 0 && firstNonZeroSample < 0) {
                firstNonZeroSample = sampleIndex
            }
            sampleIndex++
        }
        assertTrue("Audio must not be held back by a look-ahead delay", firstNonZeroSample in 0..3)
    }

    @Test
    fun bassControlChangesLowFrequencySignal() {
        val flatEnergy = renderEnergy(
            frequencyHz = 45f, bass = 0f, punch = 0f, vocal = 0f, gains = FloatArray(10)
        )
        val bassEnergy = renderEnergy(
            frequencyHz = 45f, bass = 100f, punch = 0f, vocal = 0f, gains = FloatArray(10)
        )
        assertTrue("Bass control must noticeably increase low-frequency energy", bassEnergy > flatEnergy * 1.8)
    }

    @Test
    fun equalizerGainChangesProcessedSignal() {
        val flat = renderEnergy(
            frequencyHz = 1000f, bass = 0f, punch = 0f, vocal = 0f, gains = FloatArray(10)
        )
        val boostedGains = FloatArray(10).also { it[5] = 8f }
        val boosted = renderEnergy(
            frequencyHz = 1000f, bass = 0f, punch = 0f, vocal = 0f, gains = boostedGains
        )
        assertTrue("A +8 dB 1 kHz band must measurably change the signal", boosted > flat * 1.5)
    }

    private fun renderEnergy(
        frequencyHz: Float,
        bass: Float,
        punch: Float,
        vocal: Float,
        gains: FloatArray
    ): Double {
        val processor = configuredProcessor(
            bass = bass, punch = punch, vocal = vocal, preamp = 0f, gains = gains
        )
        val input = makeToneBuffer(frameCount = 8192, frequencyHz = frequencyHz)
        processor.queueInput(input)
        val output = processor.output.order(ByteOrder.nativeOrder())

        var energy = 0.0
        var count = 0
        while (output.remaining() >= 2) {
            val sample = output.short.toDouble()
            energy += sample * sample
            count++
        }
        assertTrue("The processor should return PCM samples", count > 0)
        return energy / count
    }

    private fun configuredProcessor(
        bass: Float,
        punch: Float,
        vocal: Float,
        preamp: Float,
        gains: FloatArray
    ): DeepBassAudioProcessor {
        return DeepBassAudioProcessor().apply {
            bassAmount = bass
            punchAmount = punch
            vocalAmount = vocal
            preampDb = preamp
            setEqualizerGains(gains)
            configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
            flush()
        }
    }

    private fun makeToneBuffer(frameCount: Int, frequencyHz: Float): ByteBuffer {
        val sampleRate = 48_000
        val buffer = ByteBuffer.allocateDirect(frameCount * 2 * 2)
            .order(ByteOrder.nativeOrder())
        repeat(frameCount) { frame ->
            val sample = (sin(2.0 * PI * frequencyHz * frame / sampleRate) * 6000.0).toInt()
            buffer.putShort(sample.toShort())
            buffer.putShort(sample.toShort())
        }
        buffer.flip()
        return buffer
    }
}
