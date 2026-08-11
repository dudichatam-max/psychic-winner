package com.microtonal.synth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.io.File

enum class Waveform { SINE, SQUARE, TRIANGLE, PIANO }

class MainActivity : ComponentActivity() {

    private val defaultFrequencies = mutableStateListOf(
        261.63f, 293.66f, 329.63f, 349.23f,
        392.00f, 440.00f, 493.88f, 523.25f
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SynthAppUI(frequencies = defaultFrequencies, cacheDir = cacheDir)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(frequencies: MutableList<Float>, cacheDir: File) {
    var selectedWaveform by remember { mutableStateOf(Waveform.SINE) }
    var reverbAmount by remember { mutableFloatStateOf(0.2f) }
    var isRecording by remember { mutableStateOf(false) }
    var activeNoteIndex by remember { mutableStateOf<Int?>(null) }
    var exportedPath by remember { mutableStateOf("") }

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
        Text(
            text = "MicroScale Synth",
            color = cyanAccent,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Waveform.values().forEach { wave ->
                FilterChip(
                    selected = selectedWaveform == wave,
                    onClick = { selectedWaveform = wave },
                    label = { Text(wave.name, fontSize = 12.sp) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Reverb / Echo Amount", color = Color.White, fontSize = 14.sp)
        Slider(
            value = reverbAmount,
            onValueChange = { reverbAmount = it },
            valueRange = 0f..0.8f,
            modifier = Modifier.fillMaxWidth(0.9f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("הגדרת תדרים (Hz)", color = goldAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            itemsIndexed(frequencies) { index, freq ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(noteNames[index], color = Color.White, fontSize = 12.sp)
                    OutlinedTextField(
                        value = freq.toString(),
                        onValueChange = { newValue ->
                            newValue.toFloatOrNull()?.let { frequencies[index] = it }
                        },
                        modifier = Modifier.width(65.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 10.sp, color = Color.White)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                isRecording = !isRecording
                if (!isRecording) {
                    exportedPath = "${cacheDir.absolutePath}/recording.wav"
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else cyanAccent
            )
        ) {
            Text(if (isRecording) "עצור הקלטה" else "הקלט ל-WAV (BandLab)", color = darkBg, fontWeight = FontWeight.Bold)
        }

        if (exportedPath.isNotEmpty()) {
            Text("קובץ נשמר: $exportedPath", color = goldAccent, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

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
                        containerColor = if (isPressed) cyanAccent else Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    activeNoteIndex = index
                                    tryAwaitRelease()
                                    activeNoteIndex = null
                                }
                            )
                        }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Text(
                            text = "${noteNames[index]}\n${freq.toInt()}Hz",
                            color = if (isPressed) darkBg else Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
