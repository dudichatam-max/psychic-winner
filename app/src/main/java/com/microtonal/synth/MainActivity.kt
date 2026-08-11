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

class NoteState(var targetFreq: Float, var currentFreq: Float = targetFreq) {
    var phase: Double = 0.0
    var envelopeVolume = 0.0
    var isReleasing = false
}

class SynthEngine(private val context: Context) {
    private val sampleRate = 44100
    @Volatile private var isRunning = true
    private val activeNotes = ConcurrentHashMap<Float, NoteState>()

    var volume = 0.5f
    var waveformType = 3 // 0=Sine, 1=Square, 2=Triangle, 3=Sawtooth, 4=Noise

    // ADSR
    var attackMs = 15f
    var sustainLevel = 0.8f
    var releaseMs = 200f

    // DSP Effects
    var cutoffFreq = 5000f
    var echoMix = 0.25f
    var glideMs = 30f

    var octaveShift = 0

    val visualizerBuffer = FloatArray(256)

    private val delayBuffer = FloatArray(44100)
    private var delayWritePos = 0

    @Volatile var isRecording = false
        private set
    private var recordedAudioStream: ByteArrayOutputStream? = null

    fun getEffectiveFrequency(baseFreq: Float): Float {
        val multiplier = Math.pow(2.0, octaveShift.toDouble()).toFloat()
        return baseFreq * multiplier
    }

    fun noteOn(baseFreq: Float) {
        val freq = getEffectiveFrequency(baseFreq)
        val existing = activeNotes[freq]
        if (existing != null) {
            existing.isReleasing = false
            existing.targetFreq = freq
        } else {
            val lastFreq = activeNotes.values.lastOrNull()?.currentFreq ?: freq
            activeNotes[freq] = NoteState(targetFreq = freq, currentFreq = if (glideMs > 0) lastFreq else freq)
        }
    }

    fun noteOff(baseFreq: Float) {
        val freq = getEffectiveFrequency(baseFreq)
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
        val fileName = "ElScale_Rec_${System.currentTimeMillis()}.wav"
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

    private fun softClip(sample: Double): Double {
        return Math.tanh(sample)
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

            var filterState = 0.0
            val delaySamples = (sampleRate * 0.25).toInt()

            while (isRunning) {
                byteBuffer.clear()

                val attackStep = 1.0 / (sampleRate * (attackMs / 1000.0))
                val releaseStep = 1.0 / (sampleRate * (releaseMs / 1000.0))
                val glideFactor = if (glideMs > 0) (1.0 / (sampleRate * (glideMs / 1000.0))).coerceIn(0.001, 1.0) else 1.0

                for (i in buffer.indices) {
                    var sample = 0.0
                    val iterator = activeNotes.entries.iterator()

                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        val state = entry.value

                        if (glideMs > 0 && Math.abs(state.currentFreq - state.targetFreq) > 0.05f) {
                            state.currentFreq += ((state.targetFreq - state.currentFreq) * glideFactor).toFloat()
                        } else {
                            state.currentFreq = state.targetFreq
                        }

                        state.phase += (2.0 * Math.PI * state.currentFreq / sampleRate)
                        if (state.phase >= 2.0 * Math.PI) {
                            state.phase %= (2.0 * Math.PI)
                        }

                        if (!state.isReleasing) {
                            if (state.envelopeVolume < sustainLevel) {
                                state.envelopeVolume += attackStep
                                if (state.envelopeVolume > sustainLevel) state.envelopeVolume = sustainLevel.toDouble()
                            } else if (state.envelopeVolume > sustainLevel) {
                                state.envelopeVolume -= attackStep
                                if (state.envelopeVolume < sustainLevel) state.envelopeVolume = sustainLevel.toDouble()
                            }
                        } else {
                            state.envelopeVolume -= releaseStep
                            if (state.envelopeVolume <= 0.0) {
                                state.envelopeVolume = 0.0
                                iterator.remove()
                                continue
                            }
                        }

                        val raw = when (waveformType) {
                            0 -> sin(state.phase)
                            1 -> if (sin(state.phase) >= 0) 0.3 else -0.3
                            2 -> (2.0 / Math.PI) * Math.asin(sin(state.phase))
                            3 -> (1.0 - (state.phase / Math.PI)) * 0.4
                            else -> (Math.random() * 2.0 - 1.0) * 0.2
                        }

                        sample += raw * state.envelopeVolume
                    }

                    val filterAlpha = (2.0 * Math.PI * cutoffFreq / sampleRate).coerceIn(0.01, 1.0)
                    filterState += filterAlpha * (sample - filterState)
                    sample = filterState

                    val delayReadPos = (delayWritePos - delaySamples + delayBuffer.size) % delayBuffer.size
                    val echoSample = delayBuffer[delayReadPos]
                    delayBuffer[delayWritePos] = (sample + echoSample * 0.4).toFloat()
                    delayWritePos = (delayWritePos + 1) % delayBuffer.size

                    sample += echoSample * echoMix

                    sample = softClip(sample * volume * 0.7)

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
    // סולם 8 התווים הייחודי (עם לה = 440Hz ואל = 489.94Hz)
    val defaultFrequencies = remember {
        listOf(264.00f, 297.00f, 330.00f, 352.00f, 396.00f, 440.00f, 462.00f, 489.94f)
    }
    val frequencies = remember {
        mutableStateListOf(264.00f, 297.00f, 330.00f, 352.00f, 396.00f, 440.00f, 462.00f, 489.94f)
    }
    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")

    var currentWave by remember { mutableIntStateOf(3) }
    var vol by remember { mutableFloatStateOf(0.5f) }
    var attackVal by remember { mutableFloatStateOf(15f) }
    var sustainVal by remember { mutableFloatStateOf(0.8f) }
    var releaseVal by remember { mutableFloatStateOf(200f) }

    var cutoffVal by remember { mutableFloatStateOf(5000f) }
    var echoVal by remember { mutableFloatStateOf(0.25f) }
    var glideVal by remember { mutableFloatStateOf(30f) }

    var currentOctave by remember { mutableIntStateOf(0) }
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
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MicroScale Synth", color = Color(0xFF00E5FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)

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
                        .size(8.dp)
                        .background(if (isRec) Color.White else Color.Red, shape = CircleShape)
                )
                Spacer(Modifier.width(6.dp))
                Text(if (isRec) "שמור הקלטה" else "הקלט", color = Color.White, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(2.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val waves = listOf("Sine", "Square", "Triangle", "Saw", "Noise")
            itemsIndexed(waves) { index, name ->
                FilterChip(
                    selected = currentWave == index,
                    onClick = { currentWave = index; engine.waveformType = index },
                    label = { Text(name, fontSize = 9.sp) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        if (currentOctave > -2) {
                            currentOctave--
                            engine.octaveShift = currentOctave
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) { Text("-1 Oct", fontSize = 9.sp, color = Color.White) }

                Text(" Oct: $currentOctave ", color = Color.Yellow, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = {
                        if (currentOctave < 2) {
                            currentOctave++
                            engine.octaveShift = currentOctave
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) { Text("+1 Oct", fontSize = 9.sp, color = Color.White) }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Volume: ${(vol * 100).toInt()}%", color = Color.White, fontSize = 9.sp)
                Slider(value = vol, onValueChange = { vol = it; engine.volume = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Attack: ${attackVal.toInt()}ms", color = Color(0xFF00E5FF), fontSize = 9.sp)
                Slider(value = attackVal, valueRange = 5f..500f, onValueChange = { attackVal = it; engine.attackMs = it })
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Sustain: ${(sustainVal * 100).toInt()}%", color = Color(0xFF00FF66), fontSize = 9.sp)
                Slider(value = sustainVal, valueRange = 0f..1f, onValueChange = { sustainVal = it; engine.sustainLevel = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Release: ${releaseVal.toInt()}ms", color = Color(0xFFFFD700), fontSize = 9.sp)
                Slider(value = releaseVal, valueRange = 20f..2000f, onValueChange = { releaseVal = it; engine.releaseMs = it })
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Filter Cutoff: ${cutoffVal.toInt()}Hz", color = Color(0xFFFF7043), fontSize = 9.sp)
                Slider(value = cutoffVal, valueRange = 200f..12000f, onValueChange = { cutoffVal = it; engine.cutoffFreq = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Echo: ${(echoVal * 100).toInt()}%", color = Color(0xFFAB47BC), fontSize = 9.sp)
                Slider(value = echoVal, valueRange = 0f..0.6f, onValueChange = { echoVal = it; engine.echoMix = it })
            }
        }

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(0.5f)) {
                Text("Glide / Portamento: ${glideVal.toInt()}ms", color = Color(0xFF26C6DA), fontSize = 9.sp)
                Slider(value = glideVal, valueRange = 0f..200f, onValueChange = { glideVal = it; engine.glideMs = it })
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(45.dp)
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
            drawPath(path, Color(0xFF00FF66), style = Stroke(width = 2f))
        }

        Spacer(Modifier.height(2.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("כיוון תדרים (Hz):", color = Color(0xFFFFD700), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(
                onClick = { defaultFrequencies.forEachIndexed { i, f -> frequencies[i] = f } },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("איפוס לסולם אל", color = Color(0xFFFFD700), fontSize = 9.sp) }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(vertical = 2.dp)
        ) {
            itemsIndexed(frequencies) { index, freq ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(noteNames[index], color = Color.White, fontSize = 9.sp)
                    OutlinedTextField(
                        value = freq.toString(),
                        onValueChange = { newValue ->
                            newValue.toFloatOrNull()?.let { frequencies[index] = it }
                        },
                        modifier = Modifier.width(62.dp),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 9.sp, color = Color.White)
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
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
                            text = "${noteNames[index]}\n${engine.getEffectiveFrequency(freq).toInt()}Hz",
                            color = if (isPressed) Color.Black else Color.DarkGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
