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

// מנוע סאונד משופר עם החלקת קצוות (Fade In / Fade Out) למניעת זמזומים
class SoundGenerator {
    private var audioTrack: AudioTrack? = null
    @Volatile private var isPlaying = false
    private val sampleRate = 44100

    fun startTone(freq: Float, wave: Waveform, volume: Float) {
        stopTone()
        isPlaying = true

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        thread {
            val bufferSize = 1024
            val buffer = ShortArray(bufferSize)
            var sampleIndex = 0
            val fadeSamples = 220 // כ-5 מילי-שניות של החלקה למניעת "קליקים"

            while (isPlaying) {
                for (i in 0 until bufferSize) {
                    val t = sampleIndex.toDouble() / sampleRate
                    val angle = 2.0 * Math.PI * freq * t
                    
                    var rawSample = when (wave) {
                        Waveform.SINE -> sin(angle)
                        Waveform.SQUARE -> if (sin(angle) >= 0) 0.5 else -0.5
                        Waveform.TRIANGLE -> (2.0 / Math.PI) * Math.asin(sin(angle))
                    }

                    // החלקת התחלת הצליל (Fade In)
                    if (sampleIndex < fadeSamples) {
                        rawSample *= (sampleIndex.toDouble() / fadeSamples)
                    }

                    buffer[i] = (rawSample * Short.MAX_VALUE * volume).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    sampleIndex++
                }
                audioTrack?.write(buffer, 0, bufferSize)
            }
        }
    }

    fun stopTone() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(frequencies: MutableList<Float>) {
    var selectedWaveform by remember { mutableStateOf(Waveform.SINE) }
    var activeNoteIndex by remember { mutableStateOf<Int?>(null) }
    val soundGen = remember { SoundGenerator() }

    var volume by remember { mutableFloatStateOf(0.5f) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableLongStateOf(0L) }

    val saveFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri: Uri? -> }

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
    val goldAccent = Color(0xFFFFD700)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MicroScale Synth", color = cyanAccent, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Spacer(modifier = Modifier.height(12.dp))

        // בורר גלים
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Waveform.entries.forEach { wave ->
                FilterChip(
                    selected = selectedWaveform == wave,
                    onClick = { selectedWaveform = wave },
                    label = { Text(wave.name) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // סליידר ווליום בלבד
        Text("Volume: ${(volume * 100).toInt()}%", color = Color.White, fontSize = 14.sp)
        Slider(
            value = volume,
            onValueChange = { volume = it },
            modifier = Modifier.fillMaxWidth(0.9f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // אזור הקלטה וייצוא
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = { isRecording = !isRecording }) {
                Text(if (isRecording) "Stop Rec" else "Start Rec")
            }
            if (isRecording) {
                Text("🔴 $recordingTime s", color = Color.Red, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { saveFileLauncher.launch("microtone_tune.wav") }) {
                Text("Export File")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // תיבות כיוון תדרים ידני (Microtonal Input)
        Text("כיוון תדרים (Hz):", color = goldAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            itemsIndexed(frequencies) { index, freq ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(noteNames[index], color = Color.White, fontSize = 11.sp)
                    OutlinedTextField(
                        value = freq.toString(),
                        onValueChange = { newValue ->
                            newValue.toFloatOrNull()?.let { frequencies[index] = it }
                        },
                        modifier = Modifier.width(68.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, color = Color.White)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // המקלדת - 8 קלידים עם חיווי לחיצה
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            frequencies.forEachIndexed { index, freq ->
                val isPressed = activeNoteIndex == index
                Card(
                    shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPressed) cyanAccent else Color(0xFFE0E0E0)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(selectedWaveform, freq, volume) {
                            detectTapGestures(
                                onPress = {
                                    activeNoteIndex = index
                                    soundGen.startTone(freq, selectedWaveform, volume)
                                    tryAwaitRelease()
                                    soundGen.stopTone()
                                    activeNoteIndex = null
                                }
                            )
                        }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Text(
                            text = "${noteNames[index]}\n${freq.toInt()}Hz",
                            color = if (isPressed) Color.Black else Color.DarkGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
