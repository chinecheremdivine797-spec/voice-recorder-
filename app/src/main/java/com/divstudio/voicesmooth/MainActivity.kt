package com.divstudio.voicesmooth

import android.Manifest
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
 private var recorder: MediaRecorder? = null
 private var output: File? = null
 private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startRecording() }
 override fun onCreate(b: Bundle?) { super.onCreate(b); setContent { VoiceSmoothScreen() } }
 private fun startRecording() { output = File(cacheDir, "voice_" + System.currentTimeMillis() + ".m4a"); recorder = MediaRecorder(this).apply { setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(192000); setAudioSamplingRate(48000); setOutputFile(output!!.absolutePath); prepare(); start() } }
 private fun stopRecording() { recorder?.runCatching { stop(); release() }; recorder = null }
 @Composable fun VoiceSmoothScreen() { var recording by remember { mutableStateOf(false) }; var status by remember { mutableStateOf("Ready to record") }; MaterialTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("DIV Voice Smooth", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(8.dp)); Text("Record • Clean • Smooth • Share"); Spacer(Modifier.height(40.dp)); Text(status); Spacer(Modifier.height(20.dp)); Button(onClick = { if (!recording) { permission.launch(Manifest.permission.RECORD_AUDIO); recording = true; status = "Recording…" } else { stopRecording(); recording = false; status = "Recording saved." } }) { Text(if (recording) "STOP RECORDING" else "RECORD VOICE") }; Spacer(Modifier.height(16.dp)); OutlinedButton(onClick = { status = "Enhancement pipeline will process the recording." }) { Text("SMOOTH VOICE") } } } } }
}
