package com.microtonal.synth

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

class DspFrame(
    var liveSample: Float = 0f,
    var looperSample: Float = 0f,
    var masterSample: Float = 0f
)

// אירועים מה-UI ל-DSP (מנגנון Lock-Free בטוח)
sealed class AudioEvent {
    data class NoteOn(
        val voiceIndex: Int,
        val freq: Float,
        val waveform: Int,
        val isLooper: Boolean,
        val sustain: Float,
        val attackMs: Float,
        val releaseMs: Float,
        val cutoff: Float,
        val res: Float
    ) : AudioEvent()

    data class NoteOff(val voiceIndex: Int) : AudioEvent()
    data class SetFreq(val voiceIndex: Int, val freq: Float) : AudioEvent()
}

class DspEngine(
    val sampleRate: Int = 44100,
    private val maxVoices: Int = 16
) {
    // --- OPTIMIZATION 1: STRUCTURE OF ARRAYS (SoA) ---
    // זיכרון רציף ושטוח בבלוק יחיד במקום מערך אובייקטים (מונע Cache Misses ב-L1/L2)
    private val active = BooleanArray(maxVoices)
    private val currentFreq = FloatArray(maxVoices)
    private val targetFreq = FloatArray(maxVoices)
    private val phase = DoubleArray(maxVoices)
    private val envelopeVolume = DoubleArray(maxVoices)
    private val isReleasing = BooleanArray(maxVoices)
    private val waveform = IntArray(maxVoices)
    private val zdfState1 = DoubleArray(maxVoices)
    private val zdfState2 = DoubleArray(maxVoices)
    private val smoothedCutoff = FloatArray(maxVoices)
    private val smoothedRes = FloatArray(maxVoices)
    private val isLooperNote = BooleanArray(maxVoices)
    private val frozenSustain = FloatArray(maxVoices)
    private val frozenAttack = FloatArray(maxVoices)
    private val frozenRelease = FloatArray(maxVoices)
    private val frozenCutoff = FloatArray(maxVoices)
    private val frozenRes = FloatArray(maxVoices)
    private val attackCoeff = DoubleArray(maxVoices)
    private val releaseCoeff = DoubleArray(maxVoices)

    // --- OPTIMIZATION 2: LOCK-FREE SPSC EVENT QUEUE ---
    private val eventQueue = ConcurrentLinkedQueue<AudioEvent>()

    fun postEvent(event: AudioEvent) {
        eventQueue.offer(event)
    }

    private fun processEvents() {
        while (true) {
            val event = eventQueue.poll() ?: break
            when (event) {
                is AudioEvent.NoteOn -> {
                    val v = event.voiceIndex
                    if (v in 0 until maxVoices) {
                        active[v] = true
                        isReleasing[v] = false
                        targetFreq[v] = event.freq
                        if (currentFreq[v] == 0f) currentFreq[v] = event.freq
                        waveform[v] = event.waveform
                        isLooperNote[v] = event.isLooper
                        frozenSustain[v] = event.sustain
                        frozenAttack[v] = event.attackMs
                        frozenRelease[v] = event.releaseMs
                        frozenCutoff[v] = event.cutoff
                        frozenRes[v] = event.res

                        val attackSec = (event.attackMs / 1000.0).coerceAtLeast(0.001)
                        attackCoeff[v] = 1.0 - Math.exp(-1.0 / (sampleRate * attackSec))

                        val releaseSec = (event.releaseMs / 1000.0).coerceAtLeast(0.001)
                        releaseCoeff[v] = Math.exp(-1.0 / (sampleRate * releaseSec))
                    }
                }
                is AudioEvent.NoteOff -> {
                    val v = event.voiceIndex
                    if (v in 0 until maxVoices) {
                        isReleasing[v] = true
                    }
                }
                is AudioEvent.SetFreq -> {
                    val v = event.voiceIndex
                    if (v in 0 until maxVoices) {
                        targetFreq[v] = event.freq
                    }
                }
            }
        }
    }

    private val reusableFrame = DspFrame()

    private val delayBuffer = FloatArray(sampleRate)
    private var delayWritePos = 0
    private var dcX1 = 0.0
    private var dcY1 = 0.0
    private var delayFilterState = 0.0
    private var currentHeadroom = 1.0

    private var smoothedLiveVol = 0.5
    private var smoothedLooperVol = 1.0
    private var smoothedEchoMix = 0.25

    // Sine Lookup Table (LUT)
    private val lutSize = 4096
    private val lutMask = lutSize - 1
    private val sineLUT = FloatArray(lutSize) { i ->
        sin(2.0 * PI * i / lutSize).toFloat()
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastSine(phaseNorm: Double): Double {
        val index = (phaseNorm * lutSize).toInt() and lutMask
        return sineLUT[index].toDouble()
    }

    // Fast PRNG Noise
    private var noiseSeed = 123456789
    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastNoise(): Double {
        noiseSeed = noiseSeed xor (noiseSeed shl 13)
        noiseSeed = noiseSeed xor (noiseSeed ushr 17)
        noiseSeed = noiseSeed xor (noiseSeed shl 5)
        return (noiseSeed.toDouble() / Int.MAX_VALUE) * 0.15
    }

    fun processNextSample(
        glideMs: Float,
        liveVolume: Float,
        looperVolume: Float,
        cutoffFreq: Float,
        resonance: Float,
        attackMs: Float,
        sustainLevel: Float,
        releaseMs: Float,
        echoMix: Float
    ): DspFrame {
        // 1. שריפת אירועים שנשלחו מה-UI ללא נעילות
        processEvents()

        // החלקת עוצמות
        smoothedLiveVol += (liveVolume - smoothedLiveVol) * 0.005
        smoothedLooperVol += (looperVolume - smoothedLooperVol) * 0.005
        smoothedEchoMix += (echoMix - smoothedEchoMix) * 0.005

        val glideFactor = if (glideMs > 0) (1.0 / (sampleRate * (glideMs / 1000.0))).coerceIn(0.001, 1.0) else 1.0

        var activeCount = 0
        for (v in 0 until maxVoices) {
            if (active[v]) activeCount++
        }

        val targetHeadroom = if (activeCount > 0) 1.0 / (1.0 + activeCount * 0.12) else 1.0
        currentHeadroom += (targetHeadroom - currentHeadroom) * 0.01

        var liveChannelMix = 0.0
        var looperChannelMix = 0.0

        // ריצה רציפה על זיכרון מושלם
        for (v in 0 until maxVoices) {
            if (!active[v]) continue

            if (glideMs > 0 && abs(currentFreq[v] - targetFreq[v]) > 0.05f) {
                currentFreq[v] += ((targetFreq[v] - currentFreq[v]) * glideFactor).toFloat()
            } else {
                currentFreq[v] = targetFreq[v]
            }

            val dt = (currentFreq[v] / sampleRate.toDouble()).coerceIn(0.0001, 0.45)
            phase[v] += 2.0 * PI * dt
            if (phase[v] >= 2.0 * PI) {
                phase[v] %= (2.0 * PI)
            }
            val phaseNorm = phase[v] / (2.0 * PI)

            val actualSustain = if (isLooperNote[v]) frozenSustain[v] else sustainLevel

            val attCoeff = if (attackCoeff[v] > 0.0) attackCoeff[v] else {
                val actualAttack = if (isLooperNote[v]) frozenAttack[v] else attackMs
                1.0 - Math.exp(-1.0 / (sampleRate * (actualAttack / 1000.0).coerceAtLeast(0.001)))
            }

            val relCoeff = if (releaseCoeff[v] > 0.0) releaseCoeff[v] else {
                val actualRelease = if (isLooperNote[v]) frozenRelease[v] else releaseMs
                Math.exp(-1.0 / (sampleRate * (actualRelease / 1000.0).coerceAtLeast(0.001)))
            }

            if (!isReleasing[v]) {
                envelopeVolume[v] += (actualSustain.toDouble() - envelopeVolume[v]) * attCoeff
            } else {
                envelopeVolume[v] *= relCoeff
                if (envelopeVolume[v] < 0.0005) {
                    envelopeVolume[v] = 0.0
                    active[v] = false
                    zdfState1[v] = 0.0
                    zdfState2[v] = 0.0
                    continue
                }
            }

            val raw = generateOptimizedWaveform(waveform[v], phaseNorm, dt)
            var voiceSample = raw * envelopeVolume[v] * currentHeadroom * 0.5

            val targetCut = (if (isLooperNote[v]) frozenCutoff[v] else cutoffFreq).coerceIn(20f, 16000f)
            val targetR = (if (isLooperNote[v]) frozenRes[v] else resonance).coerceIn(0.0f, 0.95f)

            smoothedCutoff[v] += (targetCut - smoothedCutoff[v]) * 0.005f
            smoothedRes[v] += (targetR - smoothedRes[v]) * 0.005f

            val g = tan(PI * smoothedCutoff[v] / sampleRate)
            val k = 2.0 * (1.0 - smoothedRes[v].toDouble())
            val h = 1.0 / (1.0 + g * (g + k))

            val hp = h * (voiceSample - (g + k) * zdfState1[v] - zdfState2[v])
            val bp = g * hp + zdfState1[v]
            val lp = g * bp + zdfState2[v]

            zdfState1[v] = g * hp + bp
            zdfState2[v] = g * bp + lp

            voiceSample = lp

            if (isLooperNote[v]) {
                looperChannelMix += voiceSample
            } else {
                liveChannelMix += voiceSample
            }
        }

        val finalLiveSample = (liveChannelMix * smoothedLiveVol).toFloat()
        val finalLooperSample = (looperChannelMix * smoothedLooperVol).toFloat()

        var totalSample = (liveChannelMix * smoothedLiveVol) + (looperChannelMix * smoothedLooperVol)

        val delaySamples = (sampleRate * 0.28).toInt()
        val delayReadPos = (delayWritePos - delaySamples + delayBuffer.size) % delayBuffer.size
        var echoSample = delayBuffer[delayReadPos].toDouble()

        echoSample = echoSample * 0.82 + delayFilterState * 0.18
        delayFilterState = echoSample

        val feedback = 0.42
        delayBuffer[delayWritePos] = (totalSample + echoSample * feedback).toFloat()
        delayWritePos = (delayWritePos + 1) % delayBuffer.size

        totalSample += echoSample * smoothedEchoMix

        val dcSample = totalSample - dcX1 + 0.995 * dcY1
        dcX1 = totalSample
        dcY1 = if (dcSample.isNaN() || dcSample.isInfinite()) 0.0 else dcSample

        val masterSample = softSaturate(dcY1 * 0.52).toFloat()

        reusableFrame.liveSample = finalLiveSample
        reusableFrame.looperSample = finalLooperSample
        reusableFrame.masterSample = masterSample

        return reusableFrame
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun polyBlep(t: Double, dt: Double): Double {
        return when {
            t < dt -> {
                val p = t / dt
                p + p - p * p - 1.0
            }
            t > 1.0 - dt -> {
                val p = (t - 1.0) / dt
                p * p + p + p + 1.0
            }
            else -> 0.0
        }
    }

    private fun generateOptimizedWaveform(waveType: Int, phase: Double, dt: Double): Double {
        return when (waveType) {
            0 -> fastSine(phase)
            1 -> {
                var naive = if (phase < 0.5) 0.3 else -0.3
                naive += polyBlep(phase, dt) * 0.3
                naive -= polyBlep((phase + 0.5) % 1.0, dt) * 0.3
                naive
            }
            2 -> (2.0 * abs(2.0 * phase - 1.0) - 1.0) * 0.35
            3 -> {
                var naive = (2.0 * phase - 1.0) * 0.35
                naive -= polyBlep(phase, dt) * 0.35
                naive
            }
            else -> fastNoise()
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun softSaturate(x: Double): Double {
        val driven = x * 1.35
        val x2 = driven * driven
        return driven * (27.0 + x2) / (27.0 + 9.0 * x2)
    }
}
