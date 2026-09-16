package com.microtonal.synth

/**
 * Immutable E5 auto-scenario (stimulus / timeline).
 * Separate from [E5TestReference] (assertions only) and from E5Diagnostics (observe only).
 */
data class E5Scenario(
    val schema: String,
    val schemaVersion: Int,
    val scenarioId: String,
    val title: String,
    val description: String? = null,
    val sampleRate: Int? = null,
    val durationUs: Long,
    val initialSettings: BenchmarkReferenceSettings,
    val events: List<E5ScenarioEvent>
)

enum class E5ScenarioEventType {
    NOTE_ON,
    NOTE_OFF,
    SETTINGS
}

/**
 * Single timeline event. [freq] required for NOTE_ON / NOTE_OFF.
 * [settings] is a patch (or full settings) for SETTINGS; ignored for notes
 * (runtime conversion carries cumulative settings on every Benchmark event).
 */
data class E5ScenarioEvent(
    val timeUs: Long,
    val type: E5ScenarioEventType,
    val freq: Float? = null,
    val settings: BenchmarkReferenceSettings? = null,
    /** Raw patch keys for SETTINGS (optional; when null, [settings] is treated as full snapshot). */
    val settingsPatch: Map<String, Any?>? = null
)
