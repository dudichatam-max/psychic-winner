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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sin

enum class Waveform { SINE, SQUARE, TRIANGLE }

class MainActivity : ComponentActivity() {
    private val defaultFrequencies = mutableStateListOf(
        261.63f, 293.66f, 329.63f, 349.23f,
        392.00f, 440.00f, 493.88f, 523.25f
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SynthAppUI(frequencies = defaultFrequencies)
        }
    }
}

class SoundGenerator {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private val sampleRate = 44100

    // הוספנו פרמטר volume למנוע הסאונד
    fun startTone(freq: Float, wave: Waveform, volume: Float) {
        stopTone()
        isPlaying = true

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        thread {
            val buffer = ShortArray(1024)
            var sampleIndex = 0
            while (isPlaying) {
                for (i in buffer.indices) {
                    val t = sampleIndex.toDouble() / sampleRate
                    val angle = 2.0 * Math.PI * freq * t
                    val rawSample = when (wave) {
                        Waveform.SINE -> sin(angle)
                        Waveform.SQUARE -> if (sin(angle) >= 0) 0.6 else -0.6
                        Waveform.TRIANGLE -> (2.0 / Math.PI) * Math.asin(sin(angle))
                    }
                    // מכפילים ב-volume שנבחר
                    buffer[i] = (rawSample * Short.MAX_VALUE * volume).toInt().toShort()
                    sampleIndex++
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }
        }
    }

    fun stopTone() {
        isPlaying = false
        try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(frequencies: MutableList<Float>) {
    var selectedWaveform by remember { mutableStateOf(Waveform.SINE) }
    var activeNoteIndex by remember { mutableStateOf<Int?>(null) }
    val soundGen = remember { SoundGenerator() }

    // משתני State חדשים
    var volume by remember { mutableFloatStateOf(0.5f) }
    var reverb by remember { mutableFloatStateOf(0.0f) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableLongStateOf(0L) }

    // מנגנון שמירת קובץ (פותח חלון בחירת תיקייה)
    val saveFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri: Uri? ->
        // כאן תוסיף את הלוגיקה לכתיבת קובץ ה-WAV בפועל ל-URI שנבחר
    }

    // לוגיקת הטיימר
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startTime = System.currentTimeMillis()
            while (isRecording) {
                recordingTime = (System.currentTimeMillis() - startTime) / 1000
                delay(1000)
            }
        } else {
            recordingTime = 0
        }
    }

    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")
    val darkBg = Color(0xFF121212)
    val cyanAccent = Color(0xFF00E5FF)

    Column(modifier = Modifier.fillMaxSize().background(darkBg).padding(16.dp)) {
        Text("MicroScale Synth", color = cyanAccent, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        // בורר גלים
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Waveform.entries.forEach { wave ->
                FilterChip(selected = selectedWaveform == wave, onClick = { selectedWaveform = wave }, label = { Text(wave.name) })
            }
        }

        // --- הוספנו סליידרים ---
        Text("Volume: ${(volume * 100).toInt()}%", color = Color.White)
        Slider(value = volume, onValueChange = { volume = it })
        
        Text("Reverb: ${(reverb * 100).toInt()}%", color = Color.White)
        Slider(value = reverb, onValueChange = { reverb = it })

        // --- כפתורי הקלטה וייצוא ---
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { isRecording = !isRecording }) {
                Text(if (isRecording) "Stop Rec" else "Start Rec")
            }
            Spacer(modifier = Modifier.width(16.dp))
            if (isRecording) Text("Time: $recordingTime s", color = Color.Red)
        }

        Button(onClick = { saveFileLauncher.launch("my_recording.wav") }) {
            Text("Export to File")
        }

        Spacer(modifier = Modifier.weight(1f))

        // מקלדת
        Row(modifier = Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            frequencies.forEachIndexed { index, freq ->
                Card(
                    modifier = Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                activeNoteIndex = index
                                soundGen.startTone(freq, selectedWaveform, volume) // מעבירים את ה-volume
                                tryAwaitRelease()
                                soundGen.stopTone()
                                activeNoteIndex = null
                            }
                        )
                    }
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(noteNames[index]) }
                }
            }
        }
    }
}
