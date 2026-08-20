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
import androidx.activity.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    var frozenSustain: Float = 0.8f
    var frozenRelease: Float = 200f

    var zdfState1: Double = 0.0
    var zdfState2: Double = 0.0
    var smoothedCutoff: Float = 5000f
    var smoothedRes: Float = 0.3f

    var attackCoeff: Double = 0.0
    var releaseCoeff: Double = 0.0

    // נעילה מקומית למניעת Race Condition בעת הפעלה מחדש של קול תפוס
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
        frozenSustain = sustain
        frozenRelease = release
        zdfState1 = 0.0
        zdfState2 = 0.0

        attackCoeff = 1.0 - Math.exp(-1.0 / (sampleRate * (attack / 1000.0).coerceAtLeast(0.001)))
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
    @Volatile private var isRunning = true

    private val maxVoices = 8
    private val noteSlots = Array(maxVoices) { NoteSlot() }

    private val recordingQueue = LinkedBlockingQueue<ByteArray>()
    private var recordingWriterThread: Thread? = null

    @Volatile var waveformType = 3
    @Volatile var volume = 0.5f
    @Volatile var looperVolume = 1.0f
    @Volatile var attackMs = 15f
    @Volatile var sustainLevel = 0.8f
    @Volatile var releaseMs = 200f
    @Volatile var cutoffFreq = 5000f
    @Volatile var resonance = 0.3f
    @Volatile var echoMix = 0.25f
    @Volatile var glideMs = 30f
    @Volatile var octaveShift = 0
    
    @Volatile var performanceX: Float = 0f
    @Volatile var performanceY: Float = 0f

    private var lastPlayedFreq: Float = 440f

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
    
    // MIDI Recording Variables
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
                        sustainLevel = sustainLevel,
                        releaseMs = releaseMs,
                        echoMix = echoMix,
                        performanceX = performanceX,
                        performanceY = performanceY
                    )

                    val rawMaster = frame.masterSample
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
            val startFreq = if (!isLooper && glideMs > 0f) lastPlayedFreq else freq
            if (!isLooper) lastPlayedFreq = freq

            slot.updateAndActivate(
                newBaseFreq = baseFreq,
                newTargetFreq = freq,
                newStartFreq = startFreq,
                newWaveform = wave ?: waveformType,
                isLooper = isLooper,
                cutoff = cutoff ?: cutoffFreq,
                res = res ?: resonance,
                attack = attack ?: attackMs,
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
                                cutoff = ev.cutoff,
                                res = ev.res,
                                attack = ev.attack,
                                sustain = ev.sustain,
                                release = ev.release,
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

    // תיקון: העברת עצירת ההקלטה וסגירת הקובץ אל מחוץ ל-Main Thread כדי למנוע UI Jank
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

    // תיקון: שימוש ב-ByteBuffer לעדכון כותרת WAV בצורה בטוחה ותקינה
    private fun updateWavHeader(file: File) {
        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36

        RandomAccessFile(file, "rw").use { raf ->
            val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

            // עדכון גודל ה-RIFF (בייטים 4-7)
            raf.seek(4)
            buffer.clear()
            buffer.putInt(totalDataLen.toInt())
            raf.write(buffer.array())

            // עדכון גודל נתוני האודיו הכלליים בתוך ה-Data Chunk (בייטים 40-43)
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

    /**
     * תיקון דליפת זיכרון: שימוש בבלוק try-finally לשחרור בטוח של ה-MediaCodec וה-MediaExtractor גם בזמן שגיאה.
     */
    private fun decodeAudioToPCM(context: Context, uri: Uri): FloatArray? {    
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

    var currentOctave by remember { mutableIntStateOf(0) }
    var isRec by remember { mutableStateOf(false) }
    var isMidiRec by remember { mutableStateOf(false) }

    var isLoopRecState by remember { mutableStateOf(false) }
    var isLoopPlayState by remember { mutableStateOf(false) }
    var looperVolState by remember { mutableFloatStateOf(1.0f) }

    var selectedPresetSlot by remember { mutableIntStateOf(1) }

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
            val success = MidiExporter.exportMidiToUri(context, it, engine.recordedMidiNotes.toList())
            if (success) {
                Toast.makeText(context, "קובץ ה-MIDI נשמר בהצלחה!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "שגיאה בשמירת קובץ ה-MIDI", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- הוספת הלאונצ'ר החדש לבחירת קובץ אודיו ---
    val loadAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            engine.loadAndPlayBackgroundAudio(context, it)
            isLoopPlayState = true
        }
    }

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
        currentWave = prefs.getInt("p_${slot}_wave", 3)
        engine.setLiveWaveform(currentWave)
        currentOctave = prefs.getInt("p_${slot}_octave", 0)
        engine.octaveShift = currentOctave

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
            putInt("p_${slot}_wave", currentWave)
            putInt("p_${slot}_octave", currentOctave)
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
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- HEADER ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("SIREN", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { showTuningDialog = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                ) {
                    Text("⚙️ תדרים", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).background(if (isMidiRec) Color.White else Color(0xFF2979FF), shape = CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isMidiRec) "שמור MIDI" else "MIDI", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Box(modifier = Modifier.size(6.dp).background(if (isRec) Color.White else Color.Red, shape = CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isRec) "שמור WAV" else "WAV", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- OSCILLOSCOPE ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(panelBg, shape = RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF2A2A2A), shape = RoundedCornerShape(10.dp))
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
                drawPath(livePath, gold, style = Stroke(width = 2.5f))

                val looperPath = Path()
                val looperCenterY = halfH + (halfH / 2f)
                val looperStep = w / engine.looperVisualizerBuffer.size
                engine.looperVisualizerBuffer.forEachIndexed { i, sample ->
                    val x = i * looperStep
                    val y = looperCenterY + (sample * (halfH / 2f) * 0.85f)
                    if (i == 0) looperPath.moveTo(x, y) else looperPath.lineTo(x, y)
                }
                drawPath(looperPath, Color.White.copy(alpha = 0.7f), style = Stroke(width = 2.5f))
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

        Spacer(Modifier.height(6.dp))

        // --- TABS ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(panelBg, shape = RoundedCornerShape(8.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val tabs = listOf("🎛️ סאונד", "🎚️ פילטר", "🔄 לופר", "🚀 PERFORMANCE")
            tabs.forEachIndexed { index, title ->
                Button(
                    onClick = { selectedTab = index },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTab == index) panelBg2 else Color.Transparent
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        title,
                        color = if (selectedTab == index) gold else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // --- TAB CONTENT ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(panelBg, shape = RoundedCornerShape(10.dp))
                .padding(8.dp)
        ) {
            when (selectedTab) {
                0 -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("סוג גל (נגינה חיה)", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val waves = listOf("Sine", "Square", "Triangle", "Saw", "Noise")
                            itemsIndexed(waves) { index, name ->
                                FilterChip(
                                    selected = currentWave == index,
                                    onClick = {
                                        currentWave = index
                                        engine.setLiveWaveform(index)
                                    },
                                    label = { Text(name, fontSize = 10.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = gold,
                                        selectedLabelColor = Color.Black
                                    )
                                )
                            }
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
                                modifier = Modifier.height(28.dp)
                            ) { Text("-1 Oct", fontSize = 9.sp, color = Color.White) }

                            Text(" Oct: $currentOctave ", color = gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                            OutlinedButton(
                                onClick = {
                                    if (currentOctave < 2) {
                                        currentOctave++
                                        engine.octaveShift = currentOctave
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) { Text("+1 Oct", fontSize = 9.sp, color = Color.White) }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            (1..4).forEach { slot ->
                                Button(
                                    onClick = {
                                        selectedPresetSlot = slot
                                        loadPresetFromSlot(slot)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selectedPresetSlot == slot) gold else panelBg2
                                    ),
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Text("$slot", fontSize = 10.sp, color = if (selectedPresetSlot == slot) Color.Black else Color.White)
                                }
                            }
                            Button(
                                onClick = { savePresetToSlot(selectedPresetSlot) },
                                colors = ButtonDefaults.buttonColors(containerColor = gold),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("שמור", fontSize = 9.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    SynthSlider("ווליום נגינה", "${(vol * 100).toInt()}%", vol, accentColor = gold) { vol = it; engine.volume = it }
                    SynthSlider("Attack (התקפה)", "${attackVal.toInt()}ms", attackVal, 5f..500f, gold) { attackVal = it; engine.attackMs = it }
                    SynthSlider("Sustain (החזקה)", "${(sustainVal * 100).toInt()}%", sustainVal, 0f..1f, gold) { sustainVal = it; engine.sustainLevel = it }
                    SynthSlider("Release (דעיכה)", "${releaseVal.toInt()}ms", releaseVal, 20f..2000f, gold) { releaseVal = it; engine.releaseMs = it }
                }

                1 -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceAround) {
                    SynthSlider("Cutoff (חיתוך תדרים)", "${cutoffVal.toInt()}Hz", cutoffVal, 200f..12000f, gold) { cutoffVal = it; engine.cutoffFreq = it }
                    SynthSlider("Resonance (תהודה)", "${(resVal * 100).toInt()}%", resVal, 0f..1f, gold) { resVal = it; engine.resonance = it }
                    SynthSlider("Echo Mix (דיליי)", "${(echoVal * 100).toInt()}%", echoVal, 0f..1f, gold) { echoVal = it; engine.echoMix = it }
                    SynthSlider("Glide (פליסנדו)", "${glideVal.toInt()}ms", glideVal, 0f..200f, gold) { glideVal = it; engine.glideMs = it }
                }

                2 -> Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceAround) {
                    SynthSlider("עוצמת הלופר", "${(looperVolState * 100).toInt()}%", looperVolState, accentColor = gold) {
                        looperVolState = it
                        engine.setLooperVol(it) // קורא לפונקציה המעודכנת לעדכון גם של מנוע ה-DSP וגם של ה-MediaPlayer
                    }

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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLoopRecState) Color(0xFFFF5252) else panelBg2
                            ),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text(if (isLoopRecState) "עצור הקלטה" else "🔴 הקלט לופ", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (isLoopPlayState) {
                                    engine.stopLoopPlayback()
                                    engine.pauseBackgroundAudio() // השהיית קובץ הרקע
                                    isLoopPlayState = false
                                } else {
                                    engine.startLoopPlayback()
                                    engine.resumeBackgroundAudio() // המשך ניגון קובץ הרקע
                                    isLoopPlayState = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLoopPlayState) Color(0xFF00C853) else panelBg2
                            ),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text(if (isLoopPlayState) "עצור ניגון" else "▶️ נגן לופ", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // --- הכפתור לטעינת קובץ שמע חיצוני ---
                    OutlinedButton(
                        onClick = { loadAudioLauncher.launch("audio/*") },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(gold))
                    ) {
                        Text("📂 טען קובץ שמע (WAV/MP3)", color = gold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            engine.clearLoop()
                            engine.stopBackgroundAudio() // איפוס ועצירת קובץ הרקע
                            isLoopRecState = false
                            isLoopPlayState = false
                        },
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Color.Gray))
                    ) {
                        Text("🗑️ נקה לופר ורקע", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                
                3 -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("LIVE PERFORMANCE PAD", color = gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("משפיע על נגינה חיה בלבד. שחרר לחזרה אוטומטית למרכז.", color = Color.Gray, fontSize = 9.sp)
                    Spacer(Modifier.height(8.dp))

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
                                radius = 50f,
                                center = Offset(cursorX, cursorY)
                            )
                            drawCircle(
                                color = gold,
                                radius = 20f,
                                center = Offset(cursorX, cursorY),
                                style = Stroke(width = 4f)
                            )
                            drawCircle(
                                color = Color.White,
                                radius = 4f,
                                center = Offset(cursorX, cursorY)
                            )
                        }

                        Text("LFO RATE (קצב)", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp))
                        Text("Resonance (תהודה)", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp).rotate(-90f))
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // --- KEYBOARD ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(155.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Text(
                                text = noteNames[index],
                                color = if (isPressed) Color.Black else Color(0xFF1A1A1A),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "${engine.getEffectiveFrequency(freq).toInt()}Hz",
                                color = if (isPressed) Color.Black.copy(alpha = 0.7f) else Color(0xFF555555),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SynthSlider(
    label: String,
    valueDisplay: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    accentColor: Color = Color(0xFFD4AF37),
    onValueChange: (Float) -> Unit
) {
    var showInputDialog by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf(value.toString()) }

    if (showInputDialog) {
        AlertDialog(
            onDismissRequest = { showInputDialog = false },
            title = {
                Text("הזנת ערך עבור $label", fontSize = 14.sp, color = accentColor, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("הכנס ערך בין ${valueRange.start} ל-${valueRange.endInclusive}:", color = Color.White, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 12.sp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    textInput.toFloatOrNull()?.let { inputVal ->
                        onValueChange(inputVal.coerceIn(valueRange))
                    }
                    showInputDialog = false
                }) {
                    Text("אישור")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showInputDialog = false }) {
                    Text("ביטול")
                }
            },
            containerColor = Color(0xFF1A1A1A)
        )
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)

            Text(
                text = valueDisplay,
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        textInput = String.format("%.2f", value)
                        showInputDialog = true
                    })
                }
            )
        }
        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color(0xFF2A2A2A)
            )
        )
    }
}


