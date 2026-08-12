package com.microtonal.synth

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.tanh

class DspEngine(private val sampleRate: Int = 44100) {

    private val delayBuffer = FloatArray(sampleRate) // באפר של שנייה עבור Echo
    private var delayWritePos = 0
    private var dcX1 = 0.0
    private var dcY1 = 0.0
    private var currentHeadroom = 1.0

    /**
     * מעבד דגימה בודדת (Sample) מכל הקולות, מפריד בין ערוץ ה-Live לערוץ ה-Looper,
     * ומפעיל מנגנון Limiter למניעת פיצוצים וקליקים.
     */
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
    ): Float {
        val glideFactor = if (glideMs > 0) (1.0 / (sampleRate * (glideMs / 1000.0))).coerceIn(0.001, 1.0) else 1.0

        var activeCount = 0
        for (v in 0 until maxVoices) {
            if (noteSlots[v].active) activeCount++
        }

        // 1. החלקה דינמית של ה-Headroom למניעת קליקים וקפיצות עוצמה בלופר
        val targetHeadroom = if (activeCount > 0) 1.0 / (1.0 + activeCount * 0.15) else 1.0
        currentHeadroom += (targetHeadroom - currentHeadroom) * 0.05

        var liveChannelMix = 0.0
        var looperChannelMix = 0.0

        // 2. חישוב הקולות והפרדה בין הערוצים
        for (v in 0 until maxVoices) {
            val slot = noteSlots[v]
            if (!slot.active) continue

            // Glide (פורטמנטו)
            if (glideMs > 0 && abs(slot.currentFreq - slot.targetFreq) > 0.05f) {
                slot.currentFreq += ((slot.targetFreq - slot.currentFreq) * glideFactor).toFloat()
            } else {
                slot.currentFreq = slot.targetFreq
            }

            // Phase step
            slot.phase += (2.0 * PI * slot.currentFreq / sampleRate)
            if (slot.phase >= 2.0 * PI) {
                slot.phase %= (2.0 * PI)
            }

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
                if (slot.envelopeVolume < 0.001) {
                    slot.envelopeVolume = 0.0
                    slot.active = false
                    continue
                }
            }

            // מחולל גלים (Waveforms)
            val raw = when (slot.waveform) {
                0 -> sin(slot.phase)
                1 -> if (sin(slot.phase) >= 0) 0.3 else -0.3
                2 -> (2.0 / PI) * asin(sin(slot.phase))
                3 -> (1.0 - (slot.phase / PI)) * 0.4
                else -> (Math.random() * 2.0 - 1.0) * 0.2
            }

            var voiceSample = raw * slot.envelopeVolume * currentHeadroom * 0.55

            // פילטר SVF לכל קול
            val actualCutoff = if (slot.isLooperNote) slot.frozenCutoff else cutoffFreq
            val actualRes = if (slot.isLooperNote) slot.frozenRes else resonance

            val f = (2.0 * sin(PI * actualCutoff / sampleRate)).coerceIn(0.01, 0.8)
            val q = (1.0 - actualRes.toDouble().coerceIn(0.0, 0.95))

            val hp = voiceSample - slot.svfLow - q * slot.svfBand
            slot.svfBand += f * hp
            slot.svfLow += f * slot.svfBand
            voiceSample = slot.svfLow

            // פיצול ערוצים: הפרדה מוחלטת בין הלופר לנגינה החיה
            if (slot.isLooperNote) {
                looperChannelMix += voiceSample
            } else {
                liveChannelMix += voiceSample
            }
        }

        // 3. איחוד ערוצים עם עוצמה נפרדת (מונע בדיוק את העומס והפיצוצים)
        var totalSample = (liveChannelMix * liveVolume) + (looperChannelMix * looperVolume)

        // 4. אפקט דיליי (Echo)
        val delaySamples = (sampleRate * 0.25).toInt()
        val delayReadPos = (delayWritePos - delaySamples + delayBuffer.size) % delayBuffer.size
        val echoSample = delayBuffer[delayReadPos]

        delayBuffer[delayWritePos] = (totalSample + echoSample * 0.4).toFloat()
        delayWritePos = (delayWritePos + 1) % delayBuffer.size

        totalSample += echoSample * echoMix

        // 5. ניקוי תדרים נמוכים (DC Offset Filter)
        val dcSample = totalSample - dcX1 + 0.995 * dcY1
        dcX1 = totalSample
        dcY1 = dcSample
        totalSample = dcSample

        // 6. אלגוריתם Soft-Clipping / Limiter (דוחס את הסאונד בעדינות במקום להתפוצץ)
        return softClip(totalSample * 0.45).toFloat()
    }

    private fun softClip(sample: Double): Double {
        return tanh(sample)
    }
}
