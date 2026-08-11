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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
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

class SynthEngine {
    private val sampleRate = 44100
    private var isRunning = true
    private val activeNotes = ConcurrentHashMap<Float, Float>() // Freq -> Phase
    var volume = 0.5f
    var waveformType = 0 // 0=Sine, 1=Square, 2=Triangle
    
    // Buffer for Visualizer
    var visualizerBuffer = FloatArray(512)

    fun noteOn(freq: Float) { activeNotes[freq] = 0f }
    fun noteOff(freq: Float) { activeNotes.remove(freq) }

    fun start() {
        thread {
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack.play()
            val buffer = ShortArray(512)
            
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
                        sample = (sample / activeCount) * volume
                    }
                    
                    buffer[i] = (sample * Short.MAX_VALUE).toInt().toShort()
                    visualizerBuffer[i] = sample.toFloat()
                }
                audioTrack.write(buffer, 0, buffer.size)
            }
        }
    }
}

@Composable
fun SynthAppUI(engine: SynthEngine) {
    val frequencies = listOf(261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f, 523.25f)
    var currentWave by remember { mutableIntStateOf(0) }
    var vol by remember { mutableFloatStateOf(0.5f) }
    
    // Trigger recomposition for visualizer
    var trigger by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while(true) { delay(30); trigger = System.currentTimeMillis() } }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Text("MicroScale Synth", color = Color.Cyan, style = MaterialTheme.typography.headlineMedium)
        
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            listOf("Sine", "Square", "Tri").forEachIndexed { i, name ->
                FilterChip(selected = currentWave == i, onClick = { currentWave = i; engine.waveformType = i }, label = { Text(name) })
            }
        }
        
        Slider(value = vol, onValueChange = { vol = it; engine.volume = it })

        // Visualizer
        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp).padding(8.dp).background(Color.Black)) {
            val path = Path()
            val centerY = size.height / 2
            val step = size.width / engine.visualizerBuffer.size
            
            engine.visualizerBuffer.forEachIndexed { i, sample ->
                val x = i * step
                val y = centerY + (sample * centerY)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color.Green, style = Stroke(width = 4f))
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.height(180.dp), Arrangement.spacedBy(4.dp)) {
            frequencies.forEach { freq ->
                Card(
                    modifier = Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { engine.noteOn(freq); tryAwaitRelease(); engine.noteOff(freq) }
                        )
                    }
                ) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text(freq.toInt().toString()) } }
            }
        }
    }
}
