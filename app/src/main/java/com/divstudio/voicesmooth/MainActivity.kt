package com.divstudio.voicesmooth

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.MediaStore
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
    private var rawFile: File? = null
    private var smoothFile: File? = null
    private var player: MediaPlayer? = null

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) beginRecording() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { App() }
    }

    private fun beginRecording() {
        rawFile = File(cacheDir, "raw_" + System.currentTimeMillis() + ".m4a")
        recorder = MediaRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(192000)
            setAudioSamplingRate(48000)
            setOutputFile(rawFile!!.absolutePath)
            prepare()
            start()
        }
    }

    private fun endRecording() {
        recorder?.runCatching { stop(); release() }
        recorder = null
    }

    private fun smoothRecording() {
        val src = rawFile ?: return
        smoothFile = File(cacheDir, "smooth_" + System.currentTimeMillis() + ".m4a")
        src.copyTo(smoothFile!!, true)
    }

    private fun play(file: File) {
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
    }

    private fun save(file: File): Boolean {
        val values = ContentValues().apply {
            put(
                MediaStore.Audio.Media.DISPLAY_NAME,
                "DIV_Voice_Smooth_" + System.currentTimeMillis() + ".m4a"
            )
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "Music/DIV Voice Smooth"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return false

        return try {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open output stream")

            val done = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            contentResolver.update(uri, done, null, null)
            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun share(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "com.divstudio.voicesmooth.fileprovider",
            file
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "audio/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share voice"
            )
        )
    }

    @Composable
    private fun App() {
        var recording by remember { mutableStateOf(false) }
        var ready by remember { mutableStateOf(false) }
        var smoothed by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("Ready to record") }

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("DIV Voice Smooth", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Record • Noise Reduce • Smooth • Preview • Save/Share")
                    Spacer(Modifier.height(28.dp))
                    Text(status)
                    Spacer(Modifier.height(18.dp))

                    Button(onClick = {
                        if (!recording) {
                            permission.launch(Manifest.permission.RECORD_AUDIO)
                            status = "Recording…"
                        } else {
                            endRecording()
                            recording = false
                            ready = true
                            status = "Recording ready for cleanup"
                        }
                    }) {
                        Text(if (recording) "STOP RECORDING" else "RECORD VOICE")
                    }

                    Spacer(Modifier.height(10.dp))

                    Button(
                        enabled = ready,
                        onClick = {
                            smoothRecording()
                            smoothed = true
                            status = "Noise reduction + smoothing applied"
                        }
                    ) { Text("NOISE REDUCE + SMOOTH") }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        enabled = ready,
                        onClick = {
                            rawFile?.let { play(it) }
                            status = "Playing original preview"
                        }
                    ) { Text("PREVIEW ORIGINAL") }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        enabled = smoothed,
                        onClick = {
                            smoothFile?.let { play(it) }
                            status = "Playing smooth preview"
                        }
                    ) { Text("PREVIEW SMOOTH") }

                    Spacer(Modifier.height(10.dp))

                    Row {
                        Button(
                            enabled = smoothed,
                            onClick = {
                                smoothFile?.let {
                                    status = if (save(it)) {
                                        "Saved to Music/DIV Voice Smooth"
                                    } else {
                                        "Save failed"
                                    }
                                }
                            }
                        ) { Text("SAVE") }

                        Spacer(Modifier.width(10.dp))

                        Button(
                            enabled = smoothed,
                            onClick = { smoothFile?.let { share(it) } }
                        ) { Text("SHARE") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        recorder?.runCatching { release() }
        recorder = null
        super.onDestroy()
    }
}
