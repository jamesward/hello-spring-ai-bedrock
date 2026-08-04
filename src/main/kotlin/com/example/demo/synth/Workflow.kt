package com.example.demo.synth

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/**
 * A declarative, JSONata-driven workflow that the orchestrator LLM emits. It is a small DATA
 * structure — not a single JSONata program — because tool invocation and (controlled) fan-out are
 * structural, while JSONata is used only as a sandboxed, data-only layer for binding one step's
 * output into the next step's input and for selecting collections.
 *
 * A [Step] is a plain tool call, OR a fan-out ("map") step when [Step.items] is set: `items` is a
 * JSONata expression yielding a collection, and the tool runs once per element (the interpreter
 * controls concurrency, NOT the LLM). Each finished step's parsed output is bound as a JSONata
 * variable `$<id>`; inside a map step the current element is bound as `$item`. Arg values are
 * literals unless wrapped in `${ ... }`, in which case the inside is evaluated as JSONata.
 */
data class Workflow(
    val steps: List<Step> = emptyList(),
    val result: String? = null,
) {
    data class Step(
        val id: String,
        val tool: String,
        val args: Map<String, Any?> = emptyMap(),
        val items: String? = null, // present => fan-out (map) step; JSONata collection expression
    )

    companion object {
        private val mapper = JsonMapper.builder().build()
        private val type = object : TypeReference<Workflow>() {}

        fun parse(text: String): Workflow = mapper.readValue(stripFences(text), type)

        private fun stripFences(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
    }
}
