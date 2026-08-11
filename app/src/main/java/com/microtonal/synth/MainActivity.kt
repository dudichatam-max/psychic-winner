package com.microtonal.synth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = SynthEngine()
        engine.start()
        setContent { SynthAppUI(engine) }
    }
}

// מנוע סאונד רציף למניעת קליקים וקריסות + תמיכה בריבוי צלילים (Polyphony)
class SynthEngine {
    private val sampleRate = 44100
    @Volatile private var isRunning = true
    private val activeNotes = ConcurrentHashMap<Float, Float>() // Freq -> Phase
    var volume = 0.5f
    var waveformType = 0 // 0=Sine, 1=Square, 2=Triangle
    
    // בופר שמזין את הצג הויזואלי
    val visualizerBuffer = FloatArray(256)

    fun noteOn(freq: Float) { activeNotes[freq] = 0f }
    fun noteOff(freq: Float) { activeNotes.remove(freq) }

    fun start() {
        thread {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val audioTrack = AudioTrack.Builder()
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

            audioTrack.play()
            val buffer = ShortArray(256)
            
            while (isRunning) {
                for (i in buffer.indices) {
                    var sample = 0.0
                    val activeCount = activeNotes.size
                    
                    if (activeCount > 0) {
                        for ((freq, phase) in activeNotes) {
                            val newPhase = phase + (2.0 * Math.PI * freq / sampleRate)
                            activeNotes[freq] = (newPhase % (2.0 * Math.PI)).toFloat()
                            
                            val raw = when(waveformType) {
                                0 -> sin(newPhase)
                                1 -> if (sin(newPhase) >= 0) 0.5 else -0.5
                                else -> (2.0 / Math.PI) * Math.asin(sin(newPhase))
                            }
                            sample += raw
                        }
                        // סכימה ומניעת עיוות סאונד (Distortion) כשיש כמה צלילים במקביל
                        sample = (sample / activeCount) * volume
                    }
                    
                    val shortVal = (sample * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
                    buffer[i] = shortVal
                    visualizerBuffer[i] = sample.toFloat()
                }
                audioTrack.write(buffer, 0, buffer.size)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(engine: SynthEngine) {
    val frequencies = remember {
        mutableStateListOf(261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f, 523.25f)
    }
    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")
    
    var currentWave by remember { mutableIntStateOf(0) }
    var vol by remember { mutableFloatStateOf(0.5f) }
    
    // רענון הצג הויזואלי
    var renderTrigger by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while(true) {
            delay(30)
            renderTrigger = System.currentTimeMillis()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MicroScale Synth", color = Color(0xFF00E5FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        
        Spacer(Modifier.height(8.dp))

        // בורר גלים
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            listOf("Sine", "Square", "Triangle").forEachIndexed { i, name ->
                FilterChip(
                    selected = currentWave == i,
                    onClick = { currentWave = i; engine.waveformType = i },
                    label = { Text(name) }
                )
            }
        }
        
        // סליידר ווליום
        Text("Volume: ${(vol * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
        Slider(
            value = vol,
            onValueChange = { vol = it; engine.volume = it },
            modifier = Modifier.fillMaxWidth(0.9f)
        )

        Spacer(Modifier.height(8.dp))

        // צג ויזואליזר ירוק לגל הקול
        Text("Waveform Monitor", color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(Color.Black, shape = RoundedCornerShape(8.dp))
                .padding(4.dp)
        ) {
            // שימוש ב-renderTrigger כדי להכריח רענון ציור
            val dummy = renderTrigger
            val path = Path()
            val centerY = size.height / 2
            val step = size.width / engine.visualizerBuffer.size
            
            engine.visualizerBuffer.forEachIndexed { i, sample ->
                val x = i * step
                val y = centerY + (sample * centerY * 0.9f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(0xFF00FF66), style = Stroke(width = 3f))
        }

        Spacer(Modifier.height(8.dp))

        // תיבות כיוון תדרים
        Text("כיוון תדרים (Hz):", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            itemsIndexed(frequencies) { index, freq ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(noteNames[index], color = Color.White, fontSize = 10.sp)
                    OutlinedTextField(
                        value = freq.toString(),
                        onValueChange = { newValue ->
                            newValue.toFloatOrNull()?.let { frequencies[index] = it }
                        },
                        modifier = Modifier.width(66.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 10.sp, color = Color.White)
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // מקלדת מולטי-טאץ'
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            frequencies.forEachIndexed { index, freq ->
                var isPressed by remember { mutableStateOf(false) }
                Card(
                    shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPressed) Color(0xFF00E5FF) else Color(0xFFE0E0E0)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .pointerInput(freq) {
                            detectTapGestures(
                                onPress = {
                                    isPressed = true
                                    engine.noteOn(freq)
                                    tryAwaitRelease()
                                    engine.noteOff(freq)
                                    isPressed = false
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
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
