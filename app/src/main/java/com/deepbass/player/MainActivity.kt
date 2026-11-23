package com.deepbass.player

import androidx.activity.ComponentActivity
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DeepBassScreen() }
    }
}

@Composable
fun DeepBassScreen() {
    var selectedSong by remember { mutableStateOf("No track selected") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedSong = it.lastPathSegment ?: "Selected audio file" }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB7F36B), background = Color(0xFF090B0F), surface = Color(0xFF151922))) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF090B0F)) {
            Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFB7F36B))
                    Spacer(Modifier.width(10.dp))
                    Text("DEEPBASS", color = Color.White, fontSize = 22.sp)
                    Spacer(Modifier.weight(1f))
                    Text("0.1.0", color = Color.Gray, fontSize = 12.sp)
                }
                Text("Feel the depth.", color = Color.White, fontSize = 30.sp)
                Text("Smooth low-end. Clear vocals.", color = Color.LightGray)
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151922)), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("NOW PLAYING", color = Color(0xFFB7F36B), fontSize = 11.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(selectedSong, color = Color.White, fontSize = 16.sp)
                        Spacer(Modifier.height(18.dp))
                        Button(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Choose a song")
                        }
                    }
                }
                Text("BASS CONTROL", color = Color.White, fontSize = 16.sp)
                BassSlider("Deep Bass")
                BassSlider("Punch")
                BassSlider("Vocal Clarity")
                Spacer(Modifier.weight(1f))
                Text("Audio engine setup is next. This screen is the first build checkpoint.", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BassSlider(label: String) {
    var value by remember { mutableFloatStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, color = Color.White)
            Spacer(Modifier.weight(1f))
            Text("${value.toInt()}%", color = Color(0xFFB7F36B))
        }
        Slider(value = value, onValueChange = { value = it }, valueRange = 0f..100f)
    }
}
