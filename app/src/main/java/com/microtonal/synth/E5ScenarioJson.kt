package com.microtonal.synth

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parse / validate e5-scenario (+ optional e5-bundle) and convert to
 * [BenchmarkReferenceSession] for [BenchmarkReferencePlayer.playBlocking].
 */
object E5ScenarioJson {

    const val SCHEMA_ID = "e5-scenario"
    const val BUNDLE_SCHEMA_ID = "e5-bundle"
    val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1)

    const val MAX_JSON_BYTES = 262144
    const val MAX_STRING_LENGTH = 256
    const val MAX_SCENARIO_ID_LENGTH = 128
    const val MAX_TITLE_LENGTH = 256
    const val MAX_DESCRIPTION_LEN = 1024
    const val MAX_EVENTS = 4096
    const val MAX_NESTING_DEPTH = 12
    const val MIN_DURATION_US = 1L
    const val MAX_DURATION_US = 600_000_000L // 10 minutes

    sealed class ParseResult {
        data class Ok(val scenario: E5Scenario) : ParseResult()
        data class Invalid(val message: String) : ParseResult()
    }

    sealed class BundleParseResult {
        data class Ok(val scenario: E5Scenario, val reference: E5TestReference) : BundleParseResult()
        data class Invalid(val message: String) : BundleParseResult()
    }

    /** Top-level import detection for controller. */
    sealed class ImportParse {
        data class Scenario(val scenario: E5Scenario) : ImportParse()
        data class Reference(val reference: E5TestReference) : ImportParse()
        data class Bundle(val scenario: E5Scenario, val reference: E5TestReference) : ImportParse()
        data class Invalid(val message: String) : ImportParse()
    }

    fun parseImport(bytes: ByteArray): ImportParse {
        if (bytes.size > MAX_JSON_BYTES) {
            return ImportParse.Invalid(
                "JSON exceeds MAX_JSON_BYTES ($MAX_JSON_BYTES); got ${bytes.size}"
            )
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            return ImportParse.Invalid("UTF-8 decode failed: ${t.message}")
        }
        return parseImport(text)
    }

    fun parseImport(json: String): ImportParse {
        val byteLen = json.toByteArray(Charsets.UTF_8).size
        if (byteLen > MAX_JSON_BYTES) {
            return ImportParse.Invalid(
                "JSON exceeds MAX_JSON_BYTES ($MAX_JSON_BYTES); got $byteLen"
            )
        }
        val root: Any = try {
            JSONTokener(json).nextValue()
        } catch (t: Throwable) {
            return ImportParse.Invalid("Malformed JSON: ${t.message}")
        }
        if (root !is JSONObject) {
            return ImportParse.Invalid("Root must be a JSON object")
        }
        val schema = root.opt("schema")
        if (schema !is String) {
            return ImportParse.Invalid("schema must be a string")
        }
        return when (schema) {
            SCHEMA_ID -> when (val r = parseObject(root)) {
                is ParseResult.Ok -> ImportParse.Scenario(r.scenario)
                is ParseResult.Invalid -> ImportParse.Invalid(r.message)
            }
            E5TestReferenceJson.SCHEMA_ID -> when (val r = E5TestReferenceJson.parseObject(root)) {
                is E5TestReferenceJson.ParseResult.Ok -> ImportParse.Reference(r.reference)
                is E5TestReferenceJson.ParseResult.Invalid -> ImportParse.Invalid(r.message)
            }
            BUNDLE_SCHEMA_ID -> when (val r = parseBundleObject(root)) {
                is BundleParseResult.Ok -> ImportParse.Bundle(r.scenario, r.reference)
                is BundleParseResult.Invalid -> ImportParse.Invalid(r.message)
            }
            else -> ImportParse.Invalid(
                "schema must be \"$SCHEMA_ID\", \"${E5TestReferenceJson.SCHEMA_ID}\", or \"$BUNDLE_SCHEMA_ID\" (got \"$schema\")"
            )
        }
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
        val schemaVersion = parseFiniteInt(root.opt("schemaVersion"))
            ?: return ParseResult.Invalid("schemaVersion must be a finite integer")
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            return ParseResult.Invalid(
                "unsupported schemaVersion $schemaVersion; supported=$SUPPORTED_SCHEMA_VERSIONS"
            )
        }

        val scenarioId = root.opt("scenarioId")
        if (scenarioId !is String || scenarioId.isEmpty()) {
            return ParseResult.Invalid("scenarioId is required (non-empty string)")
        }
        if (scenarioId.length > MAX_SCENARIO_ID_LENGTH) {
            return ParseResult.Invalid(
                "scenarioId exceeds MAX_SCENARIO_ID_LENGTH ($MAX_SCENARIO_ID_LENGTH)"
            )
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

        val sampleRate: Int? = when {
            !root.has("sampleRate") || root.isNull("sampleRate") -> null
            else -> {
                val sr = parseFiniteInt(root.opt("sampleRate"))
                    ?: return ParseResult.Invalid("sampleRate must be a finite integer when present")
                if (sr <= 0) return ParseResult.Invalid("sampleRate must be > 0 when present")
                sr
            }
        }

        val durationUs = parseDurationUs(root)
            ?: return ParseResult.Invalid(
                "durationUs or durationMs is required (finite, in $MIN_DURATION_US..$MAX_DURATION_US us)"
            )

        val initialSettings = when {
            !root.has("initialSettings") || root.isNull("initialSettings") ->
                BenchmarkReferenceSettings.defaults()
            else -> {
                val s = root.opt("initialSettings")
                if (s !is JSONObject) {
                    return ParseResult.Invalid("initialSettings must be an object when present")
                }
                mergeSettings(BenchmarkReferenceSettings.defaults(), s)
            }
        }

        val eventsRaw = root.opt("events")
        if (eventsRaw == null || eventsRaw == JSONObject.NULL) {
            return ParseResult.Invalid("events array is required")
        }
        if (eventsRaw !is JSONArray) {
            return ParseResult.Invalid("events must be an array")
        }
        if (eventsRaw.length() > MAX_EVENTS) {
            return ParseResult.Invalid(
                "events exceed MAX_EVENTS ($MAX_EVENTS); got ${eventsRaw.length()}"
            )
        }

        var running = initialSettings
        val events = ArrayList<E5ScenarioEvent>(eventsRaw.length())
        for (i in 0 until eventsRaw.length()) {
            val item = eventsRaw.opt(i)
            if (item !is JSONObject) {
                return ParseResult.Invalid("events[$i] must be an object")
            }
            when (val parsed = parseEvent(item, i, running)) {
                is EventParse.Ok -> {
                    events.add(parsed.event)
                    running = parsed.runningSettings
                }
                is EventParse.Err -> return ParseResult.Invalid(parsed.message)
            }
        }
        events.sortBy { it.timeUs }

        return ParseResult.Ok(
            E5Scenario(
                schema = schema,
                schemaVersion = schemaVersion,
                scenarioId = scenarioId,
                title = title,
                description = description,
                sampleRate = sampleRate,
                durationUs = durationUs,
                initialSettings = initialSettings,
                events = events
            )
        )
    }

    fun parseBundle(bytes: ByteArray): BundleParseResult {
        if (bytes.size > MAX_JSON_BYTES) {
            return BundleParseResult.Invalid(
                "JSON exceeds MAX_JSON_BYTES ($MAX_JSON_BYTES); got ${bytes.size}"
            )
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            return BundleParseResult.Invalid("UTF-8 decode failed: ${t.message}")
        }
        val root: Any = try {
            JSONTokener(text).nextValue()
        } catch (t: Throwable) {
            return BundleParseResult.Invalid("Malformed JSON: ${t.message}")
        }
        if (root !is JSONObject) {
            return BundleParseResult.Invalid("Root must be a JSON object")
        }
        return parseBundleObject(root)
    }

    fun parseBundleObject(root: JSONObject): BundleParseResult {
        val depthErr = checkNestingDepth(root, 1)
        if (depthErr != null) return BundleParseResult.Invalid(depthErr)

        val schema = root.opt("schema")
        if (schema !is String || schema != BUNDLE_SCHEMA_ID) {
            return BundleParseResult.Invalid("schema must be \"$BUNDLE_SCHEMA_ID\"")
        }
        if (!root.has("schemaVersion")) {
            return BundleParseResult.Invalid("schemaVersion is required")
        }
        val schemaVersion = parseFiniteInt(root.opt("schemaVersion"))
            ?: return BundleParseResult.Invalid("schemaVersion must be a finite integer")
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            return BundleParseResult.Invalid("unsupported schemaVersion $schemaVersion")
        }

        val scenarioObj = root.opt("scenario")
        if (scenarioObj !is JSONObject) {
            return BundleParseResult.Invalid("bundle.scenario must be an object")
        }
        // Allow nested scenario without repeating schema, or with schema.
        val scenarioNormalized = ensureScenarioSchema(scenarioObj)
        val scenarioParsed = parseObject(scenarioNormalized)
        if (scenarioParsed is ParseResult.Invalid) {
            return BundleParseResult.Invalid("bundle.scenario: ${scenarioParsed.message}")
        }

        val referenceObj = root.opt("reference")
        if (referenceObj !is JSONObject) {
            return BundleParseResult.Invalid("bundle.reference must be an object")
        }
        val referenceNormalized = ensureReferenceSchema(referenceObj)
        val referenceParsed = E5TestReferenceJson.parseObject(referenceNormalized)
        if (referenceParsed is E5TestReferenceJson.ParseResult.Invalid) {
            return BundleParseResult.Invalid("bundle.reference: ${referenceParsed.message}")
        }

        return BundleParseResult.Ok(
            (scenarioParsed as ParseResult.Ok).scenario,
            (referenceParsed as E5TestReferenceJson.ParseResult.Ok).reference
        )
    }

    /**
     * Convert scenario → [BenchmarkReferenceSession] for Benchmark-style replay.
     * Empty loops/recordings/drums; STATE at t=0 for initialSettings; SETTINGS→STATE;
     * trailing STATE at durationUs when needed so playBlocking covers full duration.
     */
    fun toBenchmarkSession(scenario: E5Scenario, engineSampleRate: Int): BenchmarkReferenceSession {
        val sampleRate = scenario.sampleRate?.takeIf { it > 0 } ?: engineSampleRate
        var running = scenario.initialSettings
        val out = ArrayList<BenchmarkReferenceEvent>(scenario.events.size + 2)

        out += BenchmarkReferenceEvent(
            timeUs = 0L,
            type = BenchmarkReferenceEventType.STATE,
            settings = running
        )

        for (ev in scenario.events) {
            when (ev.type) {
                E5ScenarioEventType.NOTE_ON -> {
                    out += BenchmarkReferenceEvent(
                        timeUs = ev.timeUs,
                        type = BenchmarkReferenceEventType.NOTE_ON,
                        valueA = ev.freq ?: 0f,
                        settings = running
                    )
                }
                E5ScenarioEventType.NOTE_OFF -> {
                    out += BenchmarkReferenceEvent(
                        timeUs = ev.timeUs,
                        type = BenchmarkReferenceEventType.NOTE_OFF,
                        valueA = ev.freq ?: 0f,
                        settings = running
                    )
                }
                E5ScenarioEventType.SETTINGS -> {
                    if (ev.settings != null) {
                        running = ev.settings
                    }
                    out += BenchmarkReferenceEvent(
                        timeUs = ev.timeUs,
                        type = BenchmarkReferenceEventType.STATE,
                        settings = running
                    )
                }
            }
        }

        val lastUs = out.maxOfOrNull { it.timeUs } ?: 0L
        val durationUs = maxOf(scenario.durationUs, lastUs)
        if (durationUs > lastUs) {
            out += BenchmarkReferenceEvent(
                timeUs = durationUs,
                type = BenchmarkReferenceEventType.STATE,
                settings = running
            )
        }

        return BenchmarkReferenceSession(
            referenceId = scenario.scenarioId,
            sampleRate = sampleRate,
            durationUs = durationUs,
            events = out.sortedBy { it.timeUs },
            initialLoops = emptyList(),
            loops = emptyList(),
            recordings = emptyList(),
            drums = null
        )
    }

    // ---- internals ----

    private fun ensureScenarioSchema(obj: JSONObject): JSONObject {
        if (obj.optString("schema") == SCHEMA_ID) return obj
        val copy = JSONObject(obj.toString())
        copy.put("schema", SCHEMA_ID)
        if (!copy.has("schemaVersion")) copy.put("schemaVersion", 1)
        return copy
    }

    private fun ensureReferenceSchema(obj: JSONObject): JSONObject {
        if (obj.optString("schema") == E5TestReferenceJson.SCHEMA_ID) return obj
        val copy = JSONObject(obj.toString())
        copy.put("schema", E5TestReferenceJson.SCHEMA_ID)
        if (!copy.has("schemaVersion")) copy.put("schemaVersion", 1)
        return copy
    }

    private fun parseDurationUs(root: JSONObject): Long? {
        val hasUs = root.has("durationUs") && !root.isNull("durationUs")
        val hasMs = root.has("durationMs") && !root.isNull("durationMs")
        if (!hasUs && !hasMs) return null
        val us: Long = when {
            hasUs -> parseFiniteLong(root.opt("durationUs")) ?: return null
            else -> {
                val ms = parseFiniteLong(root.opt("durationMs")) ?: return null
                if (ms > MAX_DURATION_US / 1000L) return null
                ms * 1000L
            }
        }
        if (us < MIN_DURATION_US || us > MAX_DURATION_US) return null
        return us
    }

    private sealed class EventParse {
        data class Ok(val event: E5ScenarioEvent, val runningSettings: BenchmarkReferenceSettings) : EventParse()
        data class Err(val message: String) : EventParse()
    }

    private fun parseEvent(
        obj: JSONObject,
        index: Int,
        running: BenchmarkReferenceSettings
    ): EventParse {
        val timeUs = parseFiniteLong(obj.opt("timeUs"))
            ?: return EventParse.Err("events[$index].timeUs must be a finite integer >= 0")
        if (timeUs < 0L) {
            return EventParse.Err("events[$index].timeUs must be >= 0")
        }

        val typeRaw = obj.opt("type")
        if (typeRaw !is String) {
            return EventParse.Err("events[$index].type is required")
        }
        val type = when (typeRaw) {
            "NOTE_ON" -> E5ScenarioEventType.NOTE_ON
            "NOTE_OFF" -> E5ScenarioEventType.NOTE_OFF
            "SETTINGS" -> E5ScenarioEventType.SETTINGS
            else -> return EventParse.Err(
                "events[$index].type must be NOTE_ON|NOTE_OFF|SETTINGS (got \"$typeRaw\")"
            )
        }

        return when (type) {
            E5ScenarioEventType.NOTE_ON, E5ScenarioEventType.NOTE_OFF -> {
                val freq = parseFiniteFloat(obj.opt("freq"))
                    ?: return EventParse.Err("events[$index].freq must be a finite number > 0")
                if (freq <= 0f) {
                    return EventParse.Err("events[$index].freq must be > 0")
                }
                EventParse.Ok(
                    E5ScenarioEvent(timeUs = timeUs, type = type, freq = freq),
                    running
                )
            }
            E5ScenarioEventType.SETTINGS -> {
                val settingsObj = obj.opt("settings")
                if (settingsObj == null || settingsObj == JSONObject.NULL) {
                    return EventParse.Err("events[$index].settings object is required for SETTINGS")
                }
                if (settingsObj !is JSONObject) {
                    return EventParse.Err("events[$index].settings must be an object")
                }
                val merged = mergeSettings(running, settingsObj)
                EventParse.Ok(
                    E5ScenarioEvent(
                        timeUs = timeUs,
                        type = type,
                        settings = merged,
                        settingsPatch = jsonObjectToMap(settingsObj)
                    ),
                    merged
                )
            }
        }
    }

    fun mergeSettings(base: BenchmarkReferenceSettings, patch: JSONObject): BenchmarkReferenceSettings {
        val o = base.toJson()
        val keys = patch.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            o.put(k, patch.get(k))
        }
        return BenchmarkReferenceSettings.fromJson(o)
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.opt(k)
        }
        return map
    }

    private fun parseFiniteInt(raw: Any?): Int? {
        return when (raw) {
            is Int -> raw
            is Long -> if (raw in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) raw.toInt() else null
            is Double -> if (!raw.isFinite() || raw != raw.toInt().toDouble()) null else raw.toInt()
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

    private fun parseFiniteLong(raw: Any?): Long? {
        return when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is Double -> if (!raw.isFinite() || raw != raw.toLong().toDouble()) null else raw.toLong()
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

    private fun parseFiniteFloat(raw: Any?): Float? {
        return when (raw) {
            is Double -> if (raw.isFinite()) raw.toFloat() else null
            is Float -> if (raw.isFinite()) raw else null
            is Int -> raw.toFloat()
            is Long -> raw.toFloat()
            is Number -> {
                val d = raw.toDouble()
                if (d.isFinite()) d.toFloat() else null
            }
            else -> null
        }
    }

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
