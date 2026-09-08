package com.microtonal.synth
 
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class LooperTrackRestoreSnapshot(
    val samples: FloatArray,
    val volume: Float,
    val pan: Float,
    val wasPlaying: Boolean,
    val playPos: Int,
    val padN: Int,
    val padI: Int,
    val lastStampMs: Long,
    val padMs: LongArray,
    val padXs: FloatArray,
    val padYs: FloatArray
)

enum class BenchPhase {
    IDLE, WARMUP, MEASURE, COMPLETED, ERROR
}

enum class BenchVerdict {
    PASS, WARNING, FAIL, ERROR
}

class BenchReport(
    val verdict: BenchVerdict,
    val errorMessage: String?,
    val device: String,
    val androidVersion: String,
    val appVersion: String,
    val sampleRate: Int,
    val bufferFrames: Int,
    val deadlineNs: Long,
    val includeLooper: Boolean,
    val includeDrums: Boolean,
    val workloadId: String = "real-session-4-loopers-v1",
    val waveformType: Int,
    val bpm: Float,
    val avgNs: Long,
    val p50Ns: Long,
    val p95Ns: Long,
    val p99Ns: Long,
    val maxNs: Long,
    val buffers: Int,
    val misses: Int,
    val missRate: Double,
    val underrunsAvailable: Boolean,
    val deltaUnderruns: Int,
    val cpuAvgPct: Double,
    val cpuPeakPct: Double,
    // Optional sampled hot-path profiling (average estimated time per audio buffer).
    val profileDspAvgNs: Long = 0L,
    val profileDrumsAvgNs: Long = 0L,
    val profileLooperAvgNs: Long = 0L,
    val profileMicAvgNs: Long = 0L,
    val profilePadMasterAvgNs: Long = 0L,
    val profileAudioWriteAvgNs: Long = 0L,
    val profileVoiceAvgNs: Long = 0L,
    val profileZdfAvgNs: Long = 0L,
    val profileExternalAvgNs: Long = 0L,
    val profileLiveFxAvgNs: Long = 0L,
    val profileDelayAvgNs: Long = 0L,
    val profileReverbAvgNs: Long = 0L,
    val profileMasterAvgNs: Long = 0L,
    val profileVoiceFreqAvgNs: Long = 0L,
    val profileEnvelopeAvgNs: Long = 0L,
    val profileOscillatorAvgNs: Long = 0L,
    val profileModulationAvgNs: Long = 0L,
    val profileVoiceMixAvgNs: Long = 0L,
    val profileOscMainAvgNs: Long = 0L,
    val profileOscPianoAvgNs: Long = 0L,
    val profileOscSubAvgNs: Long = 0L,
    val profileOscDetuneAvgNs: Long = 0L,
    val profileOscDividersAvgNs: Long = 0L
)

/**
 * Measurement guest. Does not own audio output.
 * Audio thread may call [onBufferDone] only.
 */
class AudioBenchmark(private val engine: SynthEngine) {

    companion object {
        const val WORKLOAD_ID = "real-session-4-loopers-v1"
        const val STRESS_WORKLOAD_ID = "stress-max-v1"
        const val WARMUP_MS = 3_000L
        const val MEASURE_MS = 30_000L
        const val TOTAL_MS = WARMUP_MS + MEASURE_MS
        const val PROCESS_FRAMES = 512
        const val FIXTURE_SECONDS = 2.0
        private const val MAX_SAMPLES = 16_384

        val FREQS = floatArrayOf(
            222.00f, 299.00f, 333.00f, 355.00f,
            396.00f, 444.00f, 463.00f, 477.00f
        )

        // atMs, on=true/false, freq index. Logically verified: no double-on / off-missing.
        val VOICE_EVENTS: LongArray = longArrayOf(
            0, 500, 1000, 1500, 2000, 2400, 2700, 3000,
            3800, 4100, 4400, 4700, 5000, 5400, 5800,
            6200, 6600, 7000, 7400, 7800, 8200, 8600,
            9400, 9700, 10000, 10400, 10800, 11200, 11600,
            12000, 12400, 12800, 13200, 13600, 14000, 14400,
            15200, 15500, 15800, 16200, 16600, 17000, 17400,
            17800, 18200, 18600, 19000, 19400, 19800, 20200,
            21000, 21300, 21600, 22000, 22400, 22800, 23200,
            23600, 24000, 24400, 24800, 25200, 25600, 26000,
            26800, 27100, 27400, 27800, 28200, 28600, 29000,
            29500, 30000, 30500, 31000, 31400, 31800, 32200,
            32700
        )
        val VOICE_ON: BooleanArray = booleanArrayOf(
            true, true, true, true, true, true, true, true,
            false, false, false, false, false, false, false,
            true, true, true, true, true, true, true,
            false, false, false, false, false, false, false,
            true, true, true, true, true, true, true,
            false, false, false, false, false, false, false,
            true, true, true, true, true, true, true,
            false, false, false, false, false, false, false,
            true, true, true, true, true, true, true,
            false, false, false, false, false, false, false,
            true, true, true, true, true, true, true,
            false
        )
        val VOICE_IDX: IntArray = intArrayOf(
            0, 2, 4, 1, 3, 5, 6, 7,
            7, 6, 5, 3, 1, 4, 2,
            2, 4, 6, 1, 3, 5, 7,
            7, 5, 3, 1, 6, 4, 2,
            7, 5, 3, 1, 2, 4, 6,
            6, 4, 2, 1, 3, 5, 7,
            1, 2, 3, 4, 5, 6, 7,
            7, 6, 5, 4, 3, 2, 1,
            4, 6, 2, 7, 1, 3, 5,
            5, 3, 1, 7, 6, 4, 2,
            3, 5, 7, 2, 4, 6, 1,
            1
        )

        val FIXTURE_HZ = doubleArrayOf(110.0, 165.0, 220.0, 277.0, 330.0, 440.0)
        val FIXTURE_PAN = floatArrayOf(-0.80f, -0.48f, -0.16f, 0.16f, 0.48f, 0.80f)

        // Real-session timeline, absolute milliseconds from benchmark start.
        const val MASTER_RECORD_START_MS = 14_500L
        const val MASTER_RECORD_STOP_MS = 29_000L
        val LOOP_RECORD_START_MS = longArrayOf(3_200L, 6_200L, 9_200L, 12_200L)
        val LOOP_RECORD_STOP_MS = longArrayOf(5_200L, 8_200L, 11_200L, 14_200L)

        val REAL_VOICE_AT_MS: LongArray = longArrayOf(
            3500, 3590, 3680, 3770, 5000, 5070, 5140, 5210, 5700, 5790,
            5880, 5970, 6060, 7100, 7170, 7240, 7310, 7380, 7600, 7690,
            7780, 7870, 9300, 9370, 9440, 9510, 9800, 9890, 9980, 10070,
            10160, 10250, 11400, 11470, 11540, 11610, 11680, 11750, 11800, 11890,
            11980, 12070, 13600, 13670, 13740, 13800, 13810, 13890, 13980, 14070,
            14160, 15600, 15670, 15740, 15800, 15810, 15880, 15890, 15980, 16070,
            16160, 16250, 17500, 17570, 17640, 17710, 17780, 17850, 18000, 18090,
            18180, 18270, 18360, 19600, 19670, 19740, 19810, 19880, 20100, 20190,
            20280, 20370, 20460, 20550, 20640, 21900, 21970, 22040, 22110, 22180,
            22250, 22320, 22400, 22490, 22580, 22670, 22760, 22850, 24100, 24170,
            24240, 24310, 24380, 24450, 24600, 24690, 24780, 24870, 24960, 25050,
            26300, 26370, 26440, 26510, 26580, 26650, 26800, 26890, 26980, 27070,
            27160, 27250, 28500, 28570, 28640, 28710, 28780, 28850, 28900, 28990,
            29080, 29170, 29260, 30100, 30170, 30240, 30310, 30380
        )

        val REAL_VOICE_ON: BooleanArray = booleanArrayOf(
            true, true, true, true, false, false, false, false, true, true,
            true, true, true, false, false, false, false, false, true, true,
            true, true, false, false, false, false, true, true, true, true,
            true, true, false, false, false, false, false, false, true, true,
            true, true, false, false, false, true, false, true, true, true,
            true, false, false, false, true, false, false, true, true, true,
            true, true, false, false, false, false, false, false, true, true,
            true, true, true, false, false, false, false, false, true, true,
            true, true, true, true, true, false, false, false, false, false,
            false, false, true, true, true, true, true, true, false, false,
            false, false, false, false, true, true, true, true, true, true,
            false, false, false, false, false, false, true, true, true, true,
            true, true, false, false, false, false, false, false, true, true,
            true, true, true, false, false, false, false, false
        )

        val REAL_VOICE_IDX: IntArray = intArrayOf(
            0, 2, 4, 1, 0, 2, 4, 1, 3, 5,
            6, 2, 0, 3, 5, 6, 2, 0, 7, 4,
            1, 6, 7, 4, 1, 6, 2, 4, 6, 7,
            3, 1, 2, 4, 6, 7, 3, 1, 0, 3,
            5, 7, 0, 3, 5, 1, 7, 4, 6, 2,
            7, 1, 4, 6, 0, 2, 7, 2, 5, 7,
            3, 6, 0, 2, 5, 7, 3, 6, 1, 3,
            4, 6, 7, 1, 3, 4, 6, 7, 0, 2,
            4, 5, 7, 1, 3, 0, 2, 4, 5, 7,
            1, 3, 2, 4, 6, 1, 5, 7, 2, 4,
            6, 1, 5, 7, 2, 4, 6, 1, 5, 7,
            2, 4, 6, 1, 5, 7, 0, 3, 5, 7,
            2, 6, 0, 3, 5, 7, 2, 6, 1, 4,
            6, 7, 3, 1, 4, 6, 7, 3
        )
    }

    @Volatile var captureMode: Int = 0
        private set

    @Volatile var startMeasureAfterWrite: Boolean = false
    @Volatile var stopAfterWrite: Boolean = false

    private val timesNs = LongArray(MAX_SAMPLES)
    private var writeIdx = 0
    @Volatile private var bufCount = 0
    @Volatile private var missCount = 0

    // Sampled hot-path profiling state. Used only during benchmark measurement.
    private val profileStride = 128
    private var profileSampleCount = 0L
    private var profileDspNs = 0L
    private var profileDrumsNs = 0L
    private var profileLooperNs = 0L
    private var profileMicNs = 0L
    private var profilePadMasterNs = 0L
    private var profileAudioWriteNs = 0L

    private var deepProfileSampleCount = 0L
    private var deepProfileVoiceNs = 0L
    private var deepProfileZdfNs = 0L
    private var deepProfileExternalNs = 0L
    private var deepProfileLiveFxNs = 0L
    private var deepProfileDelayNs = 0L
    private var deepProfileReverbNs = 0L
    private var deepProfileMasterNs = 0L
    private var deepProfileVoiceFreqNs = 0L
    private var deepProfileEnvelopeNs = 0L
    private var deepProfileOscillatorNs = 0L
    private var deepProfileModulationNs = 0L
    private var deepProfileVoiceMixNs = 0L
    private var deepProfileOscMainNs = 0L
    private var deepProfileOscPianoNs = 0L
    private var deepProfileOscSubNs = 0L
    private var deepProfileOscDetuneNs = 0L
    private var deepProfileOscDividersNs = 0L

    @Volatile var deadlineNs: Long = 0L
        private set

    @Volatile var phase: BenchPhase = BenchPhase.IDLE
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var remainingMs: Long = TOTAL_MS
        private set
    @Volatile var liveBuffers: Int = 0
        private set
    @Volatile var liveMisses: Int = 0
        private set
    @Volatile var liveCpuPct: Double = 0.0
        private set

    var lastReport: BenchReport? = null
        private set

    private val heldNotes = BooleanArray(8)
    @Volatile private var cancelRequested = false

    fun processBufferFrames(): Int = PROCESS_FRAMES

    fun shouldProfileSample(sampleIndex: Int): Boolean {
        return captureMode == 2 && (sampleIndex and (profileStride - 1)) == 31
    }

    fun recordProfileSample(
        dspNs: Long,
        drumsNs: Long,
        looperNs: Long,
        micNs: Long,
        padMasterNs: Long
    ) {
        if (captureMode != 2) return
        profileDspNs += dspNs
        profileDrumsNs += drumsNs
        profileLooperNs += looperNs
        profileMicNs += micNs
        profilePadMasterNs += padMasterNs
        profileSampleCount++
    }

    fun recordAudioWrite(ns: Long) {
        if (captureMode == 2) profileAudioWriteNs += ns
    }

    fun recordDeepDspProfile(
        sampleCount: Long,
        voiceNs: Long,
        zdfNs: Long,
        externalNs: Long,
        liveFxNs: Long,
        delayNs: Long,
        reverbNs: Long,
        masterNs: Long,
        voiceFreqNs: Long,
        envelopeNs: Long,
        oscillatorNs: Long,
        modulationNs: Long,
        voiceMixNs: Long,
        oscMainNs: Long,
        oscPianoNs: Long,
        oscSubNs: Long,
        oscDetuneNs: Long,
        oscDividersNs: Long
    ) {
        if (captureMode != 2 || sampleCount <= 0L) return
        deepProfileSampleCount += sampleCount
        deepProfileVoiceNs += voiceNs
        deepProfileZdfNs += zdfNs
        deepProfileExternalNs += externalNs
        deepProfileLiveFxNs += liveFxNs
        deepProfileDelayNs += delayNs
        deepProfileReverbNs += reverbNs
        deepProfileMasterNs += masterNs
        deepProfileVoiceFreqNs += voiceFreqNs
        deepProfileEnvelopeNs += envelopeNs
        deepProfileOscillatorNs += oscillatorNs
        deepProfileModulationNs += modulationNs
        deepProfileVoiceMixNs += voiceMixNs
        deepProfileOscMainNs += oscMainNs
        deepProfileOscPianoNs += oscPianoNs
        deepProfileOscSubNs += oscSubNs
        deepProfileOscDetuneNs += oscDetuneNs
        deepProfileOscDividersNs += oscDividersNs
    }

    private fun profileAveragePerBuffer(sumNs: Long, sampleCount: Long, buffers: Int): Long {
        if (sampleCount <= 0L || buffers <= 0) return 0L
        return ((sumNs.toDouble() / sampleCount.toDouble()) * PROCESS_FRAMES).toLong()
    }

    fun onBufferDone(dtNs: Long) {
        if (captureMode != 2) return
        val i = writeIdx
        if (i < timesNs.size) {
            timesNs[i] = dtNs
            writeIdx = i + 1
        }
        bufCount++
        if (dtNs > deadlineNs) missCount++
    }

    fun onBufferBoundary() {
        if (stopAfterWrite) {
            captureMode = 0
            stopAfterWrite = false
            startMeasureAfterWrite = false
            return
        }
        if (startMeasureAfterWrite && captureMode == 1) {
            writeIdx = 0
            bufCount = 0
            missCount = 0
            profileSampleCount = 0L
            profileDspNs = 0L
            profileDrumsNs = 0L
            profileLooperNs = 0L
            profileMicNs = 0L
            profilePadMasterNs = 0L
            profileAudioWriteNs = 0L
            deepProfileSampleCount = 0L
            deepProfileVoiceNs = 0L
            deepProfileZdfNs = 0L
            deepProfileExternalNs = 0L
            deepProfileLiveFxNs = 0L
            deepProfileDelayNs = 0L
            deepProfileReverbNs = 0L
            deepProfileMasterNs = 0L
            deepProfileVoiceFreqNs = 0L
            deepProfileEnvelopeNs = 0L
            deepProfileOscillatorNs = 0L
            deepProfileModulationNs = 0L
            deepProfileVoiceMixNs = 0L
            deepProfileOscMainNs = 0L
            deepProfileOscPianoNs = 0L
            deepProfileOscSubNs = 0L
            deepProfileOscDetuneNs = 0L
            deepProfileOscDividersNs = 0L
            captureMode = 2
            startMeasureAfterWrite = false
        }
    }

    fun requestCancel() {
        cancelRequested = true
        stopAfterWrite = true
    }

    fun runBlockingStressWorkload(
        context: Context,
        includeLooper: Boolean,
        includeDrums: Boolean
    ): BenchReport {
        cancelRequested = false
        lastError = null
        lastReport = null
        phase = BenchPhase.WARMUP
        remainingMs = TOTAL_MS
        liveBuffers = 0
        liveMisses = 0
        liveCpuPct = 0.0
        writeIdx = 0
        bufCount = 0
        missCount = 0
        captureMode = 0
        startMeasureAfterWrite = false
        stopAfterWrite = false

        val snap = WorkloadSnapshot.capture(engine)
        var looperSnaps: Array<LooperTrackRestoreSnapshot>? = null
        var drumsGrid: Array<BooleanArray>? = null
        var drumsVol: FloatArray? = null
        var drumsPan: FloatArray? = null
        var drumsSamples: Array<FloatArray?>? = null
        var drumsPlaying = false
        var drumsPattern = 0

        try {
            deadlineNs = PROCESS_FRAMES.toLong() * 1_000_000_000L / engine.sampleRate.toLong()
            if (deadlineNs <= 0L) {
                return fail(context, includeLooper, includeDrums, "Invalid sample rate")
            }

            if (includeLooper) {
                for (t in 0 until 6) {
                    val tr = engine.looperTracks.getOrNull(t)
                    if (tr != null && tr.isRecording) {
                        return fail(context, includeLooper, includeDrums, "Looper track ${t + 1} is recording")
                    }
                }
                val mem = Array(6) { i -> engine.looperTracks[i].snapshotForRestore() }
                if (!writeLooperDiskBackup(context, mem)) {
                    return fail(context, includeLooper, includeDrums, "Looper disk backup failed")
                }
                looperSnaps = mem
                loadLooperFixtures()
            }

            if (includeDrums) {
                val de = engine.drumEngine
                drumsSamples = Array(8) { t -> de.drumSamples[t] }
                drumsGrid = Array(8) { t -> de.grid[t].copyOf() }
                drumsVol = de.trackVolumes.copyOf()
                drumsPan = de.trackPans.copyOf()
                drumsPlaying = de.isPlaying
                drumsPattern = de.currentPatternIndex
                var needKit = false
                var s = 0
                while (s < 4) {
                    if (de.drumSamples[s] == null) needKit = true
                    s++
                }
                if (needKit) {
                    val loaded = try {
                        kotlinx.coroutines.runBlocking { de.loadDefaultKit(context) }
                    } catch (_: Throwable) {
                        false
                    }
                    if (!loaded) {
                        releaseWorkload(
                            snap, looperSnaps, context, includeLooper, includeDrums,
                            drumsGrid, drumsVol, drumsPan, drumsSamples, drumsPlaying, drumsPattern
                        )
                        return fail(context, includeLooper, includeDrums, "Could not load default drum kit")
                    }
                }
                applyDrumPattern()
                de.startFromBeginning()
            }

            engine.detuneOn = true
            engine.warmOn = true
            engine.reverbMix = 1f
            engine.padTargetKey = true
            engine.busPadTouched = true

            val startRt = SystemClock.elapsedRealtime()
            var eventI = 0
            var measureStarted = false
            var underrun0 = -1
            var previousCpu = android.os.Process.getElapsedCpuTime()
            var previousWall = SystemClock.elapsedRealtime()
            var cpuSum = 0.0
            var cpuPeak = 0.0
            var cpuSamples = 0
            var lastCpuTick = startRt

            captureMode = 1
            startMeasureAfterWrite = false
            stopAfterWrite = false

            while (!cancelRequested) {
                val elapsed = SystemClock.elapsedRealtime() - startRt
                remainingMs = (TOTAL_MS - elapsed).coerceAtLeast(0L)

                while (eventI < VOICE_EVENTS.size && VOICE_EVENTS[eventI] <= elapsed) {
                    fireVoice(eventI)
                    eventI++
                }

                val tSec = elapsed / 1000.0
                engine.busPadX = (0.5 + 0.35 * sin(2.0 * PI * tSec / 7.0)).toFloat()
                engine.busPadY = (0.5 + 0.35 * sin(2.0 * PI * tSec / 11.0 + PI / 4.0)).toFloat()

                if (!measureStarted && elapsed >= WARMUP_MS) {
                    startMeasureAfterWrite = true
                }
                if (!measureStarted && captureMode == 2) {
                    underrun0 = engine.underrunCount()
                    previousCpu = android.os.Process.getElapsedCpuTime()
                    previousWall = SystemClock.elapsedRealtime()
                    lastCpuTick = previousWall
                    measureStarted = true
                    phase = BenchPhase.MEASURE
                }

                val nowRt = SystemClock.elapsedRealtime()
                if (measureStarted && nowRt - lastCpuTick >= 1000L) {
                    val cpuNow = android.os.Process.getElapsedCpuTime()
                    val wallNow = nowRt
                    val dCpu = (cpuNow - previousCpu).toDouble()
                    val dWall = (wallNow - previousWall).toDouble().coerceAtLeast(1.0)
                    val pct = (dCpu / dWall) * 100.0
                    previousCpu = cpuNow
                    previousWall = wallNow
                    liveCpuPct = pct
                    cpuSum += pct
                    if (pct > cpuPeak) cpuPeak = pct
                    cpuSamples++
                    lastCpuTick = nowRt
                }

                liveBuffers = bufCount
                liveMisses = missCount

                if (elapsed >= TOTAL_MS) {
                    stopAfterWrite = true
                    var spins = 0
                    while (captureMode != 0 && spins < 80) {
                        Thread.sleep(8L)
                        spins++
                    }
                    break
                }
                Thread.sleep(8L)
            }

            if (captureMode != 0) {
                stopAfterWrite = true
                var spins = 0
                while (captureMode != 0 && spins < 80) {
                    Thread.sleep(8L)
                    spins++
                }
            }
            val underrun1 = engine.underrunCount()
            val n = writeIdx.coerceIn(0, timesNs.size)
            val copy = LongArray(n)
            if (n > 0) System.arraycopy(timesNs, 0, copy, 0, n)

            if (cancelRequested) {
                val restoreErr = releaseWorkload(
                    snap, looperSnaps, context, includeLooper, includeDrums,
                    drumsGrid, drumsVol, drumsPan, drumsSamples, drumsPlaying, drumsPattern
                )
                val msg = if (restoreErr != null) "Cancelled; $restoreErr" else "Cancelled"
                return fail(context, includeLooper, includeDrums, msg)
            }

            val restoreErr = releaseWorkload(
                snap, looperSnaps, context, includeLooper, includeDrums,
                drumsGrid, drumsVol, drumsPan, drumsSamples, drumsPlaying, drumsPattern
            )
            if (restoreErr != null) {
                return fail(context, includeLooper, includeDrums, restoreErr)
            }

            val stats = computeStats(copy)
            val underrunAvail = underrun0 >= 0 && underrun1 >= 0
            val deltaU = if (underrunAvail) (underrun1 - underrun0).coerceAtLeast(0) else 0
            val missRate = if (stats.count > 0) stats.misses.toDouble() / stats.count.toDouble() else 0.0
            val cpuAvg = if (cpuSamples > 0) cpuSum / cpuSamples else liveCpuPct

            val verdict = when {
                stats.count <= 0 -> BenchVerdict.ERROR
                missRate >= 0.005 || (underrunAvail && deltaU > 0) -> BenchVerdict.FAIL
                missRate > 0.0 && missRate < 0.005 && (!underrunAvail || deltaU == 0) -> BenchVerdict.WARNING
                stats.misses == 0 && (!underrunAvail || deltaU == 0) -> BenchVerdict.PASS
                else -> BenchVerdict.WARNING
            }

            val report = BenchReport(
                verdict = if (stats.count <= 0) BenchVerdict.ERROR else verdict,
                errorMessage = if (stats.count <= 0) "No buffers captured" else null,
                device = Build.MODEL ?: "unknown",
                androidVersion = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}",
                appVersion = appVersion(context),
                sampleRate = engine.sampleRate,
                bufferFrames = PROCESS_FRAMES,
                deadlineNs = deadlineNs,
                includeLooper = includeLooper,
                includeDrums = includeDrums,
                workloadId = STRESS_WORKLOAD_ID,
                waveformType = engine.waveformType,
                bpm = engine.drumEngine.bpm,
                avgNs = stats.avg,
                p50Ns = stats.p50,
                p95Ns = stats.p95,
                p99Ns = stats.p99,
                maxNs = stats.max,
                buffers = stats.count,
                misses = stats.misses,
                missRate = missRate,
                underrunsAvailable = underrunAvail,
                deltaUnderruns = deltaU,
                cpuAvgPct = cpuAvg,
                cpuPeakPct = cpuPeak,
                profileDspAvgNs = profileAveragePerBuffer(profileDspNs, profileSampleCount, stats.count),
                profileDrumsAvgNs = profileAveragePerBuffer(profileDrumsNs, profileSampleCount, stats.count),
                profileLooperAvgNs = profileAveragePerBuffer(profileLooperNs, profileSampleCount, stats.count),
                profileMicAvgNs = profileAveragePerBuffer(profileMicNs, profileSampleCount, stats.count),
                profilePadMasterAvgNs = profileAveragePerBuffer(profilePadMasterNs, profileSampleCount, stats.count),
                profileAudioWriteAvgNs = profileAveragePerBuffer(profileAudioWriteNs, profileSampleCount, stats.count),
                profileVoiceAvgNs = profileAveragePerBuffer(deepProfileVoiceNs, deepProfileSampleCount, stats.count),
                profileZdfAvgNs = profileAveragePerBuffer(deepProfileZdfNs, deepProfileSampleCount, stats.count),
                profileExternalAvgNs = profileAveragePerBuffer(deepProfileExternalNs, deepProfileSampleCount, stats.count),
                profileLiveFxAvgNs = profileAveragePerBuffer(deepProfileLiveFxNs, deepProfileSampleCount, stats.count),
                profileDelayAvgNs = profileAveragePerBuffer(deepProfileDelayNs, deepProfileSampleCount, stats.count),
                profileReverbAvgNs = profileAveragePerBuffer(deepProfileReverbNs, deepProfileSampleCount, stats.count),
                profileMasterAvgNs = profileAveragePerBuffer(deepProfileMasterNs, deepProfileSampleCount, stats.count),
                profileVoiceFreqAvgNs = profileAveragePerBuffer(deepProfileVoiceFreqNs, deepProfileSampleCount, stats.count),
                profileEnvelopeAvgNs = profileAveragePerBuffer(deepProfileEnvelopeNs, deepProfileSampleCount, stats.count),
                profileOscillatorAvgNs = profileAveragePerBuffer(deepProfileOscillatorNs, deepProfileSampleCount, stats.count),
                profileModulationAvgNs = profileAveragePerBuffer(deepProfileModulationNs, deepProfileSampleCount, stats.count),
                profileVoiceMixAvgNs = profileAveragePerBuffer(deepProfileVoiceMixNs, deepProfileSampleCount, stats.count),
                profileOscMainAvgNs = profileAveragePerBuffer(deepProfileOscMainNs, deepProfileSampleCount, stats.count),
                profileOscPianoAvgNs = profileAveragePerBuffer(deepProfileOscPianoNs, deepProfileSampleCount, stats.count),
                profileOscSubAvgNs = profileAveragePerBuffer(deepProfileOscSubNs, deepProfileSampleCount, stats.count),
                profileOscDetuneAvgNs = profileAveragePerBuffer(deepProfileOscDetuneNs, deepProfileSampleCount, stats.count),
                profileOscDividersAvgNs = profileAveragePerBuffer(deepProfileOscDividersNs, deepProfileSampleCount, stats.count)
            )
            lastReport = report
            phase = if (report.verdict == BenchVerdict.ERROR) BenchPhase.ERROR else BenchPhase.COMPLETED
            if (report.verdict != BenchVerdict.ERROR) saveLast(context, report)
            return report
        } catch (t: Throwable) {
            releaseWorkload(
                snap, looperSnaps, context, includeLooper, includeDrums,
                drumsGrid, drumsVol, drumsPan, drumsSamples, drumsPlaying, drumsPattern
            )
            return fail(context, includeLooper, includeDrums, t.message ?: t.javaClass.simpleName)
        } finally {
            captureMode = 0
            startMeasureAfterWrite = false
            stopAfterWrite = false
        }
    }

    /**
     * Deterministic realistic playing-session benchmark.
     *
     * Unlike the stress workload, this does not preload six finished loopers.
     * It builds four loopers sequentially through the same live record tap used
     * by the app, then starts the Master WAV recording and performs a fixed
     * sequence of notes, pad gestures and FX changes.
     *
     * Existing UI callers keep using this method; the stress workload remains
     * available through [runBlockingStressWorkload].
     */
    fun runBlockingWorkload(
        context: Context,
        includeLooper: Boolean,
        includeDrums: Boolean
    ): BenchReport {
        cancelRequested = false
        lastError = null
        lastReport = null
        phase = BenchPhase.WARMUP
        remainingMs = TOTAL_MS
        liveBuffers = 0
        liveMisses = 0
        liveCpuPct = 0.0
        writeIdx = 0
        bufCount = 0
        missCount = 0
        captureMode = 0
        startMeasureAfterWrite = false
        stopAfterWrite = false

        val snap = WorkloadSnapshot.capture(engine)
        var looperSnaps: Array<LooperTrackRestoreSnapshot>? = null
        var drumsGrid: Array<BooleanArray>? = null
        var drumsVol: FloatArray? = null
        var drumsPan: FloatArray? = null
        var drumsSamples: Array<FloatArray?>? = null
        var drumsPlaying = false
        var drumsPattern = 0
        var masterRecordingStarted = false

        try {
            deadlineNs = PROCESS_FRAMES.toLong() * 1_000_000_000L / engine.sampleRate.toLong()
            if (deadlineNs <= 0L) {
                return fail(context, includeLooper, includeDrums, "Invalid sample rate")
            }

            if (includeLooper) {
                var t = 0
                while (t < 4) {
                    val tr = engine.looperTracks.getOrNull(t)
                    if (tr != null && tr.isRecording) {
                        return fail(context, includeLooper, includeDrums, "Looper track ${t + 1} is recording")
                    }
                    t++
                }
                val mem = Array(4) { i -> engine.looperTracks[i].snapshotForRestore() }
                if (!writeLooperDiskBackup(context, mem)) {
                    return fail(context, includeLooper, includeDrums, "Looper disk backup failed")
                }
                looperSnaps = mem
            }

            if (includeDrums) {
                val de = engine.drumEngine
                drumsSamples = Array(8) { t -> de.drumSamples[t] }
                drumsGrid = Array(8) { t -> de.grid[t].copyOf() }
                drumsVol = de.trackVolumes.copyOf()
                drumsPan = de.trackPans.copyOf()
                drumsPlaying = de.isPlaying
                drumsPattern = de.currentPatternIndex

                var needKit = false
                var s = 0
                while (s < 4) {
                    if (de.drumSamples[s] == null) needKit = true
                    s++
                }
                if (needKit) {
                    val loaded = try {
                        kotlinx.coroutines.runBlocking { de.loadDefaultKit(context) }
                    } catch (_: Throwable) {
                        false
                    }
                    if (!loaded) {
                        releaseWorkload(
                            snap, looperSnaps, context, includeLooper, includeDrums,
                            drumsGrid, drumsVol, drumsPan, drumsSamples, drumsPlaying, drumsPattern
                        )
                        return fail(context, includeLooper, includeDrums, "Could not load default drum kit")
                    }
                }

                applyRealDrumPattern()
                de.startFromBeginning()
            }

            engine.detuneOn = false
            engine.warmOn = true
            // Synth/DSP reverb only. DrumEngine has no reverb control.
            engine.reverbMix = 0.45f
            engine.padTargetKey = true
            engine.busPadTouched = false
            engine.busPadX = 0.5f
            engine.busPadY = 0.5f
            setRealFx(false, false, false, false, false, false, false, false)

            val startRt = SystemClock.elapsedRealtime()
            var eventI = 0
            var measureStarted = false
            var underrun0 = -1
            var previousCpu = android.os.Process.getElapsedCpuTime()
            var previousWall = SystemClock.elapsedRealtime()
            var cpuSum = 0.0
            var cpuPeak = 0.0
            var cpuSamples = 0
            var lastCpuTick = startRt

            var loopStage = 0
            var masterRecordingStartedAt = false

            captureMode = 1
            startMeasureAfterWrite = false
            stopAfterWrite = false

            while (!cancelRequested) {
                val elapsed = SystemClock.elapsedRealtime() - startRt
                remainingMs = (TOTAL_MS - elapsed).coerceAtLeast(0L)

                while (eventI < REAL_VOICE_EVENTS.size && REAL_VOICE_EVENTS[eventI] <= elapsed) {
                    fireRealVoice(eventI)
                    eventI++
                }

                updateRealSession(elapsed)

                if (!masterRecordingStartedAt && elapsed >= MASTER_RECORD_START_MS) {
                    engine.startRecording()
                    masterRecordingStarted = true
                    masterRecordingStartedAt = true
                }
                if (masterRecordingStartedAt && elapsed >= MASTER_RECORD_STOP_MS) {
                    engine.stopRecording()
                    masterRecordingStarted = false
                }

                if (includeLooper) {
                    when (loopStage) {
                        0 -> if (elapsed >= LOOP_RECORD_START_MS[0]) {
                            engine.startTrackRecording(0)
                            loopStage = 1
                        }
                        1 -> if (elapsed >= LOOP_RECORD_STOP_MS[0]) {
                            engine.setTrackPlaying(0, true)
                            loopStage = 2
                        }
                        2 -> if (elapsed >= LOOP_RECORD_START_MS[1]) {
                            engine.startTrackRecording(1)
                            loopStage = 3
                        }
                        3 -> if (elapsed >= LOOP_RECORD_STOP_MS[1]) {
                            engine.setTrackPlaying(1, true)
                            loopStage = 4
                        }
                        4 -> if (elapsed >= LOOP_RECORD_START_MS[2]) {
                            engine.startTrackRecording(2)
                            loopStage = 5
                        }
                        5 -> if (elapsed >= LOOP_RECORD_STOP_MS[2]) {
                            engine.setTrackPlaying(2, true)
                            loopStage = 6
                        }
                        6 -> if (elapsed >= LOOP_RECORD_START_MS[3]) {
                            engine.startTrackRecording(3)
                            loopStage = 7
                        }
                        7 -> if (elapsed >= LOOP_RECORD_STOP_MS[3]) {
                            engine.setTrackPlaying(3, true)
                            loopStage = 8
                        }
                    }
                }

                if (!measureStarted && elapsed >= WARMUP_MS) {
                    startMeasureAfterWrite = true
                }
                if (!measureStarted && captureMode == 2) {
                    underrun0 = engine.underrunCount()
                    previousCpu = android.os.Process.getElapsedCpuTime()
                    previousWall = SystemClock.elapsedRealtime()
                    lastCpuTick = previousWall
                    measureStarted = true
                    phase = BenchPhase.MEASURE
                }

                val nowRt = SystemClock.elapsedRealtime()
                if (measureStarted && nowRt - lastCpuTick >= 1000L) {
                    val cpuNow = android.os.Process.getElapsedCpuTime()
                    val wallNow = nowRt
                    val dCpu = (cpuNow - previousCpu).toDouble()
                    val dWall = (wallNow - previousWall).toDouble().coerceAtLeast(1.0)
                    val pct = (dCpu / dWall) * 100.0
                    previousCpu = cpuNow
                    previousWall = wallNow
                    liveCpuPct = pct
                    cpuSum += pct
                    if (pct > cpuPeak) cpuPeak = pct
                    cpuSamples++
                    lastCpuTick = nowRt
                }

                liveBuffers = bufCount
                liveMisses = missCount

                if (elapsed >= TOTAL_MS) {
                    stopAfterWrite = true
                    var spins = 0
                    while (captureMode != 0 && spins < 80) {
                        Thread.sleep(8L)
                        spins++
                    }
                    break
                }
                Thread.sleep(8L)
            }

            if (masterRecordingStarted) {
                try {
                    engine.stopRecording()
                } catch (_: Throwable) {
                }
                masterRecordingStarted = false
            }

            if (captureMode != 0) {
                stopAfterWrite = true
                var spins = 0
                while (captureMode != 0 && spins < 80) {
                    Thread.sleep(8L)
                    spins++
                }
            }

            val underrun1 = engine.underrunCount()
            val n = writeIdx.coerceIn(0, timesNs.size)
            val copy = LongArray(n)
            if (n > 0) System.arraycopy(timesNs, 0, copy, 0, n)

            if (cancelRequested) {
                val restoreErr = releaseWorkload(
                    snap, looperSnaps, context, includeLooper, includeDrums,
                    drumsGrid, drumsVol, drumsPan, drumsSamples, drumsPlaying, drumsPattern
                )
                val msg = if (restoreErr != null) "Cancelled; $restoreErr" else "Cancelled"
                return fail(context, includeLooper, includeDrums, msg)
            }

            setRealFx(false, false, false, false, false, false, false, false)
            engine.busPadTouched = false

            val restoreErr = releaseWorkload(
                snap, looperSnaps, context, includeLooper, includeDrums,
                drumsGrid, drumsVol, drumsPan, drumsSamples, drumsPlaying, drumsPattern
            )
            if (restoreErr != null) {
                return fail(context, includeLooper, includeDrums, restoreErr)
            }

            val stats = computeStats(copy)
            val underrunAvail = underrun0 >= 0 && underrun1 >= 0
            val deltaU = if (underrunAvail) (underrun1 - underrun0).coerceAtLeast(0) else 0
            val missRate = if (stats.count > 0) stats.misses.toDouble() / stats.count.toDouble() else 0.0
            val cpuAvg = if (cpuSamples > 0) cpuSum / cpuSamples else liveCpuPct

            val verdict = when {
                stats.count <= 0 -> BenchVerdict.ERROR
                missRate >= 0.005 || (underrunAvail && deltaU > 0) -> BenchVerdict.FAIL
                missRate > 0.0 && missRate < 0.005 && (!underrunAvail || deltaU == 0) -> BenchVerdict.WARNING
                stats.misses == 0 && (!underrunAvail || deltaU == 0) -> BenchVerdict.PASS
                else -> BenchVerdict.WARNING
            }

            val report = BenchReport(
                verdict = if (stats.count <= 0) BenchVerdict.ERROR else verdict,
                errorMessage = if (stats.count <= 0) "No buffers captured" else null,
                device = Build.MODEL ?: "unknown",
                androidVersion = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}",
                appVersion = appVersion(context),
                sampleRate = engine.sampleRate,
                bufferFrames = PROCESS_FRAMES,
                deadlineNs = deadlineNs,
                includeLooper = includeLooper,
                includeDrums = includeDrums,
                waveformType = engine.waveformType,
                bpm = engine.drumEngine.bpm,
                avgNs = stats.avg,
                p50Ns = stats.p50,
                p95Ns = stats.p95,
                p99Ns = stats.p99,
                maxNs = stats.max,
                buffers = stats.count,
                misses = stats.misses,
                missRate = missRate,
                underrunsAvailable = underrunAvail,
                deltaUnderruns = deltaU,
                cpuAvgPct = cpuAvg,
                cpuPeakPct = cpuPeak,
                profileDspAvgNs = profileAveragePerBuffer(profileDspNs, profileSampleCount, stats.count),
                profileDrumsAvgNs = profileAveragePerBuffer(profileDrumsNs, profileSampleCount, stats.count),
                profileLooperAvgNs = profileAveragePerBuffer(profileLooperNs, profileSampleCount, stats.count),
                profileMicAvgNs = profileAveragePerBuffer(profileMicNs, profileSampleCount, stats.count),
                profilePadMasterAvgNs = profileAveragePerBuffer(profilePadMasterNs, profileSampleCount, stats.count),
                profileAudioWriteAvgNs = profileAveragePerBuffer(profileAudioWriteNs, profileSampleCount, stats.count),
                profileVoiceAvgNs = profileAveragePerBuffer(deepProfileVoiceNs, deepProfileSampleCount, stats.count),
                profileZdfAvgNs = profileAveragePerBuffer(deepProfileZdfNs, deepProfileSampleCount, stats.count),
                profileExternalAvgNs = profileAveragePerBuffer(deepProfileExternalNs, deepProfileSampleCount, stats.count),
                profileLiveFxAvgNs = profileAveragePerBuffer(deepProfileLiveFxNs, deepProfileSampleCount, stats.count),
                profileDelayAvgNs = profileAveragePerBuffer(deepProfileDelayNs, deepProfileSampleCount, stats.count),
                profileReverbAvgNs = profileAveragePerBuffer(deepProfileReverbNs, deepProfileSampleCount, stats.count),
                profileMasterAvgNs = profileAveragePerBuffer(deepProfileMasterNs, deepProfileSampleCount, stats.count),
                profileVoiceFreqAvgNs = profileAveragePerBuffer(deepProfileVoiceFreqNs, deepProfileSampleCount, stats.count),
                profileEnvelopeAvgNs = profileAveragePerBuffer(deepProfileEnvelopeNs, deepProfileSampleCount, stats.count),
                profileOscillatorAvgNs = profileAveragePerBuffer(deepProfileOscillatorNs, deepProfileSampleCount, stats.count),
                profileModulationAvgNs = profileAveragePerBuffer(deepProfileModulationNs, deepProfileSampleCount, stats.count),
                profileVoiceMixAvgNs = profileAveragePerBuffer(deepProfileVoiceMixNs, deepProfileSampleCount, stats.count),
                profileOscMainAvgNs = profileAveragePerBuffer(deepProfileOscMainNs, deepProfileSampleCount, stats.count),
                profileOscPianoAvgNs = profileAveragePerBuffer(deepProfileOscPianoNs, deepProfileSampleCount, stats.count),
                profileOscSubAvgNs = profileAveragePerBuffer(deepProfileOscSubNs, deepProfileSampleCount, stats.count),
                profileOscDetuneAvgNs = profileAveragePerBuffer(deepProfileOscDetuneNs, deepProfileSampleCount, stats.count),
                profileOscDividersAvgNs = profileAveragePerBuffer(deepProfileOscDividersNs, deepProfileSampleCount, stats.count)
            )
            lastReport = report
            phase = if (report.verdict == BenchVerdict.ERROR) BenchPhase.ERROR else BenchPhase.COMPLETED
            if (report.verdict != BenchVerdict.ERROR) saveLast(context, report)
            return report
        } catch (t: Throwable) {
            if (masterRecordingStarted) {
                try {
                    engine.stopRecording()
                } catch (_: Throwable) {
                }
            }
            releaseWorkload(
                snap, looperSnaps, context, includeLooper, includeDrums,
                drumsGrid, drumsVol, drumsPan, drumsSamples, drumsPlaying, drumsPattern
            )
            return fail(context, includeLooper, includeDrums, t.message ?: t.javaClass.simpleName)
        } finally {
            captureMode = 0
            startMeasureAfterWrite = false
            stopAfterWrite = false
            engine.busPadTouched = false
            setRealFx(false, false, false, false, false, false, false, false)
        }
    }

    private fun updateRealSession(elapsedMs: Long) {
        // Deterministic Pad gestures: touch/move/release windows, not a free-running LFO.
        val padActive =
            (elapsedMs in 4_000L..5_000L) ||
            (elapsedMs in 9_900L..11_000L) ||
            (elapsedMs in 16_000L..18_000L) ||
            (elapsedMs in 21_800L..24_000L) ||
            (elapsedMs in 26_000L..27_500L)

        if (padActive) {
            val t = elapsedMs / 1000.0
            engine.busPadX = (0.50 + 0.36 * sin(2.0 * PI * t / 1.7)).toFloat().coerceIn(0.05f, 0.95f)
            engine.busPadY = (0.50 + 0.36 * sin(2.0 * PI * t / 1.3 + PI / 3.0)).toFloat().coerceIn(0.05f, 0.95f)
            engine.busPadTouched = true
        } else {
            engine.busPadTouched = false
            engine.busPadX = 0.5f
            engine.busPadY = 0.5f
        }

        // Dynamic but deterministic FX. They overlap only in the same way every run.
        val vibe = elapsedMs in 8_400L..12_400L
        val rip = elapsedMs in 12_800L..17_000L
        val fuzz = elapsedMs in 20_600L..24_200L
        val phaz = elapsedMs in 24_000L..26_400L
        val piano = elapsedMs in 17_400L..20_000L
        val div2 = elapsedMs in 21_600L..24_800L
        val div3 = elapsedMs in 26_000L..28_200L
        val div4 = elapsedMs in 28_000L..29_000L
        setRealFx(vibe, rip, fuzz, phaz, piano, div2, div3, div4)

        // Detune is deliberately introduced during the late performance section,
        // matching a realistic "play with detune" pass rather than being on for all 30s.
        engine.detuneOn = elapsedMs in 10_000L..13_400L || elapsedMs in 20_400L..29_000L

    }

    private fun setRealFx(
        vibe: Boolean,
        rip: Boolean,
        fuzz: Boolean,
        phaz: Boolean,
        piano: Boolean,
        div2: Boolean,
        div3: Boolean,
        div4: Boolean
    ) {
        engine.vibeOn = vibe
        engine.ripOn = rip
        engine.fuzzOn = fuzz
        engine.phazOn = phaz
        engine.pianoOn = piano
        engine.div2On = div2
        engine.div3On = div3
        engine.div4On = div4
    }

    private fun fireRealVoice(i: Int) {
        if (i !in REAL_VOICE_AT_MS.indices) return
        val idx = REAL_VOICE_IDX[i]
        if (idx !in 0 until FREQS.size) return
        val freq = FREQS[idx]
        if (REAL_VOICE_ON[i]) {
            if (!heldNotes[idx]) {
                engine.noteOn(freq)
                heldNotes[idx] = true
            }
        } else {
            if (heldNotes[idx]) {
                engine.noteOff(freq)
                heldNotes[idx] = false
            }
        }
    }

    private fun applyRealDrumPattern() {
        val de = engine.drumEngine
        de.bpm = 120f
        de.swing = 0f
        de.masterVolume = 0.80f

        var t = 0
        while (t < 8) {
            var s = 0
            while (s < 16) {
                de.grid[t][s] = false
                s++
            }
            de.trackVolumes[t] = if (t < 4) 0.85f else 0f
            de.setTrackPan(t, 0f)
            t++
        }

        val kick = intArrayOf(0, 4, 8, 12)
        val snare = intArrayOf(4, 12)
        val hat = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14)
        val perc = intArrayOf(2, 6, 10, 14)
        for (s in kick) de.grid[0][s] = true
        for (s in snare) de.grid[1][s] = true
        for (s in hat) de.grid[2][s] = true
        for (s in perc) de.grid[3][s] = true
    }

    private fun fireVoice(i: Int) {
        val idx = VOICE_IDX[i]
        if (idx !in 0 until 8) return
        val freq = FREQS[idx]
        if (VOICE_ON[i]) {
            if (!heldNotes[idx]) {
                engine.noteOn(freq)
                heldNotes[idx] = true
            }
        } else {
            if (heldNotes[idx]) {
                engine.noteOff(freq)
                heldNotes[idx] = false
            }
        }
    }

    private fun loadLooperFixtures() {
        val sr = engine.sampleRate
        var t = 0
        while (t < 6) {
            val pcm = sineFixture(FIXTURE_HZ[t], sr, FIXTURE_SECONDS)
            engine.looperTracks[t].loadFromSamples(pcm)
            engine.setTrackVolume(t, 1f)
            engine.setTrackPan(t, FIXTURE_PAN[t])
            engine.setTrackPlaying(t, true)
            t++
        }
    }

    private fun sineFixture(freq: Double, sr: Int, seconds: Double): FloatArray {
        val n = (sr * seconds).toInt().coerceAtLeast(sr / 4)
        val out = FloatArray(n)
        val inc = freq / sr.toDouble()
        var ph = 0.0
        var i = 0
        while (i < n) {
            out[i] = (sin(2.0 * PI * ph) * 0.22).toFloat()
            ph += inc
            if (ph >= 1.0) ph -= 1.0
            i++
        }
        return out
    }

    private fun applyDrumPattern() {
        val de = engine.drumEngine
        var t = 0
        while (t < 8) {
            var s = 0
            while (s < 16) {
                de.grid[t][s] = false
                s++
            }
            t++
        }
        val kick = intArrayOf(0, 4, 8, 12)
        val snare = intArrayOf(4, 12)
        val hat = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14)
        for (s in kick) de.grid[0][s] = true
        for (s in snare) de.grid[1][s] = true
        for (s in hat) de.grid[2][s] = true
        de.grid[3][8] = true
    }

    private fun writeLooperDiskBackup(context: Context, snaps: Array<LooperTrackRestoreSnapshot>): Boolean {
        return try {
            val dir = File(context.filesDir, "benchmark/looper_restore")
            if (!dir.exists() && !dir.mkdirs()) return false
            var i = 0
            while (i < snaps.size) {
                val s = snaps[i]
                val pcmFile = File(dir, "track$i.pcm")
                engine.looperTracks[i].writeSessionPcm(pcmFile)
                val meta = JSONObject()
                meta.put("volume", s.volume.toDouble())
                meta.put("pan", s.pan.toDouble())
                meta.put("wasPlaying", s.wasPlaying)
                meta.put("playPos", s.playPos)
                meta.put("padN", s.padN)
                meta.put("padI", s.padI)
                meta.put("lastStampMs", s.lastStampMs)
                val ms = JSONArray()
                val xs = JSONArray()
                val ys = JSONArray()
                var p = 0
                while (p < s.padN) {
                    ms.put(s.padMs[p])
                    xs.put(s.padXs[p].toDouble())
                    ys.put(s.padYs[p].toDouble())
                    p++
                }
                meta.put("padMs", ms)
                meta.put("padXs", xs)
                meta.put("padYs", ys)
                File(dir, "track$i.meta.json").writeText(meta.toString())
                i++
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun restoreDrumSamples(samples: Array<FloatArray?>?) {
        if (samples == null) return
        val de = engine.drumEngine
        var t = 0
        while (t < 8 && t < samples.size) {
            de.drumSamples[t] = samples[t]
            t++
        }
    }

    private fun releaseWorkload(
        snap: WorkloadSnapshot,
        looperSnaps: Array<LooperTrackRestoreSnapshot>?,
        context: Context,
        includeLooper: Boolean,
        includeDrums: Boolean,
        drumsGrid: Array<BooleanArray>?,
        drumsVol: FloatArray?,
        drumsPan: FloatArray?,
        drumsSamples: Array<FloatArray?>?,
        drumsPlaying: Boolean,
        drumsPattern: Int
    ): String? {
        var i = 0
        while (i < 8) {
            if (heldNotes[i]) {
                engine.noteOff(FREQS[i])
                heldNotes[i] = false
            }
            i++
        }
        snap.restore(engine)
        var looperErr: String? = null
        if (includeLooper && looperSnaps != null) {
            var t = 0
            while (t < looperSnaps.size) {
                if (!restoreLooperTrack(context, t, looperSnaps[t])) {
                    looperErr = "Looper state was not restored for track ${t + 1}"
                }
                t++
            }
        }
        if (includeDrums) {
            val de = engine.drumEngine
            de.stopAndRewind()
            restoreDrumSamples(drumsSamples)
            if (drumsGrid != null) {
                var t = 0
                while (t < 8) {
                    System.arraycopy(drumsGrid[t], 0, de.grid[t], 0, minOf(16, drumsGrid[t].size))
                    if (drumsVol != null && t < drumsVol.size) de.trackVolumes[t] = drumsVol[t]
                    if (drumsPan != null && t < drumsPan.size) de.setTrackPan(t, drumsPan[t])
                    t++
                }
            }
            if (drumsPlaying) de.startFromBeginning()
            else de.currentPatternIndex = drumsPattern
        }
        remainingMs = 0L
        return looperErr
    }

    private fun restoreLooperTrack(
        context: Context,
        track: Int,
        memory: LooperTrackRestoreSnapshot
    ): Boolean {
        try {
            engine.setTrackPlaying(track, false)
            engine.looperTracks[track].restoreFromSnapshot(memory)
            return true
        } catch (_: Throwable) {
        }
        return try {
            val disk = loadLooperDiskBackup(context, track) ?: return false
            engine.setTrackPlaying(track, false)
            engine.looperTracks[track].restoreFromSnapshot(disk)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun loadLooperDiskBackup(context: Context, track: Int): LooperTrackRestoreSnapshot? {
        val dir = File(context.filesDir, "benchmark/looper_restore")
        val metaFile = File(dir, "track$track.meta.json")
        if (!metaFile.exists()) return null
        val meta = JSONObject(metaFile.readText())
        val pcmFile = File(dir, "track$track.pcm")
        val samples = readBackupPcm(pcmFile)
        val padN = meta.optInt("padN", 0).coerceAtLeast(0)
        val padMsArr = LongArray(padN)
        val padXsArr = FloatArray(padN)
        val padYsArr = FloatArray(padN)
        val ms = meta.optJSONArray("padMs")
        val xs = meta.optJSONArray("padXs")
        val ys = meta.optJSONArray("padYs")
        var p = 0
        while (p < padN) {
            if (ms != null && p < ms.length()) padMsArr[p] = ms.optLong(p, 0L)
            if (xs != null && p < xs.length()) padXsArr[p] = xs.optDouble(p, 0.5).toFloat()
            if (ys != null && p < ys.length()) padYsArr[p] = ys.optDouble(p, 0.5).toFloat()
            p++
        }
        return LooperTrackRestoreSnapshot(
            samples = samples,
            volume = meta.optDouble("volume", 1.0).toFloat(),
            pan = meta.optDouble("pan", 0.0).toFloat(),
            wasPlaying = meta.optBoolean("wasPlaying", false),
            playPos = meta.optInt("playPos", 0),
            padN = padN,
            padI = meta.optInt("padI", 0),
            lastStampMs = meta.optLong("lastStampMs", -15L),
            padMs = padMsArr,
            padXs = padXsArr,
            padYs = padYsArr
        )
    }

    private fun readBackupPcm(file: File): FloatArray {
        if (!file.exists()) return FloatArray(0)
        val bytes = file.readBytes()
        if (bytes.size < 4) return FloatArray(0)
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val size = bb.int
        if (size <= 0 || size * 4 + 4 > bytes.size) return FloatArray(0)
        val floats = FloatArray(size)
        var i = 0
        while (i < size) {
            floats[i] = bb.float
            i++
        }
        return floats
    }

    private fun fail(context: Context, includeLooper: Boolean, includeDrums: Boolean, msg: String): BenchReport {
        lastError = msg
        phase = BenchPhase.ERROR
        val report = BenchReport(
            verdict = BenchVerdict.ERROR,
            errorMessage = msg,
            device = Build.MODEL ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}",
            appVersion = appVersion(context),
            sampleRate = engine.sampleRate,
            bufferFrames = PROCESS_FRAMES,
            deadlineNs = deadlineNs,
            includeLooper = includeLooper,
            includeDrums = includeDrums,
            waveformType = engine.waveformType,
            bpm = engine.drumEngine.bpm,
            avgNs = 0L, p50Ns = 0L, p95Ns = 0L, p99Ns = 0L, maxNs = 0L,
            buffers = 0, misses = 0, missRate = 0.0,
            underrunsAvailable = false, deltaUnderruns = 0,
            cpuAvgPct = 0.0, cpuPeakPct = 0.0
        )
        lastReport = report
        return report
    }

    private class Stats(
        val count: Int,
        val avg: Long,
        val p50: Long,
        val p95: Long,
        val p99: Long,
        val max: Long,
        val misses: Int
    )

    private fun computeStats(copy: LongArray): Stats {
        val n = copy.size
        if (n <= 0) return Stats(0, 0, 0, 0, 0, 0, 0)
        copy.sort()
        var sum = 0L
        var misses = 0
        var i = 0
        while (i < n) {
            val v = copy[i]
            sum += v
            if (v > deadlineNs) misses++
            i++
        }
        return Stats(
            count = n,
            avg = sum / n,
            p50 = copy[((n - 1) * 50) / 100],
            p95 = copy[((n - 1) * 95) / 100],
            p99 = copy[((n - 1) * 99) / 100],
            max = copy[n - 1],
            misses = misses
        )
    }

    private fun saveLast(context: Context, report: BenchReport) {
        try {
            val dir = File(context.filesDir, "benchmark")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "last.json").writeText(reportToJson(report).toString())
        } catch (_: Throwable) {
        }
    }

    fun saveAsBefore(context: Context): Boolean {
        val report = lastReport ?: return false
        if (report.verdict == BenchVerdict.ERROR) return false
        return try {
            val dir = File(context.filesDir, "benchmark")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "before.json").writeText(reportToJson(report).toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun loadBefore(context: Context): BenchReport? {
        return try {
            val f = File(context.filesDir, "benchmark/before.json")
            if (!f.exists()) return null
            jsonToReport(JSONObject(f.readText()))
        } catch (_: Throwable) {
            null
        }
    }

    fun comparisonAllowed(before: BenchReport, after: BenchReport): Boolean {
        return before.device == after.device &&
            before.appVersion == after.appVersion &&
            before.sampleRate == after.sampleRate &&
            before.bufferFrames == after.bufferFrames &&
            before.includeLooper == after.includeLooper &&
            before.includeDrums == after.includeDrums &&
            before.workloadId == after.workloadId
    }
}

private class WorkloadSnapshot(
    val detuneOn: Boolean,
    val warmOn: Boolean,
    val reverbMix: Float,
    val padTargetKey: Boolean,
    val busPadTouched: Boolean,
    val busPadX: Float,
    val busPadY: Float,
    val performanceX: Float,
    val performanceY: Float,
    val vibeOn: Boolean,
    val ripOn: Boolean,
    val fuzzOn: Boolean,
    val phazOn: Boolean,
    val pianoOn: Boolean,
    val div2On: Boolean,
    val div3On: Boolean,
    val div4On: Boolean
) {
    fun restore(engine: SynthEngine) {
        engine.detuneOn = detuneOn
        engine.warmOn = warmOn
        engine.reverbMix = reverbMix
        engine.padTargetKey = padTargetKey
        engine.busPadTouched = busPadTouched
        engine.busPadX = busPadX
        engine.busPadY = busPadY
        engine.performanceX = performanceX
        engine.performanceY = performanceY
        engine.vibeOn = vibeOn
        engine.ripOn = ripOn
        engine.fuzzOn = fuzzOn
        engine.phazOn = phazOn
        engine.pianoOn = pianoOn
        engine.div2On = div2On
        engine.div3On = div3On
        engine.div4On = div4On
    }

    companion object {
        fun capture(engine: SynthEngine) = WorkloadSnapshot(
            detuneOn = engine.detuneOn,
            warmOn = engine.warmOn,
            reverbMix = engine.reverbMix,
            padTargetKey = engine.padTargetKey,
            busPadTouched = engine.busPadTouched,
            busPadX = engine.busPadX,
            busPadY = engine.busPadY,
            performanceX = engine.performanceX,
            performanceY = engine.performanceY,
            vibeOn = engine.vibeOn,
            ripOn = engine.ripOn,
            fuzzOn = engine.fuzzOn,
            phazOn = engine.phazOn,
            pianoOn = engine.pianoOn,
            div2On = engine.div2On,
            div3On = engine.div3On,
            div4On = engine.div4On
        )
    }
}

internal fun nsToMs(ns: Long): String {
    val ms = ns / 1_000_000.0
    return String.format("%.3f ms", ms)
}

internal fun appVersion(context: Context): String {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
    } catch (_: Throwable) {
        "1.0"
    }
}

internal fun reportToJson(r: BenchReport): JSONObject {
    val o = JSONObject()
    o.put("workloadId", AudioBenchmark.WORKLOAD_ID)
    o.put("verdict", r.verdict.name)
    o.put("errorMessage", r.errorMessage ?: "")
    o.put("device", r.device)
    o.put("androidVersion", r.androidVersion)
    o.put("appVersion", r.appVersion)
    o.put("sampleRate", r.sampleRate)
    o.put("bufferFrames", r.bufferFrames)
    o.put("deadlineNs", r.deadlineNs)
    o.put("includeLooper", r.includeLooper)
    o.put("includeDrums", r.includeDrums)
    o.put("workloadId", r.workloadId)
    o.put("waveformType", r.waveformType)
    o.put("bpm", r.bpm.toDouble())
    o.put("avgNs", r.avgNs)
    o.put("p50Ns", r.p50Ns)
    o.put("p95Ns", r.p95Ns)
    o.put("p99Ns", r.p99Ns)
    o.put("maxNs", r.maxNs)
    o.put("buffers", r.buffers)
    o.put("misses", r.misses)
    o.put("missRate", r.missRate)
    o.put("underrunsAvailable", r.underrunsAvailable)
    o.put("deltaUnderruns", r.deltaUnderruns)
    o.put("cpuAvgPct", r.cpuAvgPct)
    o.put("cpuPeakPct", r.cpuPeakPct)
    o.put("profileDspAvgNs", r.profileDspAvgNs)
    o.put("profileDrumsAvgNs", r.profileDrumsAvgNs)
    o.put("profileLooperAvgNs", r.profileLooperAvgNs)
    o.put("profileMicAvgNs", r.profileMicAvgNs)
    o.put("profilePadMasterAvgNs", r.profilePadMasterAvgNs)
    o.put("profileAudioWriteAvgNs", r.profileAudioWriteAvgNs)
    o.put("profileVoiceAvgNs", r.profileVoiceAvgNs)
    o.put("profileZdfAvgNs", r.profileZdfAvgNs)
    o.put("profileExternalAvgNs", r.profileExternalAvgNs)
    o.put("profileLiveFxAvgNs", r.profileLiveFxAvgNs)
    o.put("profileDelayAvgNs", r.profileDelayAvgNs)
    o.put("profileReverbAvgNs", r.profileReverbAvgNs)
    o.put("profileMasterAvgNs", r.profileMasterAvgNs)
    o.put("profileVoiceFreqAvgNs", r.profileVoiceFreqAvgNs)
    o.put("profileEnvelopeAvgNs", r.profileEnvelopeAvgNs)
    o.put("profileOscillatorAvgNs", r.profileOscillatorAvgNs)
    o.put("profileModulationAvgNs", r.profileModulationAvgNs)
    o.put("profileVoiceMixAvgNs", r.profileVoiceMixAvgNs)
    o.put("profileOscMainAvgNs", r.profileOscMainAvgNs)
    o.put("profileOscPianoAvgNs", r.profileOscPianoAvgNs)
    o.put("profileOscSubAvgNs", r.profileOscSubAvgNs)
    o.put("profileOscDetuneAvgNs", r.profileOscDetuneAvgNs)
    o.put("profileOscDividersAvgNs", r.profileOscDividersAvgNs)
    return o
}

internal fun jsonToReport(o: JSONObject): BenchReport {
    val verdict = try {
        BenchVerdict.valueOf(o.optString("verdict", "ERROR"))
    } catch (_: Throwable) {
        BenchVerdict.ERROR
    }
    return BenchReport(
        verdict = verdict,
        errorMessage = o.optString("errorMessage", "").ifEmpty { null },
        device = o.optString("device", "unknown"),
        androidVersion = o.optString("androidVersion", ""),
        appVersion = o.optString("appVersion", "1.0"),
        sampleRate = o.optInt("sampleRate", 0),
        bufferFrames = o.optInt("bufferFrames", 512),
        deadlineNs = o.optLong("deadlineNs", 0L),
        includeLooper = o.optBoolean("includeLooper", false),
        includeDrums = o.optBoolean("includeDrums", false),
        workloadId = o.optString("workloadId", AudioBenchmark.WORKLOAD_ID),
        waveformType = o.optInt("waveformType", 0),
        bpm = o.optDouble("bpm", 120.0).toFloat(),
        avgNs = o.optLong("avgNs", 0L),
        p50Ns = o.optLong("p50Ns", 0L),
        p95Ns = o.optLong("p95Ns", 0L),
        p99Ns = o.optLong("p99Ns", 0L),
        maxNs = o.optLong("maxNs", 0L),
        buffers = o.optInt("buffers", 0),
        misses = o.optInt("misses", 0),
        missRate = o.optDouble("missRate", 0.0),
        underrunsAvailable = o.optBoolean("underrunsAvailable", false),
        deltaUnderruns = o.optInt("deltaUnderruns", 0),
        cpuAvgPct = o.optDouble("cpuAvgPct", 0.0),
        cpuPeakPct = o.optDouble("cpuPeakPct", 0.0),
        profileDspAvgNs = o.optLong("profileDspAvgNs", 0L),
        profileDrumsAvgNs = o.optLong("profileDrumsAvgNs", 0L),
        profileLooperAvgNs = o.optLong("profileLooperAvgNs", 0L),
        profileMicAvgNs = o.optLong("profileMicAvgNs", 0L),
        profilePadMasterAvgNs = o.optLong("profilePadMasterAvgNs", 0L),
        profileAudioWriteAvgNs = o.optLong("profileAudioWriteAvgNs", 0L),
        profileVoiceAvgNs = o.optLong("profileVoiceAvgNs", 0L),
        profileZdfAvgNs = o.optLong("profileZdfAvgNs", 0L),
        profileExternalAvgNs = o.optLong("profileExternalAvgNs", 0L),
        profileLiveFxAvgNs = o.optLong("profileLiveFxAvgNs", 0L),
        profileDelayAvgNs = o.optLong("profileDelayAvgNs", 0L),
        profileReverbAvgNs = o.optLong("profileReverbAvgNs", 0L),
        profileMasterAvgNs = o.optLong("profileMasterAvgNs", 0L),
        profileVoiceFreqAvgNs = o.optLong("profileVoiceFreqAvgNs", 0L),
        profileEnvelopeAvgNs = o.optLong("profileEnvelopeAvgNs", 0L),
        profileOscillatorAvgNs = o.optLong("profileOscillatorAvgNs", 0L),
        profileModulationAvgNs = o.optLong("profileModulationAvgNs", 0L),
        profileVoiceMixAvgNs = o.optLong("profileVoiceMixAvgNs", 0L),
        profileOscMainAvgNs = o.optLong("profileOscMainAvgNs", 0L),
        profileOscPianoAvgNs = o.optLong("profileOscPianoAvgNs", 0L),
        profileOscSubAvgNs = o.optLong("profileOscSubAvgNs", 0L),
        profileOscDetuneAvgNs = o.optLong("profileOscDetuneAvgNs", 0L),
        profileOscDividersAvgNs = o.optLong("profileOscDividersAvgNs", 0L)
    )
}
