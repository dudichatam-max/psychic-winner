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
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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


class NoteSlot {
    @Volatile var active: Boolean = false
    @Volatile var baseFreq: Float = 440f
    @Volatile var targetFreq: Float = 440f
    @Volatile var currentFreq: Float = 440f
    var phase: Double = 0.0
    var phase2: Double = 0.0  // second oscillator phase for Detune/Unison
    var phaseSub: Double = 0.0 // sine sub oscillator (-1 octave), live analog bass
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
        phase2 = 0.0
        phaseSub = 0.0
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
















data class PadEvent(
    val timestampMs: Long,
    val x: Float,
    val y: Float
)

/**
 * Independent PCM looper track.
 * Captures the live wet bus so waveform / pad / LFO / drive / detune
 * are frozen at record time and later live edits cannot rewrite the take.
 * Original event-based looper types above are kept intact.
 */
class LooperPcmTrack(private val sampleRate: Int, maxSeconds: Int = 60) {
    val maxSamples: Int = (sampleRate * maxSeconds).coerceAtLeast(sampleRate)
    val buffer = FloatArray(maxSamples)
    val visualizerBuffer = FloatArray(128)

    @Volatile var length: Int = 0
    @Volatile var writePos: Int = 0
    @Volatile var playPos: Int = 0
    @Volatile var isRecording: Boolean = false
    @Volatile var isPlaying: Boolean = false
    @Volatile var volume: Float = 1.0f
    @Volatile var visWrite: Int = 0

    fun beginRecord() {
        isPlaying = false
        isRecording = true
        writePos = 0
        length = 0
        playPos = 0
    }

    fun endRecord() {
        if (!isRecording) return
        isRecording = false
        length = writePos
        playPos = 0
    }

    fun pushSample(sample: Float) {
        if (!isRecording) return
        if (writePos < maxSamples) {
            buffer[writePos] = sample
            writePos++
            length = writePos
            writeVis(sample)
        } else {
            isRecording = false
        }
    }

    fun readSample(): Float {
        if (!isPlaying || length <= 0) return 0f
        val s = buffer[playPos]
        playPos++
        if (playPos >= length) playPos = 0
        val out = s * volume
        writeVis(out)
        return out
    }

    fun startPlayback() {
        if (length <= 0) {
            isPlaying = false
            return
        }
        playPos = 0
        isPlaying = true
    }

    fun stopPlayback() {
        isPlaying = false
        playPos = 0
        for (i in visualizerBuffer.indices) visualizerBuffer[i] = 0f
    }

    fun clear() {
        isRecording = false
        isPlaying = false
        length = 0
        writePos = 0
        playPos = 0
        for (i in visualizerBuffer.indices) visualizerBuffer[i] = 0f
    }

    fun hasContent(): Boolean = length > 1

    fun durationMs(): Long =
        if (length <= 0) 0L else (length * 1000L / sampleRate)

    private fun writeVis(sample: Float) {
        val i = visWrite
        visualizerBuffer[i] = sample
        visWrite = (i + 1) % visualizerBuffer.size
    }
}
















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
    /** Drive / Saturation amount 0..1 – controls soft-clip intensity at master */
    @Volatile var driveAmount = 0.35f
    /** When true, each voice runs a second lightly-detuned oscillator (cheap unison) */
    @Volatile var detuneOn = false
    /** Live analog pad companions — applied only to the live bus (baked into PCM loops). */
    @Volatile var subOn = false
    @Volatile var warmOn = false
    @Volatile var vibeOn = false
    
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
    // Separate pad state for loop playback so live pad remains free while loop is playing
    @Volatile var loopPerformanceX: Float = 0f
    @Volatile var loopPerformanceY: Float = 0f
















    private var lastPlayedFreq: Float = 440f
    private var lastLooperPlayedFreq: Float = 440f
















    val liveVisualizerBuffer = FloatArray(512)
    val looperVisualizerBuffer = FloatArray(512)
    val drumVisualizerBuffer = FloatArray(512)
















    val recordedNotes = java.util.concurrent.CopyOnWriteArrayList<LooperNoteEvent>()
    val recordedPadEvents = java.util.concurrent.CopyOnWriteArrayList<PadEvent>()
    @Volatile private var isLoopRecording = false
    @Volatile private var isLoopPlaying = false
    private var loopStartTime = 0L
    private var loopDurationMs = 0L
    private var loopThread: Thread? = null
    private var lastPadSampleTime = 0L

    val looperTrackCount = 4
    val looperTracks: Array<LooperPcmTrack> = Array(looperTrackCount) { LooperPcmTrack(44100) }
    @Volatile var externalVolume = 1.0f
    val externalVisualizerBuffer = FloatArray(128)
    @Volatile private var externalVisWrite = 0
    @Volatile var isExternalPlayingUi = false
















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
        for (i in 0 until looperTrackCount) {
            looperTracks[i] = LooperPcmTrack(sampleRate)
        }
















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
                        performanceY = performanceY,
                        loopPerformanceX = loopPerformanceX,
                        loopPerformanceY = loopPerformanceY,
                        driveAmount = driveAmount,
                        detuneOn = detuneOn,
                        externalVolume = externalVolume,
                        subOn = subOn,
                        warmOn = warmOn,
                        vibeOn = vibeOn
                    )
















                    val drumSample = drumEngine.processNextSample()

                    // 4-track PCM looper: record the frozen live wet tap and/or
                    // play already-captured audio. Added after master saturate so
                    // current live drive/detune/waveform cannot recast old takes.
                    var pcmLoopMix = 0f
                    val liveTap = frame.liveRecordTap
                    for (t in 0 until looperTrackCount) {
                        val track = looperTracks[t]
                        if (track.isRecording) {
                            track.pushSample(liveTap)
                        }
                        if (track.isPlaying) {
                            pcmLoopMix += track.readSample()
                        }
                    }

                    if (dspEngine.isExternalAudioPlaying) {
                        val ev = frame.externalSample
                        externalVisualizerBuffer[externalVisWrite] = ev
                        externalVisWrite = (externalVisWrite + 1) % externalVisualizerBuffer.size
                    }

                    val rawMaster = (frame.masterSample + drumSample + pcmLoopMix).coerceIn(-1.0f, 1.0f)
                    val shortVal = (rawMaster * Short.MAX_VALUE * 0.85f).toInt().coerceIn(-32768, 32767).toShort()
















                    buffer[i] = shortVal
                    liveVisualizerBuffer[i] = frame.liveSample
                    looperVisualizerBuffer[i] = frame.looperSample
                    drumVisualizerBuffer[i] = drumSample
















                    byteBuffer.putShort(shortVal)
                }
















                // Sample Performance Pad position while loop-recording (~every 15 ms)
                if (isLoopRecording) {
                    val now = System.currentTimeMillis() - loopStartTime
                    if (now - lastPadSampleTime >= 15L) {
                        recordedPadEvents.add(PadEvent(now, performanceX, performanceY))
                        lastPadSampleTime = now
                    }
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
        recordedPadEvents.clear()
        lastPadSampleTime = 0L
        isLoopRecording = true
        loopStartTime = System.currentTimeMillis()
    }
















    fun stopLoopRecording() {
        if (!isLoopRecording) return
        isLoopRecording = false
        loopDurationMs = System.currentTimeMillis() - loopStartTime
        // Capture final pad position
        recordedPadEvents.add(PadEvent(loopDurationMs, performanceX, performanceY))
        // Return live pad to neutral so it can be used freely without affecting the recorded loop
        performanceX = 0f
        performanceY = 0f
    }
















    fun startLoopPlayback() {
        dspEngine.startExternalPlayback()
        if ((recordedNotes.isEmpty() && recordedPadEvents.isEmpty()) || loopDurationMs <= 0) return
        stopLoopPlayback()
        isLoopPlaying = true
















        loopThread = Thread {
            while (isLoopPlaying) {
                val start = System.currentTimeMillis()
                var eventIndex = 0
                var padIndex = 0
















                while (isLoopPlaying) {
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed >= loopDurationMs) break
















                    // Replay note events
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
















                    // Replay Performance Pad (LFO / Res) automation onto the LOOP pad only
                    // so the live pad remains free for the user while the loop is playing
                    while (padIndex < recordedPadEvents.size && recordedPadEvents[padIndex].timestampMs <= elapsed) {
                        val pe = recordedPadEvents[padIndex]
                        loopPerformanceX = pe.x
                        loopPerformanceY = pe.y
                        padIndex++
                    }
















                    try { Thread.sleep(1) } catch (_: Exception) {}
                }
















                for (i in 0 until maxVoices) {
                    if (noteSlots[i].isLooperNote) {
                        noteSlots[i].active = false
                    }
                }
                // Reset LOOP pad to center at end of each loop cycle (live pad stays under user control)
                loopPerformanceX = 0f
                loopPerformanceY = 0f
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
        // Reset loop pad; live pad (performanceX/Y) stays under user control
        loopPerformanceX = 0f
        loopPerformanceY = 0f
    }
















    fun clearLoop() {
        stopLoopPlayback()
        recordedNotes.clear()
        recordedPadEvents.clear()
        loopDurationMs = 0L
        looperTracks[0].clear()
    }

    fun startTrackRecording(trackIndex: Int) {
        val track = looperTracks.getOrNull(trackIndex) ?: return
        track.beginRecord()
    }

    fun stopTrackRecording(trackIndex: Int) {
        val track = looperTracks.getOrNull(trackIndex) ?: return
        track.endRecord()
    }

    fun toggleTrackPlayback(trackIndex: Int): Boolean {
        val track = looperTracks.getOrNull(trackIndex) ?: return false
        return if (track.isPlaying) {
            track.stopPlayback()
            false
        } else {
            if (track.isRecording) track.endRecord()
            track.startPlayback()
            track.isPlaying
        }
    }

    fun setTrackPlaying(trackIndex: Int, playing: Boolean) {
        val track = looperTracks.getOrNull(trackIndex) ?: return
        if (playing) {
            if (track.isRecording) track.endRecord()
            track.startPlayback()
        } else {
            track.stopPlayback()
        }
    }

    fun clearTrack(trackIndex: Int) {
        looperTracks.getOrNull(trackIndex)?.clear()
    }

    fun setTrackVolume(trackIndex: Int, vol: Float) {
        looperTracks.getOrNull(trackIndex)?.volume = vol.coerceIn(0f, 1.5f)
    }

    fun isTrackRecording(trackIndex: Int): Boolean =
        looperTracks.getOrNull(trackIndex)?.isRecording == true

    fun isTrackPlaying(trackIndex: Int): Boolean =
        looperTracks.getOrNull(trackIndex)?.isPlaying == true

    fun trackHasContent(trackIndex: Int): Boolean =
        looperTracks.getOrNull(trackIndex)?.hasContent() == true

    fun setExternalVol(vol: Float) {
        externalVolume = vol.coerceIn(0f, 1.5f)
    }

    fun isExternalLoaded(): Boolean = dspEngine.hasExternalAudio()

    fun isExternalPlaying(): Boolean = dspEngine.isExternalAudioPlaying && dspEngine.hasExternalAudio()
















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
                isExternalPlayingUi = true
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "שגיאה בפענוח קובץ השמע, ייתכן שהפורמט אינו נתמך", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
















    fun pauseBackgroundAudio() {
        dspEngine.isExternalAudioPlaying = false
        isExternalPlayingUi = false
    }
















    fun resumeBackgroundAudio() {
        if (dspEngine.hasExternalAudio()) {
            dspEngine.isExternalAudioPlaying = true
            isExternalPlayingUi = true
        }
    }
















    fun stopBackgroundAudio() {
        dspEngine.stopExternalPlayback()
        dspEngine.setExternalAudioBuffer(null)
        isExternalPlayingUi = false
        for (i in externalVisualizerBuffer.indices) externalVisualizerBuffer[i] = 0f
    }

    fun toggleExternalPlayback(): Boolean {
        return if (dspEngine.isExternalAudioPlaying) {
            pauseBackgroundAudio()
            false
        } else {
            if (!dspEngine.hasExternalAudio()) return false
            resumeBackgroundAudio()
            true
        }
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
