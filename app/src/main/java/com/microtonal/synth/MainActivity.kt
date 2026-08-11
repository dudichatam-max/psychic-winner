package com.microtonal.synth

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = SynthEngine(this)
        engine.start()
        setContent { SynthAppUI(engine) }
    }
}

class NoteState(var phase: Float) {
    var isReleasing = false
    var releaseVolume = 1.0f
}

class SynthEngine(private val context: Context) {
    private val sampleRate = 44100
    @Volatile private var isRunning = true
    private val activeNotes = ConcurrentHashMap<Float, NoteState>()
    
    var volume = 0.5f
    var waveformType = 0 // 0=Sine, 1=Square, 2=Triangle
    var releaseTimeVal = 50 // ערך ברירת מחדל בין 1 ל-100

    val visualizerBuffer = FloatArray(256)

    // משתני הקלטה
    @Volatile var isRecording = false
        private set
    private var recordedAudioStream: ByteArrayOutputStream? = null

    fun noteOn(freq: Float) {
        activeNotes[freq] = NoteState(0f)
    }

    fun noteOff(freq: Float) {
        activeNotes[freq]?.isReleasing = true
    }

    fun startRecording() {
        recordedAudioStream = ByteArrayOutputStream()
        isRecording = true
    }

    fun stopAndSaveRecording() {
        if (!isRecording) return
        isRecording = false

        thread {
            val audioBytes = recordedAudioStream?.toByteArray() ?: return@thread
            saveWavFile(audioBytes)
        }
    }

    private fun saveWavFile(pcmData: ByteArray) {
        val fileName = "Synth_Rec_${System.currentTimeMillis()}.wav"
        val header = createWavHeader(pcmData.size, sampleRate, 1, 16)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/MicroSynth")
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { os ->
                        os.write(header)
                        os.write(pcmData)
                    }
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { os ->
                    os.write(header)
                    os.write(pcmData)
                }
            }
            
            thread {
                context.getMainExecutor().execute {
                    Toast.makeText(context, "ההקלטה שנשמרה: $fileName", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createWavHeader(pcmDataLen: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val totalDataLen = pcmDataLen + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteArray(44)
        
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1.toShort())
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * bitsPerSample / 8).toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray())
        buffer.putInt(pcmDataLen)
        
        return header
    }

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
            val byteBuffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)

            while (isRunning) {
                byteBuffer.clear()
                
                // חישוב מקדם דעיכה מעריכי לפי ה-Release שנבחר בסליידר (1-100)
                // ערך 1 = דעיכה מהירה מאד, ערך 100 = דעיכה ארוכה ורכה מאוד
                val releaseFactor = 1.0f - (0.0001f + (101 - releaseTimeVal) * 0.0002f)

                for (i in buffer.indices) {
                    var sample = 0.0
                    val iterator = activeNotes.entries.iterator()
                    var activeCount = activeNotes.size

                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val freq = entry.key
                        val state = entry.value

                        state.phase = (state.phase + (2.0 * Math.PI * freq / sampleRate) % (2.0 * Math.PI)).toFloat()

                        // דעיכה מעריכית הרמונית
                        if (state.isReleasing) {
                            state.releaseVolume *= releaseFactor
                            if (state.releaseVolume <= 0.001f) {
                                iterator.remove()
                                activeCount--
                                continue
                            }
                        }

                        val raw = when (waveformType) {
                            0 -> sin(state.phase.toDouble())
                            1 -> if (sin(state.phase.toDouble()) >= 0) 0.5 else -0.5
                            else -> (2.0 / Math.PI) * Math.asin(sin(state.phase.toDouble()))
                        }

                        sample += raw * state.releaseVolume
                    }

                    if (activeCount > 0) {
                        sample = (sample / activeCount) * volume
                    }

                    val shortVal = (sample * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
                    buffer[i] = shortVal
                    visualizerBuffer[i] = sample.toFloat()
                    byteBuffer.putShort(shortVal)
                }

                if (isRecording) {
                    recordedAudioStream?.write(byteBuffer.array())
                }

                audioTrack.write(buffer, 0, buffer.size)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(engine: SynthEngine) {
    val defaultFrequencies = remember {
        listOf(261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f, 523.25f)
    }
    val frequencies = remember {
        mutableStateListOf(261.63f, 293.66f, 329.63f, 349.23f, 392.00f, 440.00f, 493.88f, 523.25f)
    }
    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")

    var currentWave by remember { mutableIntStateOf(0) }
    var vol by remember { mutableFloatStateOf(0.5f) }
    var releaseVal by remember { mutableFloatStateOf(50f) } // ערך הסליידר 1-100
    var isRec by remember { mutableStateOf(false) }

    var renderTrigger by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MicroScale Synth", color = Color(0xFF00E5FF), fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Button(
                onClick = {
                    if (isRec) {
                        engine.stopAndSaveRecording()
                        isRec = false
                    } else {
                        engine.startRecording()
                        isRec = true
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRec) Color(0xFFFF1744) else Color(0xFF333333)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (isRec) Color.White else Color.Red, shape = CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isRec) "שמור הקלטה" else "הקלט", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(4.dp))

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

        // סליידר עוצמה (Volume)
        Text("Volume: ${(vol * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
        Slider(
            value = vol,
            onValueChange = { vol = it; engine.volume = it },
            modifier = Modifier.fillMaxWidth(0.9f)
        )

        // סליידר דעיכה (Release: 1 - 100)
        Text("Release (Fade-out): ${releaseVal.toInt()}", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Slider(
            value = releaseVal,
            valueRange = 1f..100f,
            onValueChange = { 
                releaseVal = it
                engine.releaseTimeVal = it.toInt()
            },
            modifier = Modifier.fillMaxWidth(0.9f)
        )

        Spacer(Modifier.height(4.dp))

        // צג ויזואליזר ירוק
        Text("Waveform Monitor", color = Color.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color.Black, shape = RoundedCornerShape(8.dp))
                .padding(4.dp)
        ) {
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

        Spacer(Modifier.height(4.dp))

        // תיבות כיוון תדרים
        Text("כיוון תדרים (Hz):", color = Color(0xFFFFD700), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 2.dp)
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

        // כפתור איפוס תדרים לברירת המחדל
        OutlinedButton(
            onClick = {
                defaultFrequencies.forEachIndexed { i, f -> frequencies[i] = f }
            },
            modifier = Modifier.padding(top = 2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("איפוס תדרים (Reset)", color = Color(0xFFFFD700), fontSize = 10.sp)
        }

        Spacer(Modifier.weight(1f))

        // מקלדת
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
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
                            fontSize = 10.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
