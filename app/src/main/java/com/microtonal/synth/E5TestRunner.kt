package com.microtonal.synth

import kotlin.math.abs

/**
 * Read-only snapshot of E5Diagnostics public APIs for assertion evaluation.
 * Built off the AudioThread after STOP; no new measurement APIs on E5Diagnostics.
 */
data class E5DiagnosticsSnapshot(
    val sessionActive: Boolean,
    val controlRecordCount: Int,
    val pendingWindowCount: Int,
    val audioObservationCount: Int,
    val absoluteRenderFrame: Long,
    val controlOverflowCount: Int,
    val windowOverflowCount: Int,
    val audioObservationOverflowCount: Int,
    val hasExportableData: Boolean
) {
    fun valueForPath(path: String): Any? = when (path) {
        "e5.sessionActive" -> sessionActive
        "e5.controlRecordCount" -> controlRecordCount
        "e5.pendingWindowCount" -> pendingWindowCount
        "e5.audioObservationCount" -> audioObservationCount
        "e5.absoluteRenderFrame" -> absoluteRenderFrame
        "e5.controlOverflowCount" -> controlOverflowCount
        "e5.windowOverflowCount" -> windowOverflowCount
        "e5.audioObservationOverflowCount" -> audioObservationOverflowCount
        "e5.hasExportableData" -> hasExportableData
        else -> null
    }

    companion object {
        /** Capture from existing public E5Diagnostics APIs only (zero diagnostics changes). */
        fun from(e5: E5Diagnostics): E5DiagnosticsSnapshot = E5DiagnosticsSnapshot(
            sessionActive = e5.isSessionActive(),
            controlRecordCount = e5.controlRecordCount(),
            pendingWindowCount = e5.pendingWindowCount(),
            audioObservationCount = e5.audioObservationCount(),
            absoluteRenderFrame = e5.absoluteRenderFrame,
            controlOverflowCount = e5.e5ControlOverflowCount.get(),
            windowOverflowCount = e5.e5WindowOverflowCount.get(),
            audioObservationOverflowCount = e5.e5AudioObservationOverflowCount.get(),
            hasExportableData = e5.hasExportableData()
        )
    }
}

/**
 * Pure assertion evaluation: no I/O, no RT, no re-measure.
 */
object E5TestRunner {

    fun evaluate(reference: E5TestReference, snapshot: E5DiagnosticsSnapshot): E5TestResult {
        val results = ArrayList<E5AssertionResult>(reference.assertions.size)
        var anyFail = false
        var anyError = false

        for (assertion in reference.assertions) {
            val actual = snapshot.valueForPath(assertion.path)
            if (actual == null && assertion.path !in E5TestReferenceJson.SUPPORTED_ASSERTION_PATHS) {
                // Should be impossible if parse validated; treat as ERROR for safety.
                anyError = true
                results.add(
                    E5AssertionResult(
                        assertionId = assertion.id,
                        actual = null,
                        expected = assertion.expected,
                        operator = assertion.operator.json,
                        passed = false,
                        error = "unsupported path at evaluate: ${assertion.path}"
                    )
                )
                continue
            }
            val eval = evaluateOne(assertion, actual)
            if (eval.error != null) anyError = true
            if (!eval.passed) anyFail = true
            results.add(eval)
        }

        val overall = when {
            anyError -> E5OverallStatus.ERROR
            anyFail -> E5OverallStatus.FAIL
            else -> E5OverallStatus.PASS
        }
        return E5TestResult(
            testId = reference.testId,
            overallStatus = overall,
            assertionResults = results
        )
    }

    private fun evaluateOne(assertion: E5Assertion, actual: Any?): E5AssertionResult {
        val op = assertion.operator
        val expected = assertion.expected
        return try {
            val passed = when (op) {
                E5AssertionOperator.EQUALS -> equalsWithTolerance(actual, expected, assertion.tolerance)
                E5AssertionOperator.NOT_EQUALS -> !equalsWithTolerance(actual, expected, assertion.tolerance)
                E5AssertionOperator.GREATER_THAN -> {
                    val c = compareNumeric(actual, expected)
                        ?: return fail(assertion, actual, "greaterThan requires numeric actual/expected")
                    c > 0
                }
                E5AssertionOperator.GREATER_THAN_OR_EQUAL -> {
                    val c = compareNumeric(actual, expected)
                        ?: return fail(assertion, actual, "greaterThanOrEqual requires numeric actual/expected")
                    c >= 0
                }
                E5AssertionOperator.LESS_THAN -> {
                    val c = compareNumeric(actual, expected)
                        ?: return fail(assertion, actual, "lessThan requires numeric actual/expected")
                    c < 0
                }
                E5AssertionOperator.LESS_THAN_OR_EQUAL -> {
                    val c = compareNumeric(actual, expected)
                        ?: return fail(assertion, actual, "lessThanOrEqual requires numeric actual/expected")
                    c <= 0
                }
            }
            E5AssertionResult(
                assertionId = assertion.id,
                actual = actual,
                expected = expected,
                operator = op.json,
                passed = passed
            )
        } catch (t: Throwable) {
            E5AssertionResult(
                assertionId = assertion.id,
                actual = actual,
                expected = expected,
                operator = op.json,
                passed = false,
                error = t.message ?: "evaluation error"
            )
        }
    }

    private fun fail(assertion: E5Assertion, actual: Any?, error: String) =
        E5AssertionResult(
            assertionId = assertion.id,
            actual = actual,
            expected = assertion.expected,
            operator = assertion.operator.json,
            passed = false,
            error = error
        )

    private fun equalsWithTolerance(actual: Any?, expected: Any, tolerance: Double): Boolean {
        if (actual == null) return false
        if (actual is Boolean && expected is Boolean) return actual == expected
        if (actual is String && expected is String) return actual == expected
        if (actual is Boolean || expected is Boolean || actual is String || expected is String) {
            return actual == expected
        }
        val a = toDouble(actual) ?: return actual == expected
        val e = toDouble(expected) ?: return actual == expected
        return abs(a - e) <= tolerance
    }

    /** Returns actual compared to expected: >0 if actual > expected. */
    private fun compareNumeric(actual: Any?, expected: Any): Int? {
        val a = toDouble(actual) ?: return null
        val e = toDouble(expected) ?: return null
        return when {
            a > e -> 1
            a < e -> -1
            else -> 0
        }
    }

    private fun toDouble(v: Any?): Double? = when (v) {
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Double -> if (v.isFinite()) v else null
        is Float -> if (v.isFinite()) v.toDouble() else null
        is Number -> {
            val d = v.toDouble()
            if (d.isFinite()) d else null
        }
        else -> null
    }
}
