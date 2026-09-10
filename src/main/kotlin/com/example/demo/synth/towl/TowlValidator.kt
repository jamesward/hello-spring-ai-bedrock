package com.example.demo.synth.towl

import java.util.IdentityHashMap

/**
 * Builds the validated plan: name resolution (one namespace, no shadowing), catalog resolution
 * (exact and frozen), capability gating (paginate), argument-schema checks, bare-path scoping,
 * the grouped-result rule, traversal depth, and Assemble sink determination. Execution accepts
 * only this type.
 */
class ValidatedPlan(
    val plan: Plan,
    val resolutions: IdentityHashMap<Invocation, ResolvedOperation>,
    val sinks: IdentityHashMap<Assemble, String>, // sink binding when result is absent
    val warnings: List<String>,
)

class TowlValidator(
    private val registry: TowlRegistry,
    private val catalog: TowlCatalog,
    private val envNames: Set<String>,
) {
    fun validate(plan: Plan): ValidatedPlan {
        inputsMemo = plan.inputs.keys
        val diags = mutableListOf<Diagnostic>()
        val warnings = mutableListOf<String>()
        val resolutions = IdentityHashMap<Invocation, ResolvedOperation>()
        val sinks = IdentityHashMap<Assemble, String>()

        fun block(b: Block, names: Set<String>, depth: Int, where: String) {
            when (b) {
                is Traverse -> {
                    if (depth >= 2) diags += Diagnostic(where, "traversal nesting depth exceeds two; flatten or decompose")
                    if (b.elementName in names) diags += Diagnostic(where, "'${b.elementName}' shadows an enclosing name")
                    expr(b.from, names, implicitOk = false, diags, "$where.from")
                    if (!referencesName(b.body, b.elementName)) warnings += "$where: element variable '${b.elementName}' is never referenced"
                    block(b.body, names + b.elementName, depth + 1, "$where.${b.elementName}")
                }
                is Express -> {
                    when (val p = b.producer) {
                        is PCall -> invocation(p.invocation, names, diags, resolutions, where)
                        is PExpr -> expr(p.expr, names, implicitOk = false, diags, "$where.source")
                    }
                    b.filter?.let { expr(it, names, implicitOk = true, diags, "$where.filter") }
                    b.dedup?.forEachIndexed { i, e -> expr(e, names, implicitOk = true, diags, "$where.dedup[$i]") }
                    b.result?.let { result(it, names, diags, "$where.result", implicitAllowed = true) }
                    val streamy = b.filter != null || b.dedup != null || (b.result?.let(::hasAggLeaf) == true)
                    if (streamy && p2Cardinality(b, resolutions) == Cardinality.ONE)
                        diags += Diagnostic(where, "stream stages and aggregate results require a Many producer")
                }
                is Assemble -> {
                    for ((name, binding) in b.let) {
                        if (name in names) diags += Diagnostic("$where.let", "'$name' shadows an enclosing name")
                        block(binding, names + b.let.keys, depth, "$where.let.$name")
                    }
                    b.result?.let {
                        if (hasAggLeaf(it)) diags += Diagnostic("$where.result", "an Assemble block has no stream to fold")
                        result(it, names + b.let.keys, diags, "$where.result", implicitAllowed = false)
                    }
                    if (b.result == null) {
                        val consumed = b.let.keys.filter { n -> b.let.any { (o, blk) -> o != n && referencesName(blk, n) } }
                        val sinkCandidates = b.let.keys - consumed.toSet()
                        when {
                            sinkCandidates.size == 1 -> sinks[b] = sinkCandidates.first()
                            else -> diags += Diagnostic(where, "no unique sink among ${b.let.keys}; add an explicit result")
                        }
                    }
                }
            }
        }

        block(plan.block, plan.inputs.keys, 0, "plan")
        if (diags.isNotEmpty()) throw TowlException(diags)
        return ValidatedPlan(plan, resolutions, sinks, warnings)
    }

    private fun p2Cardinality(b: Express, resolutions: Map<Invocation, ResolvedOperation>): Cardinality =
        when (val p = b.producer) {
            is PCall -> resolutions[p.invocation]?.cardinality ?: Cardinality.MANY
            is PExpr -> Cardinality.MANY // expression streams are decided by the runtime value; checked at run time
        }

    private fun invocation(
        inv: Invocation,
        names: Set<String>,
        diags: MutableList<Diagnostic>,
        resolutions: IdentityHashMap<Invocation, ResolvedOperation>,
        where: String,
    ) {
        when (val outcome = catalog.resolve(inv.service, inv.operation)) {
            is Resolved -> {
                val op = outcome.op
                resolutions[inv] = op
                if (inv.paginate != null && !(op.paged && op.cardinality == Cardinality.MANY))
                    diags += Diagnostic(where, "'${inv.operation}' does not advertise paging; \"paginate\" is capability-gated")
                val declared = op.declaredArgs()
                if (declared != null) {
                    val unknown = inv.args.keys - declared
                    if (unknown.isNotEmpty()) diags += Diagnostic(where, "unknown argument(s) $unknown for '${inv.operation}'; declared: $declared")
                    val missing = op.requiredArgs() - inv.args.keys
                    if (missing.isNotEmpty()) diags += Diagnostic(where, "missing required argument(s) $missing for '${inv.operation}'")
                }
            }
            is Unknown -> diags += Diagnostic(where,
                "unknown operation '${inv.operation}'" + if (outcome.didYouMean.isEmpty()) "" else " (did you mean ${outcome.didYouMean}?)")
            is Ambiguous -> diags += Diagnostic(where, "'${inv.operation}' is ambiguous; qualify with one of ${outcome.qualifiers}")
        }
        // args are evaluated before the stream exists: never an implicit path inside
        fun iv(v: InputValue, w: String) {
            when (v) {
                is IExpr -> expr(v.expr, names, implicitOk = false, diags, w)
                is IList -> v.items.forEachIndexed { i, e -> iv(e, "$w[$i]") }
                is IMap -> v.members.forEach { (k, e) -> iv(e, "$w.$k") }
            }
        }
        inv.args.forEach { (k, v) -> iv(v, "$where.args.$k") }
    }

    private fun expr(e: Expr, names: Set<String>, implicitOk: Boolean, diags: MutableList<Diagnostic>, where: String) {
        when (e) {
            is Lit -> {}
            is ImplicitE -> if (!implicitOk)
                diags += Diagnostic(where, "bare path is legal only inside an Express block's stages and result")
            is RefE -> if (e.name !in names) diags += Diagnostic(where, "unknown name '${e.name}'")
            is InputE -> if (e.name !in namesOfInputs()) diags += Diagnostic(where, "undeclared input '${e.name}'")
            is EnvE -> if (e.name !in envNames) diags += Diagnostic(where, "unknown env value '${e.name}' (host context is a closed schema)")
            is ApplyE -> {
                val def = registry.functions[e.function]
                if (def == null) diags += Diagnostic(where, "unknown function '${e.function}'")
                else if (e.args.size < def.minArity || (def.maxArity != null && e.args.size > def.maxArity))
                    diags += Diagnostic(where, "'${e.function}' arity ${e.args.size} outside ${def.minArity}..${def.maxArity ?: "n"}")
                e.args.forEachIndexed { i, a -> expr(a, names, implicitOk, diags, "$where.${e.function}[$i]") }
            }
        }
    }

    private var inputsMemo: Set<String> = emptySet()
    private fun namesOfInputs(): Set<String> = inputsMemo

    private fun result(r: ResultNode, names: Set<String>, diags: MutableList<Diagnostic>, where: String, implicitAllowed: Boolean) {
        val grouped = hasAggLeaf(r)
        fun walk(n: ResultNode, w: String) {
            when (n) {
                is RExpr -> {
                    // grouping rule: beside an aggregate leaf, pure leaves must be element-independent
                    expr(n.expr, names, implicitOk = implicitAllowed && !grouped, diags, w)
                    if (grouped && containsImplicit(n.expr))
                        diags += Diagnostic(w, "element-dependent pure leaf beside an aggregate leaf (the SQL non-grouped-column rule)")
                }
                is RAgg -> agg(n.agg, names, diags, w)
                is RRecord -> n.members.forEach { (k, m) -> walk(m, "$w.$k") }
            }
        }
        walk(r, where)
    }

    private fun agg(a: AggApply, names: Set<String>, diags: MutableList<Diagnostic>, where: String) {
        val def = registry.aggregators[a.name]
        if (def == null) { diags += Diagnostic(where, "unknown aggregator '${a.name}'"); return }
        if (a.args.size != def.arity)
            diags += Diagnostic(where, "'${a.name}' takes ${def.arity} argument(s), found ${a.args.size}")
        a.args.forEachIndexed { i, arg ->
            when (arg) {
                is AExpr -> expr(arg.expr, names, implicitOk = true, diags, "$where.${a.name}[$i]")
                is AAgg, is AProduct -> diags += Diagnostic("$where.${a.name}[$i]",
                    "this registry's aggregators take expression arguments only")
            }
        }
    }

    private fun hasAggLeaf(r: ResultNode): Boolean = when (r) {
        is RExpr -> false
        is RAgg -> true
        is RRecord -> r.members.values.any(::hasAggLeaf)
    }

    private fun containsImplicit(e: Expr): Boolean = when (e) {
        is ImplicitE -> true
        is ApplyE -> e.args.any(::containsImplicit)
        else -> false
    }

    /** Reference scan used for sink determination, unused-variable warnings, and dependency order. */
    companion object {
        fun referencesName(b: Block, name: String): Boolean = refsIn(b).contains(name)

        fun refsIn(b: Block): Set<String> {
            val out = mutableSetOf<String>()
            fun ex(e: Expr) {
                when (e) {
                    is RefE -> out += e.name
                    is ApplyE -> e.args.forEach(::ex)
                    else -> {}
                }
            }
            fun iv(v: InputValue) {
                when (v) {
                    is IExpr -> ex(v.expr)
                    is IList -> v.items.forEach(::iv)
                    is IMap -> v.members.values.forEach(::iv)
                }
            }
            fun res(r: ResultNode) {
                when (r) {
                    is RExpr -> ex(r.expr)
                    is RAgg -> r.agg.args.forEach { if (it is AExpr) ex(it.expr) }
                    is RRecord -> r.members.values.forEach(::res)
                }
            }
            fun blk(x: Block) {
                when (x) {
                    is Traverse -> { ex(x.from); blk(x.body) }
                    is Express -> {
                        when (val p = x.producer) {
                            is PCall -> p.invocation.args.values.forEach(::iv)
                            is PExpr -> ex(p.expr)
                        }
                        x.filter?.let(::ex); x.dedup?.forEach(::ex); x.result?.let(::res)
                    }
                    is Assemble -> { x.let.values.forEach(::blk); x.result?.let(::res) }
                }
            }
            blk(b)
            return out
        }
    }
}
