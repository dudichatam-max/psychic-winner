package com.microtonal.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tan

data class DspFrame(
    val liveSample: Float,
    val looperSample: Float,
    val masterSample: Float
)

class DspEngine(private val sampleRate: Int = 44100) {

    private val delayBuffer = FloatArray(sampleRate)
    private var delayWritePos = 0
    private var dcX1 = 0.0
    private var dcY1 = 0.0
    private var currentHeadroom = 1.0

    // החלקת פרמטרים גלובלית למניעת Zipper Noise
    private var smoothedLiveVol = 0.5
    private var smoothedLooperVol = 1.0
    private var smoothedEchoMix = 0.25

    // --- OPTIMIZATION 1: SINE LOOKUP TABLE (LUT) ---
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

    // --- OPTIMIZATION 2: FAST XORSHIFT PRNG FOR NOISE ---
    private var noiseSeed = 123456789
    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastNoise(): Double {
        noiseSeed = noiseSeed xor (noiseSeed shl 13)
        noiseSeed = noiseSeed xor (noiseSeed ushr 17)
        noiseSeed = noiseSeed xor (noiseSeed shl 5)
        return (noiseSeed.toDouble() / Int.MAX_VALUE) * 0.15
    }

    fun processNextSample(
        noteSlots: Array<NoteSlot>,
        maxVoices: Int,
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
        // החלקת עוצמות (Parameter Smoothing)
        smoothedLiveVol += (liveVolume - smoothedLiveVol) * 0.005
        smoothedLooperVol += (looperVolume - smoothedLooperVol) * 0.005
        smoothedEchoMix += (echoMix - smoothedEchoMix) * 0.005

        val glideFactor = if (glideMs > 0) (1.0 / (sampleRate * (glideMs / 1000.0))).coerceIn(0.001, 1.0) else 1.0

        var activeCount = 0
        for (v in 0 until maxVoices) {
            if (noteSlots[v].active) activeCount++
        }

        val targetHeadroom = if (activeCount > 0) 1.0 / (1.0 + activeCount * 0.12) else 1.0
        currentHeadroom += (targetHeadroom - currentHeadroom) * 0.01

        var liveChannelMix = 0.0
        var looperChannelMix = 0.0

        for (v in 0 until maxVoices) {
            val slot = noteSlots[v]
            if (!slot.active) continue

            // Glissando / Glide
            if (glideMs > 0 && abs(slot.currentFreq - slot.targetFreq) > 0.05f) {
                slot.currentFreq += ((slot.targetFreq - slot.currentFreq) * glideFactor).toFloat()
            } else {
                slot.currentFreq = slot.targetFreq
            }

            val dt = (slot.currentFreq / sampleRate.toDouble()).coerceIn(0.0001, 0.45)
            slot.phase += 2.0 * PI * dt
            if (slot.phase >= 2.0 * PI) {
                slot.phase %= (2.0 * PI)
            }
            val phaseNorm = slot.phase / (2.0 * PI)

            // מעטפת ADSR
            val actualAttack = if (slot.isLooperNote) slot.frozenAttack else attackMs
            val actualSustain = if (slot.isLooperNote) slot.frozenSustain else sustainLevel
            val actualRelease = if (slot.isLooperNote) slot.frozenRelease else releaseMs

            val attackCoeff = 1.0 - Math.exp(-1.0 / (sampleRate * (actualAttack / 1000.0).coerceAtLeast(0.001)))
            val releaseCoeff = Math.exp(-1.0 / (sampleRate * (actualRelease / 1000.0).coerceAtLeast(0.001)))

            if (!slot.isReleasing) {
                slot.envelopeVolume += (actualSustain.toDouble() - slot.envelopeVolume) * attackCoeff
            } else {
                slot.envelopeVolume *= releaseCoeff
                if (slot.envelopeVolume < 0.0005) {
                    slot.envelopeVolume = 0.0
                    slot.active = false
                    slot.zdfState1 = 0.0
                    slot.zdfState2 = 0.0
                    continue
                }
            }

            // --- מחולל גלים אופטימלי (PolyBLEP + LUT) ---
            val raw = generateOptimizedWaveform(slot.waveform, phaseNorm, dt)

            var voiceSample = raw * slot.envelopeVolume * currentHeadroom * 0.5

            // --- פילטר ZDF / TPT SVF ---
            val targetCutoff = (if (slot.isLooperNote) slot.frozenCutoff else cutoffFreq).coerceIn(20f, 16000f)
            val targetRes = (if (slot.isLooperNote) slot.frozenRes else resonance).coerceIn(0.0f, 0.95f)

            slot.smoothedCutoff += (targetCutoff - slot.smoothedCutoff) * 0.005f
            slot.smoothedRes += (targetRes - slot.smoothedRes) * 0.005f

            val g = tan(PI * slot.smoothedCutoff / sampleRate)
            val k = 2.0 * (1.0 - slot.smoothedRes.toDouble())
            val h = 1.0 / (1.0 + g * (g + k))

            val hp = h * (voiceSample - (g + k) * slot.zdfState1 - slot.zdfState2)
            val bp = g * hp + slot.zdfState1
            val lp = g * bp + slot.zdfState2

            slot.zdfState1 = g * hp + bp
            slot.zdfState2 = g * bp + lp

            voiceSample = lp

            if (slot.isLooperNote) {
                looperChannelMix += voiceSample
            } else {
                liveChannelMix += voiceSample
            }
        }

        val finalLiveSample = (liveChannelMix * smoothedLiveVol).toFloat()
        val finalLooperSample = (looperChannelMix * smoothedLooperVol).toFloat()

        var totalSample = (liveChannelMix * smoothedLiveVol) + (looperChannelMix * smoothedLooperVol)

        // אפקט דיליי
        val delaySamples = (sampleRate * 0.25).toInt()
        val delayReadPos = (delayWritePos - delaySamples + delayBuffer.size) % delayBuffer.size
        val echoSample = delayBuffer[delayReadPos].toDouble()

        delayBuffer[delayWritePos] = (totalSample + echoSample * 0.35).toFloat()
        delayWritePos = (delayWritePos + 1) % delayBuffer.size

        totalSample += echoSample * smoothedEchoMix

        // DC Blocker
        val dcSample = totalSample - dcX1 + 0.995 * dcY1
        dcX1 = totalSample
        dcY1 = if (dcSample.isNaN() || dcSample.isInfinite()) 0.0 else dcSample

        // --- OPTIMIZATION 3: FAST CUBIC SOFT CLIPPER ---
        val masterSample = fastCubicSoftClip(dcY1 * 0.45).toFloat()

        return DspFrame(
            liveSample = finalLiveSample,
            looperSample = finalLooperSample,
            masterSample = masterSample
        )
    }

    // --- PolyBLEP Anti-Aliasing Logic ---
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
            0 -> fastSine(phase) // Sine מהיר מטבלת LUT
            1 -> { // Square Wave
                var naive = if (phase < 0.5) 0.3 else -0.3
                naive += polyBlep(phase, dt) * 0.3
                naive -= polyBlep((phase + 0.5) % 1.0, dt) * 0.3
                naive
            }
            2 -> { // Triangle Wave
                (2.0 * abs(2.0 * phase - 1.0) - 1.0) * 0.35
            }
            3 -> { // Sawtooth Wave
                var naive = (2.0 * phase - 1.0) * 0.35
                naive -= polyBlep(phase, dt) * 0.35
                naive
            }
            else -> fastNoise() // PRNG מהיר בסיביות
        }
    }

    // Fast Algebraic Cubic Saturator (במקום tanh)
    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastCubicSoftClip(x: Double): Double {
        val clamped = x.coerceIn(-1.5, 1.5)
        return clamped * (1.0 - (clamped * clamped) / 6.75)
    }
}
