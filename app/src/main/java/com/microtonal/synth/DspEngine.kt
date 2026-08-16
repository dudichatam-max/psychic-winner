package com.microtonal.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspFrame(
    var liveSample: Float = 0f,
    var looperSample: Float = 0f,
    var masterSample: Float = 0f
)

class DspEngine(private val sampleRate: Int = 44100) {

    private val reusableFrame = DspFrame()

    // קבועים מחושבים מראש למניעת פעולות חילוק יקרות בלולאה הפנימית
    private val invSampleRate = 1.0 / sampleRate
    private val piOverSampleRate = PI * invSampleRate

    private val delayBuffer = FloatArray(sampleRate)
    private var delayWritePos = 0
    private var dcX1 = 0.0
    private var dcY1 = 0.0
    private var delayFilterState = 0.0
    private var currentHeadroom = 1.0

    private var smoothedLiveVol = 0.5
    private var smoothedLooperVol = 1.0
    private var smoothedEchoMix = 0.25
    
    private var smoothedPerfX = 0.0f
    private var smoothedPerfY = 0.0f
    
    private var liveLfoPhase = 0.0

    // --- נתונים עבור נגינת קובץ אודיו חיצוני בלופר ---
    @Volatile
    private var externalAudioBuffer: FloatArray? = null
    private var externalAudioPos = 0
    @Volatile
    var isExternalAudioPlaying = false
    @Volatile
    var isExternalAudioLooping = true

    private val lutSize = 4096
    private val lutMask = lutSize - 1
    private val sineLUT = FloatArray(lutSize) { i ->
        sin(2.0 * PI * i / lutSize).toFloat()
    }

    /**
     * טעינת מערך PCM מפוילח מראש של הקובץ החיצוני לתוך ה-DSP
     */
    fun setExternalAudioBuffer(buffer: FloatArray?) {
        externalAudioBuffer = buffer
        externalAudioPos = 0
    }

    fun startExternalPlayback() {
        externalAudioPos = 0
        isExternalAudioPlaying = true
    }

    fun stopExternalPlayback() {
        isExternalAudioPlaying = false
        externalAudioPos = 0
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastSine(phaseNorm: Double): Double {
        val index = (phaseNorm * lutSize).toInt() and lutMask
        return sineLUT[index].toDouble()
    }

    /**
     * קירוב Padé מהיר ומדויק ל-tan(x) המבטל קריאות יקרות ל-Math.tan() בלולאת הסאמפלים.
     * שומר על יציבות דיוק תדרי מלאה עד 16kHz ב-44.1kHz/48kHz.
     */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun fastTan(x: Double): Double {
        val x2 = x * x
        return x * (15.0 - x2) / (15.0 - 6.0 * x2)
    }

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
        echoMix: Float,
        performanceX: Float,
        performanceY: Float
    ): DspFrame {
        smoothedLiveVol += (liveVolume - smoothedLiveVol) * 0.005
        smoothedLooperVol += (looperVolume - smoothedLooperVol) * 0.005
        smoothedEchoMix += (echoMix - smoothedEchoMix) * 0.005
        
        smoothedPerfX += (performanceX - smoothedPerfX) * 0.005f
        smoothedPerfY += (performanceY - smoothedPerfY) * 0.005f
        
        // --- ציר X: LFO מקורי על תדר החיתוך (Cutoff) ---
        val lfoFreq = 0.1 + smoothedPerfX * 24.9 
        liveLfoPhase += lfoFreq * invSampleRate
        if (liveLfoPhase >= 1.0) liveLfoPhase -= 1.0
        val lfoMod = fastSine(liveLfoPhase).toFloat()

        val glideFactor = if (glideMs > 0) (invSampleRate / (glideMs / 1000.0)).coerceIn(0.001, 1.0) else 1.0

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

            if (glideMs > 0 && abs(slot.currentFreq - slot.targetFreq) > 0.05f) {
                slot.currentFreq += ((slot.targetFreq - slot.currentFreq) * glideFactor).toFloat()
            } else {
                slot.currentFreq = slot.targetFreq
            }

            val dt = (slot.currentFreq * invSampleRate).coerceIn(0.0001, 0.45)
            val invDt = 1.0 / dt
            slot.phase += 2.0 * PI * dt
            if (slot.phase >= 2.0 * PI) {
                slot.phase %= (2.0 * PI)
            }
            val phaseNorm = slot.phase / (2.0 * PI)

            val actualSustain = if (slot.isLooperNote) slot.frozenSustain else sustainLevel

            val attackCoeff = if (slot.attackCoeff > 0.0) slot.attackCoeff else {
                val actualAttack = if (slot.isLooperNote) slot.frozenAttack else attackMs
                1.0 - Math.exp(-invSampleRate / (actualAttack / 1000.0).coerceAtLeast(0.001))
            }

            val releaseCoeff = if (slot.releaseCoeff > 0.0) slot.releaseCoeff else {
                val actualRelease = if (slot.isLooperNote) slot.frozenRelease else releaseMs
                Math.exp(-invSampleRate / (actualRelease / 1000.0).coerceAtLeast(0.001))
            }

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

            val raw = generateOptimizedWaveform(slot.waveform, phaseNorm, dt, invDt)

            // --- פילטר ZDF: ציר X = Cutoff LFO, ציר Y = Resonance ---
            var targetCutoff = (if (slot.isLooperNote) slot.frozenCutoff else cutoffFreq).coerceIn(20f, 16000f)
            
            if (!slot.isLooperNote && smoothedPerfX > 0.001f) {
                val modDepth = smoothedPerfX * 4000f
                targetCutoff = (targetCutoff + (lfoMod * modDepth)).coerceIn(20f, 16000f).toFloat()
            }
            
            var targetRes = (if (slot.isLooperNote) slot.frozenRes else resonance)
            
            if (!slot.isLooperNote && smoothedPerfY > 0.001f) {
                targetRes = (targetRes + smoothedPerfY * (0.82f - targetRes)).coerceIn(0.0f, 0.82f)
            } else {
                targetRes = targetRes.coerceIn(0.0f, 0.82f)
            }

            slot.smoothedCutoff += (targetCutoff - slot.smoothedCutoff) * 0.01f
            slot.smoothedRes += (targetRes - slot.smoothedRes) * 0.01f

            val resGainComp = 1.0 - (slot.smoothedRes * 0.45)
            var voiceSample = raw * slot.envelopeVolume * currentHeadroom * 0.5 * resGainComp

            // חישוב פילטר מהיר בעזרת fastTan ובלי Math.tan יקר
            val g = fastTan(piOverSampleRate * slot.smoothedCutoff.toDouble())
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

        // --- קריאת סאמפל מתוך הקובץ החיצוני שנטען לזיכרון ---
        var externalAudioSample = 0.0
        val extBuf = externalAudioBuffer
        if (isExternalAudioPlaying && extBuf != null && extBuf.isNotEmpty()) {
            if (externalAudioPos < extBuf.size) {
                externalAudioSample = extBuf[externalAudioPos].toDouble()
                externalAudioPos++
                if (externalAudioPos >= extBuf.size) {
                    if (isExternalAudioLooping) {
                        externalAudioPos = 0
                    } else {
                        isExternalAudioPlaying = false
                    }
                }
            }
        }

        val finalLiveSample = (liveChannelMix * smoothedLiveVol).toFloat()
        val finalLooperSample = ((looperChannelMix + externalAudioSample) * smoothedLooperVol).toFloat()

        var totalSample = (finalLiveSample + finalLooperSample).toDouble()

        // Delay
        val delaySamples = (sampleRate * 0.28).toInt()
        val delayReadPos = (delayWritePos - delaySamples + delayBuffer.size) % delayBuffer.size
        var echoSample = delayBuffer[delayReadPos].toDouble()

        echoSample = echoSample * 0.82 + delayFilterState * 0.18
        delayFilterState = echoSample

        val feedback = 0.42
        delayBuffer[delayWritePos] = (totalSample + echoSample * feedback).toFloat()
        delayWritePos = (delayWritePos + 1) % delayBuffer.size

        totalSample += echoSample * smoothedEchoMix

        // DC Blocker
        val dcSample = totalSample - dcX1 + 0.995 * dcY1
        dcX1 = totalSample
        dcY1 = if (dcSample.isNaN() || dcSample.isInfinite()) 0.0 else dcSample

        // Soft Clipper
        val masterSample = softSaturate(dcY1 * 0.52).toFloat()

        reusableFrame.liveSample = finalLiveSample
        reusableFrame.looperSample = finalLooperSample
        reusableFrame.masterSample = masterSample

        return reusableFrame
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun polyBlep(t: Double, dt: Double, invDt: Double): Double {
        return when {
            t < dt -> {
                val p = t * invDt
                p + p - p * p - 1.0
            }
            t > 1.0 - dt -> {
                val p = (t - 1.0) * invDt
                p * p + p + p + 1.0
            }
            else -> 0.0
        }
    }

    private fun generateOptimizedWaveform(waveType: Int, phase: Double, dt: Double, invDt: Double): Double {
        return when (waveType) {
            0 -> fastSine(phase)
            1 -> { 
                var naive = if (phase < 0.5) 0.3 else -0.3
                naive += polyBlep(phase, dt, invDt) * 0.3
                naive -= polyBlep((phase + 0.5) % 1.0, dt, invDt) * 0.3
                naive
            }
            2 -> { 
                (2.0 * abs(2.0 * phase - 1.0) - 1.0) * 0.35
            }
            3 -> { 
                var naive = (2.0 * phase - 1.0) * 0.35
                naive -= polyBlep(phase, dt, invDt) * 0.35
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
