package com.deepbass.player

import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DeepBassScreen() }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun DeepBassScreen() {
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var selectedSong by remember { mutableStateOf("No track selected") }
    var isPlaying by remember { mutableStateOf(false) }
    var audioSessionId by remember { mutableIntStateOf(0) }
    var bass by remember { mutableFloatStateOf(35f) }
    var punch by remember { mutableFloatStateOf(25f) }
    var vocal by remember { mutableFloatStateOf(20f) }
    var effectsAvailable by remember { mutableStateOf(false) }
    var bassEffect by remember { mutableStateOf<BassBoost?>(null) }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onAudioSessionIdChanged(id: Int) {
                audioSessionId = id
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            bassEffect?.release()
            equalizer?.release()
            player.release()
        }
    }

    LaunchedEffect(audioSessionId) {
        bassEffect?.release()
        equalizer?.release()
        bassEffect = null
        equalizer = null
        effectsAvailable = false
        if (audioSessionId > 0) {
            try {
                val bb = withContext(Dispatchers.IO) { BassBoost(0, audioSessionId) }
                val eq = withContext(Dispatchers.IO) { Equalizer(0, audioSessionId) }
                bb.enabled = true
                eq.enabled = true
                bassEffect = bb
                equalizer = eq
                effectsAvailable = true
            } catch (_: Exception) {
                effectsAvailable = false
            }
        }
    }

    LaunchedEffect(bass, punch, vocal, effectsAvailable) {
        val bb = bassEffect
        val eq = equalizer
        if (effectsAvailable && bb != null && eq != null) {
            try {
                bb.setStrength((bass * 10).toInt().coerceIn(0, 1000).toShort())
                val range = eq.bandLevelRange
                val maxBoost = range[1].toInt()
                for (index in 0 until eq.numberOfBands.toInt()) {
                    val band = index.toShort()
                    val hz = eq.getCenterFreq(band) / 1000
                    val gain = when {
                        hz in 45..160 -> (punch * 5.0f).toInt()
                        hz in 1000..5000 -> (vocal * 3.0f).toInt()
                        else -> 0
                    }.coerceAtMost(maxBoost)
                    eq.setBandLevel(band, gain.toShort())
                }
            } catch (_: Exception) {
                effectsAvailable = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            selectedSong = it.lastPathSegment?.substringAfterLast('/') ?: "Selected audio file"
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
            player.volume = 0.82f
            player.play()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB7F36B),
        background = Color(0xFF090B0F),
        surface = Color(0xFF151922)
    )) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF090B0F)) {
            Column(
                Modifier.fillMaxSize().padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(17.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFB7F36B))
                    Spacer(Modifier.width(10.dp))
                    Text("DEEPBASS", color = Color.White, fontSize = 22.sp)
                    Spacer(Modifier.weight(1f))
                    Text("BETA 0.2", color = Color.Gray, fontSize = 12.sp)
                }
                Text("Feel the depth.", color = Color.White, fontSize = 30.sp)
                Text("Smooth low-end. Clear vocals.", color = Color.LightGray)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151922)),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("NOW PLAYING", color = Color(0xFFB7F36B), fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(selectedSong, color = Color.White, fontSize = 16.sp, maxLines = 2)
                        Spacer(Modifier.height(15.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { picker.launch(arrayOf("audio/*")) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FolderOpen, null)
                                Spacer(Modifier.width(7.dp))
                                Text("Choose song")
                            }
                            FilledTonalButton(
                                onClick = {
                                    if (player.isPlaying) player.pause()
                                    else if (player.mediaItemCount > 0) player.play()
                                },
                                enabled = player.mediaItemCount > 0,
                                modifier = Modifier.width(58.dp)
                            ) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                            }
                        }
                    }
                }
                Text("BASS & TONE", color = Color.White, fontSize = 16.sp)
                ControlSlider("Deep Bass", bass, { bass = it }, "Sub-bass weight")
                ControlSlider("Punch", punch, { punch = it }, "Kick and bass impact")
                ControlSlider("Vocal Clarity", vocal, { vocal = it }, "Gentle upper-mid presence")
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (effectsAvailable) Color(0xFF203020) else Color(0xFF292929),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            if (effectsAvailable) "Audio effects active" else "Choose and play a song to initialise effects",
                            color = if (effectsAvailable) Color(0xFFB7F36B) else Color.LightGray,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            fontSize = 11.sp
                        )
                    }
                }
                Text(
                    "Local playback • No account • No uploads",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ControlSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    helper: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text("${value.toInt()}%", color = Color(0xFFB7F36B))
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = 0f..100f)
        Text(helper, color = Color.Gray, fontSize = 10.sp)
    }
}
