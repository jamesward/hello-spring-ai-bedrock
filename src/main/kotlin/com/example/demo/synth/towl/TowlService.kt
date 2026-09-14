package com.example.demo.synth.towl

import com.example.demo.TokenTracker
import com.example.demo.TokenTrackingAdvisor
import org.springframework.ai.chat.client.ChatClient

/**
 * The stateless phase pipeline for TOWL v3: parse -> check -> (render | execute). Nothing is
 * cached or persisted; run always re-checks from source.
 */
class TowlService(
    val catalog: TowlCatalog,
    maxConcurrency: Int = 6,
    maxWidth: Int = 200,
    maxCalls: Int = 500,
    maxResultBytes: Int = 256 * 1024,
) {
    private val width = maxWidth
    private val runtime = Runtime(catalog, maxConcurrency, maxWidth, maxCalls, maxResultBytes)

    fun parse(source: String): Program = Parser(source, catalog.namespaces).program()

    /** A fresh checker per call: checking state is never shared between programs or threads. */
    fun check(source: String): Checked = Checker(catalog, width).check(parse(source), source)

    /** parse + check; diagnostics come back as a TowlException carrying every problem found. */
    fun validate(source: String): Checked = check(stripFence(source))

    fun execute(checked: Checked, inputs: Map<String, Any?> = emptyMap()): Map<String, Any?> =
        runtime.execute(checked, inputs)

    companion object {
        /** Host tolerance: one surrounding Markdown fence is removed (TOWL §2 leaves this to hosts). */
        fun stripFence(src: String): String {
            val t = src.trim()
            if (!t.startsWith("```")) return src
            val body = t.removePrefix("```").let { if (it.startsWith("towl")) it.removePrefix("towl") else it }
            return body.removeSuffix("```").trim()
        }
    }
}

/** Several catalogs (MCP servers, LLM operations) presented as one; namespaces must be disjoint. */
class CompositeCatalog(private val parts: List<TowlCatalog>) : TowlCatalog {
    init {
        val dup = parts.flatMap { it.namespaces }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(dup.isEmpty()) { "catalogs share namespaces: $dup" }
    }
    override val namespaces = parts.flatMap { it.namespaces }.toSet()
    override fun operation(namespace: String, name: String) = parts.firstNotNullOfOrNull { it.operation(namespace, name) }
    override fun operations() = parts.flatMap { it.operations() }
    override fun errorClass(op: OperationSpec, code: String) =
        parts.first { op.namespace in it.namespaces }.errorClass(op, code)
}

/**
 * The `llm` namespace: model-backed operations that are ordinary catalog calls, so their use is a
 * visible effect in the plan rather than a hidden transform. `summarize` runs a plain prompt with
 * no tools and no history; the program author decides when a document needs it.
 *
 * Every model call made here is an INNER call of the scenario: it is recorded on the tracker that
 * [tracker] returns at call time (the scenario's "tooling" tracker), so the metrics report counts
 * summarize calls and their tokens alongside the agent's own turns.
 */
class LlmCatalog(
    private val chat: ChatClient,
    private val tracker: () -> TokenTracker? = { null },
    override val namespaces: Set<String> = setOf("llm"),
) : TowlCatalog {
    private val ns = namespaces.single()

    private val summarize = OperationSpec(
        namespace = ns, name = "summarize",
        description = "Summarize a text with a language model (no tools, no context beyond the text). " +
            "Use it to reduce a long document to what the task needs; the reduction is a visible step in the plan.",
        input = TRecord(mapOf("text" to TString, "focus" to Types.nullable(TString))),
        output = TString,
        effect = Effect.READ,
        errorCodes = setOf("ModelError"),
    ) { args ->
        val text = args["text"] as? String ?: throw OperationError("InvalidParameter", "text must be a string")
        val focus = args["focus"] as? String
        val prompt = buildString {
            append("Summarize the following text in at most five sentences. Report only what the text says; do not add outside knowledge.")
            if (focus != null) append(" Focus on: ").append(focus).append('.')
            append("\n\n").append(text)
        }
        try {
            val spec = chat.prompt().user(prompt)
            tracker()?.let { spec.advisors(TokenTrackingAdvisor(it)) }
            spec.call().content() ?: ""
        } catch (e: Exception) { throw OperationError("ModelError", e.message ?: e.toString(), e) }
    }

    private val ops = listOf(summarize).associateBy { it.name }
    override fun operation(namespace: String, name: String) = if (namespace == ns) ops[name] else null
    override fun operations() = ops.values.toList()
}
