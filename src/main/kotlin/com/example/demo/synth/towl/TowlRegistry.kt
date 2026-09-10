package com.example.demo.synth.towl

/**
 * Runtime-provided registries. TOWL core defines application syntax but NO function or aggregator
 * names; this runtime installs a small vocabulary suited to MCP/javadoc tasks. Descriptors carry
 * the laws the validator/normalizer may rely on; nothing is inferred from spelling.
 */

data class FunctionDef(
    val name: String,
    val minArity: Int,
    val maxArity: Int?, // null = variadic
    val description: String,
    val booleanRole: String? = null, // conjunction | disjunction | negation
    val relationRole: String? = null, // equality | ordering | membership
    val evaluate: (List<Any?>) -> Any?,
)

data class AggregatorDef(
    val name: String,
    val arity: Int, // expression arguments
    val description: String,
    val identity: Any?,
    val prepare: (Any?) -> Any?,
    val combine: (Any?, Any?) -> Any?,
    val present: (Any?) -> Any? = { it },
)

class TowlRegistry(
    functions: List<FunctionDef>,
    aggregators: List<AggregatorDef>,
) {
    val functions: Map<String, FunctionDef> = functions.associateBy { it.name }
    val aggregators: Map<String, AggregatorDef> = aggregators.associateBy { it.name }

    init {
        val bad = (this.functions.keys + this.aggregators.keys).filter { it in RESERVED_KEYS }
        require(bad.isEmpty()) { "registry must not register reserved keys: $bad" }
        val overlap = this.functions.keys.intersect(this.aggregators.keys)
        require(overlap.isEmpty()) { "function and aggregator names must be disjoint: $overlap" }
    }

    fun isExprKey(key: String) = key in RESERVED_KEYS || key in functions
    fun isAggKey(key: String) = key in aggregators

    /** Machine-readable vocabulary for schema generation and the planner prompt. */
    fun describe(): Map<String, Any?> = linkedMapOf(
        "functions" to functions.values.map { f ->
            linkedMapOf(
                "name" to f.name,
                "arity" to (f.maxArity?.let { if (it == f.minArity) "${f.minArity}" else "${f.minArity}..$it" } ?: "${f.minArity}..n"),
                "description" to f.description,
            )
        },
        "aggregators" to aggregators.values.map { a ->
            linkedMapOf("name" to a.name, "arity" to a.arity, "description" to a.description)
        },
    )

    companion object {
        fun default(): TowlRegistry = TowlRegistry(defaultFunctions(), defaultAggregators())

        private fun str(v: Any?): String? = v as? String
        private fun num(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            else -> null
        }

        private fun cmp(a: Any?, b: Any?): Int? {
            val na = num(a); val nb = num(b)
            if (na != null && nb != null) return na.compareTo(nb)
            if (a is String && b is String) return a.compareTo(b)
            return null
        }

        /** eq with a literal-list right side means "any of these values" (alternatives). */
        private fun eq(a: Any?, b: Any?): Boolean = when {
            b is List<*> && a !is List<*> -> b.any { eq(a, it) }
            a is Number && b is Number -> a.toDouble() == b.toDouble()
            else -> a == b
        }

        private fun truthy(v: Any?): Boolean = v == true

        private fun defaultFunctions(): List<FunctionDef> = listOf(
            FunctionDef("eq", 2, 2, "eq(a, b) — equality; list right side means any-of", relationRole = "equality") { (a, b) -> eq(a, b) },
            FunctionDef("ne", 2, 2, "ne(a, b) — negated equality") { (a, b) -> !eq(a, b) },
            FunctionDef("contains", 2, 2, "contains(string, substring) — case-sensitive") { (a, b) ->
                str(a)?.contains(str(b) ?: return@FunctionDef false) ?: false
            },
            FunctionDef("startsWith", 2, 2, "startsWith(string, prefix)") { (a, b) ->
                str(a)?.startsWith(str(b) ?: return@FunctionDef false) ?: false
            },
            FunctionDef("endsWith", 2, 2, "endsWith(string, suffix)") { (a, b) ->
                str(a)?.endsWith(str(b) ?: return@FunctionDef false) ?: false
            },
            FunctionDef("lt", 2, 2, "lt(a, b)", relationRole = "ordering") { (a, b) -> cmp(a, b)?.let { it < 0 } ?: false },
            FunctionDef("lte", 2, 2, "lte(a, b)", relationRole = "ordering") { (a, b) -> cmp(a, b)?.let { it <= 0 } ?: false },
            FunctionDef("gt", 2, 2, "gt(a, b)", relationRole = "ordering") { (a, b) -> cmp(a, b)?.let { it > 0 } ?: false },
            FunctionDef("gte", 2, 2, "gte(a, b)", relationRole = "ordering") { (a, b) -> cmp(a, b)?.let { it >= 0 } ?: false },
            FunctionDef("present", 1, 1, "present(v) — value exists and is not null") { (a) -> a != null },
            FunctionDef("absent", 1, 1, "absent(v) — value is missing or null") { (a) -> a == null },
            FunctionDef("and", 2, null, "and(a, b, ...) — n-ary conjunction", booleanRole = "conjunction") { args -> args.all(::truthy) },
            FunctionDef("or", 2, null, "or(a, b, ...) — n-ary disjunction", booleanRole = "disjunction") { args -> args.any(::truthy) },
            FunctionDef("not", 1, 1, "not(a) — negation", booleanRole = "negation") { (a) -> !truthy(a) },
            FunctionDef("casefold", 1, 1, "casefold(string) — lowercase for comparisons") { (a) -> str(a)?.lowercase() },
            FunctionDef("size", 1, 1, "size(string|list|map) — length") { (a) ->
                when (a) {
                    is String -> a.length
                    is List<*> -> a.size
                    is Map<*, *> -> a.size
                    null -> null
                    else -> null
                }
            },
            FunctionDef("afterLast", 2, 2, "afterLast(string, separator) — e.g. class name from an FQN") { (a, sep) ->
                val s = str(a) ?: return@FunctionDef null
                val d = str(sep) ?: return@FunctionDef null
                s.substringAfterLast(d)
            },
        )

        private fun defaultAggregators(): List<AggregatorDef> = listOf(
            AggregatorDef("count", 0, "count() — number of stream elements", 0L,
                prepare = { 1L }, combine = { a, b -> (a as Long) + (b as Long) }),
            AggregatorDef("sum", 1, "sum(x) — numeric sum", 0.0,
                prepare = { num(it) ?: 0.0 }, combine = { a, b -> (a as Double) + (b as Double) }),
            AggregatorDef("min", 1, "min(x) — least value or null on an empty stream", null,
                prepare = { it }, combine = { a, b -> if (a == null || (b != null && cmp(b, a) == -1)) b else a }),
            AggregatorDef("max", 1, "max(x) — greatest value or null on an empty stream", null,
                prepare = { it }, combine = { a, b -> if (a == null || (b != null && cmp(b, a) == 1)) b else a }),
            AggregatorDef("collect", 1, "collect(x) — ordered list of values", emptyList<Any?>(),
                prepare = { listOf(it) }, combine = { a, b ->
                    @Suppress("UNCHECKED_CAST")
                    (a as List<Any?>) + (b as List<Any?>)
                }),
            AggregatorDef("avg", 1, "avg(x) — mean of numeric values, null on an empty stream", 0.0 to 0L,
                prepare = { (num(it) ?: 0.0) to 1L },
                combine = { a, b ->
                    @Suppress("UNCHECKED_CAST") val pa = a as Pair<Double, Long>
                    @Suppress("UNCHECKED_CAST") val pb = b as Pair<Double, Long>
                    (pa.first + pb.first) to (pa.second + pb.second)
                },
                present = {
                    @Suppress("UNCHECKED_CAST") val p = it as Pair<Double, Long>
                    if (p.second == 0L) null else p.first / p.second
                }),
        )
    }
}
