package com.deepbass.player

import android.content.Context
import android.media.AudioFormat as AndroidAudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.audio.AudioProcessor
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class DspExportSettings(
    val bass: Float,
    val punch: Float,
    val vocal: Float,
    val preampDb: Float,
    val equalizerGainsDb: FloatArray
)

/**
 * Decodes a selected audio document locally, applies the same DeepBass PCM
 * processor used for playback, then writes a standard 16-bit PCM WAV file.
 */
object OfflineWavExporter {

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun export(
        context: Context,
        inputUri: Uri,
        outputUri: Uri,
        settings: DspExportSettings
    ): Long = withContext(Dispatchers.IO) {
        val workContext = coroutineContext
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var processor: DeepBassAudioProcessor? = null
        val tempFile = File.createTempFile("deepbass_processed_", ".wav", context.cacheDir)

        try {
            extractor.setDataSource(context, inputUri, null)

            var audioTrackIndex = -1
            var sourceFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(index)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = index
                    sourceFormat = trackFormat
                    break
                }
            }

            check(audioTrackIndex >= 0 && sourceFormat != null) {
                "No supported audio track was found in this file."
            }

            val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Audio format is missing its decoder type.")
            extractor.selectTrack(audioTrackIndex)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()

            val dsp = DeepBassAudioProcessor().apply {
                bassAmount = settings.bass.coerceIn(0f, 100f) / 100f
                punchAmount = settings.punch.coerceIn(0f, 100f) / 100f
                vocalAmount = settings.vocal.coerceIn(0f, 100f) / 100f
                preampDb = settings.preampDb.coerceIn(-12f, 0f)
                setEqualizerGains(settings.equalizerGainsDb)
            }
            processor = dsp

            RandomAccessFile(tempFile, "rw").use { wavFile ->
                wavFile.setLength(0)
                wavFile.write(ByteArray(WAV_HEADER_SIZE))

                var processorConfigured = false
                var pcmEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT
                var sampleRate = 0
                var channelCount = 0
                var inputEnded = false
                var outputEnded = false
                var dataBytes = 0L
                val bufferInfo = MediaCodec.BufferInfo()
                val timeoutUs = 10_000L

                fun configureDsp(outputFormat: MediaFormat) {
                    sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else {
                        AndroidAudioFormat.ENCODING_PCM_16BIT
                    }

                    check(
                        pcmEncoding == AndroidAudioFormat.ENCODING_PCM_16BIT ||
                            pcmEncoding == AndroidAudioFormat.ENCODING_PCM_FLOAT
                    ) {
                        "This decoder returned an unsupported PCM format."
                    }
                    check(sampleRate > 0 && channelCount > 0) {
                        "The decoded audio format is invalid."
                    }

                    dsp.configure(AudioProcessor.AudioFormat(sampleRate, channelCount, pcmEncoding))
                    check(dsp.isActive) { "DeepBass DSP could not handle this audio format." }
                    dsp.flush()
                    processorConfigured = true
                }

                fun writeDspOutput(): Long {
                    val processed = dsp.output.order(ByteOrder.nativeOrder())
                    if (!processed.hasRemaining()) return 0L

                    val sampleBytes = if (pcmEncoding == AndroidAudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val sampleCount = processed.remaining() / sampleBytes
                    if (sampleCount <= 0) return 0L

                    val wavPcm = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(sampleCount) {
                        val value = if (pcmEncoding == AndroidAudioFormat.ENCODING_PCM_FLOAT) {
                            (processed.float.coerceIn(-1f, 1f) * 32767f)
                                .toInt().coerceIn(-32768, 32767).toShort()
                        } else {
                            processed.short
                        }
                        wavPcm.putShort(value)
                    }
                    wavFile.write(wavPcm.array())
                    return (sampleCount * 2).toLong()
                }

                while (!outputEnded) {
                    workContext.ensureActive()

                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)
                            if (inputBuffer == null) {
                                decoder.queueInputBuffer(
                                    inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEnded = true
                            } else {
                                inputBuffer.clear()
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputEnded = true
                                } else {
                                    decoder.queueInputBuffer(
                                        inputIndex, 0, sampleSize, extractor.sampleTime, 0
                                    )
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            configureDsp(decoder.outputFormat)
                        }

                        outputIndex >= 0 -> {
                            if (bufferInfo.size > 0) {
                                if (!processorConfigured) configureDsp(decoder.outputFormat)

                                val decodedBuffer = decoder.getOutputBuffer(outputIndex)
                                    ?: error("Decoder returned an empty audio buffer.")
                                decodedBuffer.position(bufferInfo.offset)
                                decodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val pcmInput = decodedBuffer.slice().order(ByteOrder.nativeOrder())

                                dsp.queueInput(pcmInput)
                                dataBytes += writeDspOutput()
                            }

                            val endOfStream =
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if (endOfStream) outputEnded = true
                        }
                    }
                }

                check(processorConfigured && dataBytes > 0L) {
                    "No decoded audio samples were produced."
                }

                // End the processor cleanly; the short limiter look-ahead tail is discarded.
                dsp.queueEndOfStream()
                while (!dsp.isEnded) {
                    dataBytes += writeDspOutput()
                    workContext.ensureActive()
                }

                check(dataBytes <= 0xFFFF_FFFFL - 36L) {
                    "This WAV export is larger than the standard WAV size limit."
                }
                writeWavHeader(
                    wavFile = wavFile,
                    dataBytes = dataBytes,
                    sampleRate = sampleRate,
                    channels = channelCount
                )
            }

            val outputStream = context.contentResolver.openOutputStream(outputUri, "w")
                ?: error("Could not open the selected save location.")
            FileInputStream(tempFile).use { input ->
                outputStream.use { output -> input.copyTo(output) }
            }

            dataBytesFromWav(tempFile)
        } finally {
            try {
                decoder?.stop()
            } catch (_: Exception) { }
            decoder?.release()
            processor?.reset()
            extractor.release()
            tempFile.delete()
        }
    }

    private fun writeWavHeader(
        wavFile: RandomAccessFile,
        dataBytes: Long,
        sampleRate: Int,
        channels: Int
    ) {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        wavFile.seek(0)
        wavFile.writeBytes("RIFF")
        writeIntLittleEndian(wavFile, (dataBytes + 36L).toInt())
        wavFile.writeBytes("WAVE")
        wavFile.writeBytes("fmt ")
        writeIntLittleEndian(wavFile, 16)
        writeShortLittleEndian(wavFile, 1)
        writeShortLittleEndian(wavFile, channels)
        writeIntLittleEndian(wavFile, sampleRate)
        writeIntLittleEndian(wavFile, byteRate)
        writeShortLittleEndian(wavFile, blockAlign)
        writeShortLittleEndian(wavFile, 16)
        wavFile.writeBytes("data")
        writeIntLittleEndian(wavFile, dataBytes.toInt())
    }

    private fun writeIntLittleEndian(file: RandomAccessFile, value: Int) {
        file.write(value and 0xFF)
        file.write((value ushr 8) and 0xFF)
        file.write((value ushr 16) and 0xFF)
        file.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLittleEndian(file: RandomAccessFile, value: Int) {
        file.write(value and 0xFF)
        file.write((value ushr 8) and 0xFF)
    }

    private fun dataBytesFromWav(file: File): Long = (file.length() - WAV_HEADER_SIZE).coerceAtLeast(0L)

    private const val WAV_HEADER_SIZE = 44
}
