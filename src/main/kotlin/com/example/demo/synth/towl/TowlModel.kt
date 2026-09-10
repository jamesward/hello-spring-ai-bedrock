package com.example.demo.synth.towl

/**
 * TOWL v1 model — the typed, validated representation of a plan (see aws-cli/TOWL_SPEC.md).
 *
 * Blocks are a CLOSED sum discriminated by a distinctive required key: `forEach` selects Traverse,
 * else `source` selects Express, else the block is Assemble. Each variant admits only its own
 * members, so role mixtures are unparseable rather than "valid then rejected".
 *
 * Names: `let` is the only value binder; `forEach` binds the one named element variable. `source`
 * binds nothing — the produced element is implicit within its block's filter/dedup/result and is
 * addressed by a bare `{"path": P}` node ({"path": ""} is the element itself).
 */

// ── diagnostics ─────────────────────────────────────────────────────────────

data class Diagnostic(val where: String, val message: String) {
    override fun toString() = "$where: $message"
}

class TowlException(val diagnostics: List<Diagnostic>) :
    RuntimeException(diagnostics.joinToString("; ") { it.toString() })

fun fail(where: String, message: String): Nothing = throw TowlException(listOf(Diagnostic(where, message)))

// ── plan ────────────────────────────────────────────────────────────────────

data class Plan(
    val description: String,
    val inputs: Map<String, InputDecl>,
    val block: Block,
    val preprocessing: List<String>, // host repairs (e.g. stripped fence), always reported
)

data class InputDecl(val type: String, val default: Any?, val hasDefault: Boolean)

// ── blocks (closed union) ───────────────────────────────────────────────────

sealed interface Block

data class Traverse(
    val elementName: String,
    val from: Expr,
    val body: Block,
    val onError: OnError, // default SKIP
) : Block

data class Express(
    val producer: Producer,
    val filter: Expr?,
    val dedup: List<Expr>?,
    val result: ResultNode?,
    val onError: OnError, // default FAIL; COLLECT invalid here
) : Block

data class Assemble(
    val let: Map<String, Block>,
    val result: ResultNode?,
) : Block

enum class OnError { FAIL, SKIP, COLLECT }

// ── producers and invocations ───────────────────────────────────────────────

sealed interface Producer
data class PCall(val invocation: Invocation) : Producer
data class PExpr(val expr: Expr) : Producer

/** Identity is referential: the validator keys catalog resolutions by instance. */
class Invocation(
    val service: String?,
    val operation: String,
    val args: Map<String, InputValue>,
    val paginate: Paginate?,
) {
    override fun toString() = (service?.plus(":") ?: "") + operation
}

data class Paginate(val maxItems: Int?, val maxPages: Int?, val pageSize: Int?)

sealed interface InputValue
data class IExpr(val expr: Expr) : InputValue
data class IList(val items: List<InputValue>) : InputValue
data class IMap(val members: Map<String, InputValue>) : InputValue

// ── expressions ─────────────────────────────────────────────────────────────

val RESERVED_KEYS = setOf("ref", "input", "env", "call", "path")

sealed interface Expr
data class Lit(val value: Any?) : Expr
data class RefE(val name: String, val path: Path) : Expr
data class ImplicitE(val path: Path) : Expr // bare {"path": P}; only in Express stages/result
data class InputE(val name: String, val path: Path) : Expr
data class EnvE(val name: String, val path: Path) : Expr
data class ApplyE(val function: String, val args: List<Expr>) : Expr

// ── results (pure leaves and aggregate leaves; any aggregate leaf folds) ────

sealed interface ResultNode
data class RExpr(val expr: Expr) : ResultNode
data class RAgg(val agg: AggApply) : ResultNode
data class RRecord(val members: Map<String, ResultNode>) : ResultNode

data class AggApply(val name: String, val args: List<AggArg>)
sealed interface AggArg
data class AExpr(val expr: Expr) : AggArg
data class AAgg(val agg: AggApply) : AggArg
data class AProduct(val members: Map<String, AggApply>) : AggArg

// ── paths ───────────────────────────────────────────────────────────────────

/** Member navigation `a.b` plus one-level collection flattening `items[].name`. No indexes. */
data class Path(val segments: List<Seg>) {
    data class Seg(val name: String, val flatten: Boolean)

    val isWholeValue: Boolean get() = segments.isEmpty()

    override fun toString() = segments.joinToString(".") { it.name + if (it.flatten) "[]" else "" }

    companion object {
        fun parse(text: String, where: String): Path {
            if (text.isEmpty()) return Path(emptyList())
            val segs = text.split('.').map { raw ->
                val flatten = raw.endsWith("[]")
                val name = if (flatten) raw.dropLast(2) else raw
                if (name.isEmpty() || name.contains('[') || name.contains(']'))
                    fail(where, "invalid path segment '$raw' (no positional index or slice syntax)")
                Seg(name, flatten)
            }
            return Path(segs)
        }
    }

    /** Pure navigation. Absent members yield null; `[]` flattens one list level. */
    fun navigate(value: Any?): Any? {
        var current: Any? = value
        for ((i, seg) in segments.withIndex()) {
            val v = current ?: return null
            current = when (v) {
                is Map<*, *> -> v[seg.name]
                else -> return null
            }
            if (seg.flatten) {
                val list = current as? List<*> ?: return null
                val rest = Path(segments.drop(i + 1))
                return list.map { rest.navigate(it) }
            }
        }
        return current
    }
}
