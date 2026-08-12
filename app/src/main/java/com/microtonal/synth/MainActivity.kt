package com.microtonal.synth

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val engine = SynthEngine(this)
        engine.start()
        setContent { SynthAppUI(engine) }
    }
}

class NoteSlot {
    @Volatile var active: Boolean = false
    @Volatile var baseFreq: Float = 0f
    @Volatile var targetFreq: Float = 0f
    var currentFreq: Float = 0f
    var phase: Double = 0.0
    var envelopeVolume: Double = 0.0
    @Volatile var isReleasing: Boolean = false
    
    @Volatile var waveform: Int = 3 
    @Volatile var frozenCutoff: Float = 5000f
    @Volatile var frozenRes: Float = 0.3f
    @Volatile var frozenAttack: Float = 15f
    @Volatile var frozenSustain: Float = 0.8f
    @Volatile var frozenRelease: Float = 200f
    @Volatile var isLooperNote: Boolean = false
    
    var svfLow: Double = 0.0
    var svfBand: Double = 0.0
}

data class RecordedNote(
    val baseFreq: Float,
    val startTimeMs: Long,
    val durationMs: Long,
    val waveform: Int,
    val cutoff: Float,
    val resonance: Float,
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

    var volume = 0.5f
    var looperVolume = 1.0f 
    var waveformType = 3 

    // ADSR
    var attackMs = 15f
    var sustainLevel = 0.8f
    var releaseMs = 200f

    // DSP
    var cutoffFreq = 5000f
    var resonance = 0.3f
    var echoMix = 0.25f
    var glideMs = 30f

    var octaveShift = 0

    val liveVisualizerBuffer = FloatArray(256)
    val looperVisualizerBuffer = FloatArray(256)

    private val delayBuffer = FloatArray(44100)
    private var delayWritePos = 0

    @Volatile var isRecording = false
        private set
    private var recordedAudioStream: ByteArrayOutputStream? = null

    // Looper
    val recordedNotes = mutableListOf<RecordedNote>()
    @Volatile var isLoopRecording = false
    @Volatile var isLoopPlaying = false
    private var loopStartTime = 0L
    private var loopDurationMs = 0L
    private val activePressTimes = mutableMapOf<Float, Long>()
    private var loopJob: Job? = null

    fun getEffectiveFrequency(baseFreq: Float): Float {
        val multiplier = Math.pow(2.0, octaveShift.toDouble()).toFloat()
        return baseFreq * multiplier
    }

    fun startLoopRecording() {
        recordedNotes.clear()
        activePressTimes.clear()
        isLoopRecording = true
        loopStartTime = System.currentTimeMillis()
    }

    fun stopLoopRecording() {
        if (!isLoopRecording) return
        isLoopRecording = false
        loopDurationMs = (System.currentTimeMillis() - loopStartTime).coerceAtLeast(500L)
    }

    fun startLoopPlayback() {
        if (recordedNotes.isEmpty() || isLoopPlaying) return
        isLoopPlaying = true
        loopJob = CoroutineScope(Dispatchers.Default).launch {
            while (isLoopPlaying) {
                val currentLoopStart = System.currentTimeMillis()

                for (note in recordedNotes) {
                    launch {
                        delay(note.startTimeMs)
                        if (!isLoopPlaying) return@launch
                        
                        noteOn(
                            note.baseFreq, note.waveform, note.cutoff, note.resonance,
                            note.attack, note.sustain, note.release, true
                        )

                        delay(note.durationMs)
                        noteOff(note.baseFreq, true)
                    }
                }

                val elapsed = System.currentTimeMillis() - currentLoopStart
                val remaining = loopDurationMs - elapsed
                if (remaining > 0) delay(remaining)
            }
        }
    }

    fun stopLoopPlayback() {
        isLoopPlaying = false
        loopJob?.cancel()
        loopJob = null
        allNotesOff()
    }

    fun clearLoop() {
        stopLoopPlayback()
        recordedNotes.clear()
        loopDurationMs = 0L
    }

    fun allNotesOff() {
        for (i in 0 until maxVoices) {
            noteSlots[i].isReleasing = true
        }
    }

    fun noteOn(
        baseFreq: Float, 
        customWaveform: Int = waveformType, 
        customCutoff: Float = cutoffFreq, 
        customRes: Float = resonance,
        customAttack: Float = attackMs,
        customSustain: Float = sustainLevel,
        customRelease: Float = releaseMs,
        isLooper: Boolean = false
    ) {
        if (isLoopRecording && !isLooper) {
            activePressTimes[baseFreq] = System.currentTimeMillis()
        }

        val freq = getEffectiveFrequency(baseFreq)
        
        for (i in 0 until maxVoices) {
            val slot = noteSlots[i]
            if (slot.active && abs(slot.baseFreq - baseFreq) < 0.01f) {
                slot.isReleasing = false
                slot.targetFreq = freq
                slot.waveform = customWaveform
                slot.frozenCutoff = customCutoff
                slot.frozenRes = customRes
                slot.frozenAttack = customAttack
                slot.frozenSustain = customSustain
                slot.frozenRelease = customRelease
                slot.isLooperNote = isLooper
                return
            }
        }

        var targetSlot: NoteSlot? = null
        for (i in 0 until maxVoices) {
            if (!noteSlots[i].active) {
                targetSlot = noteSlots[i]
                break
            }
        }

        if (targetSlot == null) targetSlot = noteSlots[0]

        var lastActiveFreq = freq
        for (i in 0 until maxVoices) {
            if (noteSlots[i].active) {
                lastActiveFreq = noteSlots[i].currentFreq
                break
            }
        }

        targetSlot.baseFreq = baseFreq
        targetSlot.targetFreq = freq
        targetSlot.currentFreq = if (glideMs > 0) lastActiveFreq else freq
        targetSlot.isReleasing = false
        targetSlot.envelopeVolume = 0.001
        targetSlot.waveform = customWaveform
        targetSlot.frozenCutoff = customCutoff
        targetSlot.frozenRes = customRes
        targetSlot.frozenAttack = customAttack
        targetSlot.frozenSustain = customSustain
        targetSlot.frozenRelease = customRelease
        targetSlot.isLooperNote = isLooper
        targetSlot.active = true
    }

    fun noteOff(baseFreq: Float, isLooper: Boolean = false) {
        if (isLoopRecording && !isLooper && activePressTimes.containsKey(baseFreq)) {
            val pressTime = activePressTimes.remove(baseFreq) ?: loopStartTime
            val startTimeMs = pressTime - loopStartTime
            val durationMs = (System.currentTimeMillis() - pressTime).coerceAtLeast(50L)
            
            recordedNotes.add(RecordedNote(
                baseFreq, startTimeMs, durationMs, 
                waveformType, cutoffFreq, resonance, 
                attackMs, sustainLevel, releaseMs
            ))
        }

        for (i in 0 until maxVoices) {
            val slot = noteSlots[i]
            if (slot.active && abs(slot.baseFreq - baseFreq) < 0.01f) {
                slot.isReleasing = true
            }
        }
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

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
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack.play()
            val buffer = ShortArray(256)
            val byteBuffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)

            var dcX1 = 0.0
            var dcY1 = 0.0

            val delaySamples = (sampleRate * 0.25).toInt()
            var currentHeadroom = 1.0 // משתנה להחלקה למניעת קליקים בלופ

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
                    
                    // תצוגות נפרדות למשקי הסאונד
                    liveVisualizerBuffer[i] = frame.liveSample
                    looperVisualizerBuffer[i] = frame.looperSample
                    
                    byteBuffer.putShort(shortVal)
                }

                if (isRecording) {
                    recordedAudioStream?.write(byteBuffer.array())
                }

                audioTrack.write(buffer, 0, buffer.size)
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

    // --- חלון דיאלוג לכיוון תדרים (מתבקש בסעיף 2) ---
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
        // --- Header ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MicroScale Synth", color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // כפתור פתיחת הגדרות תדרים
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

        // --- Looper Panel ---
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

        // --- צג סאונד אדום: לופר בלבד (מתבקש בסעיף 1) ---
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

        // --- Waveform selection ---
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

        // --- Octave & Presets ---
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

        // --- Sliders Section ---
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

        // --- צג סאונד ירוק: לייב בלבד (מתבקש בסעיף 1) ---
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

        // --- Piano Keyboard ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(115.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            frequencies.forEachIndexed { index, freq ->
                var isPressed by remember { mutableStateOf(false) }
                Card(
                    shape = Ro
}


        
                                                    },
                        
            

    }
}
