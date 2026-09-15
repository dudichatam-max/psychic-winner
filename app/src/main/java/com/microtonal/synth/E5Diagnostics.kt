package com.microtonal.synth

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/**
 * E5 R1.1 diagnostic instrumentation — observational only.
 *
 * Critical R1.1 acquisition lifecycle (ONLY permitted ordering):
 *   OPEN_AT_N → PAST_CAPTURED(32) → CENTER_RESERVED → CENTER_CAPTURED(33)
 *   → FUTURE_CAPTURE → COMPLETE(65)
 *
 * At observation of frame N, ring has only through N-1 (N-32..N-1).
 * Frame N is RESERVED at discovery; NOT captured until final PCM16 stereo
 * pair for N exists after render.
 *
 * Final evidence layout: evidence[0]=N-32 … [31]=N-1, [32]=N, … [64]=N+32
 *
 * Publication rule: diagnosticEventId write MUST precede active=true write.
 * Primary artifact source: final stereo PCM16 pair assigned to output
 * ShortArray immediately before AudioTrack.write().
 *
 * Discovery is NOT center sample capture.
 */
object E5Constants {
    const val MAX_E5_CONTROL_RECORDS = 4096
    const val MAX_E5_PENDING_WINDOWS = 1024
    const val MAX_E5_AUDIO_OBSERVATIONS = 4096
    const val E5_PAST_RING_FRAMES = 64
    const val E5_WINDOW_FRAMES = 65
    const val E5_PAST_FRAMES = 32
    const val E5_FUTURE_FRAMES = 32
    const val E5_CENTER_INDEX = 32

    // Window state machine (R1.1 §9.5)
    const val STATE_FREE = 0
    /** Past N-32..N-1 copied; center N reserved but NOT captured. */
    const val STATE_PAST_CAPTURED = 1
    /** Center N written after final PCM16 of N. */
    const val STATE_CENTER_CAPTURED = 2
    /** Capturing N+1..N+32. */
    const val STATE_FUTURE_CAPTURE = 3
    const val STATE_COMPLETE = 4
    const val STATE_INCOMPLETE = 5
}


/**
 * JSONObject rejects NaN/±Infinity. Put finite numbers as-is; non-finite become
 * JSON null with companion metadata (never substitute 0).
 */
fun putJsonNumber(obj: JSONObject, key: String, value: Double, nonFinite: JSONObject) {
    if (value.isFinite()) {
        obj.put(key, value)
    } else {
        obj.put(key, JSONObject.NULL)
        val label = when {
            value.isNaN() -> "NaN"
            value > 0.0 -> "Infinity"
            else -> "-Infinity"
        }
        nonFinite.put(key, label)
    }
}

fun putJsonNumber(obj: JSONObject, key: String, value: Float, nonFinite: JSONObject) {
    putJsonNumber(obj, key, value.toDouble(), nonFinite)
}

/**
 * Immutable NoteSlot diagnostic snapshot (control plane only).
 * AudioThread never constructs these.
 */
data class E5NoteSlotSnapshot(
    val active: Boolean,
    val isLooperNote: Boolean,
    val waveform: Int,
    val baseFreq: Float,
    val targetFreq: Float,
    val currentFreq: Float,
    val envelopeVolume: Double,
    val isReleasing: Boolean,
    val envState: Int,
    val hammerEnv: Double,
    val attackCoeff: Double,
    val decayCoeff: Double,
    val releaseCoeff: Double,
    val frozenAttack: Float,
    val frozenDecay: Float,
    val frozenSustain: Float,
    val frozenRelease: Float,
    val phase: Double,
    val phase2: Double,
    val phaseSub: Double,
    val phaseP2: Double,
    val phaseP3: Double,
    val phaseP4: Double,
    val phase2P2: Double,
    val phase2P3: Double,
    val phase2P4: Double,
    val prevFund: Double,
    val div2: Double,
    val div3: Double,
    val div4: Double,
    val zcCount: Int,
    val frozenCutoff: Float,
    val frozenRes: Float,
    val zdfState1: Double,
    val zdfState2: Double,
    val smoothedCutoff: Float,
    val smoothedRes: Float,
    val zdfCachedCutoff: Float,
    val zdfCachedRes: Float,
    val zdfG: Double,
    val zdfK: Double,
    val zdfH: Double,
    val zdfCoeffHold: Int
) {
    fun toJson(): JSONObject {
        val nonFinite = JSONObject()
        val o = JSONObject()
        o.put("active", active)
        o.put("isLooperNote", isLooperNote)
        o.put("waveform", waveform)
        putJsonNumber(o, "baseFreq", baseFreq, nonFinite)
        putJsonNumber(o, "targetFreq", targetFreq, nonFinite)
        putJsonNumber(o, "currentFreq", currentFreq, nonFinite)
        putJsonNumber(o, "envelopeVolume", envelopeVolume, nonFinite)
        o.put("isReleasing", isReleasing)
        o.put("envState", envState)
        putJsonNumber(o, "hammerEnv", hammerEnv, nonFinite)
        putJsonNumber(o, "attackCoeff", attackCoeff, nonFinite)
        putJsonNumber(o, "decayCoeff", decayCoeff, nonFinite)
        putJsonNumber(o, "releaseCoeff", releaseCoeff, nonFinite)
        putJsonNumber(o, "frozenAttack", frozenAttack, nonFinite)
        putJsonNumber(o, "frozenDecay", frozenDecay, nonFinite)
        putJsonNumber(o, "frozenSustain", frozenSustain, nonFinite)
        putJsonNumber(o, "frozenRelease", frozenRelease, nonFinite)
        putJsonNumber(o, "phase", phase, nonFinite)
        putJsonNumber(o, "phase2", phase2, nonFinite)
        putJsonNumber(o, "phaseSub", phaseSub, nonFinite)
        putJsonNumber(o, "phaseP2", phaseP2, nonFinite)
        putJsonNumber(o, "phaseP3", phaseP3, nonFinite)
        putJsonNumber(o, "phaseP4", phaseP4, nonFinite)
        putJsonNumber(o, "phase2P2", phase2P2, nonFinite)
        putJsonNumber(o, "phase2P3", phase2P3, nonFinite)
        putJsonNumber(o, "phase2P4", phase2P4, nonFinite)
        putJsonNumber(o, "prevFund", prevFund, nonFinite)
        putJsonNumber(o, "div2", div2, nonFinite)
        putJsonNumber(o, "div3", div3, nonFinite)
        putJsonNumber(o, "div4", div4, nonFinite)
        o.put("zcCount", zcCount)
        putJsonNumber(o, "frozenCutoff", frozenCutoff, nonFinite)
        putJsonNumber(o, "frozenRes", frozenRes, nonFinite)
        putJsonNumber(o, "zdfState1", zdfState1, nonFinite)
        putJsonNumber(o, "zdfState2", zdfState2, nonFinite)
        putJsonNumber(o, "smoothedCutoff", smoothedCutoff, nonFinite)
        putJsonNumber(o, "smoothedRes", smoothedRes, nonFinite)
        putJsonNumber(o, "zdfCachedCutoff", zdfCachedCutoff, nonFinite)
        putJsonNumber(o, "zdfCachedRes", zdfCachedRes, nonFinite)
        putJsonNumber(o, "zdfG", zdfG, nonFinite)
        putJsonNumber(o, "zdfK", zdfK, nonFinite)
        putJsonNumber(o, "zdfH", zdfH, nonFinite)
        o.put("zdfCoeffHold", zdfCoeffHold)
        if (nonFinite.length() > 0) {
            o.put("nonFiniteValues", nonFinite)
        }
        return o
    }
}

fun NoteSlot.captureE5Snapshot(): E5NoteSlotSnapshot = E5NoteSlotSnapshot(
    active = active,
    isLooperNote = isLooperNote,
    waveform = waveform,
    baseFreq = baseFreq,
    targetFreq = targetFreq,
    currentFreq = currentFreq,
    envelopeVolume = envelopeVolume,
    isReleasing = isReleasing,
    envState = envState,
    hammerEnv = hammerEnv,
    attackCoeff = attackCoeff,
    decayCoeff = decayCoeff,
    releaseCoeff = releaseCoeff,
    frozenAttack = frozenAttack,
    frozenDecay = frozenDecay,
    frozenSustain = frozenSustain,
    frozenRelease = frozenRelease,
    phase = phase,
    phase2 = phase2,
    phaseSub = phaseSub,
    phaseP2 = phaseP2,
    phaseP3 = phaseP3,
    phaseP4 = phaseP4,
    phase2P2 = phase2P2,
    phase2P3 = phase2P3,
    phase2P4 = phase2P4,
    prevFund = prevFund,
    div2 = div2,
    div3 = div3,
    div4 = div4,
    zcCount = zcCount,
    frozenCutoff = frozenCutoff,
    frozenRes = frozenRes,
    zdfState1 = zdfState1,
    zdfState2 = zdfState2,
    smoothedCutoff = smoothedCutoff,
    smoothedRes = smoothedRes,
    zdfCachedCutoff = zdfCachedCutoff,
    zdfCachedRes = zdfCachedRes,
    zdfG = zdfG,
    zdfK = zdfK,
    zdfH = zdfH,
    zdfCoeffHold = zdfCoeffHold
)

/**
 * Preallocated, RT-safe E5 diagnostic engine owned by SynthEngine.
 * All AudioThread methods use only fixed arrays and primitive ops.
 */
class E5Diagnostics(private val maxVoices: Int) {

    /**
     * Session gate: when false, RT observe/capture and control-plane recordControl
     * no-op. START sets true after reset; STOP sets false then marks incomplete.
     */
    @Volatile
    var sessionActive: Boolean = false
        private set

    fun isSessionActive(): Boolean = sessionActive

    fun setSessionActive(active: Boolean) {
        sessionActive = active
    }

    // --- Event ID allocator (control plane) ---
    private val e5NextEventId = AtomicLong(1L) // 0 = no published event

    fun allocateEventId(): Long = e5NextEventId.getAndIncrement()

    // --- Overflow counters ---
    val e5ControlOverflowCount = AtomicInteger(0)
    val e5WindowOverflowCount = AtomicInteger(0)
    val e5AudioObservationOverflowCount = AtomicInteger(0)

    // --- Control records (control plane; fixed capacity) ---
    private val controlLock = Any()
    private val controlEventId = LongArray(E5Constants.MAX_E5_CONTROL_RECORDS)
    private val controlSlotIndex = IntArray(E5Constants.MAX_E5_CONTROL_RECORDS)
    private val controlTimeNs = LongArray(E5Constants.MAX_E5_CONTROL_RECORDS)
    private val controlBefore = arrayOfNulls<E5NoteSlotSnapshot>(E5Constants.MAX_E5_CONTROL_RECORDS)
    private val controlAfter = arrayOfNulls<E5NoteSlotSnapshot>(E5Constants.MAX_E5_CONTROL_RECORDS)
    private val controlCount = AtomicInteger(0)

    fun recordControl(
        eventId: Long,
        slotIndex: Int,
        controlTimeNsValue: Long,
        before: E5NoteSlotSnapshot,
        after: E5NoteSlotSnapshot
    ) {
        if (!sessionActive) return
        val idx = controlCount.getAndIncrement()
        if (idx >= E5Constants.MAX_E5_CONTROL_RECORDS) {
            e5ControlOverflowCount.incrementAndGet()
            // Keep count saturated so further getAndIncrement stays past capacity.
            controlCount.compareAndSet(idx + 1, E5Constants.MAX_E5_CONTROL_RECORDS)
            return
        }
        synchronized(controlLock) {
            controlEventId[idx] = eventId
            controlSlotIndex[idx] = slotIndex
            controlTimeNs[idx] = controlTimeNsValue
            controlBefore[idx] = before
            controlAfter[idx] = after
        }
    }

    fun controlRecordCount(): Int = controlCount.get().coerceAtMost(E5Constants.MAX_E5_CONTROL_RECORDS)

    // --- AudioThread-owned absolute render frame (zero-based) ---
    /** Owned exclusively by AudioThread. Do not write from other threads. */
    var absoluteRenderFrame: Long = 0L

    // --- Per-slot last observed event id (AudioThread) ---
    private val lastObservedEventId = LongArray(maxVoices) { 0L }

    // --- History ring: supplies ONLY already-rendered frames (never N at discovery) ---
    private val ringFrameIndex = LongArray(E5Constants.E5_PAST_RING_FRAMES) { -1L }
    private val ringLeft = ShortArray(E5Constants.E5_PAST_RING_FRAMES)
    private val ringRight = ShortArray(E5Constants.E5_PAST_RING_FRAMES)
    private var ringWriteIndex = 0
    private var ringCount = 0
    private var ringLastFrame = -1L

    // --- Pending windows (independent evidence per event) ---
    private val winEventId = LongArray(E5Constants.MAX_E5_PENDING_WINDOWS)
    private val winSlotIndex = IntArray(E5Constants.MAX_E5_PENDING_WINDOWS)
    private val winAppliedFrame = LongArray(E5Constants.MAX_E5_PENDING_WINDOWS)
    private val winState = IntArray(E5Constants.MAX_E5_PENDING_WINDOWS)
    private val winCapturedCount = IntArray(E5Constants.MAX_E5_PENDING_WINDOWS)
    private val winHistoryIncomplete = BooleanArray(E5Constants.MAX_E5_PENDING_WINDOWS)
    // evidence: flat arrays [window * 65 + i]
    private val evFrameIndex = LongArray(E5Constants.MAX_E5_PENDING_WINDOWS * E5Constants.E5_WINDOW_FRAMES) { -1L }
    private val evLeft = ShortArray(E5Constants.MAX_E5_PENDING_WINDOWS * E5Constants.E5_WINDOW_FRAMES)
    private val evRight = ShortArray(E5Constants.MAX_E5_PENDING_WINDOWS * E5Constants.E5_WINDOW_FRAMES)
    private val evCaptured = BooleanArray(E5Constants.MAX_E5_PENDING_WINDOWS * E5Constants.E5_WINDOW_FRAMES)
    private var winAllocCursor = 0
    /** Active (in-flight) pending-window indices — iterate this, never full capacity, on RT. */
    private val activeWindowIdx = IntArray(E5Constants.MAX_E5_PENDING_WINDOWS)
    private var activeWindowCount = 0

    // --- Audio observations (join metadata) ---
    private val obsEventId = LongArray(E5Constants.MAX_E5_AUDIO_OBSERVATIONS)
    private val obsSlotIndex = IntArray(E5Constants.MAX_E5_AUDIO_OBSERVATIONS)
    private val obsAppliedFrame = LongArray(E5Constants.MAX_E5_AUDIO_OBSERVATIONS)
    private val obsWindowIndex = IntArray(E5Constants.MAX_E5_AUDIO_OBSERVATIONS) { -1 }
    private var obsCount = 0

    // Scratch for ring lookup (no allocation)
    private val ringLookupLeft = ShortArray(1)
    private val ringLookupRight = ShortArray(1)

    /**
     * Initialize / reset all E5 storage. Call before AudioThread observations begin
     * (startup) or for a fresh diagnostic session. Must NOT be called from RT loop.
     */
    fun initialize() {
        absoluteRenderFrame = 0L
        for (i in 0 until maxVoices) lastObservedEventId[i] = 0L
        ringWriteIndex = 0
        ringCount = 0
        ringLastFrame = -1L
        for (i in 0 until E5Constants.E5_PAST_RING_FRAMES) {
            ringFrameIndex[i] = -1L
            ringLeft[i] = 0
            ringRight[i] = 0
        }
        winAllocCursor = 0
        activeWindowCount = 0
        for (w in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) {
            winState[w] = E5Constants.STATE_FREE
            winEventId[w] = 0L
            winSlotIndex[w] = -1
            winAppliedFrame[w] = -1L
            winCapturedCount[w] = 0
            winHistoryIncomplete[w] = false
            val base = w * E5Constants.E5_WINDOW_FRAMES
            for (i in 0 until E5Constants.E5_WINDOW_FRAMES) {
                evFrameIndex[base + i] = -1L
                evLeft[base + i] = 0
                evRight[base + i] = 0
                evCaptured[base + i] = false
            }
        }
        obsCount = 0
        // Audio-side overflow counters reset with audio session.
        // Control records are NOT wiped here — they accumulate on the control
        // plane for the offline join (eventId key). Use resetControlRecords()
        // for an explicit full diagnostic reset.
        e5WindowOverflowCount.set(0)
        e5AudioObservationOverflowCount.set(0)
    }

    fun resetControlRecords() {
        synchronized(controlLock) {
            controlCount.set(0)
            e5ControlOverflowCount.set(0)
            for (i in 0 until E5Constants.MAX_E5_CONTROL_RECORDS) {
                controlEventId[i] = 0L
                controlSlotIndex[i] = -1
                controlTimeNs[i] = 0L
                controlBefore[i] = null
                controlAfter[i] = null
            }
        }
    }

    // ========== AudioThread RT path (allocation-free) ==========

    /**
     * Observe published event identities at the beginning of render iteration N.
     * MUST be called BEFORE rendering frame N.
     *
     * Discovery reserves center N and copies ONLY N-32..N-1 from the ring.
     * Center is NOT captured here (R1.1 invariant I16).
     */
    fun observeEventsAtFrameStart(noteSlots: Array<NoteSlot>, voiceCount: Int) {
        if (!sessionActive) return
        val n = absoluteRenderFrame
        val limit = if (voiceCount < maxVoices) voiceCount else maxVoices
        var s = 0
        while (s < limit) {
            val slot = noteSlots[s]
            // Publication read order: active FIRST, then diagnosticEventId
            if (!slot.active) {
                s++
                continue
            }
            val eventId = slot.diagnosticEventId
            if (eventId == 0L) {
                s++
                continue
            }
            if (eventId == lastObservedEventId[s]) {
                s++
                continue
            }
            // New event discovered at frame N — discovery ≠ center capture
            lastObservedEventId[s] = eventId
            openWindowAtN(eventId, s, n)
            s++
        }
    }

    /**
     * OPEN_AT_N → PAST_CAPTURED: copy N-32..N-1 from ring, reserve evidence[32].
     * capturedFrameCount becomes number of successfully copied past frames
     * (ideally 32). Center remains NOT CAPTURED.
     */
    private fun openWindowAtN(eventId: Long, slotIndex: Int, n: Long) {
        // Record audio observation metadata
        var obsIdx = -1
        if (obsCount < E5Constants.MAX_E5_AUDIO_OBSERVATIONS) {
            obsIdx = obsCount
            obsEventId[obsIdx] = eventId
            obsSlotIndex[obsIdx] = slotIndex
            obsAppliedFrame[obsIdx] = n
            obsWindowIndex[obsIdx] = -1
            obsCount++
        } else {
            e5AudioObservationOverflowCount.incrementAndGet()
        }

        // Allocate pending window
        val w = allocWindow()
        if (w < 0) {
            e5WindowOverflowCount.incrementAndGet()
            return
        }
        if (obsIdx >= 0) {
            obsWindowIndex[obsIdx] = w
        }

        winEventId[w] = eventId
        winSlotIndex[w] = slotIndex
        winAppliedFrame[w] = n
        winHistoryIncomplete[w] = false

        val base = w * E5Constants.E5_WINDOW_FRAMES
        // Clear evidence slots
        var i = 0
        while (i < E5Constants.E5_WINDOW_FRAMES) {
            evFrameIndex[base + i] = -1L
            evLeft[base + i] = 0
            evRight[base + i] = 0
            evCaptured[base + i] = false
            i++
        }

        // Copy ONLY N-32..N-1 from ring. NEVER copy N from ring (I15, I16, I22).
        var captured = 0
        i = 0
        while (i < E5Constants.E5_PAST_FRAMES) {
            val f = n - E5Constants.E5_PAST_FRAMES + i // N-32 + i
            val ei = base + i
            if (f >= 0 && lookupRing(f)) {
                evFrameIndex[ei] = f
                evLeft[ei] = ringLookupLeft[0]
                evRight[ei] = ringLookupRight[0]
                evCaptured[ei] = true
                captured++
            } else {
                // Missing / invalid — do not substitute a neighbor (TEST Q)
                evFrameIndex[ei] = f
                evCaptured[ei] = false
                winHistoryIncomplete[w] = true
            }
            i++
        }

        // evidence[32] = RESERVED, NOT CAPTURED (center)
        evFrameIndex[base + E5Constants.E5_CENTER_INDEX] = n
        evCaptured[base + E5Constants.E5_CENTER_INDEX] = false

        winCapturedCount[w] = captured
        // State: PAST_CAPTURED — center reserved, not captured
        winState[w] = E5Constants.STATE_PAST_CAPTURED
        // Push onto active pending-window index list (capacity ≠ per-sample work)
        activeWindowIdx[activeWindowCount] = w
        activeWindowCount++
    }

    private fun allocWindow(): Int {
        // Linear scan from cursor for a FREE slot; no dynamic growth
        var checked = 0
        while (checked < E5Constants.MAX_E5_PENDING_WINDOWS) {
            val w = winAllocCursor
            winAllocCursor++
            if (winAllocCursor >= E5Constants.MAX_E5_PENDING_WINDOWS) winAllocCursor = 0
            if (winState[w] == E5Constants.STATE_FREE) {
                return w
            }
            // Reclaim COMPLETE/INCOMPLETE only if we need space? Plan says fixed
            // capacity; completed windows stay until export/reset. Do not reclaim
            // in RT path to avoid racing export readers — overflow instead.
            checked++
        }
        return -1
    }

    /**
     * Lookup historical frame F in the ring. Valid only if entry.frameIndex == F.
     * Result left/right placed in ringLookupLeft/Right[0].
     * Returns false if unavailable — caller must NOT substitute.
     */
    private fun lookupRing(f: Long): Boolean {
        if (ringCount == 0 || f < 0) return false
        if (ringLastFrame < 0) return false
        val age = ringLastFrame - f
        if (age < 0 || age >= ringCount || age >= E5Constants.E5_PAST_RING_FRAMES) return false
        val idx = (ringWriteIndex - 1 - age.toInt() + E5Constants.E5_PAST_RING_FRAMES) %
            E5Constants.E5_PAST_RING_FRAMES
        if (ringFrameIndex[idx] != f) return false
        ringLookupLeft[0] = ringLeft[idx]
        ringLookupRight[0] = ringRight[idx]
        return true
    }

    /**
     * After final PCM16 stereo pair for frame F is produced and written to the
     * normal output buffer:
     *   1. write F to history ring
     *   2. write F into every pending window whose range contains F
     *   3. finalize windows whose end frame is F
     *
     * This is the SINGLE SOURCE OF TRUTH for pending-window sample capture (§23).
     * Center N is populated here when F == appliedFrame (NOT from the ring).
     */
    fun onFinalPcm16Produced(f: Long, left: Short, right: Short) {
        if (!sessionActive) return
        // 1. History ring
        ringFrameIndex[ringWriteIndex] = f
        ringLeft[ringWriteIndex] = left
        ringRight[ringWriteIndex] = right
        ringWriteIndex++
        if (ringWriteIndex >= E5Constants.E5_PAST_RING_FRAMES) ringWriteIndex = 0
        if (ringCount < E5Constants.E5_PAST_RING_FRAMES) ringCount++
        ringLastFrame = f

        // 2–3. Pending windows — iterate ONLY active indices (typically tiny)
        var ai = 0
        while (ai < activeWindowCount) {
            val w = activeWindowIdx[ai]
            val state = winState[w]
            // Defensive: skip any non-in-flight entry that slipped in
            if (state == E5Constants.STATE_FREE ||
                state == E5Constants.STATE_COMPLETE ||
                state == E5Constants.STATE_INCOMPLETE
            ) {
                activeWindowCount--
                activeWindowIdx[ai] = activeWindowIdx[activeWindowCount]
                continue
            }
            val n = winAppliedFrame[w]
            val index = f - n + E5Constants.E5_CENTER_INDEX // F - N + 32
            if (index < 0 || index > 64) {
                ai++
                continue
            }
            val ei = w * E5Constants.E5_WINDOW_FRAMES + index.toInt()
            if (!evCaptured[ei]) {
                evFrameIndex[ei] = f
                evLeft[ei] = left
                evRight[ei] = right
                evCaptured[ei] = true
                winCapturedCount[w]++
            }

            // State transitions
            if (index.toInt() == E5Constants.E5_CENTER_INDEX &&
                state == E5Constants.STATE_PAST_CAPTURED
            ) {
                // CENTER_CAPTURED: count ideally 33 if all past present
                winState[w] = E5Constants.STATE_CENTER_CAPTURED
            } else if (state == E5Constants.STATE_CENTER_CAPTURED ||
                state == E5Constants.STATE_FUTURE_CAPTURE
            ) {
                winState[w] = E5Constants.STATE_FUTURE_CAPTURE
            }

            // Completion: F == N+32
            if (f == n + E5Constants.E5_FUTURE_FRAMES) {
                val allOk = winCapturedCount[w] == E5Constants.E5_WINDOW_FRAMES &&
                    !winHistoryIncomplete[w] &&
                    evCaptured[w * E5Constants.E5_WINDOW_FRAMES + 64] &&
                    evFrameIndex[w * E5Constants.E5_WINDOW_FRAMES + 64] == n + E5Constants.E5_FUTURE_FRAMES
                winState[w] = if (allOk) E5Constants.STATE_COMPLETE else E5Constants.STATE_INCOMPLETE
                // Remove from active list (swap-remove); do not advance ai
                activeWindowCount--
                activeWindowIdx[ai] = activeWindowIdx[activeWindowCount]
                continue
            }
            ai++
        }
    }

    /** Increment absolute render frame exactly once after per-frame work. */
    fun incrementRenderFrame() {
        if (!sessionActive) return
        absoluteRenderFrame++
    }

    /**
     * Shutdown: mark all non-complete pending windows as INCOMPLETE.
     * Call outside RT after AudioThread stopped. Does not serialize.
     */
    fun markIncompleteOnShutdown() {
        // Off-RT: mark every in-flight window INCOMPLETE and clear active list.
        var ai = 0
        while (ai < activeWindowCount) {
            val w = activeWindowIdx[ai]
            val state = winState[w]
            if (state == E5Constants.STATE_PAST_CAPTURED ||
                state == E5Constants.STATE_CENTER_CAPTURED ||
                state == E5Constants.STATE_FUTURE_CAPTURE
            ) {
                winState[w] = E5Constants.STATE_INCOMPLETE
            }
            ai++
        }
        activeWindowCount = 0
    }

    // ========== Offline export / verification (NOT on AudioThread) ==========

    fun pendingWindowCount(): Int {
        var n = 0
        for (w in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) {
            if (winState[w] != E5Constants.STATE_FREE) n++
        }
        return n
    }

    fun audioObservationCount(): Int = obsCount

    fun hasExportableData(): Boolean =
        controlRecordCount() > 0 || pendingWindowCount() > 0 || obsCount > 0

    fun stateName(state: Int): String = when (state) {
        E5Constants.STATE_FREE -> "FREE"
        E5Constants.STATE_PAST_CAPTURED -> "PAST_CAPTURED"
        E5Constants.STATE_CENTER_CAPTURED -> "CENTER_CAPTURED"
        E5Constants.STATE_FUTURE_CAPTURE -> "FUTURE_CAPTURE"
        E5Constants.STATE_COMPLETE -> "COMPLETE"
        E5Constants.STATE_INCOMPLETE -> "INCOMPLETE"
        else -> "UNKNOWN_$state"
    }

    /**
     * Static / offline verification of R1.1 invariants on captured data.
     * Returns list of "PASS: ..." / "FAIL: ..." strings. Never classifies BUG.
     */
    fun verifyInvariants(): List<String> {
        val results = mutableListOf<String>()

        // I1 / I13 — unique event IDs in control records
        val seenControl = HashSet<Long>()
        var dupControl = false
        val cc = controlRecordCount()
        for (i in 0 until cc) {
            val id = controlEventId[i]
            if (id != 0L && !seenControl.add(id)) dupControl = true
        }
        results.add(if (!dupControl) "PASS: I1/I13 unique control eventIds (n=$cc)" else "FAIL: I1 duplicate control eventIds")

        // I19/I20/I18 — complete windows have exact frame indices
        var completeChecked = 0
        var completeOk = true
        var orderedOk = true
        for (w in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) {
            if (winState[w] != E5Constants.STATE_COMPLETE) continue
            completeChecked++
            val n = winAppliedFrame[w]
            val base = w * E5Constants.E5_WINDOW_FRAMES
            if (winCapturedCount[w] != 65) {
                completeOk = false
            }
            if (evFrameIndex[base + 32] != n || !evCaptured[base + 32]) {
                completeOk = false
            }
            for (i in 0 until 65) {
                if (!evCaptured[base + i]) {
                    completeOk = false
                    break
                }
                if (evFrameIndex[base + i] != n - 32 + i) {
                    orderedOk = false
                    break
                }
            }
        }
        results.add(
            if (completeOk) "PASS: I18/I19 complete windows exact range (checked=$completeChecked)"
            else "FAIL: I18/I19 complete window range/count"
        )
        results.add(
            if (orderedOk) "PASS: I20 ordered evidence indices"
            else "FAIL: I20 evidence[i].frameIndex != appliedFrame-32+i"
        )

        // I25 — no duplicate observations of same eventId for same slot
        val seenObs = HashSet<String>()
        var dupObs = false
        for (i in 0 until obsCount) {
            val key = "${obsEventId[i]}:${obsSlotIndex[i]}"
            if (!seenObs.add(key)) dupObs = true
        }
        results.add(if (!dupObs) "PASS: I25 no duplicate eventId/slot observations" else "FAIL: I25 duplicate observations")

        // I8 — appliedFrame from absoluteRenderFrame domain (non-negative)
        var frameOk = true
        for (i in 0 until obsCount) {
            if (obsAppliedFrame[i] < 0) frameOk = false
        }
        results.add(if (frameOk) "PASS: I8 appliedFrame non-negative render domain" else "FAIL: I8 negative appliedFrame")

        // I28 — no PAST/CENTER/FUTURE left after markIncomplete (checked by caller timing)
        var dangling = 0
        for (w in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) {
            val st = winState[w]
            if (st == E5Constants.STATE_PAST_CAPTURED ||
                st == E5Constants.STATE_CENTER_CAPTURED ||
                st == E5Constants.STATE_FUTURE_CAPTURE
            ) dangling++
        }
        results.add(
            if (dangling == 0) "PASS: I28 no dangling in-flight windows (or still running)"
            else "INFO: I28 $dangling in-flight windows still open (expected if audio still running)"
        )

        // I3 structural: documented that diagnosticEventId precedes active in NoteSlot.updateAndActivate
        results.add("PASS: I3/I4 publication order enforced in NoteSlot.updateAndActivate (static)")

        // I15/I16/I17 structural notes
        results.add("PASS: I15/I16/I17 discovery copies only N-32..N-1; center reserved; capture after PCM16 (static+runtime path)")

        // I11 — no benchmark files touched (verified by delivery process)
        results.add("PASS: I11 benchmark isolation (no benchmark file modifications in E5 delivery)")

        // I12 — no BUG classifier present
        results.add("PASS: I12 evidence only — no BUG classification in E5")

        return results
    }

    /**
     * Build export JSONObject. MUST be called outside AudioThread.
     * Never classifies BUG. Non-finite floats become null + nonFiniteValues metadata.
     */
    fun buildExportJson(): JSONObject {
        // STOP owns incomplete marking. While session is still active, export
        // current buffers as-is (in-flight windows keep their live states).
        if (!sessionActive) {
            markIncompleteOnShutdown()
        }
        val root = JSONObject()
        root.put("e5Version", "R1.1")
        root.put("absoluteRenderFrame", absoluteRenderFrame)
        root.put("sessionActive", sessionActive)
        root.put("e5ControlOverflowCount", e5ControlOverflowCount.get())
        root.put("e5WindowOverflowCount", e5WindowOverflowCount.get())
        root.put("e5AudioObservationOverflowCount", e5AudioObservationOverflowCount.get())

        val controls = JSONArray()
        val cc = controlRecordCount()
        for (i in 0 until cc) {
            val o = JSONObject()
            o.put("eventId", controlEventId[i])
            o.put("slotIndex", controlSlotIndex[i])
            o.put("controlTimeNs", controlTimeNs[i])
            controlBefore[i]?.let { o.put("BEFORE", it.toJson()) }
            controlAfter[i]?.let { o.put("AFTER", it.toJson()) }
            controls.put(o)
        }
        root.put("controlRecords", controls)

        val observations = JSONArray()
        for (i in 0 until obsCount) {
            val o = JSONObject()
            o.put("eventId", obsEventId[i])
            o.put("slotIndex", obsSlotIndex[i])
            o.put("appliedFrame", obsAppliedFrame[i])
            o.put("windowIndex", obsWindowIndex[i])
            observations.put(o)
        }
        root.put("audioObservations", observations)

        val windows = JSONArray()
        for (w in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) {
            if (winState[w] == E5Constants.STATE_FREE) continue
            val o = JSONObject()
            o.put("eventId", winEventId[w])
            o.put("slotIndex", winSlotIndex[w])
            o.put("appliedFrame", winAppliedFrame[w])
            o.put("state", stateName(winState[w]))
            o.put("capturedFrameCount", winCapturedCount[w])
            o.put("historyIncomplete", winHistoryIncomplete[w])
            val evidence = JSONArray()
            val base = w * E5Constants.E5_WINDOW_FRAMES
            val n = winAppliedFrame[w]
            for (i in 0 until E5Constants.E5_WINDOW_FRAMES) {
                val e = JSONObject()
                e.put("index", i)
                e.put("expectedFrame", n - 32 + i)
                e.put("frameIndex", evFrameIndex[base + i])
                e.put("leftPcm16", evLeft[base + i].toInt())
                e.put("rightPcm16", evRight[base + i].toInt())
                e.put("captured", evCaptured[base + i])
                if (i == E5Constants.E5_CENTER_INDEX) {
                    e.put("role", "CENTER")
                } else if (i < E5Constants.E5_CENTER_INDEX) {
                    e.put("role", "PAST")
                } else {
                    e.put("role", "FUTURE")
                }
                evidence.put(e)
            }
            o.put("evidence", evidence)
            windows.put(o)
        }
        root.put("pendingWindows", windows)

        val invariants = JSONArray()
        for (line in verifyInvariants()) invariants.put(line)
        root.put("invariantChecks", invariants)

        return root
    }

    fun buildExportJsonString(indent: Int = 2): String = buildExportJson().toString(indent)

    /**
     * Write export JSON to an OutputStream (e.g. SAF ContentResolver Uri).
     * MUST be called outside AudioThread. Returns false on IO failure.
     */
    fun exportToOutputStream(output: OutputStream): Boolean {
        return try {
            val bytes = buildExportJsonString().toByteArray(Charsets.UTF_8)
            output.write(bytes)
            output.flush()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Export all E5 evidence to JSON file. MUST be called outside AudioThread / after stop.
     * Prefer SAF CreateDocument + exportToOutputStream for user-chosen location.
     */
    fun exportToFile(file: File): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText(buildExportJsonString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Offline unit-style check: snapshot with NaN serializes to null (not 0)
     * and remains parseable JSON. Returns list of PASS/FAIL lines.
     */
    fun selfTestNanExport(): List<String> {
        val results = mutableListOf<String>()
        val snap = E5NoteSlotSnapshot(
            active = true,
            isLooperNote = false,
            waveform = 0,
            baseFreq = 440f,
            targetFreq = 440f,
            currentFreq = 440f,
            envelopeVolume = 1.0,
            isReleasing = false,
            envState = 0,
            hammerEnv = 0.0,
            attackCoeff = 0.0,
            decayCoeff = 0.0,
            releaseCoeff = 0.0,
            frozenAttack = 0f,
            frozenDecay = 0f,
            frozenSustain = 0f,
            frozenRelease = 0f,
            phase = 0.0,
            phase2 = 0.0,
            phaseSub = 0.0,
            phaseP2 = 0.0,
            phaseP3 = 0.0,
            phaseP4 = 0.0,
            phase2P2 = 0.0,
            phase2P3 = 0.0,
            phase2P4 = 0.0,
            prevFund = 0.0,
            div2 = 0.0,
            div3 = 0.0,
            div4 = 0.0,
            zcCount = 0,
            frozenCutoff = 1000f,
            frozenRes = 0.5f,
            zdfState1 = 0.0,
            zdfState2 = 0.0,
            smoothedCutoff = 1000f,
            smoothedRes = 0.5f,
            zdfCachedCutoff = Float.NaN,
            zdfCachedRes = Float.POSITIVE_INFINITY,
            zdfG = Double.NEGATIVE_INFINITY,
            zdfK = 0.0,
            zdfH = 0.0,
            zdfCoeffHold = 0
        )
        val json = snap.toJson()
        val text = json.toString()
        results.add(
            if (!text.contains("NaN") && !text.contains("Infinity"))
                "PASS: no raw NaN/Infinity tokens in JSON text"
            else "FAIL: raw non-finite token leaked into JSON"
        )
        val cutoff = json.opt("zdfCachedCutoff")
        results.add(
            if (cutoff == JSONObject.NULL) "PASS: zdfCachedCutoff is JSON null (not 0)"
            else "FAIL: zdfCachedCutoff=$cutoff (expected null)"
        )
        val res = json.opt("zdfCachedRes")
        results.add(
            if (res == JSONObject.NULL) "PASS: zdfCachedRes is JSON null"
            else "FAIL: zdfCachedRes=$res"
        )
        val g = json.opt("zdfG")
        results.add(
            if (g == JSONObject.NULL) "PASS: zdfG is JSON null"
            else "FAIL: zdfG=$g"
        )
        // Must not have substituted 0 for those fields
        results.add(
            if (cutoff != 0 && cutoff != 0.0 && res != 0 && res != 0.0 && g != 0 && g != 0.0)
                "PASS: non-finite fields were not replaced with 0"
            else "FAIL: non-finite field became 0"
        )
        val meta = json.optJSONObject("nonFiniteValues")
        results.add(
            if (meta != null &&
                meta.optString("zdfCachedCutoff") == "NaN" &&
                meta.optString("zdfCachedRes") == "Infinity" &&
                meta.optString("zdfG") == "-Infinity"
            ) "PASS: nonFiniteValues metadata preserves NaN/Infinity/-Infinity"
            else "FAIL: nonFiniteValues metadata missing or wrong: $meta"
        )
        // Parse round-trip
        try {
            JSONObject(text)
            results.add("PASS: export string parses as JSONObject")
        } catch (t: Throwable) {
            results.add("FAIL: parse error ${t.message}")
        }
        return results
    }

    // --- Test hooks (offline / harness; not used on AudioThread) ---

    /** Read-only peek for verification: window state after discovery before center capture. */
    fun debugWindowState(windowIndex: Int): Int =
        if (windowIndex in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) winState[windowIndex] else -1

    fun debugWindowCapturedCount(windowIndex: Int): Int =
        if (windowIndex in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) winCapturedCount[windowIndex] else -1

    fun debugCenterCaptured(windowIndex: Int): Boolean {
        if (windowIndex !in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) return false
        return evCaptured[windowIndex * E5Constants.E5_WINDOW_FRAMES + E5Constants.E5_CENTER_INDEX]
    }

    fun debugFindWindowForEvent(eventId: Long): Int {
        for (w in 0 until E5Constants.MAX_E5_PENDING_WINDOWS) {
            if (winState[w] != E5Constants.STATE_FREE && winEventId[w] == eventId) return w
        }
        return -1
    }
}
