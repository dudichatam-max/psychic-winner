package com.microtonal.synth

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.pow

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
    var zdfState1: Double = 0.0
    var zdfState2: Double = 0.0
    var smoothedCutoff: Float = 5000f
    var smoothedRes: Float = 0.3f
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
    private val maxVoices = 12
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
    var pulseWidth = 0.5f
    var driveAmount = 0.0f
    var lfoRate = 0.0f
    var lfoAmount = 0.0f
    var subLevel = 0.0f

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
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
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
                        noteSlots, maxVoices, glideMs, volume, looperVolume,
                        cutoffFreq, resonance, attackMs, sustainLevel, releaseMs, echoMix,
                        pulseWidth, driveAmount, lfoRate, lfoAmount, subLevel
                    )
                    val shortVal = (frame.masterSample * Short.MAX_VALUE * 0.85f).toInt().coerceIn(-32768, 32767).toShort()
                    buffer[i] = shortVal
                    liveVisualizerBuffer[i] = frame.liveSample
                    looperVisualizerBuffer[i] = frame.looperSample
                    byteBuffer.putShort(shortVal)
                }
                if (isRecording) recordedAudioStream?.write(byteBuffer.array())
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

    fun getEffectiveFrequency(baseFreq: Float): Float = (baseFreq * 2.0f.pow(octaveShift)).toFloat()

    fun noteOn(baseFreq: Float, isLooper: Boolean = false, wave: Int? = null, cutoff: Float? = null, res: Float? = null, attack: Float? = null, sustain: Float? = null, release: Float? = null) {
        val freq = getEffectiveFrequency(baseFreq)
        if (isLoopRecording && !isLooper) {
            recordedNotes.add(LooperNoteEvent(System.currentTimeMillis() - loopStartTime, true, baseFreq, waveformType, cutoffFreq, resonance, attackMs, sustainLevel, releaseMs))
        }
        var slot = noteSlots.find { it.active && it.baseFreq == baseFreq && it.isLooperNote == isLooper }
        if (slot != null) {
            slot.isReleasing = false
            slot.targetFreq = freq
            return
        }
        slot = noteSlots.find { !it.active }
        if (slot == null) slot = noteSlots.filter { it.isReleasing }.minByOrNull { it.envelopeVolume }
        if (slot == null) slot = noteSlots.filter { !it.isLooperNote }.minByOrNull { it.envelopeVolume }
        if (slot == null) slot = noteSlots.minByOrNull { it.envelopeVolume }
        if (slot != null) {
            slot.active = true
            slot.baseFreq = baseFreq
            slot.targetFreq = freq
            slot.currentFreq = freq
            slot.phase = 0.0
            slot.envelopeVolume = 0.0
            slot.isReleasing = false
            slot.waveform = wave ?: waveformType
            slot.isLooperNote = isLooper
            slot.frozenCutoff = cutoff ?: cutoffFreq
            slot.frozenRes = res ?: resonance
            slot.frozenAttack = attack ?: attackMs
            slot.frozenSustain = sustain ?: sustainLevel
            slot.frozenRelease = release ?: releaseMs
            slot.zdfState1 = 0.0
            slot.zdfState2 = 0.0
        }
    }

    fun noteOff(baseFreq: Float, isLooper: Boolean = false) {
        if (isLoopRecording && !isLooper) {
            recordedNotes.add(LooperNoteEvent(System.currentTimeMillis() - loopStartTime, false, baseFreq, waveformType, cutoffFreq, resonance, attackMs, sustainLevel, releaseMs))
        }
        noteSlots.find { it.active && it.baseFreq == baseFreq && it.isLooperNote == isLooper && !it.isReleasing }?.isReleasing = true
    }

    fun startLoopRecording() { recordedNotes.clear(); isLoopRecording = true; loopStartTime = System.currentTimeMillis() }
    fun stopLoopRecording() { if (!isLoopRecording) return; isLoopRecording = false; loopDurationMs = System.currentTimeMillis() - loopStartTime }
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
                        if (ev.isNoteOn) noteOn(ev.freq, true, ev.wave, ev.cutoff, ev.res, ev.attack, ev.sustain, ev.release)
                        else noteOff(ev.freq, true)
                        eventIndex++
                    }
                    try { Thread.sleep(1) } catch (_: Exception) {}
                }
                noteSlots.filter { it.isLooperNote }.forEach { it.active = false }
            }
        }.also { it.start() }
    }
    fun stopLoopPlayback() { isLoopPlaying = false; loopThread?.interrupt(); loopThread = null; noteSlots.filter { it.isLooperNote }.forEach { it.active = false } }
    fun clearLoop() { stopLoopPlayback(); recordedNotes.clear(); loopDurationMs = 0L }

    fun startRecording() {
        try {
            val file = File(context.cacheDir, "temp_synth_recording.wav")
            wavFile = file
            recordedAudioStream = FileOutputStream(file)
            writeWavHeader(recordedAudioStream!!, 0L)
            isRecording = true
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun stopAndSaveRecording(): File? {
        if (!isRecording) return wavFile
        isRecording = false
        try {
            recordedAudioStream?.flush()
            recordedAudioStream?.close()
            recordedAudioStream = null
            wavFile?.let { updateWavHeader(it) }
        } catch (e: Exception) { e.printStackTrace() }
        return wavFile
    }

    fun exportRecordingToUri(context: Context, destinationUri: Uri): Boolean {
        val sourceFile = wavFile ?: File(context.cacheDir, "temp_synth_recording.wav")
        if (!sourceFile.exists()) return false
        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { out -> sourceFile.inputStream().use { it.copyTo(out) } }
            true
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val byteRate = longSampleRate * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte(); header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[20] = 1; header[22] = 1
        header[24] = (longSampleRate and 0xff).toByte(); header[25] = (longSampleRate shr 8 and 0xff).toByte(); header[26] = (longSampleRate shr 16 and 0xff).toByte(); header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte(); header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = 2; header[34] = 16
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = (totalAudioLen shr 8 and 0xff).toByte(); header[42] = (totalAudioLen shr 16 and 0xff).toByte(); header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }

    private fun updateWavHeader(file: File) {
        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val raf = java.io.RandomAccessFile(file, "rw")
        raf.seek(4)
        raf.write((totalDataLen and 0xff).toInt()); raf.write((totalDataLen shr 8 and 0xff).toInt()); raf.write((totalDataLen shr 16 and 0xff).toInt()); raf.write((totalDataLen shr 24 and 0xff).toInt())
        raf.seek(40)
        raf.write((totalAudioLen and 0xff).toInt()); raf.write((totalAudioLen shr 8 and 0xff).toInt()); raf.write((totalAudioLen shr 16 and 0xff).toInt()); raf.write((totalAudioLen shr 24 and 0xff).toInt())
        raf.close()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(engine: SynthEngine) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("synth_presets", Context.MODE_PRIVATE) }
    val defaultFrequencies = remember { listOf(264.00f, 297.00f, 330.00f, 352.00f, 396.00f, 440.00f, 462.00f, 475.00f) }
    val frequencies = remember { mutableStateListOf(264.00f, 297.00f, 330.00f, 352.00f, 396.00f, 440.00f, 462.00f, 475.00f) }
    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")

    var showTuningDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentWave by remember { mutableIntStateOf(3) }
    var vol by remember { mutableFloatStateOf(0.5f) }
    var attackVal by remember { mutableFloatStateOf(15f) }
    var sustainVal by remember { mutableFloatStateOf(0.8f) }
    var releaseVal by remember { mutableFloatStateOf(200f) }
    var cutoffVal by remember { mutableFloatStateOf(5000f) }
    var resVal by remember { mutableFloatStateOf(0.3f) }
    var echoVal by remember { mutableFloatStateOf(0.25f) }
    var glideVal by remember { mutableFloatStateOf(30f) }
    var pulseWidthVal by remember { mutableFloatStateOf(0.5f) }
    var driveVal by remember { mutableFloatStateOf(0.0f) }
    var lfoRateVal by remember { mutableFloatStateOf(0.0f) }
    var lfoAmountVal by remember { mutableFloatStateOf(0.0f) }
    var subVal by remember { mutableFloatStateOf(0.0f) }
    var currentOctave by remember { mutableIntStateOf(0) }
    var isRec by remember { mutableStateOf(false) }
    var isLoopRecState by remember { mutableStateOf(false) }
    var isLoopPlayState by remember { mutableStateOf(false) }
    var looperVolState by remember { mutableFloatStateOf(1.0f) }
    var selectedPresetSlot by remember { mutableIntStateOf(1) }

    val gold = Color(0xFFD4AF37)
    val darkBg = Color(0xFF0A0A0A)
    val panelBg = Color(0xFF141414)
    val panelBg2 = Color(0xFF1A1A1A)

    val createWavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        uri?.let {
            if (engine.exportRecordingToUri(context, it)) Toast.makeText(context, "ההקלטה נשמרה בהצלחה!", Toast.LENGTH_LONG).show()
            else Toast.makeText(context, "שגיאה בשמירת הקובץ", Toast.LENGTH_SHORT).show()
        }
    }

    fun loadPresetFromSlot(slot: Int, showToast: Boolean = true) {
        if (!prefs.getBoolean("p_${slot}_exists", false)) {
            if (showToast) Toast.makeText(context, "פריסט $slot עדיין ריק", Toast.LENGTH_SHORT).show()
            return
        }
        vol = prefs.getFloat("p_${slot}_vol", 0.5f); engine.volume = vol
        attackVal = prefs.getFloat("p_${slot}_attack", 15f); engine.attackMs = attackVal
        sustainVal = prefs.getFloat("p_${slot}_sustain", 0.8f); engine.sustainLevel = sustainVal
        releaseVal = prefs.getFloat("p_${slot}_release", 200f); engine.releaseMs = releaseVal
        cutoffVal = prefs.getFloat("p_${slot}_cutoff", 5000f); engine.cutoffFreq = cutoffVal
        resVal = prefs.getFloat("p_${slot}_res", 0.3f); engine.resonance = resVal
        echoVal = prefs.getFloat("p_${slot}_echo", 0.25f); engine.echoMix = echoVal
        glideVal = prefs.getFloat("p_${slot}_glide", 30f); engine.glideMs = glideVal
        val freqsStr = prefs.getString("p_${slot}_freqs", null)
        if (freqsStr != null) {
            val list = freqsStr.split(",").mapNotNull { it.toFloatOrNull() }
            if (list.size == frequencies.size) list.forEachIndexed { i, f -> frequencies[i] = f }
        }
        if (showToast) Toast.makeText(context, "פריסט $slot נטען", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) { loadPresetFromSlot(1, false) }

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
    LaunchedEffect(Unit) { while (true) { delay(33); renderTrigger = System.currentTimeMillis() } }

    if (showTuningDialog) {
        AlertDialog(
            onDismissRequest = { showTuningDialog = false },
            title = {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("כיוון תדרים (Hz)", fontSize = 14.sp, color = gold, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { defaultFrequencies.forEachIndexed { i, f -> frequencies[i] = f } }, contentPadding = PaddingValues(6.dp, 2.dp)) {
                        Text("איפוס", color = gold, fontSize = 9.sp)
                    }
                }
            },
            text = {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(frequencies) { index, freq ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(noteNames[index], color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = freq.toString(),
                                onValueChange = { text -> text.toFloatOrNull()?.let { v -> frequencies[index] = v } },
                                modifier = Modifier.width(68.dp),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontSize = 10.sp, color = Color.White)
                            )
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showTuningDialog = false }) { Text("סגור") } },
            containerColor = panelBg2
        )
    }

    Column(Modifier.fillMaxSize().background(darkBg).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("SIREN", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showTuningDialog = true },
                    contentPadding = PaddingValues(8.dp, 2.dp),
                    modifier = Modifier.height(30.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                ) {
                    Text("⚙️ תדרים", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        if (isRec) {
                            engine.stopAndSaveRecording()
                            isRec = false
                            createWavLauncher.launch("Siren_${System.currentTimeMillis()}.wav")
                        } else {
                            engine.startRecording()
                            isRec = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isRec) Color(0xFFFF1744) else panelBg2),
                    contentPadding = PaddingValues(10.dp, 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(Modifier.size(6.dp).background(if (isRec) Color.White else Color.Red, CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isRec) "שמור" else "WAV", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(100.dp).background(panelBg, RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)).padding(3.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val halfH = h / 2f
                for (i in 1 until 8) drawLine(Color(0xFF1F1F1F), Offset(w * i / 8f, 0f), Offset(w * i / 8f, h), 1f)
                drawLine(Color(0xFF2A2A2A), Offset(0f, halfH), Offset(w, halfH), 1.5f)
                val livePath = Path()
                val step = w / engine.liveVisualizerBuffer.size
                engine.liveVisualizerBuffer.forEachIndexed { i, s ->
                    val x = i * step
                    val y = halfH / 2 + s * halfH / 2 * 0.85f
                    if (i == 0) livePath.moveTo(x, y) else livePath.lineTo(x, y)
                }
                drawPath(livePath, gold, style = Stroke(2.2f))
                val loopPath = Path()
                engine.looperVisualizerBuffer.forEachIndexed { i, s ->
                    val x = i * step
                    val y = halfH + halfH / 2 + s * halfH / 2 * 0.85f
                    if (i == 0) loopPath.moveTo(x, y) else loopPath.lineTo(x, y)
                }
                drawPath(loopPath, Color.White.copy(0.65f), style = Stroke(2.2f))
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 1.dp), Arrangement.SpaceBetween) {
                Text("LIVE", color = gold.copy(0.8f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text("LOOP", color = Color.White.copy(0.55f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(5.dp))

        Row(Modifier.fillMaxWidth().background(panelBg, RoundedCornerShape(7.dp)).padding(2.dp), Arrangement.SpaceBetween) {
            listOf("סאונד", "פילטר+FX", "לופר").forEachIndexed { index, title ->
                Button(
                    onClick = { selectedTab = index },
                    colors = ButtonDefaults.buttonColors(containerColor = if (selectedTab == index) panelBg2 else Color.Transparent),
                    shape = RoundedCornerShape(5.dp),
                    contentPadding = PaddingValues(vertical = 3.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(title, color = if (selectedTab == index) gold else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        Box(Modifier.fillMaxWidth().weight(1f).background(panelBg, RoundedCornerShape(8.dp)).padding(6.dp)) {
            when (selectedTab) {
                0 -> Column(Modifier.fillMaxSize(), Arrangement.SpaceBetween) {
                    Column {
                        Text("סוג גל", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf("Sine", "Square", "Triangle", "Saw", "Noise", "Pulse").forEachIndexed { index, name ->
                                val id = if (index == 5) 5 else index
                                FilterChip(
                                    selected = currentWave == id,
                                    onClick = { currentWave = id; engine.waveformType = id },
                                    label = { Text(name, fontSize = 9.sp) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = gold, selectedLabelColor = Color.Black),
                                    modifier = Modifier.height(28.dp)
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { if (currentOctave > -2) { currentOctave--; engine.octaveShift = currentOctave } }, contentPadding = PaddingValues(4.dp, 1.dp), modifier = Modifier.height(26.dp)) { Text("-1", fontSize = 9.sp, color = Color.White) }
                            Text(" $currentOctave ", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = { if (currentOctave < 2) { currentOctave++; engine.octaveShift = currentOctave } }, contentPadding = PaddingValues(4.dp, 1.dp), modifier = Modifier.height(26.dp)) { Text("+1", fontSize = 9.sp, color = Color.White) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            (1..4).forEach { slot ->
                                Button(onClick = { selectedPresetSlot = slot; loadPresetFromSlot(slot) }, colors = ButtonDefaults.buttonColors(containerColor = if (selectedPresetSlot == slot) gold else panelBg2), contentPadding = PaddingValues(0.dp), modifier = Modifier.size(24.dp)) {
                                    Text("$slot", fontSize = 9.sp, color = if (selectedPresetSlot == slot) Color.Black else Color.White)
                                }
                            }
                            Button(onClick = { savePresetToSlot(selectedPresetSlot) }, colors = ButtonDefaults.buttonColors(containerColor = gold), contentPadding = PaddingValues(5.dp, 1.dp), modifier = Modifier.height(24.dp)) {
                                Text("שמור", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    CompactSlider("ווליום", vol, 0f..1f, "${(vol * 100).toInt()}%", gold) { v -> vol = v; engine.volume = v }
                    CompactSlider("Attack", attackVal, 5f..500f, "${attackVal.toInt()}ms", gold) { v -> attackVal = v; engine.attackMs = v }
                    CompactSlider("Sustain", sustainVal, 0f..1f, "${(sustainVal * 100).toInt()}%", gold) { v -> sustainVal = v; engine.sustainLevel = v }
                    CompactSlider("Release", releaseVal, 20f..2000f, "${releaseVal.toInt()}ms", gold) { v -> releaseVal = v; engine.releaseMs = v }
                    CompactSlider("Pulse Width", pulseWidthVal, 0.05f..0.95f, "${(pulseWidthVal * 100).toInt()}%", gold) { v -> pulseWidthVal = v; engine.pulseWidth = v }
                    CompactSlider("Sub Osc", subVal, 0f..1f, "${(subVal * 100).toInt()}%", gold) { v -> subVal = v; engine.subLevel = v }
                }
                1 -> Column(Modifier.fillMaxSize(), Arrangement.SpaceEvenly) {
                    CompactSlider("Cutoff", cutoffVal, 200f..12000f, "${cutoffVal.toInt()}Hz", gold) { v -> cutoffVal = v; engine.cutoffFreq = v }
                    CompactSlider("Resonance", resVal, 0f..0.9f, "${(resVal * 100).toInt()}%", gold) { v -> resVal = v; engine.resonance = v }
                    CompactSlider("Echo", echoVal, 0f..0.6f, "${(echoVal * 100).toInt()}%", gold) { v -> echoVal = v; engine.echoMix = v }
                    CompactSlider("Glide", glideVal, 0f..200f, "${glideVal.toInt()}ms", gold) { v -> glideVal = v; engine.glideMs = v }
                    CompactSlider("Drive", driveVal, 0f..1f, "${(driveVal * 100).toInt()}%", gold) { v -> driveVal = v; engine.driveAmount = v }
                    CompactSlider("LFO Rate", lfoRateVal, 0f..12f, String.format("%.1f", lfoRateVal) + "Hz", gold) { v -> lfoRateVal = v; engine.lfoRate = v }
                    CompactSlider("LFO Amount", lfoAmountVal, 0f..1f, "${(lfoAmountVal * 100).toInt()}%", gold) { v -> lfoAmountVal = v; engine.lfoAmount = v }
                }
                2 -> Column(Modifier.fillMaxSize(), Arrangement.SpaceEvenly) {
                    CompactSlider("Looper Vol", looperVolState, 0f..1f, "${(looperVolState * 100).toInt()}%", gold) { v -> looperVolState = v; engine.looperVolume = v }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = {
                            if (isLoopRecState) { engine.stopLoopRecording(); isLoopRecState = false }
                            else { engine.startLoopRecording(); isLoopRecState = true; isLoopPlayState = false }
                        }, colors = ButtonDefaults.buttonColors(containerColor = if (isLoopRecState) Color(0xFFFF5252) else panelBg2), modifier = Modifier.weight(1f).height(42.dp)) {
                            Text(if (isLoopRecState) "עצור" else "הקלט לופ", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(onClick = {
                            if (isLoopPlayState) { engine.stopLoopPlayback(); isLoopPlayState = false }
                            else { engine.startLoopPlayback(); isLoopPlayState = true }
                        }, enabled = engine.recordedNotes.isNotEmpty() && !isLoopRecState, colors = ButtonDefaults.buttonColors(containerColor = if (isLoopPlayState) Color(0xFF00C853) else panelBg2), modifier = Modifier.weight(1f).height(42.dp)) {
                            Text(if (isLoopPlayState) "עצור" else "נגן לופ", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(onClick = { engine.clearLoop(); isLoopRecState = false; isLoopPlayState = false }, modifier = Modifier.fillMaxWidth().height(38.dp), border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.Gray))) {
                        Text("נקה לופר", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            frequencies.forEachIndexed { index, freq ->
                var isPressed by remember { mutableStateOf(false) }
                Card(
                    shape = RoundedCornerShape(bottomStart = 7.dp, bottomEnd = 7.dp, topStart = 3.dp, topEnd = 3.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isPressed) gold else Color(0xFFE8E8E8)),
                    modifier = Modifier.weight(1f).fillMaxHeight().pointerInput(freq) {
                        detectTapGestures(onPress = {
                            isPressed = true
                            engine.noteOn(freq)
                            tryAwaitRelease()
                            engine.noteOff(freq)
                            isPressed = false
                        })
                    }
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 7.dp)) {
                            Text(noteNames[index], color = if (isPressed) Color.Black else Color(0xFF1A1A1A), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("${engine.getEffectiveFrequency(freq).toInt()}", color = if (isPressed) Color.Black.copy(0.7f) else Color(0xFF555555), fontSize = 8.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CompactSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    color: Color,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(display, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = color,
                activeTrackColor = color,
                inactiveTrackColor = Color(0xFF2A2A2A)
            ),
            modifier = Modifier.height(20.dp)
        )
    }
}
