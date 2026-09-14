package com.example.demo.synth.towl

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * The structured program form (TOWL_SPEC.md §3.1): the program SKELETON as a typed object whose
 * input schema teaches the shape, with three node forms —
 *
 *  - `value`: one TOWL expression in text;
 *  - `call` + `params` (+ `options`, `then`): an operation call whose parameters are a real JSON
 *    object — a value of the form {"$": "expr"} inside it is an expression (a reference to a
 *    binding or each variable), everything else is literal data; `then` is postfix text applied to
 *    the call's result (".result.where(...)");
 *  - `each`: a fan-out with its own inner bindings and result, so the calls inside a body are also
 *    structured.
 *
 * `render()` produces the equivalent text program; the same parser/checker run on it, and each
 * rendered line maps back to the node it came from so diagnostics say WHERE. Unknown members are
 * captured, not dropped, so a slipped key is reported with a corrective message.
 */
@JsonClassDescription(
    "A TOWL v3 program: optional inputs, ordered named bindings, and one result expression. " +
        "A binding is EITHER { name, value: <expression text> } OR { name, call: \"ns.op\", params: {...JSON...}, options?, then? } " +
        "OR { name, each: { over, as, bindings: [...], result } }. Inside params, {\"$\": \"expr\"} is an expression (a binding or " +
        "each variable, e.g. {\"$\": \"ver\"} or {\"$\": \"s.link\"}); every other value is literal data.",
)
class ProgramIr {
    @JsonPropertyDescription("language version; always 3")
    var towl: Int? = 3

    @JsonPropertyDescription("one line saying what the program does")
    var description: String? = null

    @JsonPropertyDescription("optional inputs the host supplies at run time: name -> TOWL type text (e.g. \"list[string]\", \"{ id: string }\")")
    var inputs: Map<String, String>? = null

    @JsonPropertyDescription("ordered bindings; each name is bound once and must be used by a later binding or the result")
    var bindings: List<NodeIr>? = null

    @JsonPropertyDescription("the result expression (TOWL text), usually a binding name or a record { a: x, b: y }; its value is the answer — keep it small")
    var result: String? = null

    @get:JsonIgnore
    val extras: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun any(key: String, value: Any?) { extras[key] = value }

    class Rendered(val source: String, val whereByLine: Map<Int, String>)

    fun render(): Rendered {
        val r = Renderer()
        r.add("towl ${towl ?: 3}" + (description?.let { " " + quoteText(it) } ?: ""), "header")
        inputs?.forEach { (n, t) -> r.add("input $n: $t", "input '$n'") }
        bindings?.forEachIndexed { i, b -> b.render(r, "", "binding '${b.name ?: "#$i"}'") }
        r.add(result ?: "", "result")
        return Rendered(r.lines.joinToString("\n"), r.where)
    }

    /** Structural problems the parser would otherwise report as confusing syntax errors. */
    fun structuralDiagnostics(namespaces: Set<String> = emptySet()): List<Diagnostic> {
        val out = ArrayList<Diagnostic>()
        if (towl != null && towl != 3) out += Diagnostic("error", "syntax", "syntax.version", null, "towl must be 3")
        if (result.isNullOrBlank()) out += Diagnostic("error", "syntax", "syntax.noResult", null, "'result' is required: the expression whose value is the answer",
            "add result, e.g. the name of the last binding or a record { a: x, b: y }")
        bindings?.forEachIndexed { i, b -> b.structural(out, "binding '${b.name ?: "#$i"}'", namespaces) }
        for ((k, _) in extras) out += Diagnostic("error", "syntax", "syntax.unknownMember", null,
            "'$k' is not a program member", "a program has towl, description, inputs, bindings: [{ name, ... }], result; put '$k' inside bindings as { name: \"$k\", value: ... }")
        return out
    }

    internal class Renderer {
        val lines = ArrayList<String>()
        val where = HashMap<Int, String>()
        fun add(text: String, tag: String) { for (l in text.lines()) { lines += l; where[lines.size] = tag } }
    }

    companion object {
        fun quoteText(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        /** JSON params -> TOWL record literal text; {"$": "expr"} becomes the expression text. */
        fun literal(v: Any?): String = when (v) {
            null -> "null"
            is String -> quoteText(v)
            is Boolean, is Number -> v.toString()
            is List<*> -> v.joinToString(", ", "[", "]") { literal(it) }
            is Map<*, *> -> {
                if (v.size == 1 && v.keys.single() == "$" && v.values.single() is String) v.values.single() as String
                else v.entries.joinToString(", ", "{ ", " }") { (k, x) -> "$k: ${literal(x)}" }
            }
            else -> quoteText(v.toString())
        }
    }
}

@JsonClassDescription("one binding: exactly one of value | call | each, plus name")
class NodeIr {
    @JsonPropertyDescription("the name this value is bound to (letters, digits, underscore)")
    var name: String? = null

    @JsonPropertyDescription("form 1: one TOWL expression in text, e.g. syms.where(.kind == \"class\").collect(.fqn)")
    var value: String? = null

    @JsonPropertyDescription("form 2: operation to call, as namespace.operation (e.g. javadocs.get_latest_version)")
    var call: String? = null

    @JsonPropertyDescription("form 2: the call's parameters as a JSON object. Literal data as-is; a reference to a binding or each variable as {\"$\": \"name\"} or {\"$\": \"s.link\"}")
    var params: Map<String, Any?>? = null

    @JsonPropertyDescription("form 2: call options: tolerate (error codes that yield null) and after (binding names that must finish first)")
    var options: OptionsIr? = null

    @JsonPropertyDescription("form 2: postfix text applied to the call's result, e.g. \".result\" or \".result.where(.fqn.contains(\\\"X\\\"))\"")
    var then: String? = null

    @JsonPropertyDescription("form 3: a fan-out over a list with its own bindings and result")
    var each: EachIr? = null

    @get:JsonIgnore
    val extras: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun any(key: String, value: Any?) { extras[key] = value }

    internal fun forms() = listOfNotNull(value?.let { "value" }, call?.let { "call" }, each?.let { "each" })

    internal fun structural(out: MutableList<Diagnostic>, tag: String, namespaces: Set<String>) {
        if (name.isNullOrBlank()) out += Diagnostic("error", "syntax", "syntax.binding", null, "$tag has no name")
        val forms = forms()
        if (forms.size != 1) out += Diagnostic("error", "syntax", "syntax.binding", null, "$tag must have exactly one of value, call, each; found $forms")
        if (call != null && !Regex("[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*").matches(call!!))
            out += Diagnostic("error", "syntax", "syntax.binding", null, "$tag: call must be namespace.operation, got '$call'")
        else if (call != null && namespaces.isNotEmpty() && call!!.substringBefore('.') !in namespaces)
            out += Diagnostic("error", "catalog", "catalog.unknownNamespace", null, "$tag: '${call!!.substringBefore('.')}' is not a namespace in this catalog, so ${call} does not exist",
                "namespaces: ${namespaces.sorted().joinToString(", ")}; use only operations the helper listed and design the program without this one")
        if (call == null && (params != null || options != null || then != null))
            out += Diagnostic("error", "syntax", "syntax.binding", null, "$tag: params/options/then belong to a call binding", "add call: \"namespace.operation\"")
        if (extras.isNotEmpty()) out += Diagnostic("error", "syntax", "syntax.binding", null, "$tag has unknown members ${extras.keys}", "a binding is { name, value } or { name, call, params, options?, then? } or { name, each }")
        each?.let { e ->
            if (e.over.isNullOrBlank()) out += Diagnostic("error", "syntax", "syntax.each", null, "$tag: each.over (the list expression) is required")
            if (e.`as`.isNullOrBlank()) out += Diagnostic("error", "syntax", "syntax.each", null, "$tag: each.as (the element name) is required")
            if (e.result.isNullOrBlank()) out += Diagnostic("error", "syntax", "syntax.each", null, "$tag: each.result (the body's value) is required")
            e.bindings?.forEachIndexed { i, b -> b.structural(out, "binding '${b.name ?: "#$i"}' in each '$name'", namespaces) }
        }
    }

    internal fun render(r: ProgramIr.Renderer, indent: String, tag: String) {
        val head = "$indent${name ?: "_"} = "
        when {
            value != null -> r.add(head + value, tag)
            call != null -> {
                val opts = options?.let { o ->
                    val parts = ArrayList<String>()
                    o.tolerate?.let { parts += "tolerate: " + it.joinToString(", ", "[", "]") { c -> ProgramIr.quoteText(c) } }
                    o.after?.let { parts += "after: " + (if (it.size == 1) it[0] else it.joinToString(", ", "[", "]")) }
                    if (parts.isEmpty()) null else ", { ${parts.joinToString(", ")} }"
                } ?: ""
                r.add(head + call + "(" + ProgramIr.literal(params ?: emptyMap<String, Any?>()) + opts + ")" + (then ?: ""), tag)
            }
            each != null -> {
                val e = each!!
                r.add(head + "${e.over}.each(${e.`as`} => {", tag)
                e.bindings?.forEachIndexed { i, b -> b.render(r, "$indent  ", "binding '${b.name ?: "#$i"}' in each '$name'") }
                r.add("$indent  ${e.result}", "result of each '$name'")
                r.add("$indent})", tag)
            }
            else -> r.add(head, tag)
        }
    }
}

class OptionsIr {
    @JsonPropertyDescription("provider error codes at this call that mean 'absent'; the call yields null instead of stopping the program")
    var tolerate: List<String>? = null

    @JsonPropertyDescription("names of bindings that must finish before this call is dispatched (ordering without a data dependency)")
    var after: List<String>? = null
}

@JsonClassDescription("a fan-out: run the bindings once per element of 'over', binding the element to 'as'; the value is the list of each body's result")
class EachIr {
    @JsonPropertyDescription("the list expression to fan out over (TOWL text), e.g. \"syms\" or \"regions.where(.active == true)\"")
    var over: String? = null

    @JsonPropertyDescription("the element variable name, e.g. \"s\"")
    var `as`: String? = null

    @JsonPropertyDescription("bindings evaluated per element; may reference the element variable and outer bindings")
    var bindings: List<NodeIr>? = null

    @JsonPropertyDescription("the body's value (TOWL text), usually a record such as { class: s.fqn.after_last(\".\"), summary: sum }")
    var result: String? = null
}
