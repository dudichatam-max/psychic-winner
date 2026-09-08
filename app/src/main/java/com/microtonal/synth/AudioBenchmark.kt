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
    val profileVoiceMixAvgNs: Long = 0L
)

/**
 * Measurement guest. Does not own audio output.
 * Audio thread may call [onBufferDone] only.
 */
class AudioBenchmark(private val engine: SynthEngine) {

    companion object {
        const val WORKLOAD_ID = "dynamic-1-8-v2"
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
        voiceMixNs: Long
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
            captureMode = 2
            startMeasureAfterWrite = false
        }
    }

    fun requestCancel() {
        cancelRequested = true
        stopAfterWrite = true
    }

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
                profileMasterAvgNs = profileAveragePerBuffer(deepProfileMasterNs, deepProfileSampleCount, stats.count)
                profileVoiceFreqAvgNs = profileAveragePerBuffer(deepProfileVoiceFreqNs, deepProfileSampleCount, stats.count),
                profileEnvelopeAvgNs = profileAveragePerBuffer(deepProfileEnvelopeNs, deepProfileSampleCount, stats.count),
                profileOscillatorAvgNs = profileAveragePerBuffer(deepProfileOscillatorNs, deepProfileSampleCount, stats.count),
                profileModulationAvgNs = profileAveragePerBuffer(deepProfileModulationNs, deepProfileSampleCount, stats.count),
                profileVoiceMixAvgNs = profileAveragePerBuffer(deepProfileVoiceMixNs, deepProfileSampleCount, stats.count)
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
            while (i < 6) {
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
            while (t < 6) {
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
            before.includeDrums == after.includeDrums
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
    val performanceY: Float
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
            performanceY = engine.performanceY
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
        profileMasterAvgNs = o.optLong("profileMasterAvgNs", 0L)
        profileVoiceFreqAvgNs = o.optLong("profileVoiceFreqAvgNs", 0L),
        profileEnvelopeAvgNs = o.optLong("profileEnvelopeAvgNs", 0L),
        profileOscillatorAvgNs = o.optLong("profileOscillatorAvgNs", 0L),
        profileModulationAvgNs = o.optLong("profileModulationAvgNs", 0L),
        profileVoiceMixAvgNs = o.optLong("profileVoiceMixAvgNs", 0L)
    )
}
