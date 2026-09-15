package com.microtonal.synth

enum class E5OverallStatus {
    PASS,
    FAIL,
    INVALID_REFERENCE,
    ERROR
}

data class E5AssertionResult(
    val assertionId: String,
    val actual: Any?,
    val expected: Any?,
    val operator: String,
    val passed: Boolean,
    val error: String? = null
)

data class E5TestResult(
    val testId: String,
    val overallStatus: E5OverallStatus,
    val assertionResults: List<E5AssertionResult>
) {
    val passedCount: Int get() = assertionResults.count { it.passed }
    val failedCount: Int get() = assertionResults.count { !it.passed }

    fun summaryLine(): String {
        return when (overallStatus) {
            E5OverallStatus.PASS ->
                "PASS ($passedCount/${assertionResults.size})"
            E5OverallStatus.FAIL ->
                "FAIL ($failedCount failed, $passedCount passed)"
            E5OverallStatus.INVALID_REFERENCE ->
                "INVALID_REFERENCE"
            E5OverallStatus.ERROR ->
                "ERROR"
        }
    }
}
