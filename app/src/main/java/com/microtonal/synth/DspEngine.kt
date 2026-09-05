package com.microtonal.synth
  



import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
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
    private var smoothedReverb = 0.0

    private val revCombN = 4
    private val revCombBuf: Array<FloatArray>
    private val revCombLen = IntArray(revCombN)
    private val revCombPos = IntArray(revCombN)
    private val revCombLp = FloatArray(revCombN)
    private val revCombFb = floatArrayOf(0.86f, 0.84f, 0.82f, 0.80f)
    private val revApBuf: Array<FloatArray>
    private val revApLen = IntArray(2)
    private val revApPos = IntArray(2)
    private val revDamp = 0.28f
    private val delayBufSize: Int
    private val delaySamplesFixed: Int

    init {
        val scale = sampleRate / 44100.0
        val combT = intArrayOf(1116, 1188, 1277, 1356)
        val apT = intArrayOf(225, 556)
        revCombBuf = Array(revCombN) { i ->
            val n = (combT[i] * scale).toInt().coerceAtLeast(32)
            revCombLen[i] = n
            FloatArray(n)
        }
        revApBuf = Array(2) { i ->
            val n = (apT[i] * scale).toInt().coerceAtLeast(16)
            revApLen[i] = n
            FloatArray(n)
        }
        delayBufSize = delayBuffer.size
        delaySamplesFixed = (sampleRate * 0.28).toInt().coerceIn(1, delayBufSize - 1)
    }

    private fun processReverb(input: Float): Float {
        val x = input * 0.42f
        var sum = 0f
        var i = 0
        while (i < revCombN) {
            val buf = revCombBuf[i]
            val len = revCombLen[i]
            var pos = revCombPos[i]
            val d = buf[pos]
            val lp = d * (1f - revDamp) + revCombLp[i] * revDamp
            revCombLp[i] = lp
            buf[pos] = x + lp * revCombFb[i]
            pos++
            if (pos >= len) pos = 0
            revCombPos[i] = pos
            sum += d
            i++
        }
        var y = sum * 0.25f
        i = 0
        while (i < 2) {
            val buf = revApBuf[i]
            val len = revApLen[i]
            var pos = revApPos[i]
            val d = buf[pos]
            val out = d - y * 0.5f
            buf[pos] = y + out * 0.5f
            pos++
            if (pos >= len) pos = 0
            revApPos[i] = pos
            y = out
            i++
        }
        if (y > 1.2f) y = 1.2f else if (y < -1.2f) y = -1.2f
        return y
    }








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
    private var warmEvenDc = 0.0
    // ~120 Hz one-pole. Never call Math.exp in the sample loop.
    private val warmLpCoeff = (2.0 * PI * 120.0 * invSampleRate).coerceIn(0.001, 0.25)
    private var ripLpState = 0.0
    private var ripWet = 0.0
    private val ripLpCoeff = (2.0 * PI * 900.0 * invSampleRate).coerceIn(0.001, 0.35)
    private var fuzzWet = 0.0
    private var phazZ1 = 0.0
    private var phazZ2 = 0.0
    private var phazZ3 = 0.0
    private var phazZ4 = 0.0
    private var phazLfoPhase = 0.0
    private var phazWet = 0.0
    private var pianoWet = 0.0
    private val pianoWetCoeff = 0.0012
    private var pianoPolyScale = 1.0
    private var div2Wet = 0.0
    private var div3Wet = 0.0
    private var div4Wet = 0.0
    private var divPolyScale = 1.0








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

    private inline fun wrap01(x: Double): Double {
        return x - floor(x)
    }

    private fun generatePianoWave(p1: Double, p2: Double, p3: Double): Double {
        return fastSine(p1) * 0.22 + fastSine(p2) * 0.08 + fastSine(p3) * 0.03
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
        vibeOn: Boolean = false,
        ripOn: Boolean = false,
        fuzzOn: Boolean = false,
        phazOn: Boolean = false,
        pianoOn: Boolean = false,
        div2On: Boolean = false,
        div3On: Boolean = false,
        div4On: Boolean = false,
        reverbMix: Float = 0f,
        extraReverbSend: Float = 0f
    ): DspFrame {
        smoothedReverb += (reverbMix.toDouble().coerceIn(0.0, 1.0) - smoothedReverb) * 0.005
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
        
        var activeCount = 0
        for (v in 0 until maxVoices) {
            if (noteSlots[v].active) activeCount++
        }
        val targetHeadroom = if (activeCount > 0) 1.0 / (1.0 + activeCount * 0.12) else 1.0
        currentHeadroom += (targetHeadroom - currentHeadroom) * 0.01
        pianoWet += ((if (pianoOn) 1.0 else 0.0) - pianoWet) * pianoWetCoeff
        div2Wet += ((if (div2On) 1.0 else 0.0) - div2Wet) * 0.0008
        div3Wet += ((if (div3On) 1.0 else 0.0) - div3Wet) * 0.0008
        div4Wet += ((if (div4On) 1.0 else 0.0) - div4Wet) * 0.0008
        val targetDivPoly = if (activeCount > 1) 1.0 / (1.0 + (activeCount - 1) * 0.55) else 1.0
        divPolyScale += (targetDivPoly - divPolyScale) * 0.02

        var liveLfoMod = 0f
        var loopLfoMod = 0f
        var vibeMod = 0f
        val glideFactor: Double
        if (activeCount == 0) {
            glideFactor = 1.0
        } else {
            // --- ציר X: LFO על תדר החיתוך (Cutoff) - נפרד ל-Live ול-Looper ---
            val liveLfoFreq = 0.1 + smoothedPerfX * 24.9
            liveLfoPhase += liveLfoFreq * invSampleRate
            if (liveLfoPhase >= 1.0) liveLfoPhase -= 1.0
            liveLfoMod = fastSine(liveLfoPhase).toFloat()

            val loopLfoFreq = 0.1 + smoothedLoopPerfX * 24.9
            loopLfoPhase += loopLfoFreq * invSampleRate
            if (loopLfoPhase >= 1.0) loopLfoPhase -= 1.0
            loopLfoMod = fastSine(loopLfoPhase).toFloat()

            // Analog vibe: slow pitch sway (~5.4 Hz), live voices only — does not
            // steal the pad's cutoff-LFO axis.
            vibeLfoPhase += 5.4 * invSampleRate
            if (vibeLfoPhase >= 1.0) vibeLfoPhase -= 1.0
            vibeMod = if (vibeOn) (fastSine(vibeLfoPhase) * 0.0075).toFloat() else 0f
            glideFactor = if (glideMs > 0) (invSampleRate / (glideMs / 1000.0)).coerceIn(0.001, 1.0) else 1.0
        }

        var liveChannelMix = 0.0

        var looperChannelMix = 0.0








        if (activeCount > 0) for (v in 0 until maxVoices) {
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








            val livePiano = !slot.isLooperNote && pianoWet > 0.0005
            if (livePiano) {
                slot.phaseP2 += 2.0 * PI * dt * 2.0
                slot.phaseP3 += 2.0 * PI * dt * 3.0
                if (slot.phaseP2 >= 2.0 * PI) slot.phaseP2 %= (2.0 * PI)
                if (slot.phaseP3 >= 2.0 * PI) slot.phaseP3 %= (2.0 * PI)
            }
            var raw = if (pianoWet >= 0.995 && livePiano) {
                generatePianoWave(phaseNorm, slot.phaseP2 / (2.0 * PI), slot.phaseP3 / (2.0 * PI))
            } else if (livePiano) {
                val waveRaw = generateOptimizedWaveform(slot.waveform, phaseNorm, dt, invDt)
                val pianoRaw = generatePianoWave(phaseNorm, slot.phaseP2 / (2.0 * PI), slot.phaseP3 / (2.0 * PI))
                waveRaw * (1.0 - pianoWet) + pianoRaw * pianoWet
            } else {
                generateOptimizedWaveform(slot.waveform, phaseNorm, dt, invDt)
            }

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
                raw = if (livePiano) {
                    // One piano body + a quiet detuned fundamental.
                    // A second full partial stack through the filter was the remaining crackle.
                    raw * 0.84 + fastSine(phaseNorm2) * (0.16 * slot.envelopeVolume)
                } else {
                    val raw2 = generateOptimizedWaveform(slot.waveform, phaseNorm2, dt2, invDt2)
                    raw * 0.68 + raw2 * 0.32
                }
            }

            if (!slot.isLooperNote && (div2Wet > 0.0005 || div3Wet > 0.0005 || div4Wet > 0.0005)) {
                val fund = fastSine(phaseNorm)
                if (slot.prevFund <= 0.0 && fund > 0.0) {
                    slot.zcCount++
                    if (slot.zcCount % 2 == 0) slot.div2 = -slot.div2
                    if (slot.zcCount % 3 == 0) slot.div3 = -slot.div3
                    if (slot.zcCount % 4 == 0) slot.div4 = -slot.div4
                }
                slot.prevFund = fund
                raw = raw * 0.92 + (
                    slot.div2 * (0.07 * div2Wet) +
                    slot.div3 * (0.055 * div3Wet) +
                    slot.div4 * (0.045 * div4Wet)
                ) * divPolyScale
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
            // Analog warmth on the live bus only: deeper shelf, soft-round the
            // lows, tiny even glow with DC removed. No Math.exp. No x*x on the
            // full bus (that path used to underrun and inject DC).
            warmLpState += (liveMix - warmLpState) * warmLpCoeff
            val lp = warmLpState
            val rounded = lp - lp * lp * lp * 0.18
            val even = lp * abs(lp)
            warmEvenDc += (even - warmEvenDc) * 0.004
            liveMix = liveMix * 0.62 + rounded * 0.78 + (even - warmEvenDc) * 0.16
            if (liveMix > 1.2) liveMix = 1.2 else if (liveMix < -1.2) liveMix = -1.2
        } else if (warmLpState != 0.0 || warmEvenDc != 0.0) {
            warmLpState *= 0.99
            warmEvenDc *= 0.99
        }
        // SOUND live FX (not in presets). Wet is smoothed so toggles do not click.
        // Each block is adds/multiplies only — no allocations, no Math.exp.
        ripWet += ((if (ripOn) 1.0 else 0.0) - ripWet) * 0.003
        if (ripWet > 0.0005) {
            ripLpState += (liveMix - ripLpState) * ripLpCoeff
            val hp = liveMix - ripLpState
            val ripped = hp * 1.70 + ripLpState * 0.05
            liveMix = liveMix * (1.0 - ripWet) + ripped * ripWet
        } else {
            ripLpState = liveMix
        }
        fuzzWet += ((if (fuzzOn) 1.0 else 0.0) - fuzzWet) * 0.0008
        if (fuzzWet > 0.0005) {
            val driven = liveMix * 2.15
            val clipped = if (driven > 1.0) 1.0 else if (driven < -1.0) -1.0 else driven
            val fuzzed = clipped - clipped * clipped * clipped * 0.33
            liveMix = liveMix * (1.0 - fuzzWet) + fuzzed * 0.92 * fuzzWet
        }
        phazWet += ((if (phazOn) 1.0 else 0.0) - phazWet) * 0.003
        if (phazWet > 0.0005) {
            phazLfoPhase += 0.70 * invSampleRate
            if (phazLfoPhase >= 1.0) phazLfoPhase -= 1.0
            val lfo = fastSine(phazLfoPhase)
            val a = 0.12 + 0.72 * (0.5 + 0.5 * lfo)
            val x0 = liveMix + phazZ4 * 0.32
            val y1 = -x0 + a * (x0 - phazZ1)
            phazZ1 = y1
            val y2 = -y1 + a * (y1 - phazZ2)
            phazZ2 = y2
            val y3 = -y2 + a * (y2 - phazZ3)
            phazZ3 = y3
            val y4 = -y3 + a * (y3 - phazZ4)
            phazZ4 = y4
            val wet = phazWet * 0.85
            liveMix = liveMix * (1.0 - wet) + y4 * wet
        } else if (phazZ1 != 0.0 || phazZ2 != 0.0 || phazZ3 != 0.0 || phazZ4 != 0.0) {
            phazZ1 *= 0.99
            phazZ2 *= 0.99
            phazZ3 *= 0.99
            phazZ4 *= 0.99
        }
        if (liveMix > 1.3) liveMix = 1.3 else if (liveMix < -1.3) liveMix = -1.3
        val finalLiveSample = (liveMix * smoothedLiveVol).toFloat()
        val synthLooperSample = (looperChannelMix * smoothedLooperVol).toFloat()
        val extAudioSampleScaled = (externalAudioSample * smoothedExternalVol).toFloat()








        val finalLooperSample = synthLooperSample + extAudioSampleScaled
        val synthTotal = (finalLiveSample + synthLooperSample).toDouble()








        // --- Delay Effect מעודכן עם שליחות נפרדות ---
        val delayReadRaw = delayWritePos - delaySamplesFixed
        val delayReadPos = if (delayReadRaw < 0) delayReadRaw + delayBufSize else delayReadRaw
        var echoSample = delayBuffer[delayReadPos].toDouble()








        echoSample = echoSample * 0.82 + delayFilterState * 0.18
        delayFilterState = echoSample








        val feedback = 0.42
        
        // שליחת כל ערוץ בנפרד לתוך הדיליי בהתאם לנוב המיועד לו
        val delaySend = (finalLiveSample * smoothedEchoMix) + (synthLooperSample * smoothedLooperEchoMix)
        
        delayBuffer[delayWritePos] = (delaySend + echoSample * feedback).toFloat()
        delayWritePos++
        if (delayWritePos >= delayBufSize) delayWritePos = 0








        val revIn = finalLiveSample + echoSample.toFloat() * 0.35f + extraReverbSend
        val revOut = if (smoothedReverb > 0.0008) processReverb(revIn) else 0f
        val processedSynth = synthTotal + echoSample + revOut * smoothedReverb * 2.2
        
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
            (finalLiveSample + (echoSample * smoothedEchoMix) + revOut * smoothedReverb).toDouble(),
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
