package com.microtonal.synth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class E5ScenarioControllerTest {

    private val scenarioJson = """
    {
      "schema": "e5-scenario",
      "schemaVersion": 1,
      "scenarioId": "ctrl-sc",
      "title": "Controller Scenario",
      "durationUs": 50000,
      "events": [
        {"timeUs": 0, "type": "NOTE_ON", "freq": 440.0},
        {"timeUs": 10000, "type": "NOTE_OFF", "freq": 440.0}
      ]
    }
    """.trimIndent()

    private val referenceJson = """
    {
      "schema": "e5-test-reference",
      "schemaVersion": 1,
      "testId": "ctrl-ref",
      "title": "Controller Ref",
      "assertions": [
        {"id":"a1","path":"e5.sessionActive","operator":"equals","expected":false}
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
    fun import_scenario_sets_ready_keeps_prior_reference() {
        E5TestController.importBytes(referenceJson)
        assertEquals("ctrl-ref", E5TestController.getActiveReference()?.testId)
        val r = E5TestController.importBytes(scenarioJson)
        assertTrue(r is E5ImportResult.Success)
        assertEquals("ctrl-sc", E5TestController.getActiveScenario()?.scenarioId)
        assertEquals("ctrl-ref", E5TestController.getActiveReference()?.testId)
        assertEquals(E5ControllerPhase.REFERENCE_READY, E5TestController.getPhase())
        assertTrue(E5TestController.canStart())
    }

    @Test
    fun import_bundle_sets_both_atomically() {
        val bundle = """
        {
          "schema":"e5-bundle","schemaVersion":1,
          "scenario":{
            "scenarioId":"b1","title":"B","durationUs":1000,
            "events":[{"timeUs":0,"type":"NOTE_ON","freq":100.0}]
          },
          "reference":{
            "testId":"r1","title":"R",
            "assertions":[{"id":"x","path":"e5.hasExportableData","operator":"equals","expected":false}]
          }
        }
        """.trimIndent()
        val r = E5TestController.importBytes(bundle)
        assertTrue(r is E5ImportResult.Success)
        assertEquals("b1", E5TestController.getActiveScenario()?.scenarioId)
        assertEquals("r1", E5TestController.getActiveReference()?.testId)
    }

    @Test
    fun invalid_leaves_both_unchanged() {
        E5TestController.importBytes(scenarioJson)
        E5TestController.importBytes(referenceJson)
        val rejected = E5TestController.importBytes("""{"schema":"e5-scenario","schemaVersion":99}""")
        assertTrue(rejected is E5ImportResult.Rejected)
        assertEquals("ctrl-sc", E5TestController.getActiveScenario()?.scenarioId)
        assertEquals("ctrl-ref", E5TestController.getActiveReference()?.testId)
    }

    @Test
    fun running_blocks_import() {
        E5TestController.importBytes(scenarioJson)
        E5TestController.forceRunningForTests()
        assertFalse(E5TestController.canImport())
        val rejected = E5TestController.importBytes(referenceJson)
        assertTrue(rejected is E5ImportResult.Rejected)
    }

    @Test
    fun scenario_start_replays_then_auto_finalize() {
        E5TestController.importBytes(scenarioJson)
        val started = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val played = AtomicBoolean(false)
        val playedSession = AtomicReference<BenchmarkReferenceSession?>(null)
        val done = CountDownLatch(1)

        val ops = object : E5SessionOps {
            override fun startE5Session() { started.set(true) }
            override fun stopE5Session() {
                stopped.set(true)
                done.countDown()
            }
            override fun captureSnapshot() = E5DiagnosticsSnapshot(
                sessionActive = false,
                controlRecordCount = 1,
                pendingWindowCount = 0,
                audioObservationCount = 1,
                absoluteRenderFrame = 100L,
                controlOverflowCount = 0,
                windowOverflowCount = 0,
                audioObservationOverflowCount = 0,
                hasExportableData = true
            )
        }
        val player = object : E5ScenarioPlayerOps {
            override fun playBlocking(session: BenchmarkReferenceSession) {
                played.set(true)
                playedSession.set(session)
                // return immediately (no wall-clock wait in unit test)
            }
            override fun cancel() {}
        }

        assertTrue(E5TestController.start(ops, player, engineSampleRate = 48000))
        assertTrue(started.get())
        assertTrue(done.await(3, TimeUnit.SECONDS))
        // allow finalize to publish
        Thread.sleep(50)
        assertTrue(played.get())
        assertTrue(stopped.get())
        assertEquals(E5ControllerPhase.RESULT_READY, E5TestController.getPhase())
        val result = E5TestController.getLastResult()
        assertNotNull(result)
        // no reference → empty PASS
        assertEquals(E5OverallStatus.PASS, result!!.overallStatus)
        assertEquals("ctrl-sc", result.testId)
        val session = playedSession.get()
        assertNotNull(session)
        assertTrue(session!!.events.any { it.type == BenchmarkReferenceEventType.NOTE_ON })
    }

    @Test
    fun reference_only_start_is_legacy_manual_no_player() {
        E5TestController.importBytes(referenceJson)
        val ops = object : E5SessionOps {
            override fun startE5Session() {}
            override fun stopE5Session() {}
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
        // player null is ok for reference-only
        assertTrue(E5TestController.start(ops, player = null))
        assertEquals(E5ControllerPhase.RUNNING, E5TestController.getPhase())
        val result = E5TestController.stop(ops, player = null)
        assertNotNull(result)
        assertEquals(E5OverallStatus.PASS, result!!.overallStatus)
    }

    @Test
    fun canStart_with_scenario_only() {
        E5TestController.importBytes(scenarioJson)
        assertNull(E5TestController.getActiveReference())
        assertNotNull(E5TestController.getActiveScenario())
        assertTrue(E5TestController.canStart())
    }
}
