package com.microtonal.synth

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parse + validate E5 test reference JSON only.
 * No SynthEngine / AudioThread / diagnostics coupling.
 */
object E5TestReferenceJson {

    const val SCHEMA_ID = "e5-test-reference"
    val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1)

    const val MAX_JSON_BYTES = 262144
    const val MAX_STRING_LENGTH = 256
    const val MAX_ASSERTIONS = 64
    const val MAX_TEST_ID_LENGTH = 128
    const val MAX_TITLE_LENGTH = 256
    const val MAX_DESCRIPTION_LEN = 1024
    const val MAX_NESTING_DEPTH = 8

    const val MIN_DURATION_MS = 1L
    const val MAX_DURATION_MS = 600_000L

    /** Exact path identifiers permitted in assertions (map to E5Diagnostics public APIs). */
    val SUPPORTED_ASSERTION_PATHS: Set<String> = setOf(
        "e5.sessionActive",
        "e5.controlRecordCount",
        "e5.pendingWindowCount",
        "e5.audioObservationCount",
        "e5.absoluteRenderFrame",
        "e5.controlOverflowCount",
        "e5.windowOverflowCount",
        "e5.audioObservationOverflowCount",
        "e5.hasExportableData"
    )

    sealed class ParseResult {
        data class Ok(val reference: E5TestReference) : ParseResult()
        data class Invalid(val message: String) : ParseResult()
    }

    fun parse(bytes: ByteArray): ParseResult {
        if (bytes.size > MAX_JSON_BYTES) {
            return ParseResult.Invalid(
                "JSON exceeds MAX_JSON_BYTES ($MAX_JSON_BYTES); got ${bytes.size}"
            )
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            return ParseResult.Invalid("UTF-8 decode failed: ${t.message}")
        }
        return parse(text)
    }

    fun parse(json: String): ParseResult {
        val byteLen = json.toByteArray(Charsets.UTF_8).size
        if (byteLen > MAX_JSON_BYTES) {
            return ParseResult.Invalid(
                "JSON exceeds MAX_JSON_BYTES ($MAX_JSON_BYTES); got $byteLen"
            )
        }
        val root: Any = try {
            JSONTokener(json).nextValue()
        } catch (t: Throwable) {
            return ParseResult.Invalid("Malformed JSON: ${t.message}")
        }
        if (root !is JSONObject) {
            return ParseResult.Invalid("Root must be a JSON object")
        }
        return parseObject(root)
    }

    fun parseObject(root: JSONObject): ParseResult {
        val depthErr = checkNestingDepth(root, 1)
        if (depthErr != null) return ParseResult.Invalid(depthErr)

        val schema = root.opt("schema")
        if (schema !is String) {
            return ParseResult.Invalid("schema must be string \"$SCHEMA_ID\"")
        }
        if (schema.length > MAX_STRING_LENGTH) {
            return ParseResult.Invalid("schema exceeds MAX_STRING_LENGTH")
        }
        if (schema != SCHEMA_ID) {
            return ParseResult.Invalid("schema must be \"$SCHEMA_ID\" (got \"$schema\")")
        }

        if (!root.has("schemaVersion")) {
            return ParseResult.Invalid("schemaVersion is required")
        }
        val schemaVersion = parseFiniteInt(root.opt("schemaVersion"), "schemaVersion")
            ?: return ParseResult.Invalid("schemaVersion must be a finite integer")
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            return ParseResult.Invalid(
                "unsupported schemaVersion $schemaVersion; supported=$SUPPORTED_SCHEMA_VERSIONS"
            )
        }

        val testId = root.opt("testId")
        if (testId !is String || testId.isEmpty()) {
            return ParseResult.Invalid("testId is required (non-empty string)")
        }
        if (testId.length > MAX_TEST_ID_LENGTH) {
            return ParseResult.Invalid("testId exceeds MAX_TEST_ID_LENGTH ($MAX_TEST_ID_LENGTH)")
        }

        val title = root.opt("title")
        if (title !is String || title.isEmpty()) {
            return ParseResult.Invalid("title is required (non-empty string)")
        }
        if (title.length > MAX_TITLE_LENGTH) {
            return ParseResult.Invalid("title exceeds MAX_TITLE_LENGTH ($MAX_TITLE_LENGTH)")
        }

        val description: String? = when (val d = root.opt("description")) {
            null, JSONObject.NULL -> null
            is String -> {
                if (d.length > MAX_DESCRIPTION_LEN) {
                    return ParseResult.Invalid(
                        "description exceeds MAX_DESCRIPTION_LEN ($MAX_DESCRIPTION_LEN)"
                    )
                }
                d
            }
            else -> return ParseResult.Invalid("description must be a string when present")
        }

        val session: E5SessionSpec? = when {
            !root.has("session") || root.isNull("session") -> null
            else -> {
                val s = root.opt("session")
                if (s !is JSONObject) {
                    return ParseResult.Invalid("session must be an object when present")
                }
                parseSession(s) ?: return ParseResult.Invalid(
                    "session.durationMs must be finite integer in $MIN_DURATION_MS..$MAX_DURATION_MS when present"
                )
            }
        }

        val assertionsRaw = root.opt("assertions")
        if (assertionsRaw == null || assertionsRaw == JSONObject.NULL) {
            // empty assertions allowed? Plan typically requires list; allow empty list as valid reference
            return ParseResult.Ok(
                E5TestReference(
                    schema = schema,
                    schemaVersion = schemaVersion,
                    testId = testId,
                    title = title,
                    description = description,
                    session = session,
                    assertions = emptyList()
                )
            )
        }
        if (assertionsRaw !is JSONArray) {
            return ParseResult.Invalid("assertions must be an array")
        }
        if (assertionsRaw.length() > MAX_ASSERTIONS) {
            return ParseResult.Invalid(
                "assertions exceed MAX_ASSERTIONS ($MAX_ASSERTIONS); got ${assertionsRaw.length()}"
            )
        }

        val seenIds = HashSet<String>()
        val assertions = ArrayList<E5Assertion>(assertionsRaw.length())
        for (i in 0 until assertionsRaw.length()) {
            val item = assertionsRaw.opt(i)
            if (item !is JSONObject) {
                return ParseResult.Invalid("assertions[$i] must be an object")
            }
            when (val a = parseAssertion(item, i, seenIds)) {
                is AssertionParse.Ok -> assertions.add(a.assertion)
                is AssertionParse.Err -> return ParseResult.Invalid(a.message)
            }
        }

        return ParseResult.Ok(
            E5TestReference(
                schema = schema,
                schemaVersion = schemaVersion,
                testId = testId,
                title = title,
                description = description,
                session = session,
                assertions = assertions
            )
        )
    }

    private sealed class AssertionParse {
        data class Ok(val assertion: E5Assertion) : AssertionParse()
        data class Err(val message: String) : AssertionParse()
    }

    private fun parseSession(obj: JSONObject): E5SessionSpec? {
        if (!obj.has("durationMs") || obj.isNull("durationMs")) {
            return E5SessionSpec(durationMs = null)
        }
        val raw = obj.opt("durationMs")
        val ms = parseFiniteLong(raw, "session.durationMs") ?: return null
        if (ms < MIN_DURATION_MS || ms > MAX_DURATION_MS) return null
        // Reject unexpected keys? Keep permissive for forward-compat except durationMs rules.
        return E5SessionSpec(durationMs = ms)
    }

    private fun parseAssertion(
        obj: JSONObject,
        index: Int,
        seenIds: MutableSet<String>
    ): AssertionParse {
        val id = obj.opt("id")
        if (id !is String || id.isEmpty()) {
            return AssertionParse.Err("assertions[$index].id is required (non-empty string)")
        }
        if (id.length > MAX_STRING_LENGTH) {
            return AssertionParse.Err("assertions[$index].id exceeds MAX_STRING_LENGTH")
        }
        if (!seenIds.add(id)) {
            return AssertionParse.Err("duplicate assertion id \"$id\"")
        }

        val path = obj.opt("path")
        if (path !is String || path.isEmpty()) {
            return AssertionParse.Err("assertions[$index].path is required")
        }
        if (path.length > MAX_STRING_LENGTH) {
            return AssertionParse.Err("assertions[$index].path exceeds MAX_STRING_LENGTH")
        }
        if (path !in SUPPORTED_ASSERTION_PATHS) {
            return AssertionParse.Err(
                "INVALID_REFERENCE: unsupported assertion path \"$path\""
            )
        }

        val opRaw = obj.opt("operator")
        if (opRaw !is String) {
            return AssertionParse.Err("assertions[$index].operator is required")
        }
        if (opRaw.length > MAX_STRING_LENGTH) {
            return AssertionParse.Err("assertions[$index].operator exceeds MAX_STRING_LENGTH")
        }
        val operator = E5AssertionOperator.fromJson(opRaw)
            ?: return AssertionParse.Err(
                "assertions[$index].operator must be one of equals|notEquals|greaterThan|greaterThanOrEqual|lessThan|lessThanOrEqual"
            )

        if (!obj.has("expected") || obj.isNull("expected")) {
            return AssertionParse.Err("assertions[$index].expected is required")
        }
        val expectedParsed = parseExpected(obj.opt("expected"), index)
            ?: return AssertionParse.Err(
                "assertions[$index].expected must be Boolean|String|Int|Long|Double (no object/array); NaN/Inf rejected"
            )
        if (expectedParsed is String && expectedParsed.length > MAX_STRING_LENGTH) {
            return AssertionParse.Err("assertions[$index].expected string exceeds MAX_STRING_LENGTH")
        }

        val hasTolerance = obj.has("tolerance") && !obj.isNull("tolerance")
        val tolerance: Double = if (hasTolerance) {
            val t = parseFiniteDouble(obj.opt("tolerance"), "tolerance")
                ?: return AssertionParse.Err("assertions[$index].tolerance must be finite number >= 0")
            if (t < 0.0) {
                return AssertionParse.Err("assertions[$index].tolerance must be >= 0")
            }
            when (expectedParsed) {
                is Boolean, is String ->
                    return AssertionParse.Err(
                        "assertions[$index].tolerance not allowed on String/Boolean expected"
                    )
                else -> t
            }
        } else {
            when (expectedParsed) {
                is Number -> 0.0
                else -> 0.0
            }
        }

        return AssertionParse.Ok(
            E5Assertion(
                id = id,
                path = path,
                operator = operator,
                expected = expectedParsed,
                tolerance = tolerance
            )
        )
    }

    private fun parseExpected(raw: Any?, @Suppress("UNUSED_PARAMETER") index: Int): Any? {
        return when (raw) {
            null, JSONObject.NULL -> null
            is Boolean -> raw
            is String -> raw
            is Int -> raw
            is Long -> raw
            is Double -> {
                if (!raw.isFinite()) null else raw
            }
            is Float -> {
                val d = raw.toDouble()
                if (!d.isFinite()) null else d
            }
            is Number -> {
                // org.json may yield Integer/Long/Double
                val d = raw.toDouble()
                if (!d.isFinite()) return null
                val asLong = d.toLong()
                if (d == asLong.toDouble()) {
                    if (asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                        asLong.toInt()
                    } else {
                        asLong
                    }
                } else {
                    d
                }
            }
            is JSONObject, is JSONArray -> null
            else -> null
        }
    }

    private fun parseFiniteInt(raw: Any?, label: String): Int? {
        return when (raw) {
            is Int -> raw
            is Long -> {
                if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw.toInt() else null
            }
            is Double -> {
                if (!raw.isFinite() || raw != raw.toInt().toDouble()) null else raw.toInt()
            }
            is Float -> {
                val d = raw.toDouble()
                if (!d.isFinite() || d != d.toInt().toDouble()) null else d.toInt()
            }
            is Number -> {
                val d = raw.toDouble()
                if (!d.isFinite() || d != d.toInt().toDouble()) null else d.toInt()
            }
            else -> null
        }
    }

    private fun parseFiniteLong(raw: Any?, label: String): Long? {
        return when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is Double -> {
                if (!raw.isFinite() || raw != raw.toLong().toDouble()) null else raw.toLong()
            }
            is Float -> {
                val d = raw.toDouble()
                if (!d.isFinite() || d != d.toLong().toDouble()) null else d.toLong()
            }
            is Number -> {
                val d = raw.toDouble()
                if (!d.isFinite() || d != d.toLong().toDouble()) null else d.toLong()
            }
            else -> null
        }
    }

    private fun parseFiniteDouble(raw: Any?, label: String): Double? {
        return when (raw) {
            is Double -> if (raw.isFinite()) raw else null
            is Float -> {
                val d = raw.toDouble()
                if (d.isFinite()) d else null
            }
            is Int -> raw.toDouble()
            is Long -> raw.toDouble()
            is Number -> {
                val d = raw.toDouble()
                if (d.isFinite()) d else null
            }
            else -> null
        }
    }

    /** Depth 1 = root object. Reject if any node exceeds MAX_NESTING_DEPTH. */
    private fun checkNestingDepth(node: Any?, depth: Int): String? {
        if (depth > MAX_NESTING_DEPTH) {
            return "JSON nesting exceeds MAX_NESTING_DEPTH ($MAX_NESTING_DEPTH)"
        }
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val err = checkNestingDepth(node.opt(k), depth + 1)
                    if (err != null) return err
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val err = checkNestingDepth(node.opt(i), depth + 1)
                    if (err != null) return err
                }
            }
        }
        return null
    }
}
