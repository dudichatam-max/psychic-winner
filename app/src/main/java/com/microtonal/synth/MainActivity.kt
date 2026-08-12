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
import kotlin.math.sqrt

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
    @Volatile var isLooperVoice: Boolean = false // האם התו משויך לערוץ הלופר או לנגינה החיה
}

data class RecordedNote(
    val baseFreq: Float,
    val startTimeMs: Long,
    val durationMs: Long,
    val waveform: Int,
    val volume: Float,
    val attackMs: Float,
    val sustainLevel: Float,
    val releaseMs: Float,
    val cutoffFreq: Float,
    val resonance: Float,
    val echoMix: Float,
    val glideMs: Float
)

class SynthEngine(private val context: Context) {
    private val sampleRate = 44100
    @Volatile private var isRunning = true

    private val maxVoices = 16
    private val noteSlots = Array(maxVoices) { NoteSlot() }

    var volume = 0.5f
    var waveformType = 4 // ברירת מחדל: Sine (כמו בתמונה)

    // ADSR - ברירת מחדל מעודכנת לפי התמונה
    var attackMs = 333f
    var sustainLevel = 0.33f
    var releaseMs = 1717f

    // DSP Effects - ברירת מחדל מעודכנת לפי התמונה
    var cutoffFreq = 440f
    var resonance = 0.77f
    var echoMix = 0.55f
    var glideMs = 130f

    var octaveShift = 0

    // שליטה בעוצמת הלופר בלבד (בנפרד מהמאסטר)
    var looperVolume = 0.5f

    val visualizerBuffer = FloatArray(256)

    private val delayBuffer = FloatArray(44100)
    private var delayWritePos = 0

    @Volatile var isRecording = false
        private set
    private var recordedAudioStream: ByteArrayOutputStream? = null

    // --- EVENT LOOPER ENGINE ---
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
                        
                        // הפעלת התו עם פריסט הלופ המקורי שנשמר בצורה מוצהרת בזמן ההקלטה!
                        noteOnLooper(note)

                        delay(note.durationMs)
                        noteOffLooper(note.baseFreq)
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

    // נגינה חיה מהמקלדת
    fun noteOn(baseFreq: Float) {
        if (isLoopRecording) {
            activePressTimes[baseFreq] = System.currentTimeMillis()
        }
        playVoice(baseFreq, waveformType, false, attackMs, sustainLevel, releaseMs)
    }

    // נגינה אוטומטית מתוך ערוץ הלופר העצמאי
    private fun noteOnLooper(note: RecordedNote) {
        playVoice(note.baseFreq, note.waveform, true, note.attackMs, note.sustainLevel, note.releaseMs)
    }

    private fun playVoice(baseFreq: Float, wave: Int, isLooper: Boolean, attack: Float, sustain: Float, release: Float) {
        val freq = getEffectiveFrequency(baseFreq)
        
        for (i in 0 until maxVoices) {
            val slot = noteSlots[i]
            if (slot.active && abs(slot.baseFreq - baseFreq) < 0.01f && slot.isLooperVoice == isLooper) {
                slot.isReleasing = false
                slot.targetFreq = freq
                slot.waveform = wave
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

        if (targetSlot == null) {
            targetSlot = noteSlots[0]
        }

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
        targetSlot.waveform = wave
        targetSlot.isLooperVoice = isLooper
        targetSlot.active = true
    }

    fun noteOff(baseFreq: Float) {
        if (isLoopRecording && activePressTimes.containsKey(baseFreq)) {
            val pressTime = activePressTimes.remove(baseFreq) ?: loopStartTime
            val startTimeMs = pressTime - loopStartTime
            val durationMs = (System.currentTimeMillis() - pressTime).coerceAtLeast(50L)
            
            // שמירת כל פרמטרי הפריסט הנוכחיים יחד עם התו בלופ
            recordedNotes.add(
                RecordedNote(
                    baseFreq = baseFreq,
                    startTimeMs = startTimeMs,
                    durationMs = durationMs,
                    waveform = waveformType,
                    volume = volume,
                    attackMs = attackMs,
                    sustainLevel = sustainLevel,
                    releaseMs = releaseMs,
                    cutoffFreq = cutoffFreq,
                    resonance = resonance,
                    echoMix = echoMix,
                    glideMs = glideMs
                )
            )
        }
        noteOffSlot(baseFreq, false)
    }

    private fun noteOffLooper(baseFreq: Float) {
        noteOffSlot(baseFreq, true)
    }

    private fun noteOffSlot(baseFreq: Float, isLooper: Boolean) {
        for (i in 0 until maxVoices) {
            val slot = noteSlots[i]
            if (slot.active && abs(slot.baseFreq - baseFreq) < 0.01f && slot.isLooperVoice == isLooper) {
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

            var svfLow = 0.0
            var svfBand = 0.0

            var dcX1 = 0.0
            var dcY1 = 0.0

            val delaySamples = (sampleRate * 0.25).toInt()

            while (isRunning) {
                byteBuffer.clear()

                for (i in buffer.indices) {
                    var sample = 0.0
                    var activeCount = 0

                    for (v in 0 until maxVoices) {
                        if (noteSlots[v].active) activeCount++
                    }

                    val headroomScale = if (activeCount > 0) 1.0 / sqrt(activeCount.toDouble()) else 1.0

                    for (v in 0 until maxVoices) {
                        val slot = noteSlots[v]
                        if (!slot.active) continue

                        val currentAttack = if (slot.isLooperVoice) 333.0 else (attackMs.toDouble() / 1000.0)
                        val currentRelease = if (slot.isLooperVoice) 1717.0 else (releaseMs.toDouble() / 1000.0)
                        val currentSustain = if (slot.isLooperVoice) 0.33 else sustainLevel.toDouble()
                        val currentGlide = if (slot.isLooperVoice) 130.0 else glideMs.toDouble()

                        val attackCoeff = 1.0 - Math.exp(-1.0 / (sampleRate * currentAttack.coerceAtLeast(0.001)))
                        val releaseCoeff = Math.exp(-1.0 / (sampleRate * currentRelease.coerceAtLeast(0.001)))
                        val glideFactor = if (currentGlide > 0) (1.0 / (sampleRate * (currentGlide / 1000.0))).coerceIn(0.001, 1.0) else 1.0

                        if (currentGlide > 0 && abs(slot.currentFreq - slot.targetFreq) > 0.05f) {
                            slot.currentFreq += ((slot.targetFreq - slot.currentFreq) * glideFactor).toFloat()
                        } else {
                            slot.currentFreq = slot.targetFreq
                        }

                        slot.phase += (2.0 * PI * slot.currentFreq / sampleRate)
                        if (slot.phase >= 2.0 * PI) {
                            slot.phase %= (2.0 * PI)
                        }

                        if (!slot.isReleasing) {
                            slot.envelopeVolume += (currentSustain - slot.envelopeVolume) * attackCoeff
                        } else {
                            slot.envelopeVolume *= releaseCoeff
                            if (slot.envelopeVolume < 0.001) {
                                slot.envelopeVolume = 0.0
                                slot.active = false
                                continue
                            }
                        }

                        val raw = when (slot.waveform) {
                            0 -> sin(slot.phase)
                            1 -> if (sin(slot.phase) >= 0) 0.3 else -0.3
                            2 -> (2.0 / PI) * asin(sin(slot.phase))
                            3 -> (1.0 - (slot.phase / PI)) * 0.4
                            else -> sin(slot.phase) // Sine בתור ברירת מחדל
                        }

                        val voiceMultiplier = if (slot.isLooperVoice) looperVolume else 1.0
                        sample += raw * slot.envelopeVolume * headroomScale * voiceMultiplier
                    }

                    val currentCutoff = if (activeCount > 0 && noteSlots.any { it.active && it.isLooperVoice }) 440.0 else cutoffFreq.toDouble()
                    val currentRes = if (activeCount > 0 && noteSlots.any { it.active && it.isLooperVoice }) 0.77 else resonance.toDouble()

                    val f = (2.0 * sin(PI * currentCutoff / sampleRate)).coerceIn(0.01, 0.8)
                    val q = (1.0 - currentRes.coerceIn(0.0, 0.95))
                    val hp = sample - svfLow - q * svfBand
                    svfBand += f * hp
                    svfLow += f * svfBand
                    sample = svfLow

                    val delayReadPos = (delayWritePos - delaySamples + delayBuffer.size) % delayBuffer.size
                    val echoSample = delayBuffer[delayReadPos]
                    val currentEchoMix = if (activeCount > 0 && noteSlots.any { it.active && it.isLooperVoice }) 0.55 else echoMix.toDouble()
                    delayBuffer[delayWritePos] = (sample + echoSample * 0.4).toFloat()
                    delayWritePos = (delayWritePos + 1) % delayBuffer.size
                    sample += echoSample * currentEchoMix

                    val dcSample = sample - dcX1 + 0.995 * dcY1
                    dcX1 = sample
                    dcY1 = dcSample
                    sample = dcSample

                    sample = softClip(sample * volume * 0.6)

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
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("synth_presets", Context.MODE_PRIVATE) }

    // ברירות מחדל מדויקות לתדרים לפי התמונה שלך
    val defaultFrequencies = remember {
        listOf(264.00f, 297.00f, 330.00f, 352.00f, 396.00f, 440.00f, 462.00f, 475.00f)
    }
    val frequencies = remember {
        mutableStateListOf(264.00f, 297.00f, 330.00f, 352.00f, 396.00f, 440.00f, 462.00f, 475.00f)
    }
    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")

    // ערכי ברירת מחדל תואמים במדויק לצילום המסך ששלחת (Sine, Attack 333ms, Release 1717ms, Resonance 77%, Glide 130ms, Sustain 33%, Cutoff 440Hz, Echo 55%, Volume 33%)
    var currentWave by remember { mutableIntStateOf(4) } // 4 = Sine
    var vol by remember { mutableFloatStateOf(0.33f) }
    var attackVal by remember { mutableFloatStateOf(333f) }
    var sustainVal by remember { mutableFloatStateOf(0.33f) }
    var releaseVal by remember { mutableFloatStateOf(1717f) }

    var cutoffVal by remember { mutableFloatStateOf(440f) }
    var resVal by remember { mutableFloatStateOf(0.77f) }
    var echoVal by remember { mutableFloatStateOf(0.55f) }
    var glideVal by remember { mutableFloatStateOf(130f) }

    var currentOctave by remember { mutableIntStateOf(0) }
    var isRec by remember { mutableStateOf(false) }

    // Loop States & Looper Volume Slider
    var isLoopRecState by remember { mutableStateOf(false) }
    var isLoopPlayState by remember { mutableStateOf(false) }
    var loopVol by remember { mutableFloatStateOf(0.5f) }

    var selectedPresetSlot by remember { mutableIntStateOf(2) } // ברירת מחדל סלוט 2 מסומן כמו בתמונה

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

    fun loadPresetFromSlot(slot: Int) {
        if (!prefs.getBoolean("p_${slot}_exists", false)) {
            Toast.makeText(context, "פריסט $slot עדיין ריק", Toast.LENGTH_SHORT).show()
            return
        }

        vol = prefs.getFloat("p_${slot}_vol", 0.33f); engine.volume = vol
        attackVal = prefs.getFloat("p_${slot}_attack", 333f); engine.attackMs = attackVal
        sustainVal = prefs.getFloat("p_${slot}_sustain", 0.33f); engine.sustainLevel = sustainVal
        releaseVal = prefs.getFloat("p_${slot}_release", 1717f); engine.releaseMs = releaseVal
        cutoffVal = prefs.getFloat("p_${slot}_cutoff", 440f); engine.cutoffFreq = cutoffVal
        resVal = prefs.getFloat("p_${slot}_res", 0.77f); engine.resonance = resVal
        echoVal = prefs.getFloat("p_${slot}_echo", 0.55f); engine.echoMix = echoVal
        glideVal = prefs.getFloat("p_${slot}_glide", 130f); engine.glideMs = glideVal

        val freqsStr = prefs.getString("p_${slot}_freqs", null)
        if (freqsStr != null) {
            val list = freqsStr.split(",").mapNotNull { it.toFloatOrNull() }
            if (list.size == frequencies.size) {
                list.forEachIndexed { i, f -> frequencies[i] = f }
            }
        }
        Toast.makeText(context, "פריסט $slot נטען", Toast.LENGTH_SHORT).show()
    }

    var renderTrigger by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
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
        // --- 1. כותרת והקלטת WAV ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("MicroScale Synth", color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)

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

        Spacer(Modifier.height(4.dp))

        // --- LOOP CONTROL BAR ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp))
                .padding(6.dp),
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

        Spacer(Modifier.height(4.dp))

        // מד עוצמת סאונד נפרד ללופר (מתחת לשורת הלופר)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("עוצמת לופר: ${(loopVol * 100).toInt()}%", color = Color(0xFF00FF66), fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Slider(
                value = loopVol,
                onValueChange = { loopVol = it; engine.looperVolume = it },
                modifier = Modifier.weight(1f).height(20.dp)
            )
        }

        Spacer(Modifier.height(2.dp))

        // --- 2. בחירת גל (ברירת מחדל Sine כמו בתמונה) ---
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val waves = listOf("Noise", "Saw", "Triangle", "Square", "Sine")
            itemsIndexed(waves) { index, name ->
                FilterChip(
                    selected = currentWave == index,
                    onClick = { currentWave = index; engine.waveformType = index },
                    label = { Text(name, fontSize = 9.sp) }
                )
            }
        }

        // --- 3. אוקטבות ופריסטים ---
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

        // --- 4. סליידרים (מעודכנים לערכים שבתמונה) ---
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Attack: ${attackVal.toInt()}ms", color = Color(0xFF00E5FF), fontSize = 9.sp)
                Slider(value = attackVal, valueRange = 5f..2000f, onValueChange = { attackVal = it; engine.attackMs = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Volume: ${(vol * 100).toInt()}%", color = Color.White, fontSize = 9.sp)
                Slider(value = vol, onValueChange = { vol = it; engine.volume = it })
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Release: ${releaseVal.toInt()}ms", color = Color(0xFFFFD700), fontSize = 9.sp)
                Slider(value = releaseVal, valueRange = 20f..5000f, onValueChange = { releaseVal = it; engine.releaseMs = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Sustain: ${(sustainVal * 100).toInt()}%", color = Color(0xFF00FF66), fontSize = 9.sp)
                Slider(value = sustainVal, valueRange = 0f..1f, onValueChange = { sustainVal = it; engine.sustainLevel = it })
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Resonance: ${(resVal * 100).toInt()}%", color = Color(0xFFFF4081), fontSize = 9.sp)
                Slider(value = resVal, valueRange = 0f..0.95f, onValueChange = { resVal = it; engine.resonance = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Cutoff: ${cutoffVal.toInt()}Hz", color = Color(0xFFFF7043), fontSize = 9.sp)
                Slider(value = cutoffVal, valueRange = 100f..12000f, onValueChange = { cutoffVal = it; engine.cutoffFreq = it })
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Glide: ${glideVal.toInt()}ms", color = Color(0xFF26C6DA), fontSize = 9.sp)
                Slider(value = glideVal, valueRange = 0f..500f, onValueChange = { glideVal = it; engine.glideMs = it })
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Echo: ${(echoVal * 100).toInt()}%", color = Color(0xFFAB47BC), fontSize = 9.sp)
                Slider(value = echoVal, valueRange = 0f..0.8f, onValueChange = { echoVal = it; engine.echoMix = it })
            }
        }

        // --- 5. ויזואלייזר ---
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(35.dp)
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

        Spacer(Modifier.weight(1f))

        // --- 6. כיוון תדרים (מעל המקלדת) ---
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
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

        Spacer(Modifier.height(2.dp))

        // --- 7. המקלדת (Keys) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(105.dp),
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
