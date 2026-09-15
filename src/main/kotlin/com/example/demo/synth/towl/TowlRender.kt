package com.example.demo.synth.towl

/**
 * The reviewer's artifact (TOWL_SPEC.md §13.1, Code Mode §4.2): the source with the inferred type
 * of every binding and the effect of every call as trailing comments, plus the effect table.
 */
object Render {

    fun typed(c: Checked): String {
        val byLine = HashMap<Int, MutableList<String>>()
        fun note(line: Int, s: String) = byLine.getOrPut(line) { ArrayList() }.add(s)
        for (inp in c.program.inputs) note(inp.pos.line, "input ${inp.name}: ${inp.type}")
        for (b in c.program.bindings) note(b.pos.line, "${b.name}: ${c.bindingTypes[b.name]}")
        for (site in c.effects) note(site.call.pos.line, effectText(site))
        for ((call, width) in c.waves) note(call.pos.line, "wave ×${width ?: "dynamic"}")
        note(c.program.result.pos.line, "result: ${c.resultType}")
        val lines = c.source.lines()
        val width = (lines.maxOfOrNull { it.length } ?: 0).coerceAtMost(88) + 2
        return lines.mapIndexed { i, l ->
            val notes = byLine[i + 1]
            if (notes == null) l else (if (l.length + 2 <= width) l.padEnd(width) else "$l  ") + "// " + notes.joinToString("; ")
        }.joinToString("\n")
    }

    fun effectText(site: EffectSite): String {
        val mult = site.staticWidth?.let { "×$it" } ?: "×dynamic"
        val tol = if (site.tolerate.isEmpty()) "" else " tolerate ${site.tolerate}"
        return "${site.op.id} ${site.op.effect.name.lowercase()} $mult$tol"
    }

    fun report(c: Checked): Map<String, Any?> = linkedMapOf(
        "status" to "valid",
        "result_type" to c.resultType.toString(),
        "inputs" to c.program.inputs.map { linkedMapOf("name" to it.name, "type" to it.type.toString()) },
        "bindings" to c.program.bindings.map { linkedMapOf("name" to it.name, "type" to c.bindingTypes[it.name].toString(), "line" to it.pos.line) },
        "effects" to c.effects.map { it.toMap() },
        "mutations" to c.mutations,
        "warnings" to c.warnings.map { it.toMap() },
        "typed" to typed(c),
    )
}
