package com.example.demo.synth.towl

/**
 * Static explanation — the dry run. Describes resolved operations, structure, fan-out, and
 * dependency waves without invoking anything.
 */
class TowlExplainer {

    fun explain(v: ValidatedPlan): String {
        val sb = StringBuilder()
        sb.appendLine("plan: ${v.plan.description}")
        if (v.plan.inputs.isNotEmpty())
            sb.appendLine("inputs: " + v.plan.inputs.entries.joinToString { (n, d) ->
                n + ":" + d.type + (if (d.hasDefault) "=" + d.default else " (required)")
            })
        v.plan.preprocessing.forEach { sb.appendLine("preprocessing: $it") }
        block(v.plan.block, v, indent = "", multiplicity = "once", sb)
        v.warnings.forEach { sb.appendLine("warning: $it") }
        return sb.toString().trimEnd()
    }

    private fun block(b: Block, v: ValidatedPlan, indent: String, multiplicity: String, sb: StringBuilder) {
        when (b) {
            is Assemble -> {
                if (b.let.isNotEmpty()) {
                    val waves = waves(b)
                    waves.forEachIndexed { i, wave ->
                        sb.appendLine("${indent}wave ${i + 1}: ${wave.joinToString()}")
                    }
                    for ((name, binding) in b.let) {
                        sb.appendLine("$indent$name:")
                        block(binding, v, "$indent  ", multiplicity, sb)
                    }
                }
                val sink = v.sinks[b]
                sb.appendLine(indent + (if (b.result != null) "value: explicit result" else "value: sink '$sink'"))
            }
            is Express -> {
                when (val p = b.producer) {
                    is PCall -> {
                        val op = v.resolutions[p.invocation]
                        sb.appendLine("$indent" + "call ${op?.id ?: p.invocation} [${op?.cardinality}] effects=${op?.effects} runs $multiplicity")
                    }
                    is PExpr -> sb.appendLine("$indent" + "stream from expression, runs $multiplicity")
                }
                if (b.filter != null) sb.appendLine("$indent  filter: local predicate")
                if (b.dedup != null) sb.appendLine("$indent  dedup: ${b.dedup.size} key(s)")
                if (b.result != null) sb.appendLine("$indent  result: " + if (foldy(b.result)) "grouped fold -> One" else "maps each element")
            }
            is Traverse -> {
                sb.appendLine("$indent" + "forEach ${b.elementName} (onError=${b.onError.name.lowercase()}), body runs once per element")
                block(b.body, v, "$indent  ", "once per ${b.elementName}", sb)
            }
        }
    }

    private fun foldy(r: ResultNode): Boolean = when (r) {
        is RExpr -> false
        is RAgg -> true
        is RRecord -> r.members.values.any(::foldy)
    }

    /** Topological waves of an Assemble's bindings: wave n depends only on waves < n. */
    fun waves(b: Assemble): List<List<String>> {
        val deps = b.let.mapValues { (_, blk) -> TowlValidator.refsIn(blk).intersect(b.let.keys) }
        val placed = mutableMapOf<String, Int>()
        fun level(n: String): Int = placed.getOrPut(n) {
            val d = deps.getValue(n)
            if (d.isEmpty()) 0 else 1 + d.maxOf { level(it) }
        }
        b.let.keys.forEach { level(it) }
        return placed.entries.groupBy({ it.value }, { it.key }).toSortedMap().values.toList()
    }
}
