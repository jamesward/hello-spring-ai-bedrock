package com.example.demo.synth.towl

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import tools.jackson.databind.json.JsonMapper

/**
 * TOWL exposed as agent tools (SkillsTool / dynamic-tool-search style): the agent discovers
 * operations through a SEARCH tool instead of carrying the full catalog in its prompt, authors one
 * TOWL document, and validates/runs it. Every tool returns JSON and never throws — validation and
 * execution failures come back as structured "errors" the agent can act on.
 *
 * No advisor is required for this shape: the three tools are static, and discovery happens inside
 * towlPlanHelper's catalog search rather than by re-injecting tool definitions into the request.
 */
class TowlTools(private val service: TowlService) {

    private val mapper = JsonMapper.builder().build()

    @Tool(
        name = "towlPlanHelper",
        description = "ALWAYS call this ONCE before authoring a TOWL plan, passing one search " +
            "query per CAPABILITY the task needs — do not spend turns on repeated single searches. " +
            "Queries describe what an operation DOES (verb + object, e.g. \"resolve latest version\", " +
            "\"list documented classes\", \"read documentation page\"), NEVER the task's subject: " +
            "library names, class names, and other task-specific values are call ARGUMENTS, and " +
            "putting them in a search only hides the right operations. Returns the TOWL language " +
            "guide plus the operations matching each query, with argument and return schemas, and a " +
            "bounded sample of remaining operation names in 'otherOperations'.",
    )
    fun towlPlanHelper(
        @ToolParam(description = "capability searches, one per operation family needed (verb + object, no task-specific names); matched against operation names and descriptions. Pass ALL searches in this one call.")
        queries: List<String>,
        @ToolParam(required = false, description = "maximum operations returned in full per query; default 5")
        limit: Int?,
    ): String {
        val ops = service.catalog.operations()
        val max = (limit ?: 5).coerceIn(1, ops.size.coerceAtLeast(1))
        val matched = LinkedHashSet<ResolvedOperation>()
        for (query in queries) {
            val terms = tokens(query)
            matched += ops.map { op -> op to score(op, terms) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
        }
        val others = (ops - matched).map { it.id }
        val body = linkedMapOf<String, Any?>(
            "language" to TowlPrompt.languageGuide(service.registry),
            "matched" to matched.map(TowlPrompt::operationEntry),
            "otherOperations" to others.take(OTHER_NAMES_CAP),
            "otherOperationsOmitted" to (others.size - OTHER_NAMES_CAP).coerceAtLeast(0),
        )
        return mapper.writeValueAsString(body)
    }

    /** Split on any non-alphanumerics so "javadoc-symbols" searches as [javadoc, symbols]. */
    private fun tokens(q: String): List<String> =
        q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }

    private fun score(op: ResolvedOperation, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val name = op.id.lowercase()
        val desc = op.description?.lowercase() ?: ""
        return terms.sumOf { t ->
            val variants = if (t.endsWith("s")) listOf(t, t.dropLast(1)) else listOf(t)
            (if (variants.any { name.contains(it) }) 3 else 0) +
                (if (variants.any { desc.contains(it) }) 1 else 0)
        }
    }

    @Tool(
        name = "validateTowlPlan",
        description = "Statically validate a TOWL plan WITHOUT executing anything. On success " +
            "returns {valid: true, explain, warnings} — explain shows resolved operations, " +
            "dependency waves, and fan-out. On failure returns {valid: false, errors} with " +
            "corrective messages; fix the plan and retry.",
    )
    fun validateTowlPlan(
        @ToolParam(description = "the complete TOWL v1 plan as a structured JSON object (never an escaped string)") plan: PlanIr,
    ): String = withValidated(plan) { validated ->
        linkedMapOf(
            "valid" to true,
            "explain" to service.explain(validated),
            "warnings" to validated.warnings,
        )
    }

    @Tool(
        name = "runTowlPlan",
        description = "Validate and then EXECUTE a TOWL plan against the live operations. " +
            "Explicit validateTowlPlan first is optional — this always revalidates. Returns " +
            "{valid: true, status: ok|partial|error, diagnostics, warnings, result}: 'diagnostics' " +
            "is per-binding record accounting (streamed/filtered/traversed/ok/failed) — use it to " +
            "explain empty or partial results. Validation failures return {valid: false, errors}.",
    )
    fun runTowlPlan(
        @ToolParam(description = "the complete TOWL v1 plan as a structured JSON object (never an escaped string)") plan: PlanIr,
    ): String = withValidated(plan) { validated ->
        try {
            linkedMapOf<Any?, Any?>("valid" to true).apply { putAll(service.execute(validated).toMap()) }
        } catch (e: Exception) {
            linkedMapOf(
                "valid" to true,
                "status" to "error",
                "errors" to listOf(mapOf("where" to "execution", "message" to (e.message ?: e.toString()))),
            )
        }
    }

    private fun withValidated(plan: PlanIr, body: (ValidatedPlan) -> Map<*, *>): String {
        val validated = try {
            service.validate(service.parse(mapper.writeValueAsString(plan.toWire())))
        } catch (e: TowlException) {
            return mapper.writeValueAsString(
                linkedMapOf(
                    "valid" to false,
                    "errors" to e.diagnostics.map { mapOf("where" to it.where, "message" to it.message) },
                ),
            )
        }
        return mapper.writeValueAsString(body(validated))
    }

    companion object {
        private const val OTHER_NAMES_CAP = 25

        /** System prompt for an agent whose tool belt is exactly these three tools. */
        val AGENT_SYSTEM = """
            You accomplish data tasks by authoring ONE TOWL workflow document and executing it —
            never by fetching data yourself. Workflow:
            1. Decompose the task into abstract capabilities (resolve a version, list items, read a
               document, search a catalog), then call towlPlanHelper ONCE with one query per
               capability. Search by what an operation DOES — never by the task's subject: library,
               class, or product names are call arguments, not operation names. Only search again
               if a needed capability is missing. Read the returned language guide.
            2. Author one complete TOWL v1 plan that does all fetching, filtering, traversal, and
               shaping. Pass it as a STRUCTURED JSON OBJECT in the tool arguments — never as an
               escaped string. Every binding goes inside "let". Keep the plan's final result
               SMALL: project only what the answer needs.
            3. Run it with runTowlPlan (it validates first). Use validateTowlPlan when you want to
               check a plan without executing it.
            4. If you get {valid: false}, fix the reported errors and retry. If the result looks
               empty or partial, read 'diagnostics' (per-binding record counts) to see where the
               records went before changing the plan.
            5. Answer the user from the run result only. Be concise.
        """.trimIndent()
    }
}
