package com.deepbass.player

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Owns the player and DSP chain independently of the Activity, allowing
 * Media3 to keep playback running and publish system media controls.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DeepBassPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var servicePlayer: ExoPlayer? = null
    private var audioProcessor: DeepBassAudioProcessor? = null

    override fun onCreate() {
        super.onCreate()

        val processor = DeepBassAudioProcessor()
        audioProcessor = processor
        activeProcessor = processor

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink? {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(processor))
                    .setEnableFloatOutput(false)
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this, renderersFactory).build()
        servicePlayer = player
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        servicePlayer?.release()
        servicePlayer = null
        if (activeProcessor === audioProcessor) {
            activeProcessor = null
        }
        audioProcessor = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var activeProcessor: DeepBassAudioProcessor? = null

        fun updateDsp(
            bass: Float,
            punch: Float,
            vocal: Float,
            preamp: Float,
            eqGains: FloatArray
        ) {
            activeProcessor?.let { processor ->
                processor.bassAmount = bass.coerceIn(0f, 100f) / 100f
                processor.punchAmount = punch.coerceIn(0f, 100f) / 100f
                processor.vocalAmount = vocal.coerceIn(0f, 100f) / 100f
                processor.preampDb = preamp.coerceIn(-12f, 0f)
                processor.setEqualizerGains(eqGains)
            }
        }
    }
}
