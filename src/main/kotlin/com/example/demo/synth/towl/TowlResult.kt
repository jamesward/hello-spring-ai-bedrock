package com.example.demo.synth.towl

/**
 * The execution envelope (TOWL spec §13.4): the value plus per-binding accounting, so a consumer —
 * human or LLM — can see where records appeared, were filtered, fanned out, or failed, instead of
 * guessing from a bare value.
 */
class ExecutionResult(
    val status: String, // "ok" | "partial" — partial when any element failed or was skipped
    val result: Any?,
    val diagnostics: List<Map<String, Any?>>,
    val warnings: List<String>,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "status" to status,
        "diagnostics" to diagnostics,
        "warnings" to warnings,
        "result" to result,
    )
}

/** Thread-safe per-binding metric collector; fan-out bodies record concurrently. */
internal class Recorder {
    private val entries = LinkedHashMap<String, LinkedHashMap<String, Any?>>()

    @Synchronized fun set(path: String, metric: String, value: Any?) { row(path)[metric] = value }

    @Synchronized fun bump(path: String, metric: String, delta: Long = 1L) {
        val r = row(path)
        r[metric] = ((r[metric] as? Long) ?: 0L) + delta
    }

    private fun row(path: String) = entries.getOrPut(path) { linkedMapOf() }

    @Synchronized fun snapshot(): List<Map<String, Any?>> =
        entries.map { (p, m) -> linkedMapOf<String, Any?>("binding" to p).apply { putAll(m) } }

    @Synchronized fun anyPartial(): Boolean = entries.values.any { m ->
        listOf("failed", "skipped", "sourceFailures").any { ((m[it] as? Long) ?: 0L) > 0L }
    }
}
