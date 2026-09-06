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
    var phaseP2: Double = 0.0
    var phaseP3: Double = 0.0
    var phaseP4: Double = 0.0
    var phase2P2: Double = 0.0
    var phase2P3: Double = 0.0
    var phase2P4: Double = 0.0
    var hammerEnv: Double = 0.0
    var prevFund: Double = 0.0
    var div2: Double = 1.0
    var div3: Double = 1.0
    var div4: Double = 1.0
    var zcCount: Int = 0
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
        phaseP2 = 0.0
        phaseP3 = 0.0
        phaseP4 = 0.0
        phase2P2 = 0.0
        phase2P3 = 0.0
        phase2P4 = 0.0
        hammerEnv = 1.0
        prevFund = 0.0
        div2 = 1.0
        div3 = 1.0
        div4 = 1.0
        zcCount = 0
        prevFund = 0.0
        div2 = 1.0
        div3 = 1.0
        div4 = 1.0
        zcCount = 0
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
    @Volatile var pan: Float = 0f
    @Volatile var panL: Float = 0.70710677f
    @Volatile var panR: Float = 0.70710677f

    fun applyPan(p: Float) {
        pan = p.coerceIn(-1f, 1f)
        val a = (pan + 1f) * 0.7853982f
        panL = kotlin.math.cos(a)
        panR = kotlin.math.sin(a)
    }

    @Volatile var visWrite: Int = 0
    private var visStride: Int = 0
    private var playGain = 0f
    private var playGainTarget = 0f
    private var stopping = false
    private var xfadeN = 0
    private val playFadeCoeff = (1.0 / (sampleRate * 0.010)).toFloat()

    private fun refreshXfade() {
        xfadeN = if (length > 8) (sampleRate / 100).coerceAtMost(length / 4).coerceAtLeast(1) else 0
    }

    private fun ensureBuffer() {
        if (buffer.size < maxSamples) {
            buffer = FloatArray(maxSamples)
        }
    }

    private val padMs = LongArray(2048)
    private val padXs = FloatArray(2048)
    private val padYs = FloatArray(2048)
    private var padN = 0
    private var padI = 0
    private var lastStampMs = -15L

    fun hasPadAuto(): Boolean = padN > 0

    fun stampPad(x: Float, y: Float) {
        if (!isRecording || padN >= padMs.size) return
        val ms = writePos * 1000L / sampleRate
        if (padN > 0 && ms - lastStampMs < 15L) return
        padMs[padN] = ms
        padXs[padN] = x
        padYs[padN] = y
        padN++
        lastStampMs = ms
    }

    fun padXAtPlay(): Float {
        if (padN <= 0) return 0.5f
        val ms = playPos * 1000L / sampleRate
        if (padI >= padN || ms < padMs[padI]) padI = 0
        while (padI + 1 < padN && padMs[padI + 1] <= ms) padI++
        return padXs[padI]
    }

    fun padYAtPlay(): Float {
        if (padN <= 0) return 0.5f
        val ms = playPos * 1000L / sampleRate
        if (padI >= padN || ms < padMs[padI]) padI = 0
        while (padI + 1 < padN && padMs[padI + 1] <= ms) padI++
        return padYs[padI]
    }

    fun beginRecord() {
        isPlaying = false
        isRecording = true
        ensureBuffer()
        writePos = 0
        length = 0
        playPos = 0
        padN = 0
        padI = 0
        lastStampMs = -15L
    }

    fun endRecord() {
        if (!isRecording) return
        isRecording = false
        length = writePos
        playPos = 0
        refreshXfade()
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
        if (length <= 0 || buffer.isEmpty()) return 0f
        if (playGainTarget > playGain) {
            playGain += playFadeCoeff
            if (playGain > playGainTarget) playGain = playGainTarget
        } else if (playGainTarget < playGain) {
            playGain -= playFadeCoeff
            if (playGain < playGainTarget) playGain = playGainTarget
        }
        if (stopping && playGain <= 0.0008f) {
            isPlaying = false
            stopping = false
            playPos = 0
            playGain = 0f
            return 0f
        }
        if (!isPlaying && playGain <= 0.0008f) return 0f
        var s = buffer[playPos]
        val fadeN = xfadeN
        if (fadeN > 0) {
            val remain = length - playPos
            if (remain <= fadeN) {
                val f = remain.toFloat() / fadeN.toFloat()
                val headIdx = fadeN - remain
                if (headIdx in 0 until length) {
                    s = s * f + buffer[headIdx] * (1f - f)
                }
            }
        }
        playPos++
        if (playPos >= length) {
            playPos = if (fadeN > 0 && fadeN < length) fadeN else 0
        }
        val out = s * volume * playGain
        writeVis(out)
        return out
    }

    fun startPlayback() {
        if (length <= 0) {
            isPlaying = false
            playGainTarget = 0f
            return
        }
        refreshXfade()
        playPos = 0
        padI = 0
        stopping = false
        playGain = 0f
        playGainTarget = 1f
        isPlaying = true
    }

    fun stopPlayback() {
        playGainTarget = 0f
        stopping = true
    }

    fun clear() {
        isRecording = false
        isPlaying = false
        stopping = false
        playGain = 0f
        playGainTarget = 0f
        length = 0
        writePos = 0
        playPos = 0
        padN = 0
        padI = 0
        lastStampMs = -15L
        xfadeN = 0
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
        refreshXfade()
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
        visStride++
        if (visStride < 32) return
        visStride = 0
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
    @Volatile var reverbMix = 0f
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
    @Volatile var div2On = false
    @Volatile var div3On = false
    @Volatile var div4On = false
    
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
    @Volatile var padWah: Float = 0f
    @Volatile var padOct: Float = 0f
    @Volatile var padCho: Float = 0f
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
    private var smoothModX = 0.5f
    private var smoothModY = 0.5f
    private var padCx = 0f
    private var padCy = 0f
    private val liveLp = floatArrayOf(0f)
    private val liveWet = floatArrayOf(0f)
    private val colorDrumL = StemColor()
    private val colorDrumR = StemColor()
    private val colorLoopL = StemColor()
    private val colorLoopR = StemColor()
    private val colorMicL = StemColor()
    private val colorMicR = StemColor()

    private inner class StemColor {
        private var smWah = 0.0
        private var smOct = 0.0
        private var smCho = 0.0
        private var wahEnv = 0.0
        private var wahLp = 0.0
        private var octLp = 0.0
        private val choBuf = FloatArray(2048)
        private var choWrite = 0
        private var choPhase = 0.0

        fun process(input: Float, wah: Float, oct: Float, cho: Float): Float {
            var x = input.toDouble()
            smWah += (wah.toDouble().coerceIn(0.0, 1.0) - smWah) * 0.006
            smOct += (oct.toDouble().coerceIn(0.0, 1.0) - smOct) * 0.006
            smCho += (cho.toDouble().coerceIn(0.0, 1.0) - smCho) * 0.006
            if (smWah > 0.0008) {
                val peak = if (x >= 0.0) x else -x
                wahEnv += (peak - wahEnv) * if (peak > wahEnv) 0.12 else 0.04
                val wc = (0.05 + wahEnv * smWah * 0.38).coerceIn(0.02, 0.45)
                wahLp += (x - wahLp) * wc
                val wahSig = wahLp * 0.25 + (x - wahLp) * 1.15
                x = x * (1.0 - smWah) + wahSig * smWah
            } else {
                wahEnv *= 0.99
                wahLp *= 0.99
            }
            if (smOct > 0.0008) {
                val rec = if (x >= 0.0) x else -x
                octLp += (rec - octLp) * 0.07
                x += (rec - octLp) * 1.55 * smOct * 0.8
            } else if (octLp != 0.0) {
                octLp *= 0.99
            }
            if (smCho > 0.0008) {
                choBuf[choWrite] = x.toFloat()
                val invSr = 1.0 / sampleRate
                choPhase += 0.35 * invSr
                if (choPhase >= 1.0) choPhase -= 1.0
                val twopi = 6.283185307179586
                val lfo = kotlin.math.sin(choPhase * twopi)
                val nCho = choBuf.size
                val d1 = (14.0 + 2.4 * lfo) * sampleRate / 1000.0
                var rp1 = choWrite - d1
                if (rp1 < 0.0) rp1 += nCho
                val i1 = rp1.toInt()
                val f1 = (rp1 - i1).toFloat()
                val a1 = choBuf[if (i1 >= nCho) i1 - nCho else i1]
                val j1 = i1 + 1
                val b1 = choBuf[if (j1 >= nCho) j1 - nCho else j1]
                val t1 = a1 + (b1 - a1) * f1
                val d2 = (19.0 - 2.0 * lfo) * sampleRate / 1000.0
                var rp2 = choWrite - d2
                if (rp2 < 0.0) rp2 += nCho
                val i2 = rp2.toInt()
                val f2 = (rp2 - i2).toFloat()
                val a2 = choBuf[if (i2 >= nCho) i2 - nCho else i2]
                val j2 = i2 + 1
                val b2 = choBuf[if (j2 >= nCho) j2 - nCho else j2]
                val t2 = a2 + (b2 - a2) * f2
                val choSig = (t1 + t2) * 0.5
                x = x * (1.0 - 0.20 * smCho) + choSig * (0.20 * smCho)
                choWrite++
                if (choWrite >= nCho) choWrite = 0
            }
            if (x > 1.2) x = 1.2 else if (x < -1.2) x = -1.2
            return x.toFloat()
        }
    }
    private val rollLive = SliceRoll()
    private val rollDrumL = SliceRoll()
    private val rollDrumR = SliceRoll()
    private val rollLoopL = SliceRoll()
    private val rollLoopR = SliceRoll()
    private val rollMicL = SliceRoll()
    private val rollMicR = SliceRoll()
    private val drumLpR = floatArrayOf(0f)
    private val loopLpR = floatArrayOf(0f)
    private val micLpR = floatArrayOf(0f)
    private val drumWetR = floatArrayOf(0f)
    private val loopWetR = floatArrayOf(0f)
    private val micWetR = floatArrayOf(0f)

    private class SliceRoll {
        private val buf = FloatArray(96000)
        private var len = 0
        private var fill = 0
        private var play = 0
        private var lastDiv = 0
        private var wet = 0f

        fun isHot(): Boolean = wet >= 0.002f

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
        val away = kotlin.math.abs(padCx) + kotlin.math.abs(padCy)
        val target = if (want && (busPadTouched || away > 0.04f)) 1f else 0f
        val wCoeff = if (target > 0.5f) 0.00055f else 0.00020f
        wetArr[0] += (target - wetArr[0]) * wCoeff
        lp[0] += 0.10f * (input - lp[0])
        if (wetArr[0] < 0.0008f && target < 0.5f) {
            wetArr[0] = 0f
            return input
        }
        val lfo = busLfoMod
        var tone = input
        val eqMix: Float
        if (padCy < 0f) {
            val d = -padCy
            val dCurve = d * d * d
            val wc = 0.070f * (1f - dCurve) + 0.0045f * dCurve
            lp[0] += wc * (input - lp[0])
            val vol = 1f - dCurve * 0.92f
            tone = lp[0] * vol
            eqMix = if (wetArr[0] > dCurve) wetArr[0] else dCurve
        } else {
            tone = input
            eqMix = wetArr[0]
        }
        val mixed = input * (1f - eqMix) + tone * eqMix
        val absY = if (padCy >= 0f) padCy else -padCy
        val depth = if (!want || absY < 0.25f) {
            0f
        } else if (padCy < 0f) {
            ((absY - 0.25f) / 0.75f) * 0.85f
        } else {
            ((absY - 0.25f) / 0.75f) * 0.70f
        }
        var out = mixed * (1f + lfo * depth)
        val aOut = if (out >= 0f) out else -out
        if (aOut > 0.75f) {
            val sgn = if (out >= 0f) 1f else -1f
            out = sgn * (0.75f + (aOut - 0.75f) * 0.32f)
        }
        if (out > 0.94f) out = 0.94f else if (out < -0.94f) out = -0.94f
        return out
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
    private val looperHotIdx = IntArray(20)
    @Volatile var externalVolume = 1.0f
    val externalVisualizerBuffer = FloatArray(128)
    @Volatile private var externalVisWrite = 0
    @Volatile var isExternalPlayingUi = false
















    @Volatile private var isRecording = false
    @Volatile private var recFadeTarget = 0f
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
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
















        val safeBufferSize = maxOf(minBufferSize, bufferSizeFrames * 8)
















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
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
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
            val buffer = ShortArray(bufferSize * 2)
            val byteBuffer = ByteBuffer.allocate(bufferSize * 4).order(ByteOrder.LITTLE_ENDIAN)
            val recPool = Array(8) { ByteArray(bufferSize * 4) }
            var recPoolIdx = 0
            val fadeCoeff = 1f - kotlin.math.exp((-1.0 / (0.150 * sampleRate)).toFloat())
            val padCoeffAtk = 1f - kotlin.math.exp((-1.0 / (0.090 * sampleRate)).toFloat())
            val padCoeffRel = 1f - kotlin.math.exp((-1.0 / (0.180 * sampleRate)).toFloat())
            val recFadeStep = (1.0 / (0.010 * sampleRate)).toFloat()
            val limRelease = 0.99945f
            val limThresh = 0.88f
            var limEnv = 0f
            var recFade = 0f
            var lastRevSend = 0f
            var visRing = 0
            val recBytesPerBuf = bufferSize * 6
            val recPool24 = Array(8) { ByteArray(recBytesPerBuf) }
            var recDither = 0x13579bdf
















            var warm = 0
            while (warm < 24) {
                dspEngine.processNextSample(noteSlots, maxVoices, 0f)
                drumEngine.processNextSample()
                liveLp[0] = 0f
                drumLp[0] = 0f
                drumLpR[0] = 0f
                loopLp[0] = 0f
                loopLpR[0] = 0f
                micLp[0] = 0f
                micLpR[0] = 0f
                warm++
            }
            while (isRunning) {
                val recOn = isRecording
                if (recOn) byteBuffer.clear()
                val padY01 = smoothModY.coerceIn(0f, 1f)
                val padAx = kotlin.math.abs(smoothModX * 2f - 1f).coerceIn(0f, 1f)
                val padLfoHz = 0.38 + padY01 * 6.8 + padY01 * padAx * 8.0
                var looperHotN = 0
                var ht = 0
                while (ht < looperTrackCount) {
                    val tr = looperTracks[ht]
                    if (tr.isRecording || tr.isPlaying) {
                        looperHotIdx[looperHotN] = ht
                        looperHotN++
                    }
                    ht++
                }
                var loopAutoOn = false
                var loopAutoCx = 0f
                var loopAutoCy = 0f
                ht = 0
                while (ht < looperHotN) {
                    val tr = looperTracks[looperHotIdx[ht]]
                    if (tr.isRecording) tr.stampPad(busPadX, busPadY)
                    if (tr.isPlaying && tr.hasPadAuto()) {
                        loopAutoOn = true
                        loopAutoCx = (tr.padXAtPlay() * 2f - 1f).coerceIn(-1f, 1f)
                        loopAutoCy = (tr.padYAtPlay() * 2f - 1f).coerceIn(-1f, 1f)
                    }
                    ht++
                }
















                if (!busPadTouched) {
                    busPadX += (0.5f - busPadX) * 0.12f
                    busPadY += (0.5f - busPadY) * 0.12f
                }
                dspEngine.liveWahAmt = if (padTargetKey) padWah else 0f
                dspEngine.liveOctAmt = if (padTargetKey) padOct else 0f
                dspEngine.liveChoAmt = if (padTargetKey) padCho else 0f
                dspEngine.bindControls(
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
                    performanceX = 0f,
                    performanceY = 0f,
                    loopPerformanceX = 0f,
                    loopPerformanceY = 0f,
                    drumVolume = 1f,
                    driveAmount = driveAmount,
                    detuneOn = detuneOn,
                    externalVolume = externalVolume,
                    subOn = subOn,
                    warmOn = warmOn,
                    vibeOn = vibeOn,
                    ripOn = ripOn,
                    fuzzOn = fuzzOn,
                    phazOn = phazOn,
                    pianoOn = pianoOn,
                    div2On = div2On,
                    div3On = div3On,
                    div4On = div4On,
                    reverbMix = reverbMix
                )
                for (i in 0 until bufferSize) {
                    val frame = dspEngine.processNextSample(
                        noteSlots = noteSlots,
                        maxVoices = maxVoices,
                        extraReverbSend = lastRevSend
                    )
















                    val drumSample = drumEngine.processNextSample()

                    // 4-track PCM looper: record the frozen live wet tap and/or
                    // play already-captured audio. Added after master saturate so
                    // current live drive/detune/waveform cannot recast old takes.
                    var pcmLoopL = 0f
                    var pcmLoopR = 0f
                    val liveTap = frame.liveRecordTap
                    var h = 0
                    while (h < looperHotN) {
                        val track = looperTracks[looperHotIdx[h]]
                        if (track.isRecording) {
                            track.pushSample(liveTap)
                        }
                        if (track.isPlaying) {
                            val s = track.readSample()
                            pcmLoopL += s * track.panL
                            pcmLoopR += s * track.panR
                        }
                        h++
                    }
                    lastRevSend = (pcmLoopL + pcmLoopR) * 0.35f + drumSample * 0.35f

                    if (dspEngine.isExternalAudioPlaying) {
                        val ev = frame.externalSample
                        externalVisualizerBuffer[externalVisWrite] = ev
                        externalVisWrite = (externalVisWrite + 1) % externalVisualizerBuffer.size
                    }

                    val micPlay = micEngine.playMixSample()
                    val micMon = micEngine.nextMonitorSample()
                    val padCoeff = if (busPadTouched) padCoeffAtk else padCoeffRel
                    smoothModX += (busPadX - smoothModX) * padCoeff
                    smoothModY += (busPadY - smoothModY) * padCoeff
                    padCx = (smoothModX * 2f - 1f).coerceIn(-1f, 1f)
                    padCy = (smoothModY * 2f - 1f).coerceIn(-1f, 1f)
                    val padAny = padTargetKey || padTargetMic || padTargetLoop || padTargetDrum
                    if (padAny && (busPadTouched || liveWet[0] > 0.002f || drumWet[0] > 0.002f || loopWet[0] > 0.002f || micWet[0] > 0.002f)) {
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
                    val drumL0 = drumEngine.lastOutL
                    val drumR0 = drumEngine.lastOutR
                    val micL0 = micEngine.lastMixL
                    val micR0 = micEngine.lastMixR
                    val padWork = rollOn || drumRollDiv >= 4 || busPadTouched ||
                        rollLive.isHot() || rollDrumL.isHot() || rollDrumR.isHot() ||
                        rollLoopL.isHot() || rollLoopR.isHot() || rollMicL.isHot() || rollMicR.isHot() ||
                        liveWet[0] > 0.0008f ||
                        drumWet[0] > 0.0008f || drumWetR[0] > 0.0008f ||
                        loopWet[0] > 0.0008f || loopWetR[0] > 0.0008f ||
                        micWet[0] > 0.0008f || micWetR[0] > 0.0008f
                    var liveOut: Float
                    var drumOutL: Float
                    var drumOutR: Float
                    var loopOutL: Float
                    var loopOutR: Float
                    var micOutL: Float
                    var micOutR: Float
                    if (padWork) {
                        liveOut = rollLive.process(frame.masterSample, rollOn && padTargetKey, busStutterDiv, bpmNow, sampleRate)
                        drumOutL = rollDrumL.process(drumL0, drumRollDiv >= 4, drumRollDiv, bpmNow, sampleRate)
                        drumOutR = rollDrumR.process(drumR0, drumRollDiv >= 4, drumRollDiv, bpmNow, sampleRate)
                        loopOutL = rollLoopL.process(pcmLoopL, rollOn && padTargetLoop, busStutterDiv, bpmNow, sampleRate)
                        loopOutR = rollLoopR.process(pcmLoopR, rollOn && padTargetLoop, busStutterDiv, bpmNow, sampleRate)
                        micOutL = rollMicL.process(micL0, rollOn && padTargetMic, busStutterDiv, bpmNow, sampleRate)
                        micOutR = rollMicR.process(micR0, rollOn && padTargetMic, busStutterDiv, bpmNow, sampleRate)
                        liveOut = shapePadStem(liveOut, padTargetKey, liveLp, liveWet)
                        drumOutL = shapePadStem(drumOutL, padTargetDrum, drumLp, drumWet)
                        drumOutR = shapePadStem(drumOutR, padTargetDrum, drumLpR, drumWetR)
                        val savedCx = padCx
                        val savedCy = padCy
                        if (loopAutoOn) {
                            padCx = loopAutoCx
                            padCy = loopAutoCy
                        }
                        loopOutL = shapePadStem(loopOutL, padTargetLoop || loopAutoOn, loopLp, loopWet)
                        loopOutR = shapePadStem(loopOutR, padTargetLoop || loopAutoOn, loopLpR, loopWetR)
                        if (padTargetLoop || loopAutoOn) {
                            if (padCx > 0f) loopOutL *= 1f - padCx * 0.88f
                            if (padCx < 0f) loopOutR *= 1f + padCx * 0.88f
                        }
                        padCx = savedCx
                        padCy = savedCy
                        micOutL = shapePadStem(micOutL, padTargetMic, micLp, micWet)
                        micOutR = shapePadStem(micOutR, padTargetMic, micLpR, micWetR)
                        if (padTargetDrum) {
                            if (padCx > 0f) drumOutL *= 1f - padCx * 0.88f
                            if (padCx < 0f) drumOutR *= 1f + padCx * 0.88f
                        }
                        if (padTargetMic) {
                            if (padCx > 0f) micOutL *= 1f - padCx * 0.88f
                            if (padCx < 0f) micOutR *= 1f + padCx * 0.88f
                        }
                    } else if (loopAutoOn) {
                        liveOut = frame.masterSample
                        drumOutL = drumL0
                        drumOutR = drumR0
                        micOutL = micL0
                        micOutR = micR0
                        val savedCx = padCx
                        val savedCy = padCy
                        padCx = loopAutoCx
                        padCy = loopAutoCy
                        loopOutL = shapePadStem(pcmLoopL, true, loopLp, loopWet)
                        loopOutR = shapePadStem(pcmLoopR, true, loopLpR, loopWetR)
                        if (padCx > 0f) loopOutL *= 1f - padCx * 0.88f
                        if (padCx < 0f) loopOutR *= 1f + padCx * 0.88f
                        padCx = savedCx
                        padCy = savedCy
                    } else {
                        liveOut = frame.masterSample
                        drumOutL = drumL0
                        drumOutR = drumR0
                        loopOutL = pcmLoopL
                        loopOutR = pcmLoopR
                        micOutL = micL0
                        micOutR = micR0
                    }
                    val fxAmt = padWah + padOct + padCho
                    if (fxAmt > 0.002f && padTargetDrum) {
                        drumOutL = colorDrumL.process(drumOutL, padWah, padOct, padCho)
                        drumOutR = colorDrumR.process(drumOutR, padWah, padOct, padCho)
                    }
                    if (fxAmt > 0.002f && padTargetLoop) {
                        loopOutL = colorLoopL.process(loopOutL, padWah, padOct, padCho)
                        loopOutR = colorLoopR.process(loopOutR, padWah, padOct, padCho)
                    }
                    if (fxAmt > 0.002f && padTargetMic) {
                        micOutL = colorMicL.process(micOutL, padWah, padOct, padCho)
                        micOutR = colorMicR.process(micOutR, padWah, padOct, padCho)
                    }
                    val stopTarget = if (busHoldStop) 0f else 1f
                    stopGain += (stopTarget - stopGain) * fadeCoeff
                    val drumStopTarget = if (drumHoldStop || (busHoldStop && padTargetDrum)) 0f else 1f
                    drumStopGain += (drumStopTarget - drumStopGain) * fadeCoeff
                    if (padTargetKey) liveOut *= stopGain
                    drumOutL *= drumStopGain
                    drumOutR *= drumStopGain
                    if (padTargetLoop) {
                        loopOutL *= stopGain
                        loopOutR *= stopGain
                    }
                    if (padTargetMic) {
                        micOutL *= stopGain
                        micOutR *= stopGain
                    }
                    val center = 0.70710677f
                    val livePanW = if (padTargetKey) 1f else 0f
                    val liveL = center * (1f - padCx * 0.88f * livePanW)
                    val liveR = center * (1f + padCx * 0.88f * livePanW)
                    var rawL = liveOut * liveL + drumOutL + loopOutL + micOutL + micMon * center
                    var rawR = liveOut * liveR + drumOutR + loopOutR + micOutR + micMon * center
                    val absL = abs(rawL)
                    val absR = abs(rawR)
                    val peak = if (absL > absR) absL else absR
                    limEnv = if (peak > limEnv) peak else limEnv * limRelease
                    val limG = if (limEnv > limThresh) limThresh / limEnv else 1f
                    rawL *= limG
                    rawR *= limG
                    if (rawL > 1f) rawL = 1f else if (rawL < -1f) rawL = -1f
                    if (rawR > 1f) rawR = 1f else if (rawR < -1f) rawR = -1f
                    val shortL = (rawL * Short.MAX_VALUE * 0.92f).toInt().coerceIn(-32768, 32767).toShort()
                    val shortR = (rawR * Short.MAX_VALUE * 0.92f).toInt().coerceIn(-32768, 32767).toShort()
















                    buffer[i * 2] = shortL
                    buffer[i * 2 + 1] = shortR
                    if ((i and 7) == 0) {
                        liveVisualizerBuffer[visRing] = frame.liveSample
                        looperVisualizerBuffer[visRing] = (pcmLoopL + pcmLoopR) * 0.5f
                        drumVisualizerBuffer[visRing] = drumSample
                        visRing++
                        if (visRing >= liveVisualizerBuffer.size) visRing = 0
                    }
                    if (recFadeTarget > recFade) {
                        recFade += recFadeStep
                        if (recFade > recFadeTarget) recFade = recFadeTarget
                    } else if (recFadeTarget < recFade) {
                        recFade -= recFadeStep
                        if (recFade < recFadeTarget) recFade = recFadeTarget
                    }
                    if (recOn || recFade > 0.0001f) {
                        recDither = recDither * 1664525 + 1013904223
                        val d1 = ((recDither ushr 8) and 0xFF) / 8388608f
                        recDither = recDither * 1664525 + 1013904223
                        val d2 = ((recDither ushr 8) and 0xFF) / 8388608f
                        val dither = d1 - d2
                        val slot = recPool24[recPoolIdx]
                        val o = i * 6
                        val vL = ((rawL * recFade + dither) * 8388607f).toInt().coerceIn(-8388608, 8388607)
                        val vR = ((rawR * recFade + dither) * 8388607f).toInt().coerceIn(-8388608, 8388607)
                        slot[o] = (vL and 0xFF).toByte()
                        slot[o + 1] = (vL shr 8 and 0xFF).toByte()
                        slot[o + 2] = (vL shr 16 and 0xFF).toByte()
                        slot[o + 3] = (vR and 0xFF).toByte()
                        slot[o + 4] = (vR shr 8 and 0xFF).toByte()
                        slot[o + 5] = (vR shr 16 and 0xFF).toByte()
                    }
















                }
















                // Sample Performance Pad position while loop-recording (~every 15 ms)
                if (isLoopRecording) {
                    val now = System.currentTimeMillis() - loopStartTime
                    if (now - lastPadSampleTime >= 15L) {
                        recordedPadEvents.add(PadEvent(now, performanceX, performanceY))
                        lastPadSampleTime = now
                    }
                }
















                if (recOn || recFade > 0.0001f) {
                    recordingQueue.offer(recPool24[recPoolIdx])
                    recPoolIdx = (recPoolIdx + 1) % recPool24.size
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

    fun setTrackPan(trackIndex: Int, pan: Float) {
        looperTracks.getOrNull(trackIndex)?.applyPan(pan)
    }

    fun saveLooperSession(
        projectName: String,
        channelNames: List<String>,
        currentPage: Int,
        dir: File = File(context.filesDir, "looper_session")
    ) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val json = JSONObject()
            json.put("projectName", projectName)
            json.put("currentPage", currentPage.coerceIn(0, looperPageCount - 1))
            val names = JSONArray()
            val vols = JSONArray()
            val pans = JSONArray()
            for (t in 0 until looperTrackCount) {
                names.put(channelNames.getOrNull(t) ?: "ערוץ ${(t % looperTracksPerPage) + 1}")
                vols.put(looperTracks[t].volume.toDouble())
                pans.put(looperTracks[t].pan.toDouble())
                val pcm = File(dir, "track$t.pcm")
                if (looperTracks[t].hasContent() || looperTracks[t].isRecording) {
                    looperTracks[t].writeSessionPcm(pcm)
                } else if (pcm.exists()) {
                    pcm.delete()
                }
            }
            json.put("channelNames", names)
            json.put("volumes", vols)
            json.put("pans", pans)
            File(dir, "session.json").writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadLooperSession(dir: File = File(context.filesDir, "looper_session")): LooperProjectMeta? {
        return try {
            val metaFile = File(dir, "session.json")
            if (!dir.exists()) return null
            var projectName = "פרויקט לופר"
            var currentPage = 0
            val channelNames = MutableList(looperTrackCount) { "ערוץ ${(it % looperTracksPerPage) + 1}" }
            val volumes = MutableList(looperTrackCount) { 1.0f }
            val pans = MutableList(looperTrackCount) { 0f }
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
                val pn = json.optJSONArray("pans")
                if (pn != null) {
                    for (i in 0 until minOf(pn.length(), looperTrackCount)) {
                        pans[i] = pn.optDouble(i, 0.0).toFloat().coerceIn(-1f, 1f)
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
                looperTracks[t].applyPan(pans[t])
            }
            LooperProjectMeta(projectName, channelNames, volumes, currentPage, pans)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveMicSession(
        projectName: String,
        channelNames: List<String>,
        currentPage: Int,
        dir: File
    ) {
        try {
            if (!dir.exists()) dir.mkdirs()
            val n = micEngine.trackCount
            val json = JSONObject()
            json.put("projectName", projectName)
            json.put("currentPage", currentPage.coerceIn(0, micEngine.pageCount - 1))
            val names = JSONArray()
            val vols = JSONArray()
            val pans = JSONArray()
            for (t in 0 until n) {
                names.put(channelNames.getOrNull(t) ?: "ערוץ ${(t % micEngine.tracksPerPage) + 1}")
                vols.put(micEngine.tracks[t].volume.toDouble())
                pans.put(micEngine.trackFx[t].pan.toDouble())
                micEngine.tracks[t].writeSessionPcm(File(dir, "track$t.pcm"))
            }
            json.put("channelNames", names)
            json.put("volumes", vols)
            json.put("pans", pans)
            File(dir, "session.json").writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadMicSession(dir: File): MicProjectMeta? {
        return try {
            if (!dir.exists()) return null
            val n = micEngine.trackCount
            var projectName = "פרויקט מיק"
            var currentPage = 0
            val channelNames = MutableList(n) { "ערוץ ${(it % micEngine.tracksPerPage) + 1}" }
            val volumes = MutableList(n) { 1.0f }
            val pans = MutableList(n) { 0f }
            val metaFile = File(dir, "session.json")
            if (metaFile.exists()) {
                val json = JSONObject(metaFile.readText())
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
                val pn = json.optJSONArray("pans")
                if (pn != null) {
                    for (i in 0 until minOf(pn.length(), n)) {
                        pans[i] = pn.optDouble(i, 0.0).toFloat().coerceIn(-1f, 1f)
                    }
                }
            }
            for (t in 0 until n) {
                val pcm = File(dir, "track$t.pcm")
                if (pcm.exists()) {
                    val bytes = pcm.readBytes()
                    if (bytes.size >= 4) {
                        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        val size = bb.int
                        if (size > 0 && size * 4 + 4 <= bytes.size) {
                            val floats = FloatArray(size)
                            for (i in 0 until size) floats[i] = bb.float
                            micEngine.tracks[t].loadFromSamples(floats)
                        }
                    }
                } else {
                    micEngine.tracks[t].clear()
                }
                micEngine.tracks[t].volume = volumes[t]
                micEngine.trackFx[t].volume = volumes[t]
                micEngine.trackFx[t].applyPan(pans[t])
            }
            MicProjectMeta(projectName, channelNames, volumes, currentPage, pans)
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
        val currentPage: Int = 0,
        val pans: List<Float> = emptyList()
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
                    val pans = JSONArray()
                    for (t in 0 until n) {
                        names.put(channelNames.getOrNull(t) ?: "ערוץ ${(t % micEngine.tracksPerPage) + 1}")
                        vols.put(micEngine.tracks[t].volume.toDouble())
                        pans.put(micEngine.trackFx[t].pan.toDouble())
                    }
                    json.put("channelNames", names)
                    json.put("volumes", vols)
                    json.put("pans", pans)
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
            val pans = MutableList(n) { 0f }
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
                            val pn = json.optJSONArray("pans")
                            if (pn != null) {
                                for (i in 0 until minOf(pn.length(), n)) {
                                    pans[i] = pn.optDouble(i, 0.0).toFloat().coerceIn(-1f, 1f)
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
                micEngine.trackFx[t].applyPan(pans[t])
            }
            MicProjectMeta(projectName, channelNames, volumes, currentPage, pans)
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
                    val pans = JSONArray()
                    for (t in 0 until looperTrackCount) {
                        val page = t / looperTracksPerPage
                        val row = t % looperTracksPerPage
                        names.put(channelNames.getOrNull(t) ?: "ערוץ ${row + 1}")
                        vols.put(looperTracks[t].volume.toDouble())
                        pans.put(looperTracks[t].pan.toDouble())
                    }
                    json.put("channelNames", names)
                    json.put("volumes", vols)
                    json.put("pans", pans)
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
        val currentPage: Int = 0,
        val pans: List<Float> = emptyList()
    )

    fun importLooperProjectFromZip(context: Context, sourceUri: Uri): LooperProjectMeta? {
        return try {
            var projectName = "פרויקט לופר"
            var currentPage = 0
            val channelNames = MutableList(looperTrackCount) { "ערוץ ${(it % looperTracksPerPage) + 1}" }
            val volumes = MutableList(looperTrackCount) { 1.0f }
            val pans = MutableList(looperTrackCount) { 0f }
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
                            val pn = json.optJSONArray("pans")
                            if (pn != null) {
                                for (i in 0 until minOf(pn.length(), looperTrackCount)) {
                                    pans[i] = pn.optDouble(i, 0.0).toFloat().coerceIn(-1f, 1f)
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
                looperTracks[t].applyPan(pans[t])
            }
            LooperProjectMeta(projectName, channelNames, volumes, currentPage, pans)
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
            recFadeTarget = 1f
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
        recFadeTarget = 0f
















        CoroutineScope(Dispatchers.IO).launch {
            try {
                Thread.sleep(25)
                isRecording = false
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
        val channels = 2
        val bits = 24
        val blockAlign = channels * (bits / 8)
        val byteRate = longSampleRate * blockAlign
















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
        header[32] = (blockAlign and 0xff).toByte()
        header[33] = (blockAlign shr 8 and 0xff).toByte()
        header[34] = (bits and 0xff).toByte()
        header[35] = (bits shr 8 and 0xff).toByte()
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
