package com.microtonal.synth

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

class MicChannelFx {
    @Volatile var hpfHz: Float = 50f
    @Volatile var gateThresh: Float = 0.002f
    @Volatile var presenceGain: Float = 1.0f
    @Volatile var compAmount: Float = 0.0f
    @Volatile var volume: Float = 1.0f
    private var hpZ = 0f
    private var presZ = 0f
    private var env = 0f
    private var gateOpen = 1f

    fun process(input: Float, sampleRate: Int): Float {
        val hpCoeff = 1f - exp((-2.0 * Math.PI * hpfHz.toDouble() / sampleRate).toFloat())
        hpZ += hpCoeff * (input - hpZ)
        var x = input - hpZ
        env = env * 0.97f + abs(x) * 0.03f
        val target = if (env > gateThresh) 1f else 0.15f
        gateOpen += 0.1f * (target - gateOpen)
        x *= gateOpen
        val pCoeff = 1f - exp((-2.0 * Math.PI * 2800.0 / sampleRate).toFloat())
        presZ += pCoeff * (x - presZ)
        x = presZ + (x - presZ) * presenceGain
        if (env > 0.22f && compAmount > 0f) {
            val over = (env - 0.22f) / (env + 1e-6f)
            x *= (1f - over * compAmount * 0.65f)
        }
        return (x * volume).coerceIn(-1f, 1f)
    }
}

class MonitorPresetSlot {
    var name: String = "פריסט"
    var monitorVolume: Float = 1.0f
    var inputGain: Float = 2.0f
    var hpfHz: Float = 50f
    var gateThresh: Float = 0.002f
    var lowGain: Float = 1.0f
    var presenceGain: Float = 1.0f
    var compAmount: Float = 0.0f
}

class MicCaptureEngine(private val sampleRate: Int) {
    val pageCount = 2
    val tracksPerPage = 3
    val trackCount = pageCount * tracksPerPage
    // Page 0 = vocals (long takes). Page 1 = guitar/bass (long but bounded).
    val tracks: Array<LooperPcmTrack> = Array(trackCount) { idx ->
        LooperPcmTrack(sampleRate, if (idx < tracksPerPage) 600 else 180)
    }
    val trackFx: Array<MicChannelFx> = Array(trackCount) { MicChannelFx() }
    val monitorPresets: Array<MonitorPresetSlot> = Array(3) {
        MonitorPresetSlot().also { p -> p.name = "פריסט ${it + 1}" }
    }

    val monitorVisualizer = FloatArray(128)
    @Volatile private var monVisWrite = 0
    @Volatile var inputLevel: Float = 0f

    @Volatile var monitorEnabled: Boolean = false
    @Volatile var monitorVolume: Float = 1.0f
    @Volatile var inputGain: Float = 2.0f
    @Volatile var headphonesConnected: Boolean = false

    @Volatile var hpfHz: Float = 50f
    @Volatile var gateThresh: Float = 0.002f
    @Volatile var lowGain: Float = 1.0f
    @Volatile var presenceGain: Float = 1.0f
    @Volatile var compAmount: Float = 0.0f

    @Volatile var recordingTrack: Int = -1
    @Volatile var lastError: String = ""

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing: Boolean = false

    private val ring = FloatArray(16384)
    @Volatile private var ringWrite = 0
    @Volatile private var ringRead = 0
    @Volatile private var lastMon = 0f

    private var hpZ = 0f
    private var lowZ = 0f
    private var presZ = 0f
    private var env = 0f
    private var gateOpen = 1f

    fun saveMonitorPreset(slot: Int) {
        val p = monitorPresets.getOrNull(slot) ?: return
        p.monitorVolume = monitorVolume
        p.inputGain = inputGain
        p.hpfHz = hpfHz
        p.gateThresh = gateThresh
        p.lowGain = lowGain
        p.presenceGain = presenceGain
        p.compAmount = compAmount
    }

    fun loadMonitorPreset(slot: Int) {
        val p = monitorPresets.getOrNull(slot) ?: return
        monitorVolume = p.monitorVolume
        inputGain = p.inputGain
        hpfHz = p.hpfHz
        gateThresh = p.gateThresh
        lowGain = p.lowGain
        presenceGain = p.presenceGain
        compAmount = p.compAmount
    }

    @Synchronized
    fun startCapture(): Boolean {
        if (capturing && audioRecord != null) return true
        stopCaptureInternal()
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER
        )
        var rec: AudioRecord? = null
        var min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) min = sampleRate / 20
        val recBuf = min.coerceAtLeast(1024).coerceAtMost(sampleRate / 8)
        for (src in sources) {
            try {
                val candidate = AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    rec = candidate
                    break
                }
                candidate.release()
            } catch (_: Exception) { }
        }
        if (rec == null) {
            lastError = "לא ניתן לפתוח מיקרופון"
            return false
        }
        audioRecord = rec
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(rec.audioSessionId)?.enabled = true
            }
        } catch (_: Exception) { }
        capturing = true
        ringWrite = 0
        ringRead = 0
        try {
            rec.startRecording()
        } catch (e: Exception) {
            lastError = "startRecording נכשל"
            capturing = false
            rec.release()
            audioRecord = null
            return false
        }
        captureThread = Thread({
            val buf = ShortArray(256)
            var energy = 0.0
            var seen = 0
            var blockPeak = 0f
            var blockN = 0
            while (capturing) {
                val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n <= 0) continue
                for (i in 0 until n) {
                    val raw = buf[i] / 32768f
                    energy += raw * raw
                    seen++
                    val boosted = limitPeak(raw * inputGain)
                    val monitored = processLiveFx(boosted)
                    val w = ringWrite
                    val next = (w + 1) % ring.size
                    if (next != ringRead) {
                        ring[w] = monitored
                        ringWrite = next
                    }
                    val visS = if (monitorEnabled) monitored else boosted
                    val ap = abs(visS)
                    if (ap > blockPeak) blockPeak = ap
                    blockN++
                    if (blockN >= 64) {
                        monitorVisualizer[monVisWrite] = if (visS >= 0f) blockPeak else -blockPeak
                        monVisWrite = (monVisWrite + 1) % monitorVisualizer.size
                        blockPeak = 0f
                        blockN = 0
                    }
                    val t = recordingTrack
                    if (t in 0 until trackCount && tracks[t].isRecording) {
                        tracks[t].pushSample(boosted)
                    }
                }
                if (seen >= 2048) {
                    inputLevel = sqrt((energy / seen).toFloat())
                    energy = 0.0
                    seen = 0
                }
            }
        }, "SirenMicCapture").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        lastError = ""
        return true
    }

    @Synchronized
    fun stopCapture() {
        stopCaptureInternal()
    }

    private fun stopCaptureInternal() {
        capturing = false
        try { captureThread?.join(250) } catch (_: Exception) { }
        captureThread = null
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
        recordingTrack = -1
        inputLevel = 0f
        for (i in monitorVisualizer.indices) monitorVisualizer[i] = 0f
    }

    fun startTrackRecording(index: Int): Boolean {
        val track = tracks.getOrNull(index) ?: return false
        if (!startCapture()) return false
        recordingTrack = index
        track.beginRecord()
        return true
    }

    fun stopTrackRecording(index: Int) {
        tracks.getOrNull(index)?.endRecord()
        if (recordingTrack == index) recordingTrack = -1
        if (recordingTrack < 0 && !monitorEnabled) stopCapture()
    }

    fun setMonitor(on: Boolean): Boolean {
        monitorEnabled = on
        return if (on) startCapture() else {
            if (recordingTrack < 0) stopCapture()
            true
        }
    }

    fun nextMonitorSample(): Float {
        if (!monitorEnabled) {
            lastMon = 0f
            return 0f
        }
        val w = ringWrite
        var r = ringRead
        if (r == w) return lastMon * 0.65f * monitorVolume
        // If capture ran ahead, skip stale frames so headphones hear "now".
        val pending = if (w >= r) w - r else (ring.size - r + w)
        if (pending > 1024) {
            r = (w - 256 + ring.size) % ring.size
        }
        val s = ring[r]
        ringRead = (r + 1) % ring.size
        lastMon = s
        return s * monitorVolume
    }

    fun playMixSample(): Float {
        var mix = 0f
        for (t in 0 until trackCount) {
            val track = tracks[t]
            if (!track.isPlaying) continue
            val raw = track.readSample() / track.volume.coerceAtLeast(0.0001f)
            mix += trackFx[t].process(raw, sampleRate)
        }
        return mix.coerceIn(-1f, 1f)
    }

    private fun limitPeak(x: Float): Float {
        val a = abs(x)
        if (a <= 0.88f) return x
        val extra = a - 0.88f
        return (0.88f + extra / (1f + extra * 6f)) * sign(x)
    }

    private fun processLiveFx(input: Float): Float {
        val hpCoeff = 1f - exp((-2.0 * Math.PI * hpfHz.toDouble() / sampleRate).toFloat())
        hpZ += hpCoeff * (input - hpZ)
        var x = input - hpZ
        env = env * 0.96f + abs(x) * 0.04f
        val targetGate = if (env > gateThresh) 1f else 0.2f
        gateOpen += 0.12f * (targetGate - gateOpen)
        x *= gateOpen
        val lowCoeff = 1f - exp((-2.0 * Math.PI * 180.0 / sampleRate).toFloat())
        lowZ += lowCoeff * (x - lowZ)
        val lows = lowZ * lowGain
        val highs = x - lowZ
        val pCoeff = 1f - exp((-2.0 * Math.PI * 3000.0 / sampleRate).toFloat())
        presZ += pCoeff * (highs - presZ)
        var y = lows + presZ + (highs - presZ) * presenceGain
        if (env > 0.25f && compAmount > 0f) {
            val over = (env - 0.25f) / (env + 1e-6f)
            y *= (1f - over * compAmount * 0.7f)
        }
        return y.coerceIn(-1f, 1f)
    }
}
