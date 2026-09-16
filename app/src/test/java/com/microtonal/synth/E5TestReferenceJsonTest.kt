package com.microtonal.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Parser coverage aligned to plan §41 valid + invalid cases.
 */
class E5TestReferenceJsonTest {

    private fun validMinimal(
        extraAssertions: String = "",
        session: String = "",
        description: String? = null
    ): String {
        val desc = if (description != null) """, "description": ${org.json.JSONObject.quote(description)}""" else ""
        val sess = if (session.isNotEmpty()) """, "session": $session""" else ""
        val assertions = if (extraAssertions.isNotEmpty()) extraAssertions else """
            {
              "id": "a1",
              "path": "e5.sessionActive",
              "operator": "equals",
              "expected": false
            }
        """.trimIndent()
        return """
        {
          "schema": "e5-test-reference",
          "schemaVersion": 1,
          "testId": "sample-a",
          "title": "Sample A"$desc$sess,
          "assertions": [ $assertions ]
        }
        """.trimIndent()
    }

    @Test
    fun valid_minimal_parses() {
        val r = E5TestReferenceJson.parse(validMinimal())
        assertTrue(r is E5TestReferenceJson.ParseResult.Ok)
        val ref = (r as E5TestReferenceJson.ParseResult.Ok).reference
        assertEquals("sample-a", ref.testId)
        assertEquals("Sample A", ref.title)
        assertEquals(1, ref.assertions.size)
        assertEquals("e5.sessionActive", ref.assertions[0].path)
        assertNull(ref.session)
    }

    @Test
    fun valid_with_durationMs_metadata() {
        val r = E5TestReferenceJson.parse(
            validMinimal(session = """{"durationMs": 5000}""")
        )
        assertTrue(r is E5TestReferenceJson.ParseResult.Ok)
        val ref = (r as E5TestReferenceJson.ParseResult.Ok).reference
        assertEquals(5000L, ref.session?.durationMs)
    }

    @Test
    fun valid_numeric_tolerance_default_zero() {
        val json = validMinimal(
            extraAssertions = """
            {
              "id": "frame",
              "path": "e5.absoluteRenderFrame",
              "operator": "equals",
              "expected": 100
            }
            """.trimIndent()
        )
        val r = E5TestReferenceJson.parse(json) as E5TestReferenceJson.ParseResult.Ok
        assertEquals(0.0, r.reference.assertions[0].tolerance, 0.0)
    }

    @Test
    fun invalid_wrong_schema() {
        val json = validMinimal().replace("e5-test-reference", "other")
        val r = E5TestReferenceJson.parse(json)
        assertTrue(r is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_missing_schemaVersion() {
        val json = """
        {"schema":"e5-test-reference","testId":"t","title":"T","assertions":[]}
        """.trimIndent()
        val r = E5TestReferenceJson.parse(json)
        assertTrue(r is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_unsupported_schemaVersion() {
        val json = validMinimal().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val r = E5TestReferenceJson.parse(json)
        assertTrue(r is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_missing_testId() {
        val json = """
        {"schema":"e5-test-reference","schemaVersion":1,"title":"T","assertions":[]}
        """.trimIndent()
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_missing_title() {
        val json = """
        {"schema":"e5-test-reference","schemaVersion":1,"testId":"t","assertions":[]}
        """.trimIndent()
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_unsupported_path() {
        val json = validMinimal(
            extraAssertions = """
            {"id":"x","path":"e5.notARealPath","operator":"equals","expected":1}
            """.trimIndent()
        )
        val r = E5TestReferenceJson.parse(json)
        assertTrue(r is E5TestReferenceJson.ParseResult.Invalid)
        assertTrue((r as E5TestReferenceJson.ParseResult.Invalid).message.contains("path"))
    }

    @Test
    fun invalid_bad_operator() {
        val json = validMinimal(
            extraAssertions = """
            {"id":"x","path":"e5.sessionActive","operator":"approxEquals","expected":false}
            """.trimIndent()
        )
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_duplicate_assertion_ids() {
        val json = validMinimal(
            extraAssertions = """
            {"id":"dup","path":"e5.sessionActive","operator":"equals","expected":false},
            {"id":"dup","path":"e5.hasExportableData","operator":"equals","expected":false}
            """.trimIndent()
        )
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_expected_object() {
        val json = validMinimal(
            extraAssertions = """
            {"id":"x","path":"e5.controlRecordCount","operator":"equals","expected":{"n":1}}
            """.trimIndent()
        )
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_tolerance_on_boolean() {
        val json = validMinimal(
            extraAssertions = """
            {"id":"x","path":"e5.sessionActive","operator":"equals","expected":false,"tolerance":0.1}
            """.trimIndent()
        )
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_tolerance_negative() {
        val json = validMinimal(
            extraAssertions = """
            {"id":"x","path":"e5.absoluteRenderFrame","operator":"equals","expected":1,"tolerance":-1}
            """.trimIndent()
        )
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_durationMs_out_of_range() {
        val r = E5TestReferenceJson.parse(validMinimal(session = """{"durationMs": 0}"""))
        assertTrue(r is E5TestReferenceJson.ParseResult.Invalid)
        val r2 = E5TestReferenceJson.parse(validMinimal(session = """{"durationMs": 600001}"""))
        assertTrue(r2 is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_oversized_json_bytes() {
        val big = "x".repeat(E5TestReferenceJson.MAX_JSON_BYTES + 1)
        val r = E5TestReferenceJson.parse(big.toByteArray(Charsets.UTF_8))
        assertTrue(r is E5TestReferenceJson.ParseResult.Invalid)
        assertTrue((r as E5TestReferenceJson.ParseResult.Invalid).message.contains("MAX_JSON_BYTES"))
    }

    @Test
    fun invalid_too_many_assertions() {
        val items = (1..65).joinToString(",") { i ->
            """{"id":"a$i","path":"e5.sessionActive","operator":"equals","expected":false}"""
        }
        val json = """
        {"schema":"e5-test-reference","schemaVersion":1,"testId":"t","title":"T","assertions":[$items]}
        """.trimIndent()
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun invalid_testId_too_long() {
        val longId = "t".repeat(E5TestReferenceJson.MAX_TEST_ID_LENGTH + 1)
        val json = """
        {"schema":"e5-test-reference","schemaVersion":1,"testId":"$longId","title":"T","assertions":[]}
        """.trimIndent()
        assertTrue(E5TestReferenceJson.parse(json) is E5TestReferenceJson.ParseResult.Invalid)
    }

    @Test
    fun all_supported_paths_accepted() {
        val items = E5TestReferenceJson.SUPPORTED_ASSERTION_PATHS.mapIndexed { i, path ->
            val expected = when {
                path.contains("Active") || path.contains("Exportable") -> "false"
                else -> "0"
            }
            """{"id":"p$i","path":"$path","operator":"equals","expected":$expected}"""
        }.joinToString(",")
        val json = """
        {"schema":"e5-test-reference","schemaVersion":1,"testId":"all-paths","title":"All","assertions":[$items]}
        """.trimIndent()
        val r = E5TestReferenceJson.parse(json)
        assertTrue(r is E5TestReferenceJson.ParseResult.Ok)
        assertEquals(
            E5TestReferenceJson.SUPPORTED_ASSERTION_PATHS.size,
            (r as E5TestReferenceJson.ParseResult.Ok).reference.assertions.size
        )
    }
}
