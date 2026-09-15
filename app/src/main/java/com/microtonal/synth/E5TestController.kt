package com.microtonal.synth

/**
 * Process-level Active Reference owner (singleton). Survives Composable disposal.
 * Phases: NO_REFERENCE | REFERENCE_READY | RUNNING | RESULT_READY
 *
 * Lifecycle: Import → Validate → Active Reference → START (existing E5) → STOP →
 * snapshot → evaluate → E5TestResult. EXPORT remains the existing diagnostics export.
 */
enum class E5ControllerPhase {
    NO_REFERENCE,
    REFERENCE_READY,
    RUNNING,
    RESULT_READY
}

/** Minimal facade so unit tests can exercise the state machine without Android. */
interface E5SessionOps {
    fun startE5Session()
    fun stopE5Session()
    fun captureSnapshot(): E5DiagnosticsSnapshot
}

fun SynthEngine.toE5SessionOps(): E5SessionOps = object : E5SessionOps {
    override fun startE5Session() = this@toE5SessionOps.startE5Session()
    override fun stopE5Session() = this@toE5SessionOps.stopE5Session()
    override fun captureSnapshot(): E5DiagnosticsSnapshot =
        E5DiagnosticsSnapshot.from(this@toE5SessionOps.e5)
}

sealed class E5ImportResult {
    data class Success(val reference: E5TestReference) : E5ImportResult()
    data class Rejected(val reason: String) : E5ImportResult()
}

object E5TestController {
    private val lock = Any()

    @Volatile
    private var phase: E5ControllerPhase = E5ControllerPhase.NO_REFERENCE

    @Volatile
    private var activeReference: E5TestReference? = null

    @Volatile
    private var lastResult: E5TestResult? = null

    @Volatile
    private var lastImportError: String? = null

    fun getPhase(): E5ControllerPhase = phase

    fun getActiveReference(): E5TestReference? = activeReference

    fun getLastResult(): E5TestResult? = lastResult

    fun getLastImportError(): String? = lastImportError

    fun canImport(): Boolean = synchronized(lock) {
        phase != E5ControllerPhase.RUNNING
    }

    fun canStart(): Boolean = synchronized(lock) {
        activeReference != null &&
            (phase == E5ControllerPhase.REFERENCE_READY || phase == E5ControllerPhase.RESULT_READY)
    }

    fun canStop(): Boolean = synchronized(lock) {
        phase == E5ControllerPhase.RUNNING
    }

    fun importReference(bytes: ByteArray): E5ImportResult = synchronized(lock) {
        if (phase == E5ControllerPhase.RUNNING) {
            val msg = "import rejected: E5 reference session is RUNNING"
            lastImportError = msg
            return E5ImportResult.Rejected(msg)
        }
        when (val parsed = E5TestReferenceJson.parse(bytes)) {
            is E5TestReferenceJson.ParseResult.Ok -> {
                activeReference = parsed.reference
                lastResult = null
                lastImportError = null
                phase = E5ControllerPhase.REFERENCE_READY
                E5ImportResult.Success(parsed.reference)
            }
            is E5TestReferenceJson.ParseResult.Invalid -> {
                // Leave previous active reference unchanged
                lastImportError = parsed.message
                E5ImportResult.Rejected(parsed.message)
            }
        }
    }

    fun importReference(json: String): E5ImportResult =
        importReference(json.toByteArray(Charsets.UTF_8))

    /**
     * START only when REFERENCE_READY or RESULT_READY (active reference present)
     * and not already RUNNING. Calls existing engine.startE5Session().
     */
    fun start(ops: E5SessionOps): Boolean = synchronized(lock) {
        if (!canStartUnlocked()) return false
        return try {
            ops.startE5Session()
            lastResult = null
            phase = E5ControllerPhase.RUNNING
            true
        } catch (t: Throwable) {
            lastResult = E5TestResult(
                testId = activeReference?.testId ?: "",
                overallStatus = E5OverallStatus.ERROR,
                assertionResults = emptyList()
            )
            phase = E5ControllerPhase.RESULT_READY
            false
        }
    }

    fun start(engine: SynthEngine): Boolean = start(engine.toE5SessionOps())

    /**
     * STOP → stopE5Session → snapshot diagnostics → evaluate → RESULT_READY.
     * No durationMs timer; STOP is always explicit.
     */
    fun stop(ops: E5SessionOps): E5TestResult? = synchronized(lock) {
        if (phase != E5ControllerPhase.RUNNING) return lastResult
        val ref = activeReference
        return try {
            ops.stopE5Session()
            if (ref == null) {
                phase = E5ControllerPhase.NO_REFERENCE
                null
            } else {
                val snapshot = ops.captureSnapshot()
                val result = E5TestRunner.evaluate(ref, snapshot)
                lastResult = result
                phase = E5ControllerPhase.RESULT_READY
                result
            }
        } catch (t: Throwable) {
            val result = E5TestResult(
                testId = ref?.testId ?: "",
                overallStatus = E5OverallStatus.ERROR,
                assertionResults = emptyList()
            )
            lastResult = result
            phase = E5ControllerPhase.RESULT_READY
            result
        }
    }

    fun stop(engine: SynthEngine): E5TestResult? = stop(engine.toE5SessionOps())

    private fun canStartUnlocked(): Boolean =
        activeReference != null &&
            (phase == E5ControllerPhase.REFERENCE_READY || phase == E5ControllerPhase.RESULT_READY)

    /** Test-only reset of process singleton state. */
    internal fun resetForTests() = synchronized(lock) {
        phase = E5ControllerPhase.NO_REFERENCE
        activeReference = null
        lastResult = null
        lastImportError = null
    }

    /**
     * Test-only: mark RUNNING without an engine (for import-blocked-while-RUNNING cases).
     * Requires an active reference.
     */
    internal fun forceRunningForTests() = synchronized(lock) {
        check(activeReference != null) { "forceRunningForTests requires active reference" }
        phase = E5ControllerPhase.RUNNING
    }
}
