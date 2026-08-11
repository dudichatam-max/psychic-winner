package com.microtonal.synth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sin

enum class Waveform { SINE, SQUARE, TRIANGLE }

class MainActivity : ComponentActivity() {
    private val defaultFrequencies = mutableStateListOf(261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f, 523.25f)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SynthAppUI(defaultFrequencies) }
    }
}

class SoundGenerator {
    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    private val sampleRate = 44100
    
    val recordingBuffer = ByteArrayOutputStream()
    var isRecording = false

    fun startTone(freq: Float, wave: Waveform, volume: Float) {
        stopTone()
        isPlaying = true
        
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        thread {
            var sampleIndex = 0
            val fadeSamples = 441 // 10ms fade
            
            while (isPlaying) {
                val buffer = ShortArray(1024)
                for (i in buffer.indices) {
                    val t = sampleIndex.toDouble() / sampleRate
                    val angle = 2.0 * Math.PI * freq * t
                    var raw = when(wave) {
                        Waveform.SINE -> sin(angle)
                        Waveform.SQUARE -> if (sin(angle) >= 0) 0.5 else -0.5
                        Waveform.TRIANGLE -> (2.0 / Math.PI) * Math.asin(sin(angle))
                    }
                    
                    // Fade In למניעת קליק בהתחלה
                    if (sampleIndex < fadeSamples) raw *= (sampleIndex.toDouble() / fadeSamples)
                    
                    val valShort = (raw * Short.MAX_VALUE * volume).toInt().coerceIn(-32767, 32767).toShort()
                    buffer[i] = valShort
                    
                    if (isRecording) {
                        val bytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(valShort).array()
                        recordingBuffer.write(bytes)
                    }
                    sampleIndex++
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }
        }
    }

    fun stopTone() {
        isPlaying = false
        try { 
            audioTrack?.flush()
            audioTrack?.stop()
            audioTrack?.release() 
        } catch (e: Exception) {}
        audioTrack = null
    }
}

fun writeWav(out: OutputStream, pcmData: ByteArray, sampleRate: Int) {
    val totalDataLen = pcmData.size + 36
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(totalDataLen)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)
    header.putShort(1)
    header.putShort(1)
    header.putInt(sampleRate)
    header.putInt(sampleRate * 2)
    header.putShort(2)
    header.putShort(16)
    header.put("data".toByteArray())
    header.putInt(pcmData.size)
    out.write(header.array())
    out.write(pcmData)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(frequencies: MutableList<Float>) {
    val context = LocalContext.current
    var selectedWaveform by remember { mutableStateOf(Waveform.SINE) }
    var activeNoteIndex by remember { mutableStateOf<Int?>(null) }
    val soundGen = remember { SoundGenerator() }
    var volume by remember { mutableFloatStateOf(0.5f) }
    var isRecording by remember { mutableStateOf(false) }
    
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri: Uri? ->
        uri?.let {
            val pcmData = soundGen.recordingBuffer.toByteArray()
            context.contentResolver.openOutputStream(it)?.use { out -> writeWav(out, pcmData, 44100) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Text("MicroScale Synth", color = Color(0xFF00E5FF), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        
        Row {
            Waveform.entries.forEach { wave ->
                FilterChip(selected = selectedWaveform == wave, onClick = { selectedWaveform = wave }, label = { Text(wave.name) })
            }
        }

        Slider(value = volume, onValueChange = { volume = it })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { 
                isRecording = !isRecording
                soundGen.isRecording = isRecording 
            }) { Text(if (isRecording) "Stop Recording" else "Start Recording") }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Button(onClick = { saveFileLauncher.launch("my_song.wav") }) { Text("Export WAV") }
        }

        LazyRow {
            itemsIndexed(frequencies) { index, freq ->
                OutlinedTextField(
                    value = freq.toString(),
                    onValueChange = { newValue -> newValue.toFloatOrNull()?.let { frequencies[index] = it } },
                    modifier = Modifier.width(70.dp)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(modifier = Modifier.height(150.dp)) {
            frequencies.forEachIndexed { index, freq ->
                Card(modifier = Modifier.weight(1f).pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            activeNoteIndex = index
                            soundGen.startTone(freq, selectedWaveform, volume)
                            tryAwaitRelease()
                            soundGen.stopTone()
                            activeNoteIndex = null
                        }
                    )
                }) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(freq.toInt().toString()) } }
            }
        }
    }
}
