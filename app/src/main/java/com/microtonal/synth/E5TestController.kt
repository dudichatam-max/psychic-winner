package com.microtonal.synth

/**
 * Process-level Active Scenario + Reference owner (singleton). Survives Composable disposal.
 * Phases: NO_REFERENCE | REFERENCE_READY | RUNNING | RESULT_READY
 *
 * Lifecycle:
 * - Import e5-scenario / e5-test-reference / e5-bundle
 * - START: if scenario present → startE5Session + Benchmark-style replay thread → auto stopE5 + evaluate
 *          if only reference → legacy manual E5 (no replay)
 * - STOP: cancel replay + finalize
 * - EXPORT: existing diagnostics export (unchanged)
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

/** Optional replay facade for scenario tests (engine player in production). */
interface E5ScenarioPlayerOps {
    fun playBlocking(session: BenchmarkReferenceSession)
    fun cancel()
}

fun SynthEngine.toE5SessionOps(): E5SessionOps = object : E5SessionOps {
    override fun startE5Session() = this@toE5SessionOps.startE5Session()
    override fun stopE5Session() = this@toE5SessionOps.stopE5Session()
    override fun captureSnapshot(): E5DiagnosticsSnapshot =
        E5DiagnosticsSnapshot.from(this@toE5SessionOps.e5)
}

fun SynthEngine.toE5ScenarioPlayerOps(): E5ScenarioPlayerOps = object : E5ScenarioPlayerOps {
    override fun playBlocking(session: BenchmarkReferenceSession) {
        this@toE5ScenarioPlayerOps.benchmarkReferencePlayer.playBlocking(
            session,
            includeLooper = false,
            includeDrums = false
        )
    }

    override fun cancel() {
        this@toE5ScenarioPlayerOps.benchmarkReferencePlayer.cancel()
    }
}

sealed class E5ImportResult {
    data class Success(
        val reference: E5TestReference? = null,
        val scenario: E5Scenario? = null
    ) : E5ImportResult()

    data class Rejected(val reason: String) : E5ImportResult()
}

object E5TestController {
    private val lock = Any()

    @Volatile
    private var phase: E5ControllerPhase = E5ControllerPhase.NO_REFERENCE

    @Volatile
    private var activeReference: E5TestReference? = null

    @Volatile
    private var activeScenario: E5Scenario? = null

    @Volatile
    private var lastResult: E5TestResult? = null

    @Volatile
    private var lastImportError: String? = null

    @Volatile
    private var replayThread: Thread? = null

    @Volatile
    private var activePlayer: E5ScenarioPlayerOps? = null

    fun getPhase(): E5ControllerPhase = phase

    fun getActiveReference(): E5TestReference? = activeReference

    fun getActiveScenario(): E5Scenario? = activeScenario

    fun getLastResult(): E5TestResult? = lastResult

    fun getLastImportError(): String? = lastImportError

    fun canImport(): Boolean = synchronized(lock) {
        phase != E5ControllerPhase.RUNNING
    }

    fun canStart(): Boolean = synchronized(lock) {
        canStartUnlocked()
    }

    fun canStop(): Boolean = synchronized(lock) {
        phase == E5ControllerPhase.RUNNING
    }

    /** Detect schema: e5-scenario | e5-test-reference | e5-bundle. */
    fun importBytes(bytes: ByteArray): E5ImportResult = synchronized(lock) {
        if (phase == E5ControllerPhase.RUNNING) {
            val msg = "import rejected: E5 reference session is RUNNING"
            lastImportError = msg
            return E5ImportResult.Rejected(msg)
        }
        when (val parsed = E5ScenarioJson.parseImport(bytes)) {
            is E5ScenarioJson.ImportParse.Scenario -> {
                activeScenario = parsed.scenario
                // Keep previous reference unless replaced by bundle
                lastResult = null
                lastImportError = null
                phase = E5ControllerPhase.REFERENCE_READY
                E5ImportResult.Success(reference = activeReference, scenario = parsed.scenario)
            }
            is E5ScenarioJson.ImportParse.Reference -> {
                activeReference = parsed.reference
                lastResult = null
                lastImportError = null
                phase = E5ControllerPhase.REFERENCE_READY
                E5ImportResult.Success(reference = parsed.reference, scenario = activeScenario)
            }
            is E5ScenarioJson.ImportParse.Bundle -> {
                activeScenario = parsed.scenario
                activeReference = parsed.reference
                lastResult = null
                lastImportError = null
                phase = E5ControllerPhase.REFERENCE_READY
                E5ImportResult.Success(reference = parsed.reference, scenario = parsed.scenario)
            }
            is E5ScenarioJson.ImportParse.Invalid -> {
                lastImportError = parsed.message
                E5ImportResult.Rejected(parsed.message)
            }
        }
    }

    fun importBytes(json: String): E5ImportResult =
        importBytes(json.toByteArray(Charsets.UTF_8))

    /** Backward-compatible alias (reference / scenario / bundle). */
    fun importReference(bytes: ByteArray): E5ImportResult = importBytes(bytes)

    fun importReference(json: String): E5ImportResult = importBytes(json)

    /**
     * START: scenario path arms E5 then launches [E5ScenarioReplay] thread that
     * playBlocking → stopE5 → snapshot → evaluate. UI returns immediately.
     * Reference-only path: legacy manual E5 (no replay).
     */
    fun start(
        ops: E5SessionOps,
        player: E5ScenarioPlayerOps? = null,
        engineSampleRate: Int = 48000
    ): Boolean = synchronized(lock) {
        if (!canStartUnlocked()) return false
        val scenario = activeScenario
        return try {
            if (scenario != null) {
                if (player == null) return false
                ops.startE5Session()
                lastResult = null
                phase = E5ControllerPhase.RUNNING
                activePlayer = player
                val sampleRate = engineSampleRate
                val sessionOps = ops
                val scen = scenario
                val thread = Thread {
                    try {
                        val session = E5ScenarioJson.toBenchmarkSession(scen, sampleRate)
                        player.playBlocking(session)
                    } catch (_: Throwable) {
                        // finalize below still runs
                    } finally {
                        finalizeAfterReplay(sessionOps)
                    }
                }.apply {
                    name = "E5ScenarioReplay"
                    isDaemon = true
                    start()
                }
                replayThread = thread
                true
            } else {
                ops.startE5Session()
                lastResult = null
                phase = E5ControllerPhase.RUNNING
                true
            }
        } catch (t: Throwable) {
            lastResult = E5TestResult(
                testId = activeReference?.testId ?: activeScenario?.scenarioId ?: "",
                overallStatus = E5OverallStatus.ERROR,
                assertionResults = emptyList()
            )
            phase = E5ControllerPhase.RESULT_READY
            activePlayer = null
            replayThread = null
            false
        }
    }

    fun start(engine: SynthEngine): Boolean =
        start(engine.toE5SessionOps(), engine.toE5ScenarioPlayerOps(), engine.sampleRate)

    /**
     * STOP → cancel replay if any → stopE5Session → snapshot → evaluate → RESULT_READY.
     */
    fun stop(ops: E5SessionOps, player: E5ScenarioPlayerOps? = null): E5TestResult? =
        synchronized(lock) {
            if (phase != E5ControllerPhase.RUNNING) return lastResult
            try {
                (player ?: activePlayer)?.cancel()
            } catch (_: Throwable) {
            }
            return finalizeRunningUnlocked(ops)
        }

    fun stop(engine: SynthEngine): E5TestResult? =
        stop(engine.toE5SessionOps(), engine.toE5ScenarioPlayerOps())

    private fun finalizeAfterReplay(ops: E5SessionOps) {
        synchronized(lock) {
            if (phase != E5ControllerPhase.RUNNING) return
            finalizeRunningUnlocked(ops)
        }
    }

    /** Caller must hold [lock] and phase must be RUNNING. */
    private fun finalizeRunningUnlocked(ops: E5SessionOps): E5TestResult? {
        val ref = activeReference
        val scen = activeScenario
        return try {
            ops.stopE5Session()
            val result = if (ref != null) {
                val snapshot = ops.captureSnapshot()
                E5TestRunner.evaluate(ref, snapshot)
            } else {
                E5TestResult(
                    testId = scen?.scenarioId ?: "",
                    overallStatus = E5OverallStatus.PASS,
                    assertionResults = emptyList()
                )
            }
            lastResult = result
            phase = E5ControllerPhase.RESULT_READY
            activePlayer = null
            replayThread = null
            result
        } catch (t: Throwable) {
            val result = E5TestResult(
                testId = ref?.testId ?: scen?.scenarioId ?: "",
                overallStatus = E5OverallStatus.ERROR,
                assertionResults = emptyList()
            )
            lastResult = result
            phase = E5ControllerPhase.RESULT_READY
            activePlayer = null
            replayThread = null
            result
        }
    }

    private fun canStartUnlocked(): Boolean {
        val ready =
            phase == E5ControllerPhase.REFERENCE_READY || phase == E5ControllerPhase.RESULT_READY
        if (!ready) return false
        return activeScenario != null || activeReference != null
    }

    /** Test-only reset of process singleton state. */
    internal fun resetForTests() = synchronized(lock) {
        try {
            activePlayer?.cancel()
        } catch (_: Throwable) {
        }
        phase = E5ControllerPhase.NO_REFERENCE
        activeReference = null
        activeScenario = null
        lastResult = null
        lastImportError = null
        activePlayer = null
        replayThread = null
    }

    /**
     * Test-only: mark RUNNING without an engine (for import-blocked-while-RUNNING cases).
     * Requires an active scenario or reference.
     */
    internal fun forceRunningForTests() = synchronized(lock) {
        check(activeReference != null || activeScenario != null) {
            "forceRunningForTests requires active scenario or reference"
        }
        phase = E5ControllerPhase.RUNNING
    }
}
