package com.microtonal.synth




import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin








class DspFrame(
    var liveSample: Float = 0f,
    var looperSample: Float = 0f,
    var drumSample: Float = 0f, // <--- הוספה: שמירת סאמפל התופים במסגרת
    var masterSample: Float = 0f,
    // Live bus tap used by the 4-track PCM looper so each take freezes
    // waveform / pad / LFO / detune / drive as they sounded at record time.
    var liveRecordTap: Float = 0f,
    var externalSample: Float = 0f
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
    private var smoothedExternalVol = 1.0
    private var smoothedEchoMix = 0.25
    private var smoothedLooperEchoMix = 0.25 // <--- הוספה: שליטת Echo נפרדת ללופר
    private var smoothedDrumVol = 1.0f
    private var smoothedPerfX = 0.0f
    private var smoothedPerfY = 0.0f
    private var smoothedLoopPerfX = 0.0f
    private var smoothedLoopPerfY = 0.0f
    
    private var liveLfoPhase = 0.0
    private var loopLfoPhase = 0.0
    private var vibeLfoPhase = 0.0
    private var warmLpState = 0.0








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

    fun hasExternalAudio(): Boolean {
        val buf = externalAudioBuffer
        return buf != null && buf.isNotEmpty()
    }

    fun resetExternalPlayhead() {
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
        decayMs: Float,
        sustainLevel: Float,
        releaseMs: Float,
        echoMix: Float,
        looperEchoMix: Float, // <--- הוספה: פרמטר לופר אקו
        performanceX: Float,
        performanceY: Float,
        loopPerformanceX: Float = 0f,
        loopPerformanceY: Float = 0f,
        drumSampleIn: Float = 0f, // <--- הוספה: אות התופים הנכנס מה-DrumEngine
        drumVolume: Float = 1.0f,  // <--- הוספה: שליטת ווליום ערוץ התופים
        driveAmount: Float = 0.35f, // 0..1 saturation / drive
        detuneOn: Boolean = false,  // cheap second oscillator unison
        externalVolume: Float = 1.0f,
        subOn: Boolean = false,
        warmOn: Boolean = false,
        vibeOn: Boolean = false
    ): DspFrame {
        smoothedLiveVol += (liveVolume - smoothedLiveVol) * 0.005
        smoothedLooperVol += (looperVolume - smoothedLooperVol) * 0.005
        smoothedExternalVol += (externalVolume - smoothedExternalVol) * 0.005
        smoothedEchoMix += (echoMix - smoothedEchoMix) * 0.005
        smoothedLooperEchoMix += (looperEchoMix - smoothedLooperEchoMix) * 0.005 // <--- הוספה: החלקה ללופר אקו
        smoothedDrumVol += (drumVolume - smoothedDrumVol) * 0.005f
        smoothedPerfX += (performanceX - smoothedPerfX) * 0.005f
        smoothedPerfY += (performanceY - smoothedPerfY) * 0.005f
        smoothedLoopPerfX += (loopPerformanceX - smoothedLoopPerfX) * 0.005f
        smoothedLoopPerfY += (loopPerformanceY - smoothedLoopPerfY) * 0.005f
        
        // --- ציר X: LFO על תדר החיתוך (Cutoff) - נפרד ל-Live ול-Looper ---
        val liveLfoFreq = 0.1 + smoothedPerfX * 24.9 
        liveLfoPhase += liveLfoFreq * invSampleRate
        if (liveLfoPhase >= 1.0) liveLfoPhase -= 1.0
        val liveLfoMod = fastSine(liveLfoPhase).toFloat()

        val loopLfoFreq = 0.1 + smoothedLoopPerfX * 24.9 
        loopLfoPhase += loopLfoFreq * invSampleRate
        if (loopLfoPhase >= 1.0) loopLfoPhase -= 1.0
        val loopLfoMod = fastSine(loopLfoPhase).toFloat()

        // Analog vibe: slow pitch sway (~5.4 Hz), live voices only — does not
        // steal the pad's cutoff-LFO axis.
        vibeLfoPhase += 5.4 * invSampleRate
        if (vibeLfoPhase >= 1.0) vibeLfoPhase -= 1.0
        val vibeMod = if (vibeOn) (fastSine(vibeLfoPhase) * 0.0075).toFloat() else 0f








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








            val vibeScale = if (vibeOn && !slot.isLooperNote) (1.0f + vibeMod) else 1.0f
            val dt = (slot.currentFreq * vibeScale * invSampleRate).coerceIn(0.0001, 0.45)
            val invDt = 1.0 / dt
            slot.phase += 2.0 * PI * dt
            if (slot.phase >= 2.0 * PI) {
                slot.phase %= (2.0 * PI)
            }
            val phaseNorm = slot.phase / (2.0 * PI)








            val actualSustain = if (slot.isLooperNote) slot.frozenSustain else sustainLevel
            val actualDecay = if (slot.isLooperNote) slot.frozenDecay else decayMs








            val attackCoeff = if (slot.attackCoeff > 0.0) slot.attackCoeff else {
                val actualAttack = if (slot.isLooperNote) slot.frozenAttack else attackMs
                1.0 - Math.exp(-invSampleRate / (actualAttack / 1000.0).coerceAtLeast(0.001))
            }








            val decayCoeff = if (slot.decayCoeff > 0.0) slot.decayCoeff else {
                1.0 - Math.exp(-invSampleRate / (actualDecay / 1000.0).coerceAtLeast(0.001))
            }








            val releaseCoeff = if (slot.releaseCoeff > 0.0) slot.releaseCoeff else {
                val actualRelease = if (slot.isLooperNote) slot.frozenRelease else releaseMs
                Math.exp(-invSampleRate / (actualRelease / 1000.0).coerceAtLeast(0.001))
            }








            // --- מכונת מצבים מלאה לעטיפת ADSR ---
            if (!slot.isReleasing) {
                when (slot.envState) {
                    0 -> { // Attack: עולה עד לשיא (1.0)
                        slot.envelopeVolume += (1.0 - slot.envelopeVolume) * attackCoeff
                        if (slot.envelopeVolume >= 0.99) {
                            slot.envelopeVolume = 1.0
                            slot.envState = 1 // מעבר לשלב Decay
                        }
                    }
                    1 -> { // Decay: יורד מרמת השיא לעבר רמת ה-Sustain
                        slot.envelopeVolume += (actualSustain.toDouble() - slot.envelopeVolume) * decayCoeff
                        if (abs(slot.envelopeVolume - actualSustain.toDouble()) < 0.001) {
                            slot.envelopeVolume = actualSustain.toDouble()
                            slot.envState = 2 // מעבר לשלב Sustain
                        }
                    }
                    else -> { // Sustain: שמירה על הרמה כל עוד התו לחוץ
                        slot.envelopeVolume += (actualSustain.toDouble() - slot.envelopeVolume) * 0.01
                    }
                }
            } else {
                // Release: דעיכה מלאה עד לסגירת הערוץ
                slot.envelopeVolume *= releaseCoeff
                if (slot.envelopeVolume < 0.0005) {
                    slot.envelopeVolume = 0.0
                    slot.active = false
                    slot.zdfState1 = 0.0
                    slot.zdfState2 = 0.0
                    continue
                }
            }








            var raw = generateOptimizedWaveform(slot.waveform, phaseNorm, dt, invDt)

            // Analog SUB: sine one octave down on live voices only.
            if (subOn && !slot.isLooperNote) {
                val dtSub = (slot.currentFreq * 0.5 * invSampleRate).coerceIn(0.0001, 0.45)
                slot.phaseSub += 2.0 * PI * dtSub
                if (slot.phaseSub >= 2.0 * PI) slot.phaseSub %= (2.0 * PI)
                val sub = fastSine(slot.phaseSub / (2.0 * PI))
                raw = raw * 0.70 + sub * 0.40
            }

            // Cheap unison / detune: second oscillator slightly sharp, mixed lower.
            // Only runs when detuneOn – almost zero extra cost when off (critical for weak devices).
            if (detuneOn) {
                val detuneRatio = 1.0035  // ~6 cents
                val dt2 = (slot.currentFreq * detuneRatio * invSampleRate).coerceIn(0.0001, 0.45)
                val invDt2 = 1.0 / dt2
                slot.phase2 += 2.0 * PI * dt2
                if (slot.phase2 >= 2.0 * PI) slot.phase2 %= (2.0 * PI)
                val phaseNorm2 = slot.phase2 / (2.0 * PI)
                val raw2 = generateOptimizedWaveform(slot.waveform, phaseNorm2, dt2, invDt2)
                raw = raw * 0.68 + raw2 * 0.32
            }








            // --- פילטר ZDF: ציר X = Cutoff LFO, ציר Y = Resonance ---
            // Live pad modulates only live notes; recorded loop pad modulates only looper notes.
            // This allows free live pad use while a recorded loop is playing,
            // while still replaying the pad automation that was captured during recording.
            var targetCutoff = (if (slot.isLooperNote) slot.frozenCutoff else cutoffFreq).coerceIn(20f, 16000f)
            var targetRes = (if (slot.isLooperNote) slot.frozenRes else resonance)

            val usePerfX = if (slot.isLooperNote) smoothedLoopPerfX else smoothedPerfX
            val usePerfY = if (slot.isLooperNote) smoothedLoopPerfY else smoothedPerfY
            val useLfoMod = if (slot.isLooperNote) loopLfoMod else liveLfoMod

            if (usePerfX > 0.001f) {
                val modDepth = usePerfX * 4000f
                targetCutoff = (targetCutoff + (useLfoMod * modDepth)).coerceIn(20f, 16000f).toFloat()
            }
            
            if (usePerfY > 0.001f) {
                targetRes = (targetRes + usePerfY * (0.82f - targetRes)).coerceIn(0.0f, 0.82f)
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
            if (externalAudioPos >= extBuf.size) {
                externalAudioPos = if (isExternalAudioLooping) 0 else extBuf.size
            }
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








        var liveMix = liveChannelMix
        if (warmOn) {
            // One-pole low shelf around ~180 Hz + gentle even harmonic.
            val warmCoeff = 1.0 - Math.exp(-2.0 * PI * 180.0 * invSampleRate)
            warmLpState += (liveMix - warmLpState) * warmCoeff
            liveMix = liveMix * 0.78 + warmLpState * 0.55
            liveMix += 0.08 * liveMix * liveMix
        } else {
            warmLpState += (0.0 - warmLpState) * 0.01
        }
        val finalLiveSample = (liveMix * smoothedLiveVol).toFloat()
        val synthLooperSample = (looperChannelMix * smoothedLooperVol).toFloat()
        val extAudioSampleScaled = (externalAudioSample * smoothedExternalVol).toFloat()








        val finalLooperSample = synthLooperSample + extAudioSampleScaled
        val synthTotal = (finalLiveSample + synthLooperSample).toDouble()








        // --- Delay Effect מעודכן עם שליחות נפרדות ---
        val delaySamples = (sampleRate * 0.28).toInt()
        val delayReadPos = (delayWritePos - delaySamples + delayBuffer.size) % delayBuffer.size
        var echoSample = delayBuffer[delayReadPos].toDouble()








        echoSample = echoSample * 0.82 + delayFilterState * 0.18
        delayFilterState = echoSample








        val feedback = 0.42
        
        // שליחת כל ערוץ בנפרד לתוך הדיליי בהתאם לנוב המיועד לו
        val delaySend = (finalLiveSample * smoothedEchoMix) + (synthLooperSample * smoothedLooperEchoMix)
        
        delayBuffer[delayWritePos] = (delaySend + echoSample * feedback).toFloat()
        delayWritePos = (delayWritePos + 1) % delayBuffer.size








        val processedSynth = synthTotal + echoSample
        
        // --- הוספה: שילוב התופים במיקס הכולל ---
        val processedDrum = (drumSampleIn * smoothedDrumVol).toDouble()
        var totalSample = processedSynth + extAudioSampleScaled.toDouble() + processedDrum








        // DC Blocker
        val dcSample = totalSample - dcX1 + 0.995 * dcY1
        dcX1 = totalSample
        dcY1 = if (dcSample.isNaN() || dcSample.isInfinite()) 0.0 else dcSample








        // Soft Clipper
        val masterSample = softSaturate(dcY1 * 0.52, driveAmount).toFloat()








        reusableFrame.liveSample = finalLiveSample
        reusableFrame.looperSample = finalLooperSample
        reusableFrame.drumSample = processedDrum.toFloat() // <--- הוספה: עדכון למסגרת
        reusableFrame.masterSample = masterSample
        // Freeze-in-place tap: live voices + the live echo send + the drive
        // amount that was active while the musician was playing.
        reusableFrame.liveRecordTap = softSaturate(
            (finalLiveSample + (echoSample * smoothedEchoMix)).toDouble(),
            driveAmount
        ).toFloat()
        reusableFrame.externalSample = extAudioSampleScaled








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
    private inline fun softSaturate(x: Double, drive: Float = 0.35f): Double {
        // drive 0..1 → factor roughly 1.0 .. 2.8 (gentle to strong saturation)
        val driveFactor = 1.0 + drive.toDouble().coerceIn(0.0, 1.0) * 1.8
        val driven = x * driveFactor
        val x2 = driven * driven
        return driven * (27.0 + x2) / (27.0 + 9.0 * x2)
    }
}