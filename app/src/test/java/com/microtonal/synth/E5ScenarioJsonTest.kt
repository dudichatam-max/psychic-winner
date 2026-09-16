package com.microtonal.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class E5ScenarioJsonTest {

    private val minimalScenario = """
    {
      "schema": "e5-scenario",
      "schemaVersion": 1,
      "scenarioId": "sc-min",
      "title": "Minimal",
      "durationMs": 2000,
      "initialSettings": { "waveform": 0, "cutoff": 1000.0 },
      "events": [
        { "timeUs": 100000, "type": "NOTE_ON", "freq": 440.0 },
        { "timeUs": 500000, "type": "SETTINGS", "settings": { "cutoff": 2000.0 } },
        { "timeUs": 1500000, "type": "NOTE_OFF", "freq": 440.0 }
      ]
    }
    """.trimIndent()

    @Test
    fun parse_minimal_ok() {
        val r = E5ScenarioJson.parse(minimalScenario)
        assertTrue(r is E5ScenarioJson.ParseResult.Ok)
        val s = (r as E5ScenarioJson.ParseResult.Ok).scenario
        assertEquals("sc-min", s.scenarioId)
        assertEquals(2_000_000L, s.durationUs)
        assertEquals(3, s.events.size)
        assertEquals(0, s.initialSettings.waveform)
        assertEquals(1000f, s.initialSettings.cutoff, 0.01f)
    }

    @Test
    fun toBenchmarkSession_maps_types_and_cumulative_settings() {
        val s = (E5ScenarioJson.parse(minimalScenario) as E5ScenarioJson.ParseResult.Ok).scenario
        val session = E5ScenarioJson.toBenchmarkSession(s, engineSampleRate = 48000)
        assertEquals("sc-min", session.referenceId)
        assertEquals(48000, session.sampleRate)
        assertTrue(session.initialLoops.isEmpty())
        assertTrue(session.loops.isEmpty())
        assertTrue(session.recordings.isEmpty())
        assertNull(session.drums)
        // STATE@0 + NOTE_ON + STATE(SETTINGS) + NOTE_OFF + trailing STATE@duration
        assertTrue(session.events.size >= 4)
        assertEquals(BenchmarkReferenceEventType.STATE, session.events[0].type)
        assertEquals(0L, session.events[0].timeUs)
        assertEquals(1000f, session.events[0].settings.cutoff, 0.01f)

        val noteOn = session.events.first { it.type == BenchmarkReferenceEventType.NOTE_ON }
        assertEquals(440f, noteOn.valueA, 0.01f)
        assertEquals(1000f, noteOn.settings.cutoff, 0.01f)

        val stateAfter = session.events.first {
            it.type == BenchmarkReferenceEventType.STATE && it.timeUs == 500_000L
        }
        assertEquals(2000f, stateAfter.settings.cutoff, 0.01f)

        val noteOff = session.events.first { it.type == BenchmarkReferenceEventType.NOTE_OFF }
        assertEquals(2000f, noteOff.settings.cutoff, 0.01f) // cumulative

        assertEquals(2_000_000L, session.durationUs)
        assertEquals(2_000_000L, session.events.last().timeUs)
    }

    @Test
    fun reject_bad_schema_and_freq() {
        val badSchema = minimalScenario.replace("e5-scenario", "nope")
        assertTrue(E5ScenarioJson.parse(badSchema) is E5ScenarioJson.ParseResult.Invalid)

        val badFreq = """
        {
          "schema":"e5-scenario","schemaVersion":1,"scenarioId":"x","title":"x",
          "durationUs":1000,
          "events":[{"timeUs":0,"type":"NOTE_ON","freq":0}]
        }
        """.trimIndent()
        assertTrue(E5ScenarioJson.parse(badFreq) is E5ScenarioJson.ParseResult.Invalid)
    }

    @Test
    fun parse_bundle_atomic() {
        val bundle = """
        {
          "schema": "e5-bundle",
          "schemaVersion": 1,
          "scenario": {
            "scenarioId": "b-sc",
            "title": "Bundle Scenario",
            "durationUs": 1000000,
            "events": [
              {"timeUs": 0, "type": "NOTE_ON", "freq": 220.0},
              {"timeUs": 500000, "type": "NOTE_OFF", "freq": 220.0}
            ]
          },
          "reference": {
            "testId": "b-ref",
            "title": "Bundle Ref",
            "assertions": [
              {"id":"c1","path":"e5.controlRecordCount","operator":"greaterThanOrEqual","expected":1}
            ]
          }
        }
        """.trimIndent()
        val r = E5ScenarioJson.parseBundle(bundle.toByteArray(Charsets.UTF_8))
        assertTrue(r is E5ScenarioJson.BundleParseResult.Ok)
        val ok = r as E5ScenarioJson.BundleParseResult.Ok
        assertEquals("b-sc", ok.scenario.scenarioId)
        assertEquals("b-ref", ok.reference.testId)
    }

    @Test
    fun parseImport_detects_three_schemas() {
        assertTrue(
            E5ScenarioJson.parseImport(minimalScenario) is E5ScenarioJson.ImportParse.Scenario
        )
        val ref = """
        {"schema":"e5-test-reference","schemaVersion":1,"testId":"t","title":"T","assertions":[]}
        """.trimIndent()
        assertTrue(E5ScenarioJson.parseImport(ref) is E5ScenarioJson.ImportParse.Reference)
    }

    @Test
    fun settings_merge_preserves_unpatched_fields() {
        val base = BenchmarkReferenceSettings.defaults()
        val patch = org.json.JSONObject().put("detune", true).put("drive", 1.0)
        val merged = E5ScenarioJson.mergeSettings(base, patch)
        assertTrue(merged.detune)
        assertEquals(1.0f, merged.drive, 0.01f)
        assertEquals(base.waveform, merged.waveform)
        assertEquals(base.cutoff, merged.cutoff, 0.01f)
    }
}
