package com.microtonal.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class E5TestRunnerTest {

    private fun snap(
        sessionActive: Boolean = false,
        controlRecordCount: Int = 0,
        pendingWindowCount: Int = 0,
        audioObservationCount: Int = 0,
        absoluteRenderFrame: Long = 0L,
        controlOverflowCount: Int = 0,
        windowOverflowCount: Int = 0,
        audioObservationOverflowCount: Int = 0,
        hasExportableData: Boolean = false
    ) = E5DiagnosticsSnapshot(
        sessionActive = sessionActive,
        controlRecordCount = controlRecordCount,
        pendingWindowCount = pendingWindowCount,
        audioObservationCount = audioObservationCount,
        absoluteRenderFrame = absoluteRenderFrame,
        controlOverflowCount = controlOverflowCount,
        windowOverflowCount = windowOverflowCount,
        audioObservationOverflowCount = audioObservationOverflowCount,
        hasExportableData = hasExportableData
    )

    private fun ref(vararg assertions: E5Assertion) = E5TestReference(
        schema = E5TestReferenceJson.SCHEMA_ID,
        schemaVersion = 1,
        testId = "runner-test",
        title = "Runner",
        assertions = assertions.toList()
    )

    @Test
    fun pass_boolean_equals() {
        val result = E5TestRunner.evaluate(
            ref(
                E5Assertion("a", "e5.sessionActive", E5AssertionOperator.EQUALS, false)
            ),
            snap(sessionActive = false)
        )
        assertEquals(E5OverallStatus.PASS, result.overallStatus)
        assertTrue(result.assertionResults[0].passed)
    }

    @Test
    fun fail_boolean_equals() {
        val result = E5TestRunner.evaluate(
            ref(
                E5Assertion("a", "e5.sessionActive", E5AssertionOperator.EQUALS, true)
            ),
            snap(sessionActive = false)
        )
        assertEquals(E5OverallStatus.FAIL, result.overallStatus)
        assertFalse(result.assertionResults[0].passed)
    }

    @Test
    fun pass_numeric_equals_with_tolerance() {
        val result = E5TestRunner.evaluate(
            ref(
                E5Assertion(
                    "a",
                    "e5.absoluteRenderFrame",
                    E5AssertionOperator.EQUALS,
                    100,
                    tolerance = 5.0
                )
            ),
            snap(absoluteRenderFrame = 103L)
        )
        assertEquals(E5OverallStatus.PASS, result.overallStatus)
    }

    @Test
    fun fail_numeric_equals_outside_tolerance() {
        val result = E5TestRunner.evaluate(
            ref(
                E5Assertion(
                    "a",
                    "e5.absoluteRenderFrame",
                    E5AssertionOperator.EQUALS,
                    100,
                    tolerance = 2.0
                )
            ),
            snap(absoluteRenderFrame = 110L)
        )
        assertEquals(E5OverallStatus.FAIL, result.overallStatus)
    }

    @Test
    fun pass_greaterThan() {
        val result = E5TestRunner.evaluate(
            ref(
                E5Assertion("a", "e5.controlRecordCount", E5AssertionOperator.GREATER_THAN, 0)
            ),
            snap(controlRecordCount = 3)
        )
        assertEquals(E5OverallStatus.PASS, result.overallStatus)
    }

    @Test
    fun pass_notEquals() {
        val result = E5TestRunner.evaluate(
            ref(
                E5Assertion("a", "e5.windowOverflowCount", E5AssertionOperator.NOT_EQUALS, 0)
            ),
            snap(windowOverflowCount = 2)
        )
        assertEquals(E5OverallStatus.PASS, result.overallStatus)
    }

    @Test
    fun mixed_fail_dominates_pass() {
        val result = E5TestRunner.evaluate(
            ref(
                E5Assertion("ok", "e5.sessionActive", E5AssertionOperator.EQUALS, false),
                E5Assertion("bad", "e5.hasExportableData", E5AssertionOperator.EQUALS, true)
            ),
            snap(sessionActive = false, hasExportableData = false)
        )
        assertEquals(E5OverallStatus.FAIL, result.overallStatus)
        assertEquals(1, result.passedCount)
        assertEquals(1, result.failedCount)
    }
}
