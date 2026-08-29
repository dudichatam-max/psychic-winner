package com.microtonal.synth
 
  
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioDeviceInfo
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
class LooperPcmTrack(private val sampleRate: Int, maxSeconds: Int = 30) {
    val maxSamples: Int = (sampleRate * maxSeconds).coerceAtLeast(sampleRate)
    @Volatile private var buffer: FloatArray = FloatArray(0)
    val visualizerBuffer = FloatArray(128)

    @Volatile var length: Int = 0
    @Volatile var writePos: Int = 0
    @Volatile var playPos: Int = 0
    @Volatile var isRecording: Boolean = false
    @Volatile var isPlaying: Boolean = false
    @Volatile var volume: Float = 1.0f
    @Volatile var visWrite: Int = 0

    private fun ensureBuffer() {
        if (buffer.size < maxSamples) {
            buffer = FloatArray(maxSamples)
        }
    }

    fun beginRecord() {
        isPlaying = false
        isRecording = true
        ensureBuffer()
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
        if (buffer.isEmpty()) ensureBuffer()
        if (writePos < buffer.size && writePos < maxSamples) {
            buffer[writePos] = sample
            writePos++
            length = writePos
            writeVis(sample)
        } else {
            isRecording = false
        }
    }

    fun readSample(): Float {
        if (!isPlaying || length <= 0 || buffer.isEmpty()) return 0f
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
        buffer = FloatArray(0)
        for (i in visualizerBuffer.indices) visualizerBuffer[i] = 0f
    }

    fun hasContent(): Boolean = length > 1

    fun loadFromSamples(samples: FloatArray) {
        isRecording = false
        isPlaying = false
        playPos = 0
        val n = samples.size.coerceAtMost(maxSamples)
        if (n <= 0) {
            length = 0
            writePos = 0
            buffer = FloatArray(0)
            for (i in visualizerBuffer.indices) visualizerBuffer[i] = 0f
            return
        }
        ensureBuffer()
        System.arraycopy(samples, 0, buffer, 0, n)
        length = n
        writePos = n
        val step = (n / visualizerBuffer.size).coerceAtLeast(1)
        for (i in visualizerBuffer.indices) {
            val idx = (i * step).coerceAtMost(n - 1)
            visualizerBuffer[i] = buffer[idx]
        }
        visWrite = 0
    }

    fun copySamples(): FloatArray {
        if (length <= 0) return FloatArray(0)
        return buffer.copyOfRange(0, length)
    }

    fun writeSessionPcm(file: java.io.File) {
        if (length <= 0 || buffer.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        java.io.FileOutputStream(file).use { fos ->
            val hdr = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            hdr.putInt(length)
            fos.write(hdr.array())
            val tmp = ByteArray(4096)
            val bb = java.nio.ByteBuffer.wrap(tmp).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var i = 0
            while (i < length) {
                bb.clear()
                while (bb.remaining() >= 4 && i < length) {
                    bb.putFloat(buffer[i])
                    i++
                }
                fos.write(tmp, 0, bb.position())
            }
        }
    }

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
    val micEngine: MicCaptureEngine
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
    /** Live SOUND-window FX. Not stored in presets. Baked into WAV / looper taps. */
    @Volatile var ripOn = false
    @Volatile var fuzzOn = false
    @Volatile var phazOn = false
    @Volatile var pianoOn = false
    
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
    @Volatile var padTargetKey: Boolean = true
    @Volatile var padTargetMic: Boolean = false
    @Volatile var padTargetLoop: Boolean = false
    @Volatile var padTargetDrum: Boolean = false
    @Volatile var busPadX: Float = 0f
    @Volatile var busPadY: Float = 0f
    @Volatile var busPadTouched: Boolean = false
    /** First-touch origin so returning the finger restores the dry sound. */
    @Volatile var padOriginX: Float = 0f
    @Volatile var padOriginY: Float = 0f
    /** Distance-from-origin modulation 0..1 (not absolute pad position). */
    @Volatile var padModX: Float = 0f
    @Volatile var padModY: Float = 0f
    /** Momentary bus stutter: 0 = off, else 4/8/16/32 note division. */
    @Volatile var busStutterDiv: Int = 0
    @Volatile var busHoldStop: Boolean = false
    /** Drum-window roll / stop — always hits the drum stem, even if PAD DRUM target is off. */
    @Volatile var drumStutterDiv: Int = 0
    @Volatile var drumHoldStop: Boolean = false
    private var busLfoPhase = 0.0
    private var busLfoMod = 0f
    private val drumLp = floatArrayOf(0f)
    private val drumWet = floatArrayOf(0f)
    private val loopLp = floatArrayOf(0f)
    private val loopWet = floatArrayOf(0f)
    private val micLp = floatArrayOf(0f)
    private val micWet = floatArrayOf(0f)
    private var stopGain = 1f
    private var drumStopGain = 1f
    private var smoothModX = 0f
    private var smoothModY = 0f
    private val rollLive = SliceRoll()
    private val rollDrum = SliceRoll()
    private val rollLoop = SliceRoll()
    private val rollMic = SliceRoll()

    private class SliceRoll {
        private val buf = FloatArray(96000)
        private var len = 0
        private var fill = 0
        private var play = 0
        private var lastDiv = 0
        private var wet = 0f

        fun process(input: Float, armed: Boolean, div: Int, bpm: Float, sr: Int): Float {
            if (armed && div >= 4) {
                val need = ((sr * 240.0) / (bpm * div)).toInt().coerceIn(128, buf.size)
                if (div != lastDiv) {
                    lastDiv = div
                    len = need
                    fill = 0
                    play = 0
                }
                wet += (1f - wet) * 0.004f
                if (fill < len) {
                    buf[fill] = input
                    fill++
                    return input
                }
                return readGrain()
            }
            lastDiv = 0
            if (wet < 0.002f) {
                wet = 0f
                fill = 0
                play = 0
                return input
            }
            wet *= 0.996f
            val grain = if (len > 0 && fill >= len) readGrain() else input
            return input * (1f - wet) + grain * wet
        }

        private fun readGrain(): Float {
            if (len <= 1) return 0f
            val fade = (len / 8).coerceIn(32, 256)
            var s = buf[play]
            if (play >= len - fade) {
                val t = (play - (len - fade)).toFloat() / fade
                val startIdx = play - (len - fade)
                s = s * (1f - t) + buf[startIdx] * t
            }
            play++
            if (play >= len) play = 0
            return s
        }
    }

    private fun shapePadStem(
        input: Float,
        want: Boolean,
        lp: FloatArray,
        wetArr: FloatArray
    ): Float {
        val target = if (want && (busPadTouched || smoothModX > 0.002f || smoothModY > 0.002f)) 1f else 0f
        wetArr[0] += (target - wetArr[0]) * 0.0025f
        if (wetArr[0] < 0.0008f) {
            wetArr[0] = 0f
            lp[0] = input
            return input
        }
        val depth = smoothModY.coerceIn(0f, 1f)
        val coeff = 0.035f + depth * (0.08f + busLfoMod * 0.04f)
        lp[0] += coeff.coerceIn(0.01f, 0.25f) * (input - lp[0])
        val amp = 1f + busLfoMod * depth * 0.4f
        val wet = (lp[0] * amp).coerceIn(-1f, 1f)
        return input * (1f - wetArr[0]) + wet * wetArr[0]
    }
















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

    val looperPageCount = 4
    val looperTracksPerPage = 5
    val looperTrackCount = 20
    lateinit var looperTracks: Array<LooperPcmTrack>
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
        micEngine = MicCaptureEngine(sampleRate)
        looperTracks = Array(looperTrackCount) { LooperPcmTrack(sampleRate, 30) }
        loadLooperSession()
















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
            val recPool = Array(8) { ByteArray(bufferSize * 2) }
            var recPoolIdx = 0
            val fadeCoeff = 1f - kotlin.math.exp((-1.0 / (0.300 * sampleRate)).toFloat())
            val padCoeff = 1f - kotlin.math.exp((-1.0 / (0.050 * sampleRate)).toFloat())
















            while (isRunning) {
                val recOn = isRecording
                if (recOn) byteBuffer.clear()
                val padLfoHz = 0.2 + smoothModX * 18.0
















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
                        performanceX = if (padTargetKey) smoothModX else 0f,
                        performanceY = if (padTargetKey) smoothModY else 0f,
                        loopPerformanceX = if (padTargetLoop) smoothModX else 0f,
                        loopPerformanceY = if (padTargetLoop) smoothModY else 0f,
                        driveAmount = driveAmount,
                        detuneOn = detuneOn,
                        externalVolume = externalVolume,
                        subOn = subOn,
                        warmOn = warmOn,
                        vibeOn = vibeOn,
                        ripOn = ripOn,
                        fuzzOn = fuzzOn,
                        phazOn = phazOn,
                        pianoOn = pianoOn
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

                    val micPlay = micEngine.playMixSample()
                    val micMon = micEngine.nextMonitorSample()
                    val modTargetX = if (busPadTouched) padModX else 0f
                    val modTargetY = if (busPadTouched) padModY else 0f
                    smoothModX += (modTargetX - smoothModX) * padCoeff
                    smoothModY += (modTargetY - smoothModY) * padCoeff
                    if (busPadTouched && (padTargetMic || padTargetLoop || padTargetDrum)) {
                        busLfoPhase += padLfoHz / sampleRate
                        if (busLfoPhase >= 1.0) busLfoPhase -= 1.0
                        val t = (busLfoPhase * 2.0).toFloat()
                        busLfoMod = if (t < 1f) t * 2f - 1f else 3f - t * 2f
                    } else {
                        busLfoMod *= 0.992f
                    }
                    val bpmNow = drumEngine.bpm.coerceIn(40f, 240f)
                    val rollOn = busStutterDiv >= 4 && !busHoldStop
                    val drumRollDiv = when {
                        drumStutterDiv >= 4 && !drumHoldStop -> drumStutterDiv
                        rollOn && padTargetDrum -> busStutterDiv
                        else -> 0
                    }
                    var liveOut = rollLive.process(frame.masterSample, rollOn && padTargetKey, busStutterDiv, bpmNow, sampleRate)
                    var drumOut = rollDrum.process(drumSample, drumRollDiv >= 4, drumRollDiv, bpmNow, sampleRate)
                    var loopOut = rollLoop.process(pcmLoopMix, rollOn && padTargetLoop, busStutterDiv, bpmNow, sampleRate)
                    var micOut = rollMic.process(micPlay, rollOn && padTargetMic, busStutterDiv, bpmNow, sampleRate)
                    drumOut = shapePadStem(drumOut, padTargetDrum, drumLp, drumWet)
                    loopOut = shapePadStem(loopOut, padTargetLoop, loopLp, loopWet)
                    micOut = shapePadStem(micOut, padTargetMic, micLp, micWet)
                    val stopTarget = if (busHoldStop) 0f else 1f
                    stopGain += (stopTarget - stopGain) * fadeCoeff
                    val drumStopTarget = if (drumHoldStop || (busHoldStop && padTargetDrum)) 0f else 1f
                    drumStopGain += (drumStopTarget - drumStopGain) * fadeCoeff
                    if (padTargetKey) liveOut *= stopGain
                    drumOut *= drumStopGain
                    if (padTargetLoop) loopOut *= stopGain
                    if (padTargetMic) micOut *= stopGain
                    val rawMaster = (liveOut + drumOut + loopOut + micOut + micMon).coerceIn(-1.0f, 1.0f)
                    val shortVal = (rawMaster * Short.MAX_VALUE * 0.85f).toInt().coerceIn(-32768, 32767).toShort()
















                    buffer[i] = shortVal
                    liveVisualizerBuffer[i] = frame.liveSample
                    looperVisualizerBuffer[i] = frame.looperSample
                    drumVisualizerBuffer[i] = drumSample
                    if (recOn) byteBuffer.putShort(shortVal)
















                }
















                // Sample Performance Pad position while loop-recording (~every 15 ms)
                if (isLoopRecording) {
                    val now = System.currentTimeMillis() - loopStartTime
                    if (now - lastPadSampleTime >= 15L) {
                        recordedPadEvents.add(PadEvent(now, performanceX, performanceY))
                        lastPadSampleTime = now
                    }
                }
















                if (recOn) {
                    val slot = recPool[recPoolIdx]
                    recPoolIdx = (recPoolIdx + 1) % recPool.size
                    System.arraycopy(byteBuffer.array(), 0, slot, 0, slot.size)
                    recordingQueue.offer(slot)
                }
















                audioTrack.write(buffer, 0, buffer.size)
            }
        }.start()
    }
















    fun stop() {
        isRunning = false
        stopLoopPlayback()
        stopBackgroundAudio()
        micEngine.stopCapture()
        audioTrack.stop()
        audioTrack.release()
    }

    fun refreshHeadphoneState() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            micEngine.headphonesConnected = outs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } catch (_: Exception) {
            micEngine.headphonesConnected = false
        }
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


    fun activeVoiceCount(): Int {
        var n = 0
        for (i in 0 until maxVoices) {
            if (noteSlots[i].active) n++
        }
        return n
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

    fun saveLooperSession(projectName: String, channelNames: List<String>, currentPage: Int) {
        try {
            val dir = File(context.filesDir, "looper_session")
            if (!dir.exists()) dir.mkdirs()
            val json = JSONObject()
            json.put("projectName", projectName)
            json.put("currentPage", currentPage.coerceIn(0, looperPageCount - 1))
            val names = JSONArray()
            val vols = JSONArray()
            for (t in 0 until looperTrackCount) {
                names.put(channelNames.getOrNull(t) ?: "ערוץ ${(t % looperTracksPerPage) + 1}")
                vols.put(looperTracks[t].volume.toDouble())
                val pcm = File(dir, "track$t.pcm")
                looperTracks[t].writeSessionPcm(pcm)
            }
            json.put("channelNames", names)
            json.put("volumes", vols)
            File(dir, "session.json").writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadLooperSession(): LooperProjectMeta? {
        return try {
            val dir = File(context.filesDir, "looper_session")
            val metaFile = File(dir, "session.json")
            if (!dir.exists()) return null
            var projectName = "פרויקט לופר"
            var currentPage = 0
            val channelNames = MutableList(looperTrackCount) { "ערוץ ${(it % looperTracksPerPage) + 1}" }
            val volumes = MutableList(looperTrackCount) { 1.0f }
            if (metaFile.exists()) {
                val json = JSONObject(metaFile.readText())
                projectName = json.optString("projectName", projectName)
                currentPage = json.optInt("currentPage", 0).coerceIn(0, looperPageCount - 1)
                val names = json.optJSONArray("channelNames")
                if (names != null) {
                    for (i in 0 until minOf(names.length(), looperTrackCount)) {
                        channelNames[i] = names.optString(i, channelNames[i])
                    }
                }
                val vols = json.optJSONArray("volumes")
                if (vols != null) {
                    for (i in 0 until minOf(vols.length(), looperTrackCount)) {
                        volumes[i] = vols.optDouble(i, 1.0).toFloat().coerceIn(0f, 1.5f)
                    }
                }
            }
            for (t in 0 until looperTrackCount) {
                val pcm = File(dir, "track$t.pcm")
                if (pcm.exists()) {
                    val bytes = pcm.readBytes()
                    if (bytes.size >= 4) {
                        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        val size = bb.int
                        if (size > 0 && size * 4 + 4 <= bytes.size) {
                            val floats = FloatArray(size)
                            for (i in 0 until size) floats[i] = bb.float
                            looperTracks[t].loadFromSamples(floats)
                        }
                    }
                }
                looperTracks[t].volume = volumes[t]
            }
            LooperProjectMeta(projectName, channelNames, volumes, currentPage)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun isTrackRecording(trackIndex: Int): Boolean =
        looperTracks.getOrNull(trackIndex)?.isRecording == true

    fun isTrackPlaying(trackIndex: Int): Boolean =
        looperTracks.getOrNull(trackIndex)?.isPlaying == true

    fun trackHasContent(trackIndex: Int): Boolean =
        looperTracks.getOrNull(trackIndex)?.hasContent() == true

    fun loadAudioIntoMicTrack(context: Context, trackIndex: Int, uri: Uri): Boolean {
        val track = micEngine.tracks.getOrNull(trackIndex) ?: return false
        val pcm = decodeAudioToPCM(context, uri) ?: return false
        if (pcm.isEmpty()) return false
        track.loadFromSamples(pcm)
        return true
    }

    data class MicProjectMeta(
        val projectName: String,
        val channelNames: List<String>,
        val volumes: List<Float>,
        val currentPage: Int = 0
    )

    fun exportMicProjectToZip(
        context: Context,
        destinationUri: Uri,
        projectName: String,
        channelNames: List<String>,
        currentPage: Int = 0
    ): Boolean {
        return try {
            val n = micEngine.trackCount
            context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    val json = JSONObject()
                    json.put("projectName", projectName)
                    json.put("currentPage", currentPage.coerceIn(0, micEngine.pageCount - 1))
                    json.put("pageCount", micEngine.pageCount)
                    json.put("tracksPerPage", micEngine.tracksPerPage)
                    val names = JSONArray()
                    val vols = JSONArray()
                    for (t in 0 until n) {
                        names.put(channelNames.getOrNull(t) ?: "ערוץ ${(t % micEngine.tracksPerPage) + 1}")
                        vols.put(micEngine.tracks[t].volume.toDouble())
                    }
                    json.put("channelNames", names)
                    json.put("volumes", vols)
                    zos.putNextEntry(ZipEntry("project.json"))
                    zos.write(json.toString().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    for (t in 0 until n) {
                        val samples = micEngine.tracks[t].copySamples()
                        if (samples.isEmpty()) continue
                        zos.putNextEntry(ZipEntry("track$t.wav"))
                        writeWav16Mono(zos, samples, sampleRate)
                        zos.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun importMicProjectFromZip(context: Context, sourceUri: Uri): MicProjectMeta? {
        return try {
            val n = micEngine.trackCount
            var projectName = "פרויקט מיק"
            var currentPage = 0
            val channelNames = MutableList(n) { "ערוץ ${(it % micEngine.tracksPerPage) + 1}" }
            val volumes = MutableList(n) { 1.0f }
            val loaded = mutableMapOf<Int, FloatArray>()
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == "project.json") {
                            val json = JSONObject(String(zis.readBytes(), Charsets.UTF_8))
                            projectName = json.optString("projectName", projectName)
                            currentPage = json.optInt("currentPage", 0).coerceIn(0, micEngine.pageCount - 1)
                            val names = json.optJSONArray("channelNames")
                            if (names != null) {
                                for (i in 0 until minOf(names.length(), n)) {
                                    channelNames[i] = names.optString(i, channelNames[i])
                                }
                            }
                            val vols = json.optJSONArray("volumes")
                            if (vols != null) {
                                for (i in 0 until minOf(vols.length(), n)) {
                                    volumes[i] = vols.optDouble(i, 1.0).toFloat().coerceIn(0f, 1.5f)
                                }
                            }
                        } else if (name.startsWith("track") && name.endsWith(".wav")) {
                            val idx = name.removePrefix("track").removeSuffix(".wav").toIntOrNull()
                            if (idx != null && idx in 0 until n) {
                                val floats = readWav16Mono(zis)
                                if (floats != null && floats.isNotEmpty()) loaded[idx] = floats
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            for (t in 0 until n) {
                val samples = loaded[t]
                if (samples != null) {
                    micEngine.tracks[t].loadFromSamples(samples)
                } else {
                    micEngine.tracks[t].clear()
                }
                micEngine.tracks[t].volume = volumes[t]
                micEngine.trackFx[t].volume = volumes[t]
            }
            MicProjectMeta(projectName, channelNames, volumes, currentPage)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun exportMicTrackWavToUri(context: Context, trackIndex: Int, destinationUri: Uri): Boolean {
        val track = micEngine.tracks.getOrNull(trackIndex) ?: return false
        val samples = track.copySamples()
        if (samples.isEmpty()) return false
        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                writeWav16Mono(os, samples, sampleRate)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun loadAudioIntoTrack(context: Context, trackIndex: Int, uri: Uri): Boolean {
        val track = looperTracks.getOrNull(trackIndex) ?: return false
        val pcm = decodeAudioToPCM(context, uri) ?: return false
        if (pcm.isEmpty()) return false
        track.loadFromSamples(pcm)
        return true
    }

    fun exportTrackWavToUri(context: Context, trackIndex: Int, destinationUri: Uri): Boolean {
        val track = looperTracks.getOrNull(trackIndex) ?: return false
        val samples = track.copySamples()
        if (samples.isEmpty()) return false
        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                writeWav16Mono(os, samples, sampleRate)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun exportLooperProjectToZip(
        context: Context,
        destinationUri: Uri,
        projectName: String,
        channelNames: List<String>,
        currentPage: Int = 0
    ): Boolean {
        return try {
            context.contentResolver.openOutputStream(destinationUri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    val json = JSONObject()
                    json.put("projectName", projectName)
                    json.put("currentPage", currentPage.coerceIn(0, looperPageCount - 1))
                    json.put("pageCount", looperPageCount)
                    json.put("tracksPerPage", looperTracksPerPage)
                    val names = JSONArray()
                    val vols = JSONArray()
                    for (t in 0 until looperTrackCount) {
                        val page = t / looperTracksPerPage
                        val row = t % looperTracksPerPage
                        names.put(channelNames.getOrNull(t) ?: "ערוץ ${row + 1}")
                        vols.put(looperTracks[t].volume.toDouble())
                    }
                    json.put("channelNames", names)
                    json.put("volumes", vols)
                    zos.putNextEntry(ZipEntry("project.json"))
                    zos.write(json.toString().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                    for (t in 0 until looperTrackCount) {
                        val samples = looperTracks[t].copySamples()
                        if (samples.isEmpty()) continue
                        zos.putNextEntry(ZipEntry("track$t.wav"))
                        writeWav16Mono(zos, samples, sampleRate)
                        zos.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    data class LooperProjectMeta(
        val projectName: String,
        val channelNames: List<String>,
        val volumes: List<Float>,
        val currentPage: Int = 0
    )

    fun importLooperProjectFromZip(context: Context, sourceUri: Uri): LooperProjectMeta? {
        return try {
            var projectName = "פרויקט לופר"
            var currentPage = 0
            val channelNames = MutableList(looperTrackCount) { "ערוץ ${(it % looperTracksPerPage) + 1}" }
            val volumes = MutableList(looperTrackCount) { 1.0f }
            val loaded = mutableMapOf<Int, FloatArray>()
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (name == "project.json") {
                            val json = JSONObject(String(zis.readBytes(), Charsets.UTF_8))
                            projectName = json.optString("projectName", projectName)
                            currentPage = json.optInt("currentPage", 0).coerceIn(0, looperPageCount - 1)
                            val names = json.optJSONArray("channelNames")
                            if (names != null) {
                                for (i in 0 until minOf(names.length(), looperTrackCount)) {
                                    channelNames[i] = names.optString(i, channelNames[i])
                                }
                            }
                            val vols = json.optJSONArray("volumes")
                            if (vols != null) {
                                for (i in 0 until minOf(vols.length(), looperTrackCount)) {
                                    volumes[i] = vols.optDouble(i, 1.0).toFloat().coerceIn(0f, 1.5f)
                                }
                            }
                        } else if (name.startsWith("track") && name.endsWith(".wav")) {
                            val idx = name.removePrefix("track").removeSuffix(".wav").toIntOrNull()
                            if (idx != null && idx in 0 until looperTrackCount) {
                                val floats = readWav16Mono(zis)
                                if (floats != null && floats.isNotEmpty()) loaded[idx] = floats
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            for (t in 0 until looperTrackCount) {
                val samples = loaded[t]
                if (samples != null) {
                    looperTracks[t].loadFromSamples(samples)
                    looperTracks[t].volume = volumes[t]
                } else {
                    looperTracks[t].clear()
                    looperTracks[t].volume = volumes[t]
                }
            }
            LooperProjectMeta(projectName, channelNames, volumes, currentPage)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun writeWav16Mono(out: OutputStream, samples: FloatArray, sr: Int) {
        val numSamples = samples.size
        val dataSize = numSamples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sr)
        header.putInt(sr * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)
        out.write(header.array())
        val data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (f in samples) {
            val s = (f.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            data.putShort(s)
        }
        out.write(data.array())
    }

    private fun readWav16Mono(input: InputStream): FloatArray? {
        return try {
            val header = ByteArray(44)
            var read = 0
            while (read < 44) {
                val r = input.read(header, read, 44 - read)
                if (r < 0) return null
                read += r
            }
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val audioFormat = bb.getShort(20).toInt()
            val channels = bb.getShort(22).toInt()
            val bits = bb.getShort(34).toInt()
            if (audioFormat != 1 || channels != 1 || bits != 16) return null
            val dataSize = bb.getInt(40)
            if (dataSize <= 0 || dataSize % 2 != 0) return null
            val data = ByteArray(dataSize)
            read = 0
            while (read < dataSize) {
                val r = input.read(data, read, dataSize - read)
                if (r < 0) break
                read += r
            }
            val numSamples = read / 2
            val floats = FloatArray(numSamples)
            val dbb = ByteBuffer.wrap(data, 0, read).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until numSamples) {
                floats[i] = dbb.short / 32768f
            }
            floats
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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
