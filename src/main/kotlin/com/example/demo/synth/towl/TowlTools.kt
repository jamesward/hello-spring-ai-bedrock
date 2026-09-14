package com.example.demo.synth.towl

import org.springframework.ai.tool.annotation.Tool
import org.springframework.ai.tool.annotation.ToolParam
import tools.jackson.databind.json.JsonMapper

/**
 * TOWL v3 exposed as the agent's whole tool belt: discover operations through a capability search
 * (the catalog is never loaded into the prompt), author ONE program as text, validate or run it.
 * Every tool returns JSON and never throws — diagnostics and runtime failures come back as
 * structured data the agent can act on.
 */
class TowlTools(private val service: TowlService) {

    private val mapper = JsonMapper.builder().build()

    @Tool(
        name = "towlPlanHelper",
        description = "ALWAYS call this ONCE before authoring a TOWL program, passing one search " +
            "query per CAPABILITY the task needs — do not spend turns on repeated single searches. " +
            "Queries describe what an operation DOES (verb + object, e.g. \"resolve latest version\", " +
            "\"list documented classes\", \"read documentation page\", \"summarize text\"), NEVER the task's " +
            "subject: library names, class names, and other task-specific values are call PARAMETERS. " +
            "Returns the TOWL v3 language guide plus the operations matching each query with exact " +
            "parameter and result types, and a bounded sample of remaining operation names.",
    )
    fun towlPlanHelper(
        @ToolParam(description = "capability searches, one per operation family needed (verb + object, no task-specific names). Pass ALL searches in this one call.")
        queries: List<String>,
        @ToolParam(required = false, description = "maximum operations returned in full per query; default 5")
        limit: Int?,
        @ToolParam(required = false, description = "include the language guide (default true). Set false on a repeat search in the same task: the guide does not change")
        includeGuide: Boolean?,
    ): String {
        val ops = service.catalog.operations()
        val max = (limit ?: 5).coerceIn(1, ops.size.coerceAtLeast(1))
        val matched = LinkedHashSet<OperationSpec>()
        val unmatched = ArrayList<String>()
        for (query in queries) {
            val terms = tokens(query)
            val hits = ops.map { op -> op to score(op, terms) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
            if (hits.isEmpty() && terms.isNotEmpty()) unmatched += query
            matched += hits
        }
        val others = (ops - matched).map { it.id }
        val body = linkedMapOf<String, Any?>()
        if (includeGuide != false) body["language"] = TowlPrompt.languageGuide(service.catalog)
        body.putAll(linkedMapOf(
            "matched" to matched.map { TowlPrompt.operationEntry(it, service.catalog) },
            "unmatched" to unmatched,
            "otherOperations" to others.take(OTHER_NAMES_CAP),
            "otherOperationsOmitted" to (others.size - OTHER_NAMES_CAP).coerceAtLeast(0),
        ))
        if (unmatched.isNotEmpty()) body["note"] = "No operation in this catalog provides: ${unmatched.joinToString("; ")}. " +
            "The catalog is fully listed above (matched + otherOperations); searching again will not find more. Author the program with the operations that exist."
        return mapper.writeValueAsString(body)
    }

    private fun tokens(q: String): List<String> =
        q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }

    private fun score(op: OperationSpec, terms: List<String>): Int {
        if (terms.isEmpty()) return 0
        val name = op.id.lowercase()
        val desc = op.description?.lowercase() ?: ""
        return terms.sumOf { t ->
            val variants = if (t.endsWith("s")) listOf(t, t.dropLast(1)) else listOf(t)
            (if (variants.any { name.contains(it) }) 3 else 0) + (if (variants.any { desc.contains(it) }) 1 else 0)
        }
    }

    @Tool(
        name = "validateTowlPlan",
        description = "Type-check a TOWL v3 program WITHOUT executing anything. On success returns " +
            "{valid: true, result_type, bindings, effects, typed} — 'typed' is the program annotated with every " +
            "inferred type and effect. On failure returns {valid: false, diagnostics: [{where, code, message, fix}]} " +
            "listing EVERY problem; fix them all and retry.",
    )
    fun validateTowlPlan(
        @ToolParam(description = "the program as a STRUCTURED object: { towl: 3, description, inputs?, bindings: [...], result }; a binding is { name, value } | { name, call, params, options?, then? } | { name, each: { over, as, bindings, result } }") plan: ProgramIr,
    ): String = withChecked(plan) { linkedMapOf<String, Any?>("valid" to true).apply { putAll(Render.report(it)) } }

    @Tool(
        name = "runTowlPlan",
        description = "Type-check and then EXECUTE a TOWL v3 program against the live operations (this " +
            "always re-checks; calling validateTowlPlan first is optional). On success returns " +
            "{valid: true, status: \"ok\", type, value, nodes, effects}: 'nodes' holds per-step element counts — " +
            "use it to explain an empty result before changing the program. A runtime failure returns " +
            "{status: \"error\", error: {class, code, message, action}, completed, fanout, mutations}: " +
            "'completed' are finished values you may pass back as inputs to a resume program; 'action' says " +
            "whether to rerun, rewrite, or declare tolerate for the reported code.",
    )
    fun runTowlPlan(
        @ToolParam(description = "the program as a STRUCTURED object: { towl: 3, description, inputs?, bindings: [...], result }; a binding is { name, value } | { name, call, params, options?, then? } | { name, each: { over, as, bindings, result } }") plan: ProgramIr,
        @ToolParam(required = false, description = "values for the program's declared inputs, by name") inputs: Map<String, Any?>?,
    ): String = withChecked(plan) { checked ->
        linkedMapOf<String, Any?>("valid" to true).apply { putAll(service.execute(checked, inputs ?: emptyMap())) }
    }

    private fun withChecked(plan: ProgramIr, body: (Checked) -> Map<String, Any?>): String {
        val structural = plan.structuralDiagnostics(service.catalog.namespaces)
        if (structural.any { it.severity == "error" })
            return mapper.writeValueAsString(linkedMapOf("valid" to false, "diagnostics" to structural.map { it.toMap() }))
        val rendered = plan.render()
        val checked = try {
            service.validate(rendered.source)
        } catch (e: TowlException) {
            val diags = e.diagnostics.map { d -> d.toMap() + ("where" to (d.pos?.let { rendered.whereByLine[it.line] } ?: "program")) }
            return mapper.writeValueAsString(linkedMapOf("valid" to false, "diagnostics" to diags, "source" to rendered.source))
        }
        return mapper.writeValueAsString(body(checked))
    }

    companion object {
        private const val OTHER_NAMES_CAP = 25

        /** System prompt for an agent whose tool belt is exactly these three tools. */
        val AGENT_SYSTEM = """
            You accomplish data tasks by authoring ONE TOWL v3 program and executing it — never by
            fetching data yourself. Workflow:
            1. Decompose the task into abstract capabilities (resolve a version, list items, read a
               document, summarize text), then call towlPlanHelper ONCE with one query per
               capability. Search by what an operation DOES — never by the task's subject: library,
               class, or product names are call parameters, not operation names. The helper lists
               the WHOLE catalog (matched + otherOperations); a query it reports as 'unmatched' has
               no operation and never will — do not search for it again, design the program without
               it (e.g. return the text as-is, or fewer items). Read the returned language guide.
            2. Author one complete TOWL v3 program as a STRUCTURED OBJECT: ordered bindings plus a
               result. Prefer the structured binding forms — { name, call, params, then } for an
               operation call (params is a real JSON object; references are {"$": "name"}) and
               { name, each: { over, as, bindings, result } } for a fan-out — and { name, value } for
               pure transforms. Keep the result SMALL: project only what the answer needs; if a
               document is long and the catalog has a summarize operation, include it in the program.
            3. Run it with runTowlPlan (it type-checks first). Use validateTowlPlan to check without executing.
            4. If you get {valid: false}, fix EVERY listed diagnostic and retry. If status is "error",
               read error.action: rerun, rewrite the program, or declare tolerate for the reported code;
               'completed' values can be passed back as inputs so finished work is not repeated. If the
               result is empty, read 'nodes' (per-step counts) before changing the program.
            5. Answer the user from the run result only. Be concise.
        """.trimIndent()
    }
}
