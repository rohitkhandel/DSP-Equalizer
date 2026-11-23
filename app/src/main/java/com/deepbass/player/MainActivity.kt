package com.deepbass.player

import android.content.Intent
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
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DeepBassScreen() }
    }
}

@Composable
fun DeepBassScreen() {
    val context = LocalContext.current
    val processor = remember { DeepBassAudioProcessor() }
    val player = remember {
        val sink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(processor))
            .build()
        val renderers = DefaultRenderersFactory(context).setAudioSink(sink)
        ExoPlayer.Builder(context, renderers).build()
    }
    var selectedSong by remember { mutableStateOf("No track selected") }
    var isPlaying by remember { mutableStateOf(false) }
    var bass by remember { mutableFloatStateOf(38f) }
    var punch by remember { mutableFloatStateOf(30f) }
    var vocal by remember { mutableFloatStateOf(24f) }

    SideEffect {
        processor.bassAmount = bass / 100f
        processor.punchAmount = punch / 100f
        processor.vocalAmount = vocal / 100f
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
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
                    Text("DSP BETA 0.3", color = Color.Gray, fontSize = 12.sp)
                }
                Text("Subwoofer-style depth.", color = Color.White, fontSize = 27.sp)
                Text("Deep low-end • Tight punch • Clear vocals", color = Color.LightGray)
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
                ControlSlider("Sub-bass depth", bass, { bass = it }, "Low-frequency weight and rumble")
                ControlSlider("Punch", punch, { punch = it }, "Tighter kick and mid-bass impact")
                ControlSlider("Vocal clarity", vocal, { vocal = it }, "Gentle vocal-presence lift")
                Spacer(Modifier.weight(1f))
                Surface(color = Color(0xFF203020), shape = RoundedCornerShape(50)) {
                    Text(
                        "Custom PCM DSP active • Local processing",
                        color = Color(0xFFB7F36B),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 11.sp
                    )
                }
                Text("No account • No uploads • No cloud audio processing", color = Color.Gray, fontSize = 11.sp)
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
