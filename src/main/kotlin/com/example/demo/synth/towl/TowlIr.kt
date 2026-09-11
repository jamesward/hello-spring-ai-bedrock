package com.example.demo.synth.towl

import com.fasterxml.jackson.annotation.JsonAnySetter

/**
 * The TOWL plan as a tool-input IR: a typed skeleton of the closed structural keys, so the tool's
 * input schema teaches the model the plan shape and the plan arrives as a structured JSON OBJECT —
 * no string escaping, no brace-balancing inside quotes.
 *
 * Deliberately shallow: expression positions, results, and traversal bodies are open-vocabulary
 * (registry function keys, user-chosen binder names), so they stay untyped. Unknown members are
 * CAPTURED, not dropped — they flow to the TOWL parser, whose corrective diagnostics (misplaced
 * braces, foreign role members) must keep working on exactly such mistakes.
 */
open class BlockIr {
    var let: Map<String, BlockIr>? = null
    var source: Any? = null
    var filter: Any? = null
    var dedup: List<Any?>? = null
    var result: Any? = null
    var onError: String? = null
    var forEach: Map<String, Any?>? = null

    protected val extras: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun any(key: String, value: Any?) { extras[key] = value }

    open fun toWire(): MutableMap<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        forEach?.let { m["forEach"] = it }
        let?.let { bindings -> m["let"] = bindings.mapValues { it.value.toWire() } }
        source?.let { m["source"] = it }
        filter?.let { m["filter"] = it }
        dedup?.let { m["dedup"] = it }
        result?.let { m["result"] = it }
        onError?.let { m["onError"] = it }
        m.putAll(extras)
        return m
    }
}

/** The whole document: version header, declared inputs, and the root block. */
class PlanIr : BlockIr() {
    var towl: String? = null
    var description: String? = null
    var inputs: Map<String, Any?>? = null

    override fun toWire(): MutableMap<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        m["towl"] = towl ?: "v1"
        description?.let { m["description"] = it }
        inputs?.let { m["inputs"] = it }
        m.putAll(super.toWire())
        return m
    }
}
