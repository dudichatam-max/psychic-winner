package com.microtonal.synth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class E5TestControllerTest {

    private val sampleA = """
    {
      "schema": "e5-test-reference",
      "schemaVersion": 1,
      "testId": "sample-a",
      "title": "Sample A",
      "assertions": [
        {"id":"a1","path":"e5.sessionActive","operator":"equals","expected":false}
      ]
    }
    """.trimIndent()

    private val sampleB = """
    {
      "schema": "e5-test-reference",
      "schemaVersion": 1,
      "testId": "sample-b",
      "title": "Sample B",
      "session": {"durationMs": 10000},
      "assertions": [
        {"id":"b1","path":"e5.hasExportableData","operator":"equals","expected":false}
      ]
    }
    """.trimIndent()

    private val invalidJson = """
    {
      "schema": "e5-test-reference",
      "schemaVersion": 1,
      "testId": "bad",
      "title": "Bad",
      "assertions": [
        {"id":"x","path":"e5.unknown","operator":"equals","expected":1}
      ]
    }
    """.trimIndent()

    @Before
    fun setUp() {
        E5TestController.resetForTests()
    }

    @After
    fun tearDown() {
        E5TestController.resetForTests()
    }

    @Test
    fun import_a_sets_reference_ready() {
        val r = E5TestController.importReference(sampleA)
        assertTrue(r is E5ImportResult.Success)
        assertEquals(E5ControllerPhase.REFERENCE_READY, E5TestController.getPhase())
        assertEquals("sample-a", E5TestController.getActiveReference()?.testId)
    }

    @Test
    fun invalid_preserves_active_a() {
        E5TestController.importReference(sampleA)
        val rejected = E5TestController.importReference(invalidJson)
        assertTrue(rejected is E5ImportResult.Rejected)
        assertEquals("sample-a", E5TestController.getActiveReference()?.testId)
        assertEquals(E5ControllerPhase.REFERENCE_READY, E5TestController.getPhase())
    }

    @Test
    fun import_a_then_b_replaces() {
        E5TestController.importReference(sampleA)
        val r = E5TestController.importReference(sampleB)
        assertTrue(r is E5ImportResult.Success)
        assertEquals("sample-b", E5TestController.getActiveReference()?.testId)
        assertEquals(10000L, E5TestController.getActiveReference()?.session?.durationMs)
    }

    @Test
    fun running_blocks_import() {
        E5TestController.importReference(sampleA)
        E5TestController.forceRunningForTests()
        assertFalse(E5TestController.canImport())
        val rejected = E5TestController.importReference(sampleB)
        assertTrue(rejected is E5ImportResult.Rejected)
        assertEquals("sample-a", E5TestController.getActiveReference()?.testId)
        assertEquals(E5ControllerPhase.RUNNING, E5TestController.getPhase())
    }

    @Test
    fun screen_recreation_simulation_singleton_survives() {
        E5TestController.importReference(sampleA)
        // Simulate new Composable / new "reader" — same process singleton
        val readerPhase = E5TestController.getPhase()
        val readerRef = E5TestController.getActiveReference()
        assertEquals(E5ControllerPhase.REFERENCE_READY, readerPhase)
        assertNotNull(readerRef)
        assertEquals("sample-a", readerRef!!.testId)
    }

    @Test
    fun start_stop_evaluate_with_fake_ops() {
        E5TestController.importReference(sampleA)
        val ops = object : E5SessionOps {
            var started = false
            var stopped = false
            override fun startE5Session() { started = true }
            override fun stopE5Session() { stopped = true }
            override fun captureSnapshot() = E5DiagnosticsSnapshot(
                sessionActive = false,
                controlRecordCount = 0,
                pendingWindowCount = 0,
                audioObservationCount = 0,
                absoluteRenderFrame = 0L,
                controlOverflowCount = 0,
                windowOverflowCount = 0,
                audioObservationOverflowCount = 0,
                hasExportableData = false
            )
        }
        assertTrue(E5TestController.start(ops))
        assertEquals(E5ControllerPhase.RUNNING, E5TestController.getPhase())
        assertTrue(ops.started)
        val result = E5TestController.stop(ops)
        assertTrue(ops.stopped)
        assertNotNull(result)
        assertEquals(E5OverallStatus.PASS, result!!.overallStatus)
        assertEquals(E5ControllerPhase.RESULT_READY, E5TestController.getPhase())
        assertEquals(result, E5TestController.getLastResult())
    }
}
