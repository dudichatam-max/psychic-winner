package com.microtonal.synth

/**
 * Immutable declarative E5 test reference (AI-generated JSON → Import → Validate).
 * [session.durationMs] is metadata only — existing E5 lifecycle has no duration timer.
 */
data class E5TestReference(
    val schema: String,
    val schemaVersion: Int,
    val testId: String,
    val title: String,
    val description: String? = null,
    val session: E5SessionSpec? = null,
    val assertions: List<E5Assertion>
)

/**
 * Optional session metadata. [durationMs] is declarative only (1..600000 when present);
 * it MUST NOT drive any timer or auto-STOP.
 */
data class E5SessionSpec(
    val durationMs: Long? = null
)

enum class E5AssertionOperator(val json: String) {
    EQUALS("equals"),
    NOT_EQUALS("notEquals"),
    GREATER_THAN("greaterThan"),
    GREATER_THAN_OR_EQUAL("greaterThanOrEqual"),
    LESS_THAN("lessThan"),
    LESS_THAN_OR_EQUAL("lessThanOrEqual");

    companion object {
        fun fromJson(value: String): E5AssertionOperator? =
            entries.firstOrNull { it.json == value }
    }
}

/**
 * Single assertion. [expected] is Boolean | String | Int | Long | Double only.
 * [tolerance] applies only to numeric equals (default 0.0).
 */
data class E5Assertion(
    val id: String,
    val path: String,
    val operator: E5AssertionOperator,
    val expected: Any,
    val tolerance: Double = 0.0
)
