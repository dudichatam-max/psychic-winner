package com.microtonal.synth

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : ComponentActivity() {
    private lateinit var synthEngine: SynthEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        synthEngine = SynthEngine(this)
        synthEngine.start()

        setContent {
            MaterialTheme {
                SynthAppUI(synthEngine)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        synthEngine.stop()
    }
}

class NoteSlot {
    var active: Boolean = false
    var baseFreq: Float = 440f
    var targetFreq: Float = 440f
    var currentFreq: Float = 440f
    var phase: Double = 0.0
    var envelopeVolume: Double = 0.0
    var isReleasing: Boolean = false
    var waveform: Int = 0

    var isLooperNote: Boolean = false
    var frozenCutoff: Float = 5000f
    var frozenRes: Float = 0.3f
    var frozenAttack: Float = 15f
    var frozenSustain: Float = 0.8f
    var frozenRelease: Float = 200f

    var svfLow: Double = 0.0
    var svfBand: Double = 0.0
}

data class LooperNoteEvent(
    val timestampMs: Long,
    val isNoteOn: Boolean,
    val freq: Float,
    val wave: Int,
    val cutoff: Float,
    val res: Float,
    val attack: Float,
    val sustain: Float,
    val release: Float
)

class SynthEngine(private val context: Context) {
    private val sampleRate = 44100
    private val dspEngine = DspEngine(sampleRate)
    @Volatile private var isRunning = true

    private val maxVoices = 16
    private val noteSlots = Array(maxVoices) { NoteSlot() }

    var waveformType = 3
    var volume = 0.5f
    var looperVolume = 1.0f
    var attackMs = 15f
    var sustainLevel = 0.8f
    var releaseMs = 200f
    var cutoffFreq = 5000f
    var resonance = 0.3f
    var echoMix = 0.25f
    var glideMs = 30f
    var octaveShift = 0

    val liveVisualizerBuffer = FloatArray(256)
    val looperVisualizerBuffer = FloatArray(256)

    val recordedNotes = mutableListOf<LooperNoteEvent>()
    private var isLoopRecording = false
    private var isLoopPlaying = false
    private var loopStartTime = 0L
    private var loopDurationMs = 0L
    private var loopThread: Thread? = null

    private var isRecording = false
    private var recordedAudioStream: FileOutputStream? = null
    private var wavFile: File? = null

    private val audioTrack: AudioTrack

    init {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
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
    }

    fun start() {
        isRunning = true
        audioTrack.play()

        Thread {
            val bufferSize = 256
            val buffer = ShortArray(bufferSize)
            val byteBuffer = ByteBuffer.allocate(bufferSize * 2).order(ByteOrder.LITTLE_ENDIAN)

            while (isRunning) {
                byteBuffer.clear()

                for (i in buffer.indices) {
                    val frame = dspEngine.processNextSample(
                        noteSlots = noteSlots,
                        maxVoices = maxVoices,
                        glideMs = glideMs,
                        liveVolume = volume,
                        looperVolume = looperVolume,
                        cutoffFreq = cutoffFreq,
                        resonance = resonance,
                        attackMs = attackMs,
                        sustainLevel = sustainLevel,
                        releaseMs = releaseMs,
                        echoMix = echoMix
                    )

                    val shortVal = (frame.masterSample * Short.MAX_VALUE * 0.95).toInt().coerceIn(-32768, 32767).toShort()
                    buffer[i] = shortVal

                    liveVisualizerBuffer[i] = frame.liveSample
                    looperVisualizerBuffer[i] = frame.looperSample

                    byteBuffer.putShort(shortVal)
                }

                if (isRecording) {
                    recordedAudioStream?.write(byteBuffer.array())
                }

                audioTrack.write(buffer, 0, buffer.size)
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        stopLoopPlayback()
        audioTrack.stop()
        audioTrack.release()
    }

    fun getEffectiveFrequency(baseFreq: Float): Float {
        return baseFreq * Math.pow(2.0, octaveShift.toDouble()).toFloat()
    }

    fun noteOn(baseFreq: Float, isLooper: Boolean = false) {
        val freq = getEffectiveFrequency(baseFreq)

        if (isLoopRecording && !isLooper) {
            val now = System.currentTimeMillis() - loopStartTime
            recordedNotes.add(
                LooperNoteEvent(
                    timestampMs = now,
                    isNoteOn = true,
                    freq = baseFreq,
                    wave = waveformType,
                    cutoff = cutoffFreq,
                    res = resonance,
                    attack = attackMs,
                    sustain = sustainLevel,
                    release = releaseMs
                )
            )
        }

        var slot = noteSlots.find { it.active && it.baseFreq == baseFreq && it.isLooperNote == isLooper }
        if (slot != null) {
            slot.isReleasing = false
            slot.targetFreq = freq
            return
        }

        slot = noteSlots.find { !it.active }
        if (slot != null) {
            slot.active = true
            slot.baseFreq = baseFreq
            slot.targetFreq = freq
            slot.currentFreq = freq
            slot.phase = 0.0
            slot.envelopeVolume = 0.0
            slot.isReleasing = false
            slot.waveform = waveformType
            slot.isLooperNote = isLooper
            slot.frozenCutoff = cutoffFreq
            slot.frozenRes = resonance
            slot.frozenAttack = attackMs
            slot.frozenSustain = sustainLevel
            slot.frozenRelease = releaseMs
            slot.svfLow = 0.0
            slot.svfBand = 0.0
        }
    }

    fun noteOff(baseFreq: Float, isLooper: Boolean = false) {
        if (isLoopRecording && !isLooper) {
            val now = System.currentTimeMillis() - loopStartTime
            recordedNotes.add(
                LooperNoteEvent(
                    timestampMs = now,
                    isNoteOn = false,
                    freq = baseFreq,
                    wave = waveformType,
                    cutoff = cutoffFreq,
                    res = resonance,
                    attack = attackMs,
                    sustain = sustainLevel,
                    release = releaseMs
                )
            )
        }

        val slot = noteSlots.find { it.active && it.baseFreq == baseFreq && it.isLooperNote == isLooper && !it.isReleasing }
        slot?.isReleasing = true
    }

    fun startLoopRecording() {
        recordedNotes.clear()
        isLoopRecording = true
        loopStartTime = System.currentTimeMillis()
    }

    fun stopLoopRecording() {
        if (!isLoopRecording) return
        isLoopRecording = false
        loopDurationMs = System.currentTimeMillis() - loopStartTime
    }

    fun startLoopPlayback() {
        if (recordedNotes.isEmpty() || loopDurationMs <= 0) return
        stopLoopPlayback()
        isLoopPlaying = true

        loopThread = Thread {
            while (isLoopPlaying) {
                val start = System.currentTimeMillis()
                var eventIndex = 0

                while (isLoopPlaying) {
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed >= loopDurationMs) break

                    while (eventIndex < recordedNotes.size && recordedNotes[eventIndex].timestampMs <= elapsed) {
                        val ev = recordedNotes[eventIndex]
                        if (ev.isNoteOn) {
                            noteOn(ev.freq, isLooper = true)
                        } else {
                            noteOff(ev.freq, isLooper = true)
                        }
                        eventIndex++
                    }
                    try { Thread.sleep(2) } catch (_: Exception) {}
                }

                noteSlots.filter { it.isLooperNote }.forEach { it.active = false }
            }
        }.also { it.start() }
    }

    fun stopLoopPlayback() {
        isLoopPlaying = false
        loopThread?.interrupt()
        loopThread = null
        noteSlots.filter { it.isLooperNote }.forEach { it.active = false }
    }

    fun clearLoop() {
        stopLoopPlayback()
        recordedNotes.clear()
        loopDurationMs = 0L
    }

    fun startRecording() {
        try {
            wavFile = File(context.getExternalFilesDir(null), "synth_recording_${System.currentTimeMillis()}.wav")
            recordedAudioStream = FileOutputStream(wavFile)
            writeWavHeader(recordedAudioStream!, 0)
            isRecording = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopAndSaveRecording() {
        if (!isRecording) return
        isRecording = false
        try {
            recordedAudioStream?.flush()
            recordedAudioStream?.close()
            wavFile?.let { updateWavHeader(it) }
            Toast.makeText(context, "ההקלטה נשמרה ב: ${wavFile?.name}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val channels = 1
        val byteRate = longSampleRate * channels * 2

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (1 * 16 / 8).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        out.write(header, 0, 44)
    }

    private fun updateWavHeader(file: File) {
        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * 1 * 2

        val randomAccessFile = java.io.RandomAccessFile(file, "rw")
        randomAccessFile.seek(4)
        randomAccessFile.write((totalDataLen and 0xff).toInt())
        randomAccessFile.write((totalDataLen shr 8 and 0xff).toInt())
        randomAccessFile.write((totalDataLen shr 16 and 0xff).toInt())
        randomAccessFile.write((totalDataLen shr 24 and 0xff).toInt())

        randomAccessFile.seek(40)
        randomAccessFile.write((totalAudioLen and 0xff).toInt())
        randomAccessFile.write((totalAudioLen shr 8 and 0xff).toInt())
        randomAccessFile.write((totalAudioLen shr 16 and 0xff).toInt())
        randomAccessFile.write((totalAudioLen shr 24 and 0xff).toInt())

        randomAccessFile.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(engine: SynthEngine) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("synth_presets", Context.MODE_PRIVATE) }

    val defaultFrequencies = remember {
        listOf(264.00f, 297.00f, 330.00f, 352.00f, 396.00f, 440.00f, 462.00f, 475.00f)
    }
    val frequencies = remember {
        mutableStateListOf(264.00f, 297.00f, 330.00f, 352.00f, 396.00f, 440.00f, 462.00f, 475.00f)
    }
    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")

    var showTuningDialog by remember { mutableStateOf(false) }

    var currentWave by remember { mutableIntStateOf(3) }
    var vol by remember { mutableFloatStateOf(0.5f) }
    var attackVal by remember { mutableFloatStateOf(15f) }
    var sustainVal by remember { mutableFloatStateOf(0.8f) }
    var releaseVal by remember { mutableFloatStateOf(200f) }

    var cutoffVal by remember { mutableFloatStateOf(5000f) }
    var resVal by remember { mutableFloatStateOf(0.3f) }
    var echoVal by remember { mutableFloatStateOf(0.25f) }
    var glideVal by remember { mutableFloatStateOf(30f) }

    var currentOctave by remember { mutableIntStateOf(0) }
    var isRec by remember { mutableStateOf(false) }

    var isLoopRecState by remember { mutableStateOf(false) }
    var isLoopPlayState by remember { mutableStateOf(false) }
    var looperVolState by remember { mutableFloatStateOf(1.0f) }

    var selectedPresetSlot by remember { mutableIntStateOf(1) }

    fun loadPresetFromSlot(slot: Int, showToast: Boolean = true) {
        if (!prefs.getBoolean("p_${slot}_exists", false)) {
            if (showToast) Toast.makeText(context, "פריסט $slot עדיין ריק", Toast.LENGTH_SHORT).show()
            return
        }

        vol = prefs.getFloat("p_${slot}_vol", 0.5f)
        engine.volume = vol

        attackVal = prefs.getFloat("p_${slot}_attack", 15f)
        engine.attackMs = attackVal

        sustainVal = prefs.getFloat("p_${slot}_sustain", 0.8f)
        engine.sustainLevel = sustainVal

        releaseVal = prefs.getFloat("p_${slot}_release", 200f)
        engine.releaseMs = releaseVal

        cutoffVal = prefs.getFloat("p_${slot}_cutoff", 5000f)
        engine.cutoffFreq = cutoffVal

        resVal = prefs.getFloat("p_${slot}_res", 0.3f)
        engine.resonance = resVal

        echoVal = prefs.getFloat("p_${slot}_echo", 0.25f)
        engine.echoMix = echoVal

        glideVal = prefs.getFloat("p_${slot}_glide", 30f)
        engine.glideMs = glideVal

        val freqsStr = prefs.getString("p_${slot}_freqs", null)
        if (freqsStr != null) {
            val list = freqsStr.split(",").mapNotNull { it.toFloatOrNull() }
            if (list.size == frequencies.size) {
                list.forEachIndexed { i, f -> frequencies[i] = f }
            }
        }
        if (showToast) Toast.makeText(context, "פריסט $slot נטען", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        loadPresetFromSlot(1, showToast = false)
    }

    fun savePresetToSlot(slot: Int) {
        prefs.edit().apply {
            putFloat("p_${slot}_vol", vol)
            putFloat("p_${slot}_attack", attackVal)
            putFloat("p_${slot}_sustain", sustainVal)
            putFloat("p_${slot}_release", releaseVal)
            putFloat("p_${slot}_cutoff", cutoffVal)
            putFloat("p_${slot}_res", resVal)
            putFloat("p_${slot}_echo", echoVal)
            putFloat("p_${slot}_glide", glideVal)
            putString("p_${slot}_freqs", frequencies.joinToString(","))
            putBoolean("p_${slot}_exists", true)
            apply()
        }
        Toast.makeText(context, "פריסט $slot נשמר בהצלחה!", Toast.LENGTH_SHORT).show()
    }

    var renderTrigger by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            renderTrigger = System.currentTimeMillis()
        }
    }

    if (showTuningDialog) {
        AlertDialog(
            onDismissRequest = { showTuningDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("כיוון תדרים (Hz)", fontSize = 14.sp, color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = { defaultFrequencies.forEachIndexed { i, f -> frequencies[i] = f } },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("איפוס", color = Color(0xFFFFD700), fontSize = 9.sp) }
                }
            },
            text = {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    itemsIndexed(frequencies) { index, freq ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(noteNames[index], color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = freq.toString(),
                                onValueChange = { newValue ->
                                    newValue.toFloatOrNull()?.let { frequencies[index] = it }
                                },
                                modifier = Modifier.width(68.dp),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 10.sp, color = Color.White)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showTuningDialog = false }) {
                    Text("סגור")
                }
            },
            containerColor = Color(0xFF1E1E1E)
        )
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
            Text("MicroScale Synth", color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showTuningDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("⚙️ תדרים", color = Color(0xFFFFD700), fontSize = 9.sp)
                }

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
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).background(if (isRec) Color.White else Color.Red, shape = CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isRec) "שמור WAV" else "הקלט WAV", color = Color.White, fontSize = 9.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("לופר:", color = Color(0xFF00FF66), fontSize = 10.sp, fontWeight = FontWeight.Bold)

            Button(
                onClick = {
                    if (isLoopRecState) {
                        engine.stopLoopRecording()
                        isLoopRecState = false
                    } else {
                        engine.startLoopRecording()
                        isLoopRecState = true
                        isLoopPlayState = false
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLoopRecState) Color(0xFFFF5252) else Color(0xFF424242)
                ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(if (isLoopRecState) "עצור הקלטה" else "הקלט לופ", fontSize = 9.sp, color = Color.White)
            }

            Button(
                onClick = {
                    if (isLoopPlayState) {
                        engine.stopLoopPlayback()
                        isLoopPlayState = false
                    } else {
                        engine.startLoopPlayback()
                        isLoopPlayState = true
                    }
                },
                enabled = engine.recordedNotes.isNotEmpty() && !isLoopRecState,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLoopPlayState) Color(0xFF00C853) else Color(0xFF0288D1)
                ),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(if (isLoopPlayState) "עצור לופ" else "נגן לופ", fontSize = 9.sp, color = Color.White)
            }

            OutlinedButton(
                onClick = {
                    engine.clearLoop()
                    isLoopRecState = false
                    isLoopPlayState = false
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text("נקה", fontSize = 9.sp, color = Color.Gray)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("עוצמת הלופר: ${(looperVolState * 100).toInt()}%", color = Color(0xFFFF1744), fontSize = 9.sp)
            Slider(
                value = looperVolState,
                onValueChange = {
                    looperVolState = it
                    engine.looperVolume = it
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(Color.Black, shape = RoundedCornerShape(6.dp))
                .padding(2.dp)
        ) {
            val dummy = renderTrigger
            val path = Path()
            val centerY = size.height / 2
            val step = size.width / engine.looperVisualizerBuffer.size

            engine.looperVisualizerBuffer.forEachIndexed { i, sample ->
                val x = i * step
                val y = centerY + (sample * centerY * 0.9f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(0xFFFF1744), style = Stroke(width = 2f))
        }

        Spacer(Modifier.height(4.dp))

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
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) { Text("-1 Oct", fontSize = 9.sp, color = Color.White) }

                Text(" Oct: $currentOctave ", color = Color.Yellow, fontSize = 9.sp, fontWeight = FontWeight.Bold)

                OutlinedButton(
                    onClick = {
                        if (currentOctave < 2) {
                            currentOctave++
                            engine.octaveShift = currentOctave
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) { Text("+1 Oct", fontSize = 9.sp, color = Color.White) }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                (1..4).forEach { slot ->
                    Button(
                        onClick = {
                            selectedPresetSlot = slot
                            loadPresetFromSlot(slot)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedPresetSlot == slot) Color(0xFF00E5FF) else Color(0xFF333333)
                        ),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text("$slot", fontSize = 10.sp, color = if (selectedPresetSlot == slot) Color.Black else Color.White)
                    }
                }

                Button(
                    onClick = { savePresetToSlot(selectedPresetSlot) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAB47BC)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("שמור", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Live Volume: ${(vol * 100).toInt()}%", color = Color.White, fontSize = 9.sp)
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
                Text("Cutoff: ${cutoffVal.toInt()}Hz", color = Color(0xFFFF7043), fontSize = 9.sp)
                Slider(value = cutoffVal, valueRange = 200f..12000f, onValueChange = { cutoffVal = it; engine.cutoffFreq = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Resonance: ${(resVal * 100).toInt()}%", color = Color(0xFFFF4081), fontSize = 9.sp)
                Slider(value = resVal, valueRange = 0f..0.9f, onValueChange = { resVal = it; engine.resonance = it })
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Echo: ${(echoVal * 100).toInt()}%", color = Color(0xFFAB47BC), fontSize = 9.sp)
                Slider(value = echoVal, valueRange = 0f..0.6f, onValueChange = { echoVal = it; engine.echoMix = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Glide: ${glideVal.toInt()}ms", color = Color(0xFF26C6DA), fontSize = 9.sp)
                Slider(value = glideVal, valueRange = 0f..200f, onValueChange = { glideVal = it; engine.glideMs = it })
            }
        }

        Spacer(Modifier.weight(1f))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(Color.Black, shape = RoundedCornerShape(6.dp))
                .padding(2.dp)
        ) {
            val dummy = renderTrigger
            val path = Path()
            val centerY = size.height / 2
            val step = size.width / engine.liveVisualizerBuffer.size

            engine.liveVisualizerBuffer.forEachIndexed { i, sample ->
                val x = i * step
                val y = centerY + (sample * centerY * 0.9f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, Color(0xFF00FF66), style = Stroke(width = 2f))
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp),
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
