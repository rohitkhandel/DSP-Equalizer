package com.deepbass.player

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DeepBassScreen() }
    }
}

@Composable
fun DeepBassScreen() {
    val context = LocalContext.current
    val eqFrequencies = listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")

    var selectedSong by remember { mutableStateOf("No track selected") }
    var selectedAudioUri by remember { mutableStateOf<Uri?>(null) }
    var exportStatus by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(false) }
    var connectionReady by remember { mutableStateOf(false) }
    var bass by remember { mutableFloatStateOf(34f) }
    var punch by remember { mutableFloatStateOf(28f) }
    var vocal by remember { mutableFloatStateOf(26f) }
    var preamp by remember { mutableFloatStateOf(-5f) }
    var eqGains by remember {
        mutableStateOf(listOf(1.5f, 2.5f, 1.5f, 0f, -0.5f, 0f, 0.5f, 1f, 1f, 0.5f))
    }

    val sessionToken = remember(context) {
        SessionToken(context, ComponentName(context, DeepBassPlaybackService::class.java))
    }
    val controllerFuture = remember(sessionToken) {
        MediaController.Builder(context, sessionToken).buildAsync()
    }
    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(controllerFuture) {
        controllerFuture.addListener(
            {
                try {
                    mediaController = controllerFuture.get()
                    connectionReady = true
                } catch (_: Exception) {
                    connectionReady = false
                }
            },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            MediaController.releaseFuture(controllerFuture)
            mediaController = null
        }
    }

    DisposableEffect(mediaController) {
        val controller = mediaController
        if (controller == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    selectedSong = mediaItem?.mediaMetadata?.title?.toString()
                        ?: selectedSong
                }
            }
            controller.addListener(listener)
            isPlaying = controller.isPlaying
            onDispose { controller.removeListener(listener) }
        }
    }

    LaunchedEffect(mediaController, bass, punch, vocal, preamp, eqGains) {
        if (mediaController != null) {
            DeepBassPlaybackService.updateDsp(
                bass, punch, vocal, preamp, eqGains.toFloatArray()
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                context.contentResolver.takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            selectedAudioUri = selectedUri
            exportStatus = ""
            selectedSong = selectedUri.lastPathSegment?.substringAfterLast('/')
                ?: "Selected audio file"
            mediaController?.let { controller ->
                controller.setMediaItem(MediaItem.fromUri(selectedUri))
                controller.prepare()
                controller.play()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { outputUri: Uri? ->
        val inputUri = selectedAudioUri
        if (outputUri != null && inputUri != null) {
            val settings = DspExportSettings(
                bass = bass,
                punch = punch,
                vocal = vocal,
                preampDb = preamp,
                equalizerGainsDb = eqGains.toFloatArray()
            )
            exportStatus = "Processing audio locally…"
            coroutineScope.launch {
                try {
                    val writtenBytes = OfflineWavExporter.export(
                        context = context,
                        inputUri = inputUri,
                        outputUri = outputUri,
                        settings = settings
                    )
                    exportStatus = "WAV saved • ${(writtenBytes / 1024L).coerceAtLeast(1L)} KB"
                } catch (error: Exception) {
                    exportStatus = "Export failed: ${error.message ?: "Unsupported audio file"}"
                }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFB7F36B),
        background = Color(0xFF090B0F),
        surface = Color(0xFF151922)
    )) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF090B0F)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFB7F36B))
                    Spacer(Modifier.width(10.dp))
                    Text("DEEPBASS", color = Color.White, fontSize = 22.sp)
                    Spacer(Modifier.weight(1f))
                    Text("DSP BETA 0.7", color = Color.Gray, fontSize = 12.sp)
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
                                enabled = connectionReady,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.FolderOpen, null)
                                Spacer(Modifier.width(7.dp))
                                Text(if (connectionReady) "Choose song" else "Connecting player…")
                            }
                            FilledTonalButton(
                                onClick = {
                                    mediaController?.let { controller ->
                                        if (controller.isPlaying) controller.pause()
                                        else if (controller.mediaItemCount > 0) controller.play()
                                    }
                                },
                                enabled = connectionReady && (mediaController?.mediaItemCount ?: 0) > 0,
                                modifier = Modifier.width(58.dp)
                            ) {
                                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause")
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        val baseName = selectedSong
                            .substringAfterLast('/')
                            .substringBeforeLast('.', selectedSong)
                            .take(60)
                            .ifBlank { "DeepBass" }
                        exportLauncher.launch("${baseName}_DeepBass.wav")
                    },
                    enabled = selectedAudioUri != null && connectionReady,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export processed WAV")
                }
                if (exportStatus.isNotEmpty()) {
                    Text(exportStatus, color = Color(0xFFB7F36B), fontSize = 11.sp)
                }

                Text("BASS & TONE", color = Color.White, fontSize = 16.sp)
                ControlSlider("Sub-bass depth", bass, { bass = it }, "Low-frequency weight and rumble")
                ControlSlider("Punch", punch, { punch = it }, "Tight kick and upper-bass impact")
                ControlSlider("Vocal clarity", vocal, { vocal = it }, "Gentle vocal-presence lift")
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Preamp / Headroom", color = Color.White)
                        Spacer(Modifier.weight(1f))
                        Text("${preamp.toInt()} dB", color = Color(0xFFB7F36B))
                    }
                    Slider(value = preamp, onValueChange = { preamp = it }, valueRange = -12f..0f, steps = 23)
                    Text("Lower values make extra space for bass and EQ boosts", color = Color.Gray, fontSize = 10.sp)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("10-BAND EQUALIZER", color = Color.White, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { eqGains = List(10) { 0f } }) { Text("Reset") }
                }
                for (index in 0 until 10 step 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EqBandControl(
                            eqFrequencies[index],
                            eqGains[index],
                            { value -> eqGains = eqGains.toMutableList().also { it[index] = value } },
                            Modifier.weight(1f)
                        )
                        EqBandControl(
                            eqFrequencies[index + 1],
                            eqGains[index + 1],
                            { value -> eqGains = eqGains.toMutableList().also { it[index + 1] = value } },
                            Modifier.weight(1f)
                        )
                    }
                }

                Surface(color = Color(0xFF203020), shape = RoundedCornerShape(50)) {
                    Text(
                        if (connectionReady) "Background player connected • DSP active"
                        else "Connecting to local playback service…",
                        color = Color(0xFFB7F36B),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        fontSize = 11.sp
                    )
                }
                Text("Local playback • System media notification • No audio uploads", color = Color.Gray, fontSize = 11.sp)
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

@Composable
private fun EqBandControl(
    frequency: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(frequency, color = Color.LightGray, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            val displayed = (value * 2f).toInt() / 2f
            Text(
                if (displayed > 0f) "+$displayed dB" else "$displayed dB",
                color = Color(0xFFB7F36B),
                fontSize = 11.sp
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = -12f..12f, steps = 47)
    }
}
