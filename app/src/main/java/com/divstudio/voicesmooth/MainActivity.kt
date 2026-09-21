package com.divstudio.voicesmooth

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
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
import java.io.*

class MainActivity : ComponentActivity() {
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var recording = false
    private var rawFile: File? = null
    private var smoothFile: File? = null
    private var player: MediaPlayer? = null
    private var statusText = "Ready to record"

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginRecording()
        else statusText = "Microphone permission is required"
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { App() }
    }

    private fun beginRecording() {
        val sampleRate = 48000
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) {
            statusText = "This device cannot start audio recording"
            return
        }
        rawFile = File(cacheDir, "raw_" + System.currentTimeMillis() + ".wav")
        val bufferSize = maxOf(min * 2, 4096)
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            recorder!!.startRecording()
            recording = true
            statusText = "Recording…"
            recordingThread = Thread {
                try {
                    DataOutputStream(BufferedOutputStream(FileOutputStream(rawFile!!))).use { out ->
                        writeWavHeader(out, 0, sampleRate, 1, 16)
                        val pcm = ShortArray(bufferSize / 2)
                        var totalBytes = 0
                        while (recording) {
                            val n = recorder?.read(pcm, 0, pcm.size) ?: 0
                            if (n > 0) {
                                for (i in 0 until n) out.writeShort(java.lang.Short.reverseBytes(pcm[i]))
                                totalBytes += n * 2
                            }
                        }
                        out.flush()
                        patchWavHeader(rawFile!!, totalBytes, sampleRate, 1, 16)
                    }
                } catch (e: Exception) {
                    statusText = "Recording failed"
                }
            }.also { it.start() }
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            statusText = "Could not start microphone"
        }
    }

    private fun endRecording() {
        recording = false
        recorder?.runCatching { stop(); release() }
        recorder = null
        recordingThread?.join(1000)
        recordingThread = null
    }

    private fun smoothRecording() {
        val src = rawFile ?: return
        val out = File(cacheDir, "smooth_" + System.currentTimeMillis() + ".wav")
        smoothFile = out
        statusText = "Cleaning background noise…"
        Thread {
            try {
                val audio = readWavPcm(src)
                val processed = spectralNoiseReduction(audio.samples)
                writeWav(out, processed, audio.sampleRate)
                statusText = "Real noise reduction + voice smoothing applied"
            } catch (e: Exception) {
                statusText = "Processing failed"
            }
        }.start()
    }

    private data class WavAudio(val samples: ShortArray, val sampleRate: Int)

    private fun readWavPcm(file: File): WavAudio {
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            val riff = ByteArray(4).also { input.readFully(it) }
            require(String(riff, Charsets.US_ASCII) == "RIFF") { "Invalid WAV file" }
            input.skipBytes(4)
            val wave = ByteArray(4).also { input.readFully(it) }
            require(String(wave, Charsets.US_ASCII) == "WAVE") { "Invalid WAV format" }
            var sampleRate = 48000
            var channels = 1
            var bits = 16
            var dataSize = -1
            while (dataSize < 0) {
                val id = ByteArray(4)
                input.readFully(id)
                val size = Integer.reverseBytes(input.readInt())
                when (String(id, Charsets.US_ASCII)) {
                    "fmt " -> {
                        val format = Short.reverseBytes(input.readShort()).toInt()
                        channels = Short.reverseBytes(input.readShort()).toInt()
                        sampleRate = Integer.reverseBytes(input.readInt())
                        input.skipBytes(6)
                        bits = Short.reverseBytes(input.readShort()).toInt()
                        if (size > 16) input.skipBytes(size - 16)
                        require(format == 1 && channels == 1 && bits == 16) { "Only 16-bit mono PCM WAV is supported" }
                    }
                    "data" -> dataSize = size
                    else -> input.skipBytes(size)
                }
            }
            val samples = ShortArray(dataSize / 2)
            for (i in samples.indices) samples[i] = Short.reverseBytes(input.readShort())
            return WavAudio(samples, sampleRate)
        }
    }

    private fun spectralNoiseReduction(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        val n = 1024
        val hop = 512
        val frames = maxOf(1, (input.size + hop - 1) / hop)
        val noiseFrames = minOf(10, frames)
        val noiseMag = DoubleArray(n / 2 + 1)

        fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val angle = 2.0 * Math.PI / len * if (inverse) 1 else -1
                val wrLen = kotlin.math.cos(angle)
                val wiLen = kotlin.math.sin(angle)
                var base = 0
                while (base < n) {
                    var wr = 1.0
                    var wi = 0.0
                    for (j2 in 0 until len / 2) {
                        val a = base + j2
                        val b = a + len / 2
                        val vr = re[b] * wr - im[b] * wi
                        val vi = re[b] * wi + im[b] * wr
                        val ur = re[a]
                        val ui = im[a]
                        re[a] = ur + vr
                        im[a] = ui + vi
                        re[b] = ur - vr
                        im[b] = ui - vi
                        val nextWr = wr * wrLen - wi * wiLen
                        wi = wr * wiLen + wi * wrLen
                        wr = nextWr
                    }
                    base += len
                }
                len = len shl 1
            }
            if (inverse) for (i in 0 until n) {
                re[i] /= n
                im[i] /= n
            }
        }

        fun frame(start: Int): Pair<DoubleArray, DoubleArray> {
            val re = DoubleArray(n)
            val im = DoubleArray(n)
            for (i in 0 until n) {
                val idx = start + i
                val sample = if (idx in input.indices) input[idx].toDouble() / 32768.0 else 0.0
                val window = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (n - 1))
                re[i] = sample * window
            }
            fft(re, im, false)
            return re to im
        }

        for (f in 0 until noiseFrames) {
            val (re, im) = frame(f * hop)
            for (k in noiseMag.indices) noiseMag[k] += kotlin.math.hypot(re[k], im[k])
        }
        for (k in noiseMag.indices) noiseMag[k] /= noiseFrames

        val output = DoubleArray(input.size)
        val weight = DoubleArray(input.size)

        for (f in 0 until frames) {
            val (re, im) = frame(f * hop)
            for (k in 0..n / 2) {
                val mag = kotlin.math.hypot(re[k], im[k])
                val phase = kotlin.math.atan2(im[k], re[k])
                val cleaned = maxOf(mag - noiseMag[k] * 1.15, mag * 0.12)
                re[k] = cleaned * kotlin.math.cos(phase)
                im[k] = cleaned * kotlin.math.sin(phase)
                if (k > 0 && k < n / 2) {
                    re[n - k] = re[k]
                    im[n - k] = -im[k]
                }
            }
            fft(re, im, true)
            val start = f * hop
            for (i in 0 until n) {
                val idx = start + i
                if (idx >= output.size) break
                val window = 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (n - 1))
                output[idx] += re[i] * window
                weight[idx] += window * window
            }
        }

        var peak = 0.0
        for (i in output.indices) {
            if (weight[i] > 1e-9) output[i] /= weight[i]
            peak = maxOf(peak, kotlin.math.abs(output[i]))
        }
        val gain = if (peak > 0.92) 0.92 / peak else 1.0
        return ShortArray(output.size) { i ->
            val x = output[i] * gain
            val compressed = kotlin.math.tanh(x * 1.15) / kotlin.math.tanh(1.15)
            (compressed.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
        }
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            writeWavHeader(out, samples.size * 2, sampleRate, 1, 16)
            for (sample in samples) out.writeShort(java.lang.Short.reverseBytes(sample))
        }
    }

    private fun writeWavHeader(out: DataOutputStream, dataSize: Int, sampleRate: Int, channels: Int, bits: Int) {
        out.writeBytes("RIFF")
        out.writeInt(Integer.reverseBytes(36 + dataSize))
        out.writeBytes("WAVE")
        out.writeBytes("fmt ")
        out.writeInt(Integer.reverseBytes(16))
        out.writeShort(java.lang.Short.reverseBytes(1.toShort()))
        out.writeShort(java.lang.Short.reverseBytes(channels.toShort()))
        out.writeInt(Integer.reverseBytes(sampleRate))
        out.writeInt(Integer.reverseBytes(sampleRate * channels * bits / 8))
        out.writeShort(java.lang.Short.reverseBytes((channels * bits / 8).toShort()))
        out.writeShort(java.lang.Short.reverseBytes(bits.toShort()))
        out.writeBytes("data")
        out.writeInt(Integer.reverseBytes(dataSize))
    }

    private fun patchWavHeader(file: File, dataSize: Int, sampleRate: Int, channels: Int, bits: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.writeInt(Integer.reverseBytes(36 + dataSize))
            raf.seek(24)
            raf.writeInt(Integer.reverseBytes(sampleRate))
            raf.writeInt(Integer.reverseBytes(sampleRate * channels * bits / 8))
            raf.seek(34)
            raf.writeShort(java.lang.Short.reverseBytes(bits.toShort()))
            raf.seek(40)
            raf.writeInt(Integer.reverseBytes(dataSize))
        }
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
            put(MediaStore.Audio.Media.DISPLAY_NAME, "DIV_Voice_Smooth_" + System.currentTimeMillis() + ".wav")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DIV Voice Smooth")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            contentResolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open output stream")
            contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }, null, null)
            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            false
        }
    }

    private fun share(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "com.divstudio.voicesmooth.fileprovider", file
        )
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share voice"))
    }

    @Composable
    private fun App() {
        var isRecording by remember { mutableStateOf(false) }
        var ready by remember { mutableStateOf(false) }
        var smoothed by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf(statusText) }

        LaunchedEffect(Unit) {
            while (true) {
                status = statusText
                isRecording = recording
                kotlinx.coroutines.delay(200)
            }
        }

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
                        if (!isRecording) {
                            ready = false
                            smoothed = false
                            permission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            endRecording()
                            ready = rawFile?.exists() == true && rawFile!!.length() > 44
                            statusText = if (ready) "Recording ready for cleanup" else "No usable recording"
                        }
                    }) { Text(if (isRecording) "STOP RECORDING" else "RECORD VOICE") }
                    Spacer(Modifier.height(10.dp))
                    Button(enabled = ready && !isRecording, onClick = { smoothed = true; smoothRecording() }) {
                        Text("NOISE REDUCE + SMOOTH")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(enabled = ready, onClick = {
                        rawFile?.let { play(it); statusText = "Playing original preview" }
                    }) { Text("PREVIEW ORIGINAL") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(enabled = smoothed, onClick = {
                        if (smoothFile?.exists() == true) smoothFile?.let { play(it); statusText = "Playing smooth preview" }
                        else statusText = "Still processing — please wait"
                    }) { Text("PREVIEW SMOOTH") }
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Button(enabled = smoothed, onClick = {
                            if (smoothFile?.exists() == true) smoothFile?.let { statusText = if (save(it)) "Saved to Music/DIV Voice Smooth" else "Save failed" }
                            else statusText = "Still processing — please wait"
                        }) { Text("SAVE") }
                        Spacer(Modifier.width(10.dp))
                        Button(enabled = smoothed, onClick = {
                            if (smoothFile?.exists() == true) smoothFile?.let { share(it) }
                            else statusText = "Still processing — please wait"
                        }) { Text("SHARE") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        recording = false
        recorder?.runCatching { release() }
        recorder = null
        player?.release()
        player = null
        super.onDestroy()
    }
}