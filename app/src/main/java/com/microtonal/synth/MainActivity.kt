package com.microtonal.synth

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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
    @Volatile var active: Boolean = false
    @Volatile var baseFreq: Float = 440f
    @Volatile var targetFreq: Float = 440f
    @Volatile var currentFreq: Float = 440f
    var phase: Double = 0.0
    @Volatile var envelopeVolume: Double = 0.0
    @Volatile var isReleasing: Boolean = false
    @Volatile var waveform: Int = 0

    var isLooperNote: Boolean = false
    var frozenCutoff: Float = 5000f
    var frozenRes: Float = 0.3f
    var frozenAttack: Float = 15f
    var frozenDecay: Float = 50f
    var frozenSustain: Float = 0.8f
    var frozenRelease: Float = 200f

    var envState: Int = 0 // 0: Attack, 1: Decay, 2: Sustain

    var zdfState1: Double = 0.0
    var zdfState2: Double = 0.0
    var smoothedCutoff: Float = 5000f
    var smoothedRes: Float = 0.3f

    var attackCoeff: Double = 0.0
    var decayCoeff: Double = 0.0
    var releaseCoeff: Double = 0.0

    private val lock = Any()

    fun updateAndActivate(
        newBaseFreq: Float,
        newTargetFreq: Float,
        newStartFreq: Float,
        newWaveform: Int,
        isLooper: Boolean,
        cutoff: Float,
        res: Float,
        attack: Float,
        decay: Float,
        sustain: Float,
        release: Float,
        sampleRate: Int
    ) = synchronized(lock) {
        baseFreq = newBaseFreq
        targetFreq = newTargetFreq
        currentFreq = newStartFreq
        phase = 0.0
        envelopeVolume = if (envelopeVolume < 0.001) 0.001 else envelopeVolume
        isReleasing = false
        waveform = newWaveform
        isLooperNote = isLooper
        frozenCutoff = cutoff
        frozenRes = res
        frozenAttack = attack
        frozenDecay = decay
        frozenSustain = sustain
        frozenRelease = release
        envState = 0
        zdfState1 = 0.0
        zdfState2 = 0.0

        attackCoeff = 1.0 - Math.exp(-1.0 / (sampleRate * (attack / 1000.0).coerceAtLeast(0.001)))
        decayCoeff = 1.0 - Math.exp(-1.0 / (sampleRate * (decay / 1000.0).coerceAtLeast(0.001)))
        releaseCoeff = Math.exp(-1.0 / (sampleRate * (release / 1000.0).coerceAtLeast(0.001)))
        active = true
    }
}

data class LooperNoteEvent(
    val timestampMs: Long,
    val isNoteOn: Boolean,
    val freq: Float,
    val wave: Int,
    val cutoff: Float,
    val res: Float,
    val attack: Float,
    val decay: Float,
    val sustain: Float,
    val release: Float,
    val octave: Int
)

data class MidiNoteEvent(
    val timestampMs: Long,
    val isNoteOn: Boolean,
    val freq: Float,
    val wave: Int,
    val octave: Int
)


class SynthEngine(private val context: Context) {
    var sampleRate: Int = 44100
    private var bufferSizeFrames: Int = 512
    private val dspEngine: DspEngine
    val drumEngine: DrumEngine
    @Volatile private var isRunning = true

    private val maxVoices = 8
    private val noteSlots = Array(maxVoices) { NoteSlot() }

    private val recordingQueue = LinkedBlockingQueue<ByteArray>()
    private var recordingWriterThread: Thread? = null

    // Live Keyboard Parameters
    @Volatile var waveformType = 3
    @Volatile var volume = 0.5f
    @Volatile var attackMs = 15f
    @Volatile var decayMs = 50f
    @Volatile var sustainLevel = 0.8f
    @Volatile var releaseMs = 200f
    @Volatile var cutoffFreq = 5000f
    @Volatile var resonance = 0.3f
    @Volatile var echoMix = 0.25f
    @Volatile var glideMs = 30f
    @Volatile var octaveShift = 0
    
    // Looper Specific Parameters
    @Volatile var looperVolume = 1.0f
    @Volatile var looperAttack = 15f
    @Volatile var looperDecay = 50f
    @Volatile var looperSustain = 0.8f
    @Volatile var looperRelease = 200f
    @Volatile var looperCutoff = 5000f
    @Volatile var looperResonance = 0.3f
    @Volatile var looperEcho = 0.25f
    @Volatile var looperGlide = 30f

    @Volatile var performanceX: Float = 0f
    @Volatile var performanceY: Float = 0f

    private var lastPlayedFreq: Float = 440f
    private var lastLooperPlayedFreq: Float = 440f

    val liveVisualizerBuffer = FloatArray(512)
    val looperVisualizerBuffer = FloatArray(512)

    val recordedNotes = java.util.concurrent.CopyOnWriteArrayList<LooperNoteEvent>()
    private var isLoopRecording = false
    private var isLoopPlaying = false
    private var loopStartTime = 0L
    private var loopDurationMs = 0L
    private var loopThread: Thread? = null

    @Volatile private var isRecording = false
    private var recordedAudioStream: FileOutputStream? = null
    private var wavFile: File? = null
    
    val recordedMidiNotes = java.util.concurrent.CopyOnWriteArrayList<MidiNoteEvent>()
    @Volatile var isMidiRecording = false
    private var midiStartTime = 0L

    private val audioTrack: AudioTrack

    init {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nativeSampleRateStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val nativeBufferSizeStr = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)

        sampleRate = nativeSampleRateStr?.toIntOrNull() ?: 44100
        bufferSizeFrames = nativeBufferSizeStr?.toIntOrNull() ?: 256

        dspEngine = DspEngine(sampleRate)
        drumEngine = DrumEngine(sampleRate)

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val safeBufferSize = maxOf(minBufferSize, bufferSizeFrames * 4)

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
            .setBufferSizeInBytes(safeBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    fun start() {
        isRunning = true
        audioTrack.play()

        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            val bufferSize = 512
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
                        decayMs = decayMs,
                        sustainLevel = sustainLevel,
                        releaseMs = releaseMs,
                        echoMix = echoMix,
                        looperEchoMix = looperEcho,
                        performanceX = performanceX,
                        performanceY = performanceY
                    )

                    val drumSample = drumEngine.processNextSample()
                    val rawMaster = (frame.masterSample + drumSample).coerceIn(-1.0f, 1.0f)
                    val shortVal = (rawMaster * Short.MAX_VALUE * 0.85f).toInt().coerceIn(-32768, 32767).toShort()

                    buffer[i] = shortVal
                    liveVisualizerBuffer[i] = frame.liveSample
                    looperVisualizerBuffer[i] = frame.looperSample

                    byteBuffer.putShort(shortVal)
                }

                if (isRecording) {
                    val recBytes = ByteArray(bufferSize * 2)
                    System.arraycopy(byteBuffer.array(), 0, recBytes, 0, recBytes.size)
                    recordingQueue.offer(recBytes)
                }

                audioTrack.write(buffer, 0, buffer.size)
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        stopLoopPlayback()
        stopBackgroundAudio()
        audioTrack.stop()
        audioTrack.release()
    }

    fun getEffectiveFrequency(baseFreq: Float, overrideOctave: Int? = null): Float {
        val octave = overrideOctave ?: octaveShift
        return baseFreq * Math.pow(2.0, octave.toDouble()).toFloat()
    }

    fun setLiveWaveform(wave: Int) {
        waveformType = wave
        for (i in 0 until maxVoices) {
            val slot = noteSlots[i]
            if (slot.active && !slot.isLooperNote) {
                slot.waveform = wave
            }
        }
    }

    fun startMidiRecording() {
        recordedMidiNotes.clear()
        isMidiRecording = true
        midiStartTime = System.currentTimeMillis()
    }

    fun stopMidiRecording() {
        isMidiRecording = false
    }

    fun noteOn(
        baseFreq: Float,
        isLooper: Boolean = false,
        wave: Int? = null,
        cutoff: Float? = null,
        res: Float? = null,
        attack: Float? = null,
        decay: Float? = null,
        sustain: Float? = null,
        release: Float? = null,
        targetOctave: Int? = null
    ) {
        val effectiveOctave = if (isLooper) targetOctave else octaveShift
        val freq = getEffectiveFrequency(baseFreq, effectiveOctave)

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
                    decay = decayMs,
                    sustain = sustainLevel,
                    release = releaseMs,
                    octave = octaveShift
                )
            )
        }
        
        if (isMidiRecording && !isLooper) {
            recordedMidiNotes.add(
                MidiNoteEvent(
                    timestampMs = System.currentTimeMillis() - midiStartTime,
                    isNoteOn = true,
                    freq = baseFreq,
                    wave = waveformType,
                    octave = octaveShift
                )
            )
        }

        var slot: NoteSlot? = null

        for (i in 0 until maxVoices) {
            val s = noteSlots[i]
            if (s.active && s.baseFreq == baseFreq && s.isLooperNote == isLooper) {
                slot = s
                break
            }
        }

        if (slot != null) {
            slot.isReleasing = false
            slot.targetFreq = freq
            slot.waveform = wave ?: waveformType
            slot.active = true
            if (slot.envelopeVolume < 0.001) slot.envelopeVolume = 0.001
            return
        }

        for (i in 0 until maxVoices) {
            val s = noteSlots[i]
            if (!s.active) {
                slot = s
                break
            }
        }

        if (slot == null) {
            var minVol = Double.MAX_VALUE
            for (i in 0 until maxVoices) {
                val s = noteSlots[i]
                if (s.isReleasing && s.envelopeVolume < minVol) {
                    minVol = s.envelopeVolume
                    slot = s
                }
            }
        }

        if (slot == null) {
            var minVol = Double.MAX_VALUE
            for (i in 0 until maxVoices) {
                val s = noteSlots[i]
                if (!s.isLooperNote && s.envelopeVolume < minVol) {
                    minVol = s.envelopeVolume
                    slot = s
                }
            }
        }

        if (slot == null) {
            var minVol = Double.MAX_VALUE
            for (i in 0 until maxVoices) {
                val s = noteSlots[i]
                if (s.envelopeVolume < minVol) {
                    minVol = s.envelopeVolume
                    slot = s
                }
            }
        }

        if (slot != null) {
            val startFreq = if (isLooper) {
                if (looperGlide > 0f) lastLooperPlayedFreq else freq
            } else {
                if (glideMs > 0f) lastPlayedFreq else freq
            }
            
            if (isLooper) lastLooperPlayedFreq = freq else lastPlayedFreq = freq

            slot.updateAndActivate(
                newBaseFreq = baseFreq,
                newTargetFreq = freq,
                newStartFreq = startFreq,
                newWaveform = wave ?: waveformType,
                isLooper = isLooper,
                cutoff = cutoff ?: cutoffFreq,
                res = res ?: resonance,
                attack = attack ?: attackMs,
                decay = decay ?: decayMs,
                sustain = sustain ?: sustainLevel,
                release = release ?: releaseMs,
                sampleRate = sampleRate
            )
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
                    decay = decayMs,
                    sustain = sustainLevel,
                    release = releaseMs,
                    octave = octaveShift
                )
            )
        }
        
        if (isMidiRecording && !isLooper) {
            recordedMidiNotes.add(
                MidiNoteEvent(
                    timestampMs = System.currentTimeMillis() - midiStartTime,
                    isNoteOn = false,
                    freq = baseFreq,
                    wave = waveformType,
                    octave = octaveShift
                )
            )
        }

        for (i in 0 until maxVoices) {
            val slot = noteSlots[i]
            if (slot.active && slot.baseFreq == baseFreq && slot.isLooperNote == isLooper && !slot.isReleasing) {
                slot.isReleasing = true
                break
            }
        }
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
        dspEngine.startExternalPlayback()
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
                            noteOn(
                                baseFreq = ev.freq,
                                isLooper = true,
                                wave = ev.wave,
                                cutoff = looperCutoff,
                                res = looperResonance,
                                attack = looperAttack,
                                decay = looperDecay,
                                sustain = looperSustain,
                                release = looperRelease,
                                targetOctave = ev.octave
                            )
                        } else {
                            noteOff(ev.freq, isLooper = true)
                        }
                        eventIndex++
                    }
                    try { Thread.sleep(1) } catch (_: Exception) {}
                }

                for (i in 0 until maxVoices) {
                    if (noteSlots[i].isLooperNote) {
                        noteSlots[i].active = false
                    }
                }
            }
        }.also { it.start() }
    }

    fun stopLoopPlayback() {
        isLoopPlaying = false
        dspEngine.stopExternalPlayback()
        loopThread?.interrupt()
        loopThread = null
        for (i in 0 until maxVoices) {
            if (noteSlots[i].isLooperNote) {
                noteSlots[i].active = false
            }
        }
    }

    fun clearLoop() {
        stopLoopPlayback()
        recordedNotes.clear()
        loopDurationMs = 0L
    }

    fun startRecording() {
        try {
            val file = File(context.cacheDir, "temp_synth_recording.wav")
            wavFile = file
            val stream = FileOutputStream(file)
            recordedAudioStream = stream
            writeWavHeader(stream, 0L)
            recordingQueue.clear()
            isRecording = true

            recordingWriterThread = Thread {
                while (isRecording || recordingQueue.isNotEmpty()) {
                    try {
                        val bytes = recordingQueue.poll(20, TimeUnit.MILLISECONDS)
                        if (bytes != null) {
                            recordedAudioStream?.write(bytes)
                        }
                    } catch (_: Exception) {}
                }
            }.apply {
                priority = Thread.MIN_PRIORITY
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopAndSaveRecordingAsync(onSaved: (File?) -> Unit) {
        if (!isRecording) {
            onSaved(wavFile)
            return
        }
        isRecording = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                var waitTries = 0
                while (recordingQueue.isNotEmpty() && waitTries < 50) {
                    Thread.sleep(10)
                    waitTries++
                }

                recordingWriterThread?.join(1500)
                recordingWriterThread = null
                
                recordedAudioStream?.flush()
                recordedAudioStream?.close()
                recordedAudioStream = null
                
                wavFile?.let { updateWavHeader(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                onSaved(wavFile)
            }
        }
    }

    fun exportRecordingToUri(context: Context, destinationUri: Uri): Boolean {
        val sourceFile = wavFile ?: File(context.cacheDir, "temp_synth_recording.wav")
        if (!sourceFile.exists()) return false

        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                sourceFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
        header[32] = (2).toByte()
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

        RandomAccessFile(file, "rw").use { raf ->
            val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

            raf.seek(4)
            buffer.clear()
            buffer.putInt(totalDataLen.toInt())
            raf.write(buffer.array())

            raf.seek(40)
            buffer.clear()
            buffer.putInt(totalAudioLen.toInt())
            raf.write(buffer.array())
        }
    }

    fun setLooperVol(vol: Float) {
        looperVolume = vol
    }

    fun loadAndPlayBackgroundAudio(context: Context, uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val pcmData = decodeAudioToPCM(context, uri)
            if (pcmData != null) {
                dspEngine.setExternalAudioBuffer(pcmData)
                dspEngine.startExternalPlayback()
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "שגיאה בפענוח קובץ השמע, ייתכן שהפורמט אינו נתמך", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun pauseBackgroundAudio() {
        dspEngine.isExternalAudioPlaying = false
    }

    fun resumeBackgroundAudio() {
        dspEngine.isExternalAudioPlaying = true
    }

    fun stopBackgroundAudio() {
        dspEngine.stopExternalPlayback()
        dspEngine.setExternalAudioBuffer(null)
    }

    fun decodeAudioToPCM(context: Context, uri: Uri): FloatArray? {    
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val channels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 1 }
            val fileSampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
            
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            
            var rawPcmData = FloatArray(1024 * 1024)
            var rawSize = 0
            
            val info = MediaCodec.BufferInfo()
            var isEOS = false
            
            while (!isEOS) {
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                var outIndex = codec.dequeueOutputBuffer(info, 10000)
                while (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val shortBuffer = outputBuffer.asShortBuffer()
                        
                        if (channels == 2) {
                            while (shortBuffer.remaining() >= 2) {
                                if (rawSize >= rawPcmData.size) {
                                    rawPcmData = rawPcmData.copyOf(rawPcmData.size * 2)
                                }
                                val left = shortBuffer.get() / 32768.0f
                                val right = shortBuffer.get() / 32768.0f
                                rawPcmData[rawSize++] = (left + right) / 2.0f
                            }
                        } else {
                            while (shortBuffer.hasRemaining()) {
                                if (rawSize >= rawPcmData.size) {
                                    rawPcmData = rawPcmData.copyOf(rawPcmData.size * 2)
                                }
                                rawPcmData[rawSize++] = shortBuffer.get() / 32768.0f
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                    outIndex = codec.dequeueOutputBuffer(info, 0)
                }
            }
            
            val decodedSamplesCount = rawSize
            if (decodedSamplesCount <= 0) return null

            val ratio = fileSampleRate.toDouble() / sampleRate.toDouble()
            val targetSize = (decodedSamplesCount / ratio).toInt()
            val resampledData = FloatArray(targetSize)
            
            for (i in 0 until targetSize) {
                val srcIdx = i * ratio
                val idxInt = srcIdx.toInt()
                val frac = (srcIdx - idxInt).toFloat()
                
                if (idxInt + 1 < decodedSamplesCount) {
                    val s0 = rawPcmData[idxInt]
                    val s1 = rawPcmData[idxInt + 1]
                    resampledData[i] = s0 + (s1 - s0) * frac
                } else if (idxInt < decodedSamplesCount) {
                    resampledData[i] = rawPcmData[idxInt]
                }
            }
            
            return resampledData
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthAppUI(engine: SynthEngine) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("synth_presets", Context.MODE_PRIVATE) }

    val defaultFrequencies = remember {
        listOf(222.00f, 299.00f, 333.00f, 355.00f, 396.00f, 444.00f, 463.00f, 477.00f)
    }
    val frequencies = remember {
        mutableStateListOf(222.00f, 299.00f, 333.00f, 355.00f, 396.00f, 444.00f, 463.00f, 477.00f)
    }
    val noteNames = listOf("דו", "רה", "מי", "פה", "סול", "לה", "סי", "אל")

    var showTuningDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf("SOUND") } // SOUND | PRESET | LOOP | PAD | DRUM

    var currentWave by remember { mutableIntStateOf(3) }
    var vol by remember { mutableFloatStateOf(0.5f) }
    var attackVal by remember { mutableFloatStateOf(15f) }
    var decayVal by remember { mutableFloatStateOf(50f) }
    var sustainVal by remember { mutableFloatStateOf(0.8f) }
    var releaseVal by remember { mutableFloatStateOf(200f) }

    var cutoffVal by remember { mutableFloatStateOf(5000f) }
    var resVal by remember { mutableFloatStateOf(0.3f) }
    var echoVal by remember { mutableFloatStateOf(0.25f) }
    var glideVal by remember { mutableFloatStateOf(30f) }

    var currentOctave by remember { mutableIntStateOf(0) }
    var isRec by remember { mutableStateOf(false) }
    var isMidiRec by remember { mutableStateOf(false) }

    var isLoopRecState by remember { mutableStateOf(false) }
    var isLoopPlayState by remember { mutableStateOf(false) }
    
    // Looper Knobs States
    var looperVolState by remember { mutableFloatStateOf(1.0f) }
    var looperCutoffState by remember { mutableFloatStateOf(5000f) }
    var looperResState by remember { mutableFloatStateOf(0.3f) }
    var looperAttackState by remember { mutableFloatStateOf(15f) }
    var looperDecayState by remember { mutableFloatStateOf(50f) }
    var looperSustainState by remember { mutableFloatStateOf(0.8f) }
    var looperReleaseState by remember { mutableFloatStateOf(200f) }
    var looperEchoState by remember { mutableFloatStateOf(0.25f) }
    var looperGlideState by remember { mutableFloatStateOf(30f) }

    // --- Drum States ---
    var drumBpmState by remember { mutableFloatStateOf(120f) }
    var drumVolState by remember { mutableFloatStateOf(0.8f) }
    var drumPlayingState by remember { mutableStateOf(false) }
    val trackVolStates = remember { List(4) { mutableFloatStateOf(1.0f) } }
    var activeLoadingTrack by remember { mutableIntStateOf(0) }
    var gridRefreshTrigger by remember { mutableLongStateOf(0L) }

    // --- 8 Pages Preset System States ---
    var selectedPresetPage by remember { mutableIntStateOf(1) }
    var activeLoadedPage by remember { mutableIntStateOf(-1) }
    var activeLoadedSlot by remember { mutableIntStateOf(-1) }

    val pageNames = remember {
        mutableStateMapOf<Int, String>().apply {
            for (p in 1..8) {
                put(p, prefs.getString("page_${p}_name", "עמוד $p") ?: "עמוד $p")
            }
        }
    }

    val presetNames = remember {
        mutableStateMapOf<String, String>().apply {
            for (p in 1..8) {
                for (s in 1..8) {
                    val key = "p_${p}_s_${s}"
                    put(key, prefs.getString("${key}_name", "פריסט $s") ?: "פריסט $s")
                }
            }
        }
    }

    var editingPageId by remember { mutableStateOf<Int?>(null) }
    var tempPageNameInput by remember { mutableStateOf("") }

    var editingPresetKey by remember { mutableStateOf<String?>(null) }
    var tempPresetNameInput by remember { mutableStateOf("") }

    val gold = Color(0xFFD4AF37)
    val darkBg = Color(0xFF0A0A0A)
    val panelBg = Color(0xFF141414)
    val panelBg2 = Color(0xFF1A1A1A)

    val createWavLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let {
            val success = engine.exportRecordingToUri(context, it)
            if (success) {
                Toast.makeText(context, "ההקלטה נשמרה בהצלחה!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "שגיאה בשמירת הקובץ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val createMidiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("audio/midi")
    ) { uri ->
        uri?.let {
            val success = MidiExporter.exportMidiToUri(context, engine.recordedMidiNotes.toList(), it)
            if (success) {
                Toast.makeText(context, "קובץ ה-MIDI נשמר בהצלחה!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "שגיאה בשמירת קובץ ה-MIDI", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val loadAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            engine.loadAndPlayBackgroundAudio(context, it)
            isLoopPlayState = true
        }
    }

    val loadDrumSampleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val trackIdx = activeLoadingTrack
            CoroutineScope(Dispatchers.IO).launch {
                val pcm = engine.decodeAudioToPCM(context, uri)
                if (pcm != null) {
                    engine.drumEngine.setSample(trackIdx, pcm)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "סאמפל נטען לערוץ ${engine.drumEngine.trackNames[trackIdx]}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "שגיאה בטעינת קובץ האודיו", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun loadPresetFromSlot(page: Int, slot: Int, showToast: Boolean = true) {
        val key = "p_${page}_s_${slot}"
        if (!prefs.getBoolean("${key}_exists", false)) {
            if (showToast) Toast.makeText(context, "פריסט $slot בעמוד $page עדיין ריק", Toast.LENGTH_SHORT).show()
            return
        }

        vol = prefs.getFloat("${key}_vol", 0.5f)
        engine.volume = vol
        attackVal = prefs.getFloat("${key}_attack", 15f)
        engine.attackMs = attackVal
        decayVal = prefs.getFloat("${key}_decay", 50f)
        engine.decayMs = decayVal
        sustainVal = prefs.getFloat("${key}_sustain", 0.8f)
        engine.sustainLevel = sustainVal
        releaseVal = prefs.getFloat("${key}_release", 200f)
        engine.releaseMs = releaseVal
        cutoffVal = prefs.getFloat("${key}_cutoff", 5000f)
        engine.cutoffFreq = cutoffVal
        resVal = prefs.getFloat("${key}_res", 0.3f)
        engine.resonance = resVal
        echoVal = prefs.getFloat("${key}_echo", 0.25f)
        engine.echoMix = echoVal
        glideVal = prefs.getFloat("${key}_glide", 30f)
        engine.glideMs = glideVal
        currentWave = prefs.getInt("${key}_wave", 3)
        engine.setLiveWaveform(currentWave)
        currentOctave = prefs.getInt("${key}_octave", 0)
        engine.octaveShift = currentOctave

        val freqsStr = prefs.getString("${key}_freqs", null)
        if (freqsStr != null) {
            val list = freqsStr.split(",").mapNotNull { it.toFloatOrNull() }
            if (list.size == frequencies.size) {
                list.forEachIndexed { i, f -> frequencies[i] = f }
            }
        }

        activeLoadedPage = page
        activeLoadedSlot = slot

        if (showToast) Toast.makeText(context, "פריסט $slot בעמוד $page נטען", Toast.LENGTH_SHORT).show()
    }

    fun savePresetToSlot(page: Int, slot: Int) {
        val key = "p_${page}_s_${slot}"
        val currentName = presetNames[key] ?: "פריסט $slot"
        prefs.edit().apply {
            putString("${key}_name", currentName)
            putFloat("${key}_vol", vol)
            putFloat("${key}_attack", attackVal)
            putFloat("${key}_decay", decayVal)
            putFloat("${key}_sustain", sustainVal)
            putFloat("${key}_release", releaseVal)
            putFloat("${key}_cutoff", cutoffVal)
            putFloat("${key}_res", resVal)
            putFloat("${key}_echo", echoVal)
            putFloat("${key}_glide", glideVal)
            putInt("${key}_wave", currentWave)
            putInt("${key}_octave", currentOctave)
            putString("${key}_freqs", frequencies.joinToString(","))
            putBoolean("${key}_exists", true)
            apply()
        }
        activeLoadedPage = page
        activeLoadedSlot = slot
        Toast.makeText(context, "פריסט $slot בעמוד $page נשמר בהצלחה!", Toast.LENGTH_SHORT).show()
    }

    val exportPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                val sb = StringBuilder()
                val page = selectedPresetPage
                val pNameHeader = pageNames[page] ?: "עמוד $page"
                sb.append("page:$page|$pNameHeader\n")
                for (s in 1..8) {
                    val key = "p_${page}_s_${s}"
                    val pName = presetNames[key] ?: "פריסט $s"
                    sb.append("preset:$s|$pName|")
                    sb.append("${prefs.getFloat("${key}_vol", 0.5f)},")
                    sb.append("${prefs.getFloat("${key}_attack", 15f)},")
                    sb.append("${prefs.getFloat("${key}_decay", 50f)},")
                    sb.append("${prefs.getFloat("${key}_sustain", 0.8f)},")
                    sb.append("${prefs.getFloat("${key}_release", 200f)},")
                    sb.append("${prefs.getFloat("${key}_cutoff", 5000f)},")
                    sb.append("${prefs.getFloat("${key}_res", 0.3f)},")
                    sb.append("${prefs.getFloat("${key}_echo", 0.25f)},")
                    sb.append("${prefs.getFloat("${key}_glide", 30f)},")
                    sb.append("${prefs.getInt("${key}_wave", 3)},")
                    sb.append("${prefs.getInt("${key}_octave", 0)}|")
                    sb.append("${prefs.getString("${key}_freqs", "")}\n")
                }
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(sb.toString().toByteArray())
                }
                Toast.makeText(context, "פריסטים של עמוד $page יוצאו בהצלחה!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "שגיאה בייצוא פריסטים", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importPresetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val lines = stream.bufferedReader().readLines()
                    val editor = prefs.edit()
                    val page = selectedPresetPage
                    for (line in lines) {
                        if (line.startsWith("page:")) {
                            val parts = line.split("|")
                            if (parts.size >= 2) {
                                val name = parts[1]
                                pageNames[page] = name
                                editor.putString("page_${page}_name", name)
                            }
                        } else if (line.startsWith("preset:")) {
                            val parts = line.split("|")
                            if (parts.size >= 4) {
                                val slot = parts[0].removePrefix("preset:").toIntOrNull() ?: continue
                                val name = parts[1]
                                val key = "p_${page}_s_${slot}"
                                presetNames[key] = name
                                editor.putString("${key}_name", name)
                                
                                val values = parts[2].split(",")
                                if (values.size >= 11) {
                                    editor.putFloat("${key}_vol", values[0].toFloatOrNull() ?: 0.5f)
                                    editor.putFloat("${key}_attack", values[1].toFloatOrNull() ?: 15f)
                                    editor.putFloat("${key}_decay", values[2].toFloatOrNull() ?: 50f)
                                    editor.putFloat("${key}_sustain", values[3].toFloatOrNull() ?: 0.8f)
                                    editor.putFloat("${key}_release", values[4].toFloatOrNull() ?: 200f)
                                    editor.putFloat("${key}_cutoff", values[5].toFloatOrNull() ?: 5000f)
                                    editor.putFloat("${key}_res", values[6].toFloatOrNull() ?: 0.3f)
                                    editor.putFloat("${key}_echo", values[7].toFloatOrNull() ?: 0.25f)
                                    editor.putFloat("${key}_glide", values[8].toFloatOrNull() ?: 30f)
                                    editor.putInt("${key}_wave", values[9].toIntOrNull() ?: 3)
                                    editor.putInt("${key}_octave", values[10].toIntOrNull() ?: 0)
                                }
                                
                                val freqs = parts[3]
                                editor.putString("${key}_freqs", freqs)
                                editor.putBoolean("${key}_exists", true)
                            }
                        }
                    }
                    editor.apply()
                }
                loadPresetFromSlot(selectedPresetPage, 1, showToast = false)
                Toast.makeText(context, "פריסטים יובאו בהצלחה לעמוד $selectedPresetPage!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "שגיאה בייבוא פריסטים", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (prefs.getBoolean("p_1_exists", false) && !prefs.getBoolean("p_1_s_1_exists", false)) {
            val editor = prefs.edit()
            for (i in 1..8) {
                val oldKey = "p_$i"
                val newKey = "p_1_s_$i"
                if (prefs.getBoolean("${oldKey}_exists", false)) {
                    editor.putString("${newKey}_name", prefs.getString("${oldKey}_name", "Preset $i"))
                    editor.putFloat("${newKey}_vol", prefs.getFloat("${oldKey}_vol", 0.5f))
                    editor.putFloat("${newKey}_attack", prefs.getFloat("${oldKey}_attack", 15f))
                    editor.putFloat("${newKey}_decay", prefs.getFloat("${oldKey}_decay", 50f))
                    editor.putFloat("${newKey}_sustain", prefs.getFloat("${oldKey}_sustain", 0.8f))
                    editor.putFloat("${newKey}_release", prefs.getFloat("${oldKey}_release", 200f))
                    editor.putFloat("${newKey}_cutoff", prefs.getFloat("${oldKey}_cutoff", 5000f))
                    editor.putFloat("${newKey}_res", prefs.getFloat("${oldKey}_res", 0.3f))
                    editor.putFloat("${newKey}_echo", prefs.getFloat("${oldKey}_echo", 0.25f))
                    editor.putFloat("${newKey}_glide", prefs.getFloat("${oldKey}_glide", 30f))
                    editor.putInt("${newKey}_wave", prefs.getInt("${oldKey}_wave", 3))
                    editor.putInt("${newKey}_octave", prefs.getInt("${oldKey}_octave", 0))
                    editor.putString("${newKey}_freqs", prefs.getString("${oldKey}_freqs", ""))
                    editor.putBoolean("${newKey}_exists", true)
                }
            }
            editor.apply()
        }
        loadPresetFromSlot(1, 1, showToast = false)
    }

    var renderTrigger by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            renderTrigger = System.currentTimeMillis()
            gridRefreshTrigger = engine.drumEngine.currentStep.toLong()
        }
    }

    if (editingPageId != null) {
        AlertDialog(
            onDismissRequest = { editingPageId = null },
            title = { Text("ערוך שם עמוד $editingPageId", color = gold, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempPageNameInput,
                    onValueChange = { tempPageNameInput = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    editingPageId?.let { id ->
                        pageNames[id] = tempPageNameInput
                        prefs.edit().putString("page_${id}_name", tempPageNameInput).apply()
                    }
                    editingPageId = null
                }) { Text("שמור") }
            },
            dismissButton = {
                OutlinedButton(onClick = { editingPageId = null }) { Text("ביטול") }
            },
            containerColor = panelBg2
        )
    }

    if (editingPresetKey != null) {
        AlertDialog(
            onDismissRequest = { editingPresetKey = null },
            title = { Text("ערוך שם פריסט", color = gold, fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempPresetNameInput,
                    onValueChange = { tempPresetNameInput = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    editingPresetKey?.let { key ->
                        presetNames[key] = tempPresetNameInput
                        prefs.edit().putString("${key}_name", tempPresetNameInput).apply()
                    }
                    editingPresetKey = null
                }) { Text("שמור") }
            },
            dismissButton = {
                OutlinedButton(onClick = { editingPresetKey = null }) { Text("ביטול") }
            },
            containerColor = panelBg2
        )
    }

    if (showTuningDialog) {
        val freqTexts = remember {
            mutableStateListOf(*frequencies.map { it.toString() }.toTypedArray())
        }

        AlertDialog(
            onDismissRequest = { showTuningDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("כיוון תדרים (Hz)", fontSize = 14.sp, color = gold, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = {
                            defaultFrequencies.forEachIndexed { i, f ->
                                frequencies[i] = f
                                freqTexts[i] = f.toString()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) { Text("איפוס", color = gold, fontSize = 9.sp) }
                }
            },
            text = {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    itemsIndexed(frequencies) { index, _ ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(noteNames[index], color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = freqTexts[index],
                                onValueChange = { newValue ->
                                    freqTexts[index] = newValue
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
            containerColor = panelBg2
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SIREN", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { showTuningDialog = true },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                ) {
                    Text("⚙️ תדרים", color = gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (isMidiRec) {
                            isMidiRec = false
                            engine.stopMidiRecording()
                            createMidiLauncher.launch("Siren_MIDI_${System.currentTimeMillis()}.mid")
                        } else {
                            engine.startMidiRecording()
                            isMidiRec = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMidiRec) Color(0xFF2979FF) else panelBg2
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(modifier = Modifier.size(5.dp).background(if (isMidiRec) Color.White else Color(0xFF2979FF), shape = CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text(if (isMidiRec) "שמור MIDI" else "MIDI", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (isRec) {
                            engine.stopAndSaveRecordingAsync { file ->
                                isRec = false
                                file?.let {
                                    val defaultFileName = "Siren_Recording_${System.currentTimeMillis()}.wav"
                                    createWavLauncher.launch(defaultFileName)
                                }
                            }
                        } else {
                            engine.startRecording()
                            isRec = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRec) Color(0xFFFF1744) else panelBg2
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Box(modifier = Modifier.size(5.dp).background(if (isRec) Color.White else Color.Red, shape = CircleShape))
                    Spacer(Modifier.width(3.dp))
                    Text(if (isRec) "שמור WAV" else "WAV", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .background(panelBg, shape = RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF2A2A2A), shape = RoundedCornerShape(8.dp))
                .padding(4.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val dummy = renderTrigger
                val w = size.width
                val h = size.height
                val halfH = h / 2f

                val gridColor = Color(0xFF1F1F1F)
                for (i in 1 until 8) {
                    val x = w * (i.toFloat() / 8)
                    drawLine(gridColor, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1f)
                }
                for (i in 1 until 4) {
                    val y = h * (i.toFloat() / 4)
                    drawLine(gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
                }
                drawLine(Color(0xFF2A2A2A), start = Offset(0f, halfH), end = Offset(w, halfH), strokeWidth = 1.5f)

                val livePath = Path()
                val liveCenterY = halfH / 2f
                val liveStep = w / engine.liveVisualizerBuffer.size
                engine.liveVisualizerBuffer.forEachIndexed { i, sample ->
                    val x = i * liveStep
                    val y = liveCenterY + (sample * (halfH / 2f) * 0.85f)
                    if (i == 0) livePath.moveTo(x, y) else livePath.lineTo(x, y)
                }
                drawPath(livePath, gold, style = Stroke(width = 2f))

                val looperPath = Path()
                val looperCenterY = halfH + (halfH / 2f)
                val looperStep = w / engine.looperVisualizerBuffer.size
                engine.looperVisualizerBuffer.forEachIndexed { i, sample ->
                    val x = i * looperStep
                    val y = looperCenterY + (sample * (halfH / 2f) * 0.85f)
                    if (i == 0) looperPath.moveTo(x, y) else looperPath.lineTo(x, y)
                }
                drawPath(looperPath, Color.White.copy(alpha = 0.7f), style = Stroke(width = 2f))
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text("LIVE SCOPE", color = gold.copy(alpha = 0.8f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text("LOOP SCOPE", color = Color.White.copy(alpha = 0.6f), fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(panelBg, shape = RoundedCornerShape(8.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val tabs = listOf("SOUND", "PRESET", "LOOP", "PAD", "DRUM")
            tabs.forEach { title ->
                val isSelected = selectedTab == title
                Button(
                    onClick = { selectedTab = title },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) panelBg2 else Color.Transparent
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        title,
                        color = if (isSelected) gold else Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(panelBg, shape = RoundedCornerShape(10.dp))
                .padding(6.dp)
        ) {
            when (selectedTab) {
                // --- SOUND TAB ---
                "SOUND" -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (currentOctave > -2) {
                                    currentOctave--
                                    engine.octaveShift = currentOctave
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) { Text("-1", fontSize = 10.sp, color = Color.White) }

                        Spacer(Modifier.width(8.dp))
                        Text("Oct: $currentOctave", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = {
                                if (currentOctave < 2) {
                                    currentOctave++
                                    engine.octaveShift = currentOctave
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) { Text("+1", fontSize = 10.sp, color = Color.White) }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val waves = listOf("Sine", "Square", "Triangle", "Saw", "Noise")
                        waves.forEachIndexed { index, name ->
                            FilterChip(
                                selected = currentWave == index,
                                onClick = {
                                    currentWave = index
                                    engine.setLiveWaveform(index)
                                },
                                label = { Text(name, fontSize = 8.sp, maxLines = 1) },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 1.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = gold,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            SynthKnob("Volume", "${(vol * 100).toInt()}%", vol, 0f..1f, gold) { vol = it; engine.volume = it }
                            SynthKnob("Attack", "${attackVal.toInt()}ms", attackVal, 5f..500f, gold) { attackVal = it; engine.attackMs = it }
                            SynthKnob("Decay", "${decayVal.toInt()}ms", decayVal, 5f..1000f, gold) { decayVal = it; engine.decayMs = it }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            SynthKnob("Sustain", "${(sustainVal * 100).toInt()}%", sustainVal, 0f..1f, gold) { sustainVal = it; engine.sustainLevel = it }
                            SynthKnob("Release", "${releaseVal.toInt()}ms", releaseVal, 20f..2000f, gold) { releaseVal = it; engine.releaseMs = it }
                            SynthKnob("Cutoff", "${cutoffVal.toInt()}Hz", cutoffVal, 200f..12000f, gold) { cutoffVal = it; engine.cutoffFreq = it }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            SynthKnob("Resonance", "${(resVal * 100).toInt()}%", resVal, 0f..1f, gold) { resVal = it; engine.resonance = it }
                            SynthKnob("Echo Mix", "${(echoVal * 100).toInt()}%", echoVal, 0f..1f, gold) { echoVal = it; engine.echoMix = it }
                            SynthKnob("Glide", "${glideVal.toInt()}ms", glideVal, 0f..200f, gold) { glideVal = it; engine.glideMs = it }
                        }
                    }
                }

                // --- PRESET TAB ---
                "PRESET" -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("פריסטים 8 חריצים", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        for (p in 1..8) {
                            val isPageSelected = selectedPresetPage == p
                            Button(
                                onClick = { selectedPresetPage = p },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPageSelected) gold else panelBg2
                                ),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "$p",
                                    color = if (isPageSelected) Color.Black else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    val currentPName = pageNames[selectedPresetPage] ?: "עמוד $selectedPresetPage"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "עמוד $selectedPresetPage: $currentPName",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedButton(
                            onClick = {
                                editingPageId = selectedPresetPage
                                tempPageNameInput = currentPName
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(22.dp)
                        ) {
                            Text("ערוך שם עמוד", fontSize = 8.sp, color = gold)
                        }
                    }

                    val rows = (1..8).chunked(2)
                    rows.forEach { rowSlots ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rowSlots.forEach { slot ->
                                val key = "p_${selectedPresetPage}_s_${slot}"
                                val pName = presetNames[key] ?: "פריסט $slot"
                                val isThisSlotLoaded = (activeLoadedPage == selectedPresetPage && activeLoadedSlot == slot)

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(if (isThisSlotLoaded) gold.copy(alpha = 0.2f) else panelBg2, RoundedCornerShape(6.dp))
                                        .border(1.dp, if (isThisSlotLoaded) gold else Color(0xFF2A2A2A), RoundedCornerShape(6.dp))
                                        .padding(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "$slot. $pName",
                                            color = if (isThisSlotLoaded) gold else Color.White,
                                            fontSize = 9.sp,
                                            maxLines = 1,
                                            modifier = Modifier.weight(1f),
                                            fontWeight = if (isThisSlotLoaded) FontWeight.Bold else FontWeight.Normal
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Button(
                                                onClick = { loadPresetFromSlot(selectedPresetPage, slot) },
                                                colors = ButtonDefaults.buttonColors(containerColor = if (isThisSlotLoaded) gold else Color(0xFFD4AF37)),
                                                contentPadding = PaddingValues(2.dp),
                                                modifier = Modifier.height(22.dp).width(28.dp)
                                            ) { Text("טען", fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Bold) }

                                            Button(
                                                onClick = { savePresetToSlot(selectedPresetPage, slot) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                                                contentPadding = PaddingValues(2.dp),
                                                modifier = Modifier.height(22.dp).width(30.dp)
                                            ) { Text("שמור", fontSize = 8.sp, color = Color.White) }

                                            Button(
                                                onClick = {
                                                    editingPresetKey = key
                                                    tempPresetNameInput = pName
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555555)),
                                                contentPadding = PaddingValues(2.dp),
                                                modifier = Modifier.height(22.dp).width(28.dp)
                                            ) { Text("ערוך", fontSize = 8.sp, color = Color.White) }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { importPresetLauncher.launch("application/json") },
                            modifier = Modifier.weight(1f).height(28.dp),
                            contentPadding = PaddingValues(2.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                        ) {
                            Text("ייבוא עמוד מ-JSON", color = gold, fontSize = 9.sp)
                        }
                        Button(
                            onClick = { exportPresetLauncher.launch("Siren_Page${selectedPresetPage}_${System.currentTimeMillis()}.json") },
                            colors = ButtonDefaults.buttonColors(containerColor = gold),
                            modifier = Modifier.weight(1f).height(28.dp),
                            contentPadding = PaddingValues(2.dp)
                        ) {
                            Text("ייצוא עמוד ל-JSON", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // --- LOOP TAB ---
                "LOOP" -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            SynthKnob("Volume", "${(looperVolState * 100).toInt()}%", looperVolState, 0f..1f, gold) { looperVolState = it; engine.looperVolume = it; engine.setLooperVol(it) }
                            SynthKnob("Cutoff", "${looperCutoffState.toInt()}Hz", looperCutoffState, 200f..12000f, gold) { looperCutoffState = it; engine.looperCutoff = it }
                            SynthKnob("Resonance", "${(looperResState * 100).toInt()}%", looperResState, 0f..1f, gold) { looperResState = it; engine.looperResonance = it }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            SynthKnob("Attack", "${looperAttackState.toInt()}ms", looperAttackState, 5f..500f, gold) { looperAttackState = it; engine.looperAttack = it }
                            SynthKnob("Decay", "${looperDecayState.toInt()}ms", looperDecayState, 5f..1000f, gold) { looperDecayState = it; engine.looperDecay = it }
                            SynthKnob("Sustain", "${(looperSustainState * 100).toInt()}%", looperSustainState, 0f..1f, gold) { looperSustainState = it; engine.looperSustain = it }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            SynthKnob("Release", "${looperReleaseState.toInt()}ms", looperReleaseState, 20f..2000f, gold) { looperReleaseState = it; engine.looperRelease = it }
                            SynthKnob("Echo Mix", "${(looperEchoState * 100).toInt()}%", looperEchoState, 0f..1f, gold) { looperEchoState = it; engine.looperEcho = it }
                            SynthKnob("Glide", "${looperGlideState.toInt()}ms", looperGlideState, 0f..200f, gold) { looperGlideState = it; engine.looperGlide = it }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                colors = ButtonDefaults.buttonColors(containerColor = if (isLoopRecState) Color(0xFFFF5252) else panelBg2),
                                modifier = Modifier.weight(1f).height(38.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text(if (isLoopRecState) "עצור הקלטה" else "הקלט לופ", fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                            Button(
                                onClick = {
                                    if (isLoopPlayState) {
                                        engine.stopLoopPlayback()
                                        engine.pauseBackgroundAudio()
                                        isLoopPlayState = false
                                    } else {
                                        engine.startLoopPlayback()
                                        engine.resumeBackgroundAudio()
                                        isLoopPlayState = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (isLoopPlayState) Color(0xFF00C853) else panelBg2),
                                modifier = Modifier.weight(1f).height(38.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text(if (isLoopPlayState) "עצור ניגון" else "נגן לופ", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { loadAudioLauncher.launch("audio/*") },
                                modifier = Modifier.weight(1f).height(38.dp),
                                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold)),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("טען שמע", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold) }

                            OutlinedButton(
                                onClick = {
                                    engine.clearLoop()
                                    engine.stopBackgroundAudio()
                                    isLoopRecState = false
                                    isLoopPlayState = false
                                },
                                modifier = Modifier.weight(1f).height(38.dp),
                                border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.Gray)),
                                contentPadding = PaddingValues(0.dp)
                            ) { Text("נקה לופר", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                
                // --- PAD TAB ---
                "PAD" -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("LIVE PERFORMANCE PAD", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text("משפיע על נגינה חיה בלבד. שחרר לחזרה אוטומטית למרכז.", color = Color.Gray, fontSize = 8.sp)
                    Spacer(Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF0D0D0D), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        engine.performanceX = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        engine.performanceY = 1f - (offset.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    },
                                    onDragEnd = {
                                        engine.performanceX = 0f
                                        engine.performanceY = 0f
                                    },
                                    onDragCancel = {
                                        engine.performanceX = 0f
                                        engine.performanceY = 0f
                                    },
                                    onDrag = { change, _ ->
                                        engine.performanceX = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                        engine.performanceY = 1f - (change.position.y / size.height.toFloat()).coerceIn(0f, 1f)
                                    }
                                )
                            }
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dummy = renderTrigger
                            val w = size.width
                            val h = size.height

                            val gridColor = Color(0xFF1F1F1F)
                            for (i in 1..4) {
                                drawLine(gridColor, start = Offset(w * (i / 5f), 0f), end = Offset(w * (i / 5f), h))
                                drawLine(gridColor, start = Offset(0f, h * (i / 5f)), end = Offset(w, h * (i / 5f)))
                            }
                            
                            drawLine(Color(0xFF2A2A2A), start = Offset(0f, h), end = Offset(w, h), strokeWidth = 2f)
                            drawLine(Color(0xFF2A2A2A), start = Offset(0f, 0f), end = Offset(0f, h), strokeWidth = 2f)

                            val cursorX = engine.performanceX * w
                            val cursorY = (1f - engine.performanceY) * h

                            drawCircle(
                                color = gold.copy(alpha = 0.2f),
                                radius = 40f,
                                center = Offset(cursorX, cursorY)
                            )
                            drawCircle(
                                color = gold,
                                radius = 16f,
                                center = Offset(cursorX, cursorY),
                                style = Stroke(width = 3f)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 3f,
                                center = Offset(cursorX, cursorY)
                            )
                        }

                        Text("LFO RATE", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
                        Text("Resonance", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp).rotate(-90f))
                    }
                }

                // --- DRUM TAB ---
                "DRUM" -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Drum Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SPARTAN DRUM MACHINE", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = {
                                    drumPlayingState = !drumPlayingState
                                    engine.drumEngine.isPlaying = drumPlayingState
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (drumPlayingState) Color(0xFF00C853) else Color(0xFFFF5252)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text(if (drumPlayingState) "נגן תופים" else "עצור תופים", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Knobs for Drum Master & BPM
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        SynthKnob("Drum Vol", "${(drumVolState * 100).toInt()}%", drumVolState, 0f..1f, gold) {
                            drumVolState = it
                            engine.drumEngine.masterVolume = it
                        }
                        SynthKnob("BPM", "${drumBpmState.toInt()}", drumBpmState, 60f..200f, gold) {
                            drumBpmState = it
                            engine.drumEngine.bpm = it
                        }
                    }

                    // Individual track volumes
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        for (t in 0 until 4) {
                            val name = engine.drumEngine.trackNames[t].take(4)
                            SynthKnob(
                                label = name,
                                valueDisplay = "${(trackVolStates[t].floatValue * 100).toInt()}%",
                                value = trackVolStates[t].floatValue,
                                valueRange = 0f..1f,
                                accentColor = gold
                            ) {
                                trackVolStates[t].floatValue = it
                                engine.drumEngine.trackVolumes[t] = it
                            }
                        }
                    }

                    // 4 Tracks Step Sequencer Grid
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        val currentActiveStep = remember(gridRefreshTrigger) { engine.drumEngine.currentStep }

                        for (t in 0 until 4) {
                            val trackName = engine.drumEngine.trackNames[t]
                            val isSampleLoaded = engine.drumEngine.drumSamples[t] != null

                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().height(20.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "${t + 1}. $trackName",
                                            color = if (isSampleLoaded) gold else Color.Gray,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        OutlinedButton(
                                            onClick = {
                                                activeLoadingTrack = t
                                                loadDrumSampleLauncher.launch("audio/*")
                                            },
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                            modifier = Modifier.height(18.dp)
                                        ) {
                                            Text(if (isSampleLoaded) "החלף" else "טעון סאמפל", fontSize = 7.sp, color = gold)
                                        }
                                    }
                                }

                                Spacer(Modifier.height(1.dp))

                                // 16 Steps row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    for (s in 0 until 16) {
                                        val isActive = engine.drumEngine.grid[t][s]
                                        val isCurrentStep = (drumPlayingState && s == currentActiveStep)

                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(26.dp)
                                                .background(
                                                    color = when {
                                                        isActive && isCurrentStep -> Color.White
                                                        isActive -> gold
                                                        isCurrentStep -> Color(0xFF333333)
                                                        else -> panelBg2
                                                    },
                                                    shape = RoundedCornerShape(3.dp)
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isCurrentStep) gold else Color(0xFF2A2A2A),
                                                    shape = RoundedCornerShape(3.dp)
                                                )
                                                .clickable {
                                                    engine.drumEngine.grid[t][s] = !isActive
                                                    gridRefreshTrigger = System.currentTimeMillis()
                                                }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // --- MICROTONAL KEYBOARD ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            frequencies.forEachIndexed { index, freq ->
                var isPressed by remember { mutableStateOf(false) }
                Card(
                    shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp, topStart = 4.dp, topEnd = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPressed) gold else Color(0xFFE8E8E8)
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text(
                                text = noteNames[index],
                                color = if (isPressed) Color.Black else Color(0xFF1A1A1A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${engine.getEffectiveFrequency(freq).toInt()}Hz",
                                color = if (isPressed) Color.Black.copy(alpha = 0.7f) else Color(0xFF555555),
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SynthKnob(
    label: String,
    valueDisplay: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    accentColor: Color = Color(0xFFD4AF37),
    onValueChange: (Float) -> Unit
) {
    var showInputDialog by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf(value.toString()) }
    var initialValue by remember { mutableFloatStateOf(value) }
    var totalDragY by remember { mutableFloatStateOf(0f) }

    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = {
                Text("הזנת ערך עבור $label", fontSize = 13.sp, color = accentColor, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("הכנס ערך בין ${valueRange.start} ל-${valueRange.endInclusive}:", color = Color.White, fontSize = 10.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 11.sp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    textInput.toFloatOrNull()?.let { inputVal ->
                        onValueChange(inputVal.coerceIn(valueRange))
                    }
                    showInputDialog = false
                }) { Text("אישור") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showInputDialog = false }) { Text("ביטול") }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(2.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))

        Box(
            modifier = Modifier
                .size(52.dp)
                .pointerInput(valueRange) {
                    detectDragGestures(
                        onDragStart = { 
                            initialValue = value
                            totalDragY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalDragY += dragAmount.y
                            val span = valueRange.endInclusive - valueRange.start
                            val sensitivity = span / 200f 
                            val newValue = (initialValue - totalDragY * sensitivity).coerceIn(valueRange)
                            onValueChange(newValue)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                val center = center

                drawCircle(color = Color(0xFF1F1F1F), radius = radius, center = center)
                drawCircle(color = Color(0xFF2A2A2A), radius = radius, center = center, style = Stroke(width = 1.5f))

                val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val angleDegrees = 135f + fraction * 270f
                val angleRadians = Math.toRadians(angleDegrees.toDouble())

                val lineLength = radius * 0.65f
                val endX = center.x + (lineLength * kotlin.math.cos(angleRadians)).toFloat()
                val endY = center.y + (lineLength * kotlin.math.sin(angleRadians)).toFloat()

                drawLine(
                    color = accentColor,
                    start = center,
                    end = Offset(endX, endY),
                    strokeWidth = 2.5f
                )
            }
        }

        Spacer(Modifier.height(1.dp))

        Text(
            text = valueDisplay,
            color = accentColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = {
                    textInput = String.format("%.2f", value)
                    showInputDialog = true
                })
            }
        )
    }
}
