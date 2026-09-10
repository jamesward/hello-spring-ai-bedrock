package com.example.demo.synth.towl

import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Deterministic executor for a [ValidatedPlan]. No LLM in this loop. Values are plain JSON
 * (null/Boolean/Number/String/List/Map); a MANY stream is a materialized ordered List.
 *
 * Semantics per TOWL_SPEC.md: eager execution of every declared binding; traversal outputs are
 * unwrapped under fail/skip and single-key {ok}/{error{item,code,message}} under collect; an
 * aggregate result leaf folds the staged stream; Express onError applies to the binding as a whole.
 */
class TowlInterpreter(
    private val registry: TowlRegistry,
    private val env: Map<String, Any?> = emptyMap(),
    private val maxConcurrency: Int = 6,
) {
    private val log = LoggerFactory.getLogger(TowlInterpreter::class.java)

    private inner class Scope(
        val values: Map<String, Any?>,
        val inputs: Map<String, Any?>,
        val implicit: Any?,
        val hasImplicit: Boolean,
    ) {
        fun with(name: String, value: Any?) = Scope(values + (name to value), inputs, implicit, hasImplicit)
        fun withImplicit(element: Any?) = Scope(values, inputs, element, true)
    }

    fun run(v: ValidatedPlan, inputValues: Map<String, Any?> = emptyMap()): Any? =
        execute(v, inputValues).result

    /** Runs the plan and returns the execution envelope: value + per-binding accounting. */
    fun execute(v: ValidatedPlan, inputValues: Map<String, Any?> = emptyMap()): ExecutionResult {
        val inputs = resolveInputs(v.plan, inputValues)
        val rec = Recorder()
        val result = evalBlock(v.plan.block, v, Scope(emptyMap(), inputs, null, false), rec, "")
        return ExecutionResult(
            status = if (rec.anyPartial()) "partial" else "ok",
            result = result,
            diagnostics = rec.snapshot(),
            warnings = v.warnings,
        )
    }

    private fun resolveInputs(plan: Plan, given: Map<String, Any?>): Map<String, Any?> {
        val unknown = given.keys - plan.inputs.keys
        if (unknown.isNotEmpty()) fail("inputs", "undeclared input value(s) $unknown")
        return plan.inputs.entries.associate { (name, decl) ->
            name to when {
                name in given -> given[name]
                decl.hasDefault -> decl.default
                else -> fail("inputs", "required input '$name' was not supplied")
            }
        }
    }

    // ── blocks ──────────────────────────────────────────────────────────────

    private fun evalBlock(b: Block, v: ValidatedPlan, scope: Scope, rec: Recorder, where: String): Any? = when (b) {
        is Assemble -> evalAssemble(b, v, scope, rec, where)
        is Express -> evalExpress(b, v, scope, rec, where)
        is Traverse -> evalTraverse(b, v, scope, rec, where)
    }

    private fun evalAssemble(b: Assemble, v: ValidatedPlan, scope: Scope, rec: Recorder, where: String): Any? {
        // dependency order among siblings; every declared binding executes (result is not a reachability root)
        val order = topo(b)
        var s = scope
        for (name in order)
            s = s.with(name, evalBlock(b.let.getValue(name), v, s, rec, if (where.isEmpty()) name else "$where.$name"))
        return when {
            b.result != null -> evalResultPure(b.result, s)
            else -> s.values.getValue(v.sinks[b] ?: fail("assemble", "no sink recorded"))
        }
    }

    private fun topo(b: Assemble): List<String> {
        val deps = b.let.mapValues { (_, blk) -> TowlValidator.refsIn(blk).intersect(b.let.keys) }
        val done = LinkedHashSet<String>()
        fun visit(n: String, stack: Set<String>) {
            if (n in done) return
            if (n in stack) fail("let", "circular dependency involving '$n'")
            deps.getValue(n).forEach { visit(it, stack + n) }
            done += n
        }
        b.let.keys.forEach { visit(it, emptySet()) }
        return done.toList()
    }

    private fun evalExpress(b: Express, v: ValidatedPlan, scope: Scope, rec: Recorder, where: String): Any? {
        val produced = runCatching { produce(b.producer, v, scope, rec, where) }
            .getOrElse { e ->
                if (b.onError == OnError.SKIP) return null.also {
                    rec.bump(where, "sourceFailures")
                    log.info("[towl] source failed, onError=skip: {}", e.message)
                }
                throw e
            }
        return when (produced) {
            is Stream -> {
                var elements = produced.elements
                rec.bump(where, "streamed", elements.size.toLong())
                log.info("[towl] source streamed {} element(s)", elements.size)
                b.filter?.let { f ->
                    val before = elements.size
                    elements = elements.filter { el -> evalExpr(f, scope.withImplicit(el)) == true }
                    rec.bump(where, "filterKept", elements.size.toLong())
                    rec.bump(where, "filterDropped", (before - elements.size).toLong())
                    log.info("[towl] filter kept {}/{} element(s){}", elements.size, before,
                        if (elements.isEmpty() && before > 0) " — EMPTY: the filter matched nothing" else "")
                }
                b.dedup?.let { keys ->
                    val seen = HashSet<List<Any?>>()
                    val before = elements.size
                    elements = elements.filter { el -> seen.add(keys.map { k -> evalExpr(k, scope.withImplicit(el)) }) }
                    rec.bump(where, "dedupKept", elements.size.toLong())
                    rec.bump(where, "dedupDropped", (before - elements.size).toLong())
                    log.info("[towl] dedup kept {}/{} element(s)", elements.size, before)
                }
                when {
                    b.result == null -> elements
                    hasAggLeaf(b.result) -> foldResult(b.result, elements, scope)
                    else -> elements.map { el -> evalResultMapped(b.result, scope.withImplicit(el)) }
                }
            }
            is Single -> {
                if (b.result == null) produced.value
                else evalResultMapped(b.result, scope.withImplicit(produced.value))
            }
        }
    }

    private sealed interface Produced
    private data class Stream(val elements: List<Any?>) : Produced
    private data class Single(val value: Any?) : Produced

    private fun produce(p: Producer, v: ValidatedPlan, scope: Scope, rec: Recorder, where: String): Produced = when (p) {
        is PExpr -> when (val value = evalExpr(p.expr, scope)) {
            is List<*> -> Stream(value)
            else -> Single(value)
        }
        is PCall -> {
            val op = v.resolutions[p.invocation] ?: fail("call", "unresolved invocation ${p.invocation}")
            rec.set(where, "operation", op.id)
            rec.bump(where, "calls")
            val args = p.invocation.args.mapValues { (_, iv) -> evalInputValue(iv, scope) }
            log.info("[towl] call -> {} args={}", op.id, truncate(args.toString()))
            val raw = op.invoke(args)
            log.info("[towl] call <- {}", op.id)
            when (op.cardinality) {
                Cardinality.MANY -> {
                    val subject = op.subjectPath?.navigate(raw)
                    Stream(subject as? List<Any?> ?: fail(op.id, "result subject is not a list"))
                }
                Cardinality.OPTIONAL, Cardinality.ONE -> Single(raw)
            }
        }
    }

    private fun evalTraverse(b: Traverse, v: ValidatedPlan, scope: Scope, rec: Recorder, where: String): Any? {
        val from = evalExpr(b.from, scope)
        val items = from as? List<*> ?: fail("forEach", "\"from\" must produce a list; got ${from?.javaClass?.simpleName}")
        rec.bump(where, "elements", items.size.toLong())
        log.info("[towl] forEach '{}' over {} element(s) (maxConcurrency={})", b.elementName, items.size, maxConcurrency)
        if (items.isEmpty()) return emptyList<Any?>()
        val bodyPath = if (where.isEmpty()) b.elementName else "$where.${b.elementName}"

        val pool = Executors.newFixedThreadPool(maxConcurrency.coerceAtLeast(1))
        try {
            val tasks = items.map { item ->
                Callable<Pair<Any?, Throwable?>> {
                    runCatching { evalBlock(b.body, v, scope.with(b.elementName, item), rec, bodyPath) }
                        .fold({ it to null }, { e -> null to e })
                }
            }
            val outcomes = pool.invokeAll(tasks).map { it.get() } // input order preserved
            outcomes.forEach { (_, e) -> if (e == null) rec.bump(where, "ok") }
            return when (b.onError) {
                OnError.FAIL -> outcomes.map { (value, e) -> if (e != null) throw e else value }
                OnError.SKIP -> outcomes.mapNotNull { (value, e) -> if (e != null) { rec.bump(where, "skipped"); null } else value }
                OnError.COLLECT -> outcomes.mapIndexed { i, (value, e) ->
                    if (e != null) rec.bump(where, "failed")
                    if (e == null) mapOf("ok" to value)
                    else mapOf("error" to mapOf(
                        "item" to items[i],
                        "code" to (e as? TowlException)?.let { "towl" } .let { it ?: e.javaClass.simpleName },
                        "message" to (e.message ?: e.toString()),
                    ))
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    // ── results and aggregation ─────────────────────────────────────────────

    private fun hasAggLeaf(r: ResultNode): Boolean = when (r) {
        is RExpr -> false
        is RAgg -> true
        is RRecord -> r.members.values.any(::hasAggLeaf)
    }

    private fun evalResultPure(r: ResultNode, scope: Scope): Any? = when (r) {
        is RExpr -> evalExpr(r.expr, scope)
        is RAgg -> fail("result", "aggregate leaf outside a stream")
        is RRecord -> r.members.mapValues { (_, m) -> evalResultPure(m, scope) }
    }

    private fun evalResultMapped(r: ResultNode, scope: Scope): Any? = evalResultPure(r, scope)

    /** Grouped result: aggregate leaves fold the stream; pure leaves are element-independent. */
    private fun foldResult(r: ResultNode, elements: List<Any?>, scope: Scope): Any? = when (r) {
        is RExpr -> evalExpr(r.expr, scope)
        is RAgg -> foldAgg(r.agg, elements, scope)
        is RRecord -> r.members.mapValues { (_, m) -> foldResult(m, elements, scope) }
    }

    private fun foldAgg(a: AggApply, elements: List<Any?>, scope: Scope): Any? {
        val def = registry.aggregators.getValue(a.name)
        var acc = def.identity
        for (el in elements) {
            val v = if (def.arity == 1) evalExpr((a.args[0] as AExpr).expr, scope.withImplicit(el)) else null
            acc = def.combine(acc, def.prepare(v))
        }
        return def.present(acc)
    }

    // ── expressions ─────────────────────────────────────────────────────────

    private fun evalInputValue(v: InputValue, scope: Scope): Any? = when (v) {
        is IExpr -> evalExpr(v.expr, scope)
        is IList -> v.items.map { evalInputValue(it, scope) }
        is IMap -> v.members.mapValues { (_, m) -> evalInputValue(m, scope) }
    }

    private fun evalExpr(e: Expr, scope: Scope): Any? = when (e) {
        is Lit -> e.value
        is RefE -> e.path.navigate(scope.values.getOrElse(e.name) { fail("ref", "unknown name '${e.name}'") })
        is ImplicitE -> {
            if (!scope.hasImplicit) fail("path", "no implicit element in scope")
            e.path.navigate(scope.implicit)
        }
        is InputE -> e.path.navigate(scope.inputs.getOrElse(e.name) { fail("input", "undeclared input '${e.name}'") })
        is EnvE -> e.path.navigate(env.getOrElse(e.name) { fail("env", "unknown env value '${e.name}'") })
        is ApplyE -> registry.functions.getValue(e.function).evaluate(e.args.map { evalExpr(it, scope) })
    }

    private fun truncate(s: String, max: Int = 200): String =
        if (s.length <= max) s else s.take(max) + "…[${s.length} chars]"
}
