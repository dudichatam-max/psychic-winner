package com.microtonal.synth

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max



class MicCaptureEngine(private val sampleRate: Int) {
    val trackCount = 5
    val tracks: Array<LooperPcmTrack> = Array(trackCount) { LooperPcmTrack(sampleRate) }

    @Volatile var monitorEnabled: Boolean = false
    @Volatile var monitorVolume: Float = 0.7f
    @Volatile var headphonesOnlyMonitor: Boolean = true
    @Volatile var headphonesConnected: Boolean = false

    @Volatile var hpfHz: Float = 100f
    @Volatile var gateThresh: Float = 0.03f
    @Volatile var lowGain: Float = 1.0f
    @Volatile var presenceGain: Float = 1.15f
    @Volatile var compAmount: Float = 0.35f

    @Volatile var recordingTrack: Int = -1

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var capturing: Boolean = false

    private val ring = FloatArray(8192)
    @Volatile private var ringWrite = 0
    @Volatile private var ringRead = 0

    private var hpZ = 0f
    private var lowZ = 0f
    private var presZ = 0f
    private var env = 0f
    private var gateOpen = 0f

    fun applyPreset(kind: String) {
        when (kind) {
            "vocal" -> {
                hpfHz = 110f; gateThresh = 0.028f; lowGain = 0.92f; presenceGain = 1.25f; compAmount = 0.42f
            }
            "guitar" -> {
                hpfHz = 70f; gateThresh = 0.018f; lowGain = 1.05f; presenceGain = 1.12f; compAmount = 0.28f
            }
            else -> {
                hpfHz = 140f; gateThresh = 0.04f; lowGain = 0.85f; presenceGain = 1.18f; compAmount = 0.38f
            }
        }
    }

    @Synchronized
    fun startCapture(): Boolean {
        if (capturing) return true
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return false
        val sources = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.MIC
        )
        var rec: AudioRecord? = null
        for (src in sources) {
            try {
                val candidate = AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    min * 2
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    rec = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (_: Exception) { }
        }
        if (rec == null) return false
        disableAndroidVoiceFx(rec.audioSessionId)
        audioRecord = rec
        capturing = true
        rec.startRecording()
        captureThread = Thread({
            val buf = ShortArray(256)
            while (capturing) {
                val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n <= 0) continue
                for (i in 0 until n) {
                    val raw = buf[i] / 32768f
                    val processed = processFx(raw)
                    val w = ringWrite
                    ring[w] = processed
                    ringWrite = (w + 1) % ring.size
                    val t = recordingTrack
                    if (t in 0 until trackCount && tracks[t].isRecording) {
                        tracks[t].pushSample(processed)
                    }
                }
            }
        }, "SirenMicCapture").also { it.start() }
        return true
    }

    @Synchronized
    fun stopCapture() {
        capturing = false
        try { captureThread?.join(200) } catch (_: Exception) { }
        captureThread = null
        try { audioRecord?.stop() } catch (_: Exception) { }
        try { audioRecord?.release() } catch (_: Exception) { }
        audioRecord = null
        recordingTrack = -1
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
        if (!monitorEnabled) return 0f
        if (headphonesOnlyMonitor && !headphonesConnected) return 0f
        val w = ringWrite
        if (w == 0 && ring[0] == 0f) return 0f
        val idx = if (w == 0) ring.size - 1 else w - 1
        return ring[idx] * monitorVolume
    }

    fun playMixSample(): Float {
        var mix = 0f
        for (t in 0 until trackCount) {
            if (tracks[t].isPlaying) mix += tracks[t].readSample()
        }
        return mix
    }

    fun cleanTrack(index: Int) {
        val track = tracks.getOrNull(index) ?: return
        if (!track.hasContent()) return
        val samples = track.copySamples()
        var z = 0f
        val hp = 1f - exp((-2.0 * Math.PI * 120.0 / sampleRate).toFloat())
        val out = FloatArray(samples.size)
        var e = 0f
        for (i in samples.indices) {
            val x = samples[i]
            z += hp * (x - z)
            val hpOut = x - z
            e = e * 0.995f + abs(hpOut) * 0.005f
            val g = if (e < 0.02f) (e / 0.02f).coerceIn(0f, 1f) else 1f
            out[i] = hpOut * g
        }
        track.loadFromSamples(out)
    }

    private fun processFx(input: Float): Float {
        val hpCoeff = 1f - exp((-2.0 * Math.PI * hpfHz.toDouble() / sampleRate).toFloat())
        hpZ += hpCoeff * (input - hpZ)
        var x = input - hpZ

        val absx = abs(x)
        env = env * 0.97f + absx * 0.03f
        val targetGate = if (env > gateThresh) 1f else 0.08f
        gateOpen += 0.08f * (targetGate - gateOpen)
        x *= gateOpen

        val lowCoeff = 1f - exp((-2.0 * Math.PI * 180.0 / sampleRate).toFloat())
        lowZ += lowCoeff * (x - lowZ)
        val lows = lowZ * lowGain
        val highs = (x - lowZ)

        val pCoeff = 1f - exp((-2.0 * Math.PI * 3200.0 / sampleRate).toFloat())
        presZ += pCoeff * (highs - presZ)
        val presence = (highs - presZ) * presenceGain

        var y = lows + presence

        val cEnv = max(abs(y), env)
        val thresh = 0.25f
        if (cEnv > thresh && compAmount > 0f) {
            val over = (cEnv - thresh) / (cEnv + 1e-6f)
            y *= (1f - over * compAmount * 0.7f)
        }
        return y.coerceIn(-1f, 1f)
    }

    private fun disableAndroidVoiceFx(sessionId: Int) {
        try {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.enabled = false
        } catch (_: Exception) { }
        try {
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.enabled = false
        } catch (_: Exception) { }
        try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.enabled = false
        } catch (_: Exception) { }
    }
}
