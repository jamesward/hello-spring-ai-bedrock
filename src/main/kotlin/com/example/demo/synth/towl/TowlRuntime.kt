package com.example.demo.synth.towl

import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * TOWL v3 runtime (TOWL_SPEC.md §§12–13). Values are plain JSON (null/Boolean/Number/String/List/
 * Map). Top-level bindings run as soon as their dependencies are values; `for` bodies with calls
 * run as a wave. Every error stops the program except a declared `tolerate` code, and a stopped
 * run returns the failure envelope with only complete values.
 */
class Runtime(
    private val catalog: TowlCatalog,
    private val maxConcurrency: Int = 6,
    private val maxWidth: Int = 200,
    private val maxCalls: Int = 500,
    private val maxResultBytes: Int = 256 * 1024,
    private val stopGraceMs: Long = 5_000,
) {
    private val log = LoggerFactory.getLogger(Runtime::class.java)
    private val mapper = JsonMapper.builder().build()

    class Stop(val cls: String, val code: String, message: String, val op: OperationSpec?, val pos: Pos?, val element: Any? = null, val hasElement: Boolean = false) :
        RuntimeException(message)

    private inner class Run(val c: Checked, val inputs: Map<String, Any?>) {
        val pool: ExecutorService = Executors.newCachedThreadPool()
        val calls = Semaphore(maxConcurrency.coerceAtLeast(1))
        val stopped = AtomicBoolean(false)
        /** every failure, in arrival order; the primary is chosen deterministically at the end (§12.4) */
        val failures = java.util.concurrent.CopyOnWriteArrayList<Stop>()
        val callCount = AtomicInteger(0)
        val inFlightMutations = AtomicInteger(0)
        val nodes = ConcurrentHashMap<String, MutableMap<String, Any?>>()
        val effects = ConcurrentHashMap<String, MutableMap<String, Any?>>()
        val tolerated = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        val mutations = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        val fanouts = ConcurrentHashMap<String, Map<String, Any?>>()
        val completedBindings = ConcurrentHashMap<String, Any?>()
        val waves = AtomicInteger(0)
        @Volatile var resultBytes = 0

        fun fail(s: Stop): Nothing {
            failures += s
            stopped.set(true)
            throw s
        }

        /** §12.4: smallest (line, col), then smallest canonical element. */
        fun primary(): Stop? = failures.filter { it.cls != "cancelled" }.ifEmpty { failures }
            .minWithOrNull(compareBy<Stop>({ it.pos?.line ?: Int.MAX_VALUE }, { it.pos?.col ?: Int.MAX_VALUE }, { if (it.hasElement) canonical(it.element) else "" }))

        fun node(kind: String, pos: Pos): MutableMap<String, Any?> =
            nodes.computeIfAbsent("$kind@${pos.line}:${pos.col}") { ConcurrentHashMap(mapOf("node" to kind, "line" to pos.line)) }

        fun bump(m: MutableMap<String, Any?>, key: String, by: Long = 1) {
            synchronized(m) { m[key] = ((m[key] as? Number)?.toLong() ?: 0L) + by }
        }
    }

    fun execute(c: Checked, given: Map<String, Any?>): Map<String, Any?> {
        val inputs = bindInputs(c, given) ?: return inputFailure(c, given)
        val run = Run(c, inputs)
        val started = System.nanoTime()
        val outcome = runCatching { topLevel(run) }
        run.pool.shutdown()
        // in-flight reads get the grace period; in-flight mutations are always awaited (§12.4)
        run.pool.awaitTermination(stopGraceMs, TimeUnit.MILLISECONDS)
        val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
        while (run.inFlightMutations.get() > 0 && System.nanoTime() < deadline) Thread.sleep(20)
        val ms = (System.nanoTime() - started) / 1_000_000
        var stop = run.primary()
        var value: Any? = null
        if (stop == null && outcome.isSuccess) {
            value = normalizeOrder(outcome.getOrNull())
            val bytes = canonical(value).toByteArray().size
            run.resultBytes = bytes
            if (bytes > maxResultBytes) stop = Stop("budget", "MaxResultBytes",
                "the result is $bytes bytes, over the limit of $maxResultBytes; project fewer fields or narrow the list before returning", null, c.program.result.pos)
        }
        return if (stop == null && outcome.isSuccess) success(run, value, ms)
        else failure(run, stop ?: outcome.exceptionOrNull().let { x -> Stop("other", x?.javaClass?.simpleName ?: "Unexpected", x?.message ?: x?.toString() ?: "unknown failure", null, null) }, ms)
    }

    // ── inputs ──────────────────────────────────────────────────────────────

    private fun bindInputs(c: Checked, given: Map<String, Any?>): Map<String, Any?>? {
        val declared = c.program.inputs.associateBy { it.name }
        val unknown = given.keys - declared.keys - c.implicitInputs.toSet()
        if (unknown.isNotEmpty()) return null
        val bound = HashMap(given)
        // predefined inputs (TOWL §5): bound by the runtime when the program uses them (declared or not) and the caller did not
        val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val usesNow = "now" in c.implicitInputs || declared["now"]?.type == TTimestamp
        val usesToday = "today" in c.implicitInputs || declared["today"]?.type == TString
        if ("now" !in bound && usesNow) bound["now"] = now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", "Z")
        if ("today" !in bound && usesToday) bound["today"] = now.toLocalDate().toString()
        for (d in declared.values) if (d.name !in bound || !Types.conforms(bound[d.name], d.type)) return null
        return bound
    }

    private fun inputFailure(c: Checked, given: Map<String, Any?>): Map<String, Any?> {
        val declared = c.program.inputs.associateBy { it.name }
        val problems = (given.keys - declared.keys).map { "input '$it' is not declared" } +
            declared.values.filter { it.name !in given }.map { "input '${it.name}' (${it.type}) was not provided" } +
            declared.values.filter { it.name in given && !Types.conforms(given[it.name], it.type) }.map { "input '${it.name}' does not match ${it.type}" }
        return linkedMapOf(
            "status" to "error",
            "error" to linkedMapOf("class" to "input", "code" to "Input", "message" to problems.joinToString("; "), "action" to "rewrite"),
            "completed" to emptyMap<String, Any?>(), "fanout" to emptyList<Any?>(), "mutations" to emptyList<Any?>(),
            "accounting" to linkedMapOf("calls" to 0, "waves" to 0, "wall_ms" to 0),
        )
    }

    // ── top level: dependency scheduling ────────────────────────────────────

    private fun topLevel(run: Run): Any? {
        val p = run.c.program
        val names = p.bindings.map { it.name }.toSet()
        val deps = p.bindings.associate { b -> b.name to (freeRefs(b.expr) intersect names) }
        val futures = HashMap<String, Future<Any?>>()
        val env = HashMap<String, Any?>(run.inputs)
        for (b in p.bindings) {
            futures[b.name] = run.pool.submit(Callable<Any?> {
                val scope = HashMap<String, Any?>(run.inputs)
                for (d in deps.getValue(b.name)) scope[d] = futures.getValue(d).get()
                if (run.stopped.get()) throw Stop("cancelled", "Stopped", "stopped before '${b.name}' started", null, b.pos)
                val v = eval(b.expr, Env(scope, null, false), run)
                run.completedBindings[b.name] = v
                v
            })
        }
        for (b in p.bindings) env[b.name] = await(futures.getValue(b.name))
        return eval(p.result, Env(env, null, false), run)
    }

    private fun await(f: Future<Any?>): Any? = try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }

    private fun freeRefs(e: Expr): Set<String> {
        val out = HashSet<String>()
        fun predExprs(p: Pred): List<Expr> = when (p) {
            is Cmp -> listOf(p.left, p.right); is InP -> listOf(p.left, p.right)
            is AndP -> p.terms.flatMap(::predExprs); is OrP -> p.terms.flatMap(::predExprs); is NotP -> predExprs(p.term)
            is TestP -> listOfNotNull(p.operand, p.arg); is QuantP -> listOf(p.operand) + predExprs(p.inner)
        }
        fun walk(x: Expr, bound: Set<String>) {
            when (x) {
                is Ref -> if (x.name !in bound) out += x.name
                is Member -> walk(x.target, bound)
                is RecordE -> x.fields.forEach { walk(it.second, bound) }
                is ListE -> x.items.forEach { walk(it, bound) }
                is BlockE -> { var b = bound; x.bindings.forEach { walk(it.expr, b); b = b + it.name }; walk(x.result, b) }
                is OpCall -> { walk(x.params, bound); x.options?.let { walk(it, bound) } }
                is MethodCall -> { walk(x.target, bound); x.args.forEach { a -> when (a) {
                    is ExprArg -> walk(a.expr, bound); is LambdaArg -> walk(a.body, bound + a.param)
                    is PredArg -> predExprs(a.pred).forEach { walk(it, bound) } } } }
                is Lit, is Implicit -> Unit
            }
        }
        walk(e, emptySet())
        return out
    }

    // ── environment ─────────────────────────────────────────────────────────

    private class Env(val names: Map<String, Any?>, val implicit: Any?, val hasImplicit: Boolean, val eachElement: Any? = null, val inEach: Boolean = false) {
        fun bind(n: String, v: Any?) = Env(names + (n to v), implicit, hasImplicit, eachElement, inEach)
        fun element(v: Any?) = Env(names, v, true, eachElement, inEach)
        fun forElement(n: String, v: Any?) = Env(names + (n to v), implicit, hasImplicit, v, true)
    }

    // ── evaluation ──────────────────────────────────────────────────────────

    private fun eval(e: Expr, env: Env, run: Run): Any? = when (e) {
        is Lit -> e.value
        is Ref -> env.names[e.name]
        is Implicit -> env.implicit
        is RecordE -> LinkedHashMap<String, Any?>().also { m -> e.fields.forEach { (k, v) -> m[k] = eval(v, env, run) } }
        is ListE -> e.items.map { eval(it, env, run) }
        is BlockE -> { var inner = env; e.bindings.forEach { inner = inner.bind(it.name, eval(it.expr, inner, run)) }; eval(e.result, inner, run) }
        is Member -> {
            val t = eval(e.target, env, run)
            if (t == null) null else (t as? Map<*, *>)?.get(e.name).let { v -> Types.normalize(v, run.c.types[e] ?: TJson) }
        }
        is OpCall -> call(e, env, run)
        is MethodCall -> method(e, env, run)
    }

    private fun call(e: OpCall, env: Env, run: Run): Any? {
        val op = run.c.ops.getValue(e)
        val site = run.c.effects.first { it.call === e }
        val params = eval(e.params, env, run)
        @Suppress("UNCHECKED_CAST") val args = (params as? Map<String, Any?>) ?: emptyMap()
        op.input?.fields?.forEach { (k, t) ->
            if (!Types.isNullable(t) && t !is TList && args.containsKey(k) && args[k] == null)
                run.fail(Stop("data", "NullParameter", "parameter '$k' of ${op.id} was Null at runtime", op, e.pos, env.eachElement, env.inEach))
            if (args[k] != null) Types.nullAtRequired(args[k], t, k)?.let { bad ->
                run.fail(Stop("data", "NullParameter", "parameter '$bad' of ${op.id} was Null at runtime", op, e.pos, env.eachElement, env.inEach))
            }
        }
        if (run.stopped.get()) throw Stop("cancelled", "Stopped", "stopped before ${op.id} was dispatched", op, e.pos)
        if (run.callCount.incrementAndGet() > maxCalls) run.fail(Stop("budget", "MaxCalls", "call budget of $maxCalls exceeded at ${op.id}", op, e.pos))
        val eff = run.effects.computeIfAbsent("${op.id}@${e.pos.line}") { ConcurrentHashMap(mapOf("line" to e.pos.line, "operation" to op.id, "effect" to op.effect.name.lowercase())) }
        run.bump(eff, "calls")
        run.calls.acquire()
        val mutate = op.effect != Effect.READ
        val raw = try {
            // a call that waited for a permit must not dispatch after a stop
            if (run.stopped.get()) throw Stop("cancelled", "Stopped", "stopped before ${op.id} was dispatched", op, e.pos)
            if (mutate) run.inFlightMutations.incrementAndGet()
            log.info("[towl] call -> {} args={}", op.id, truncate(args.toString())) // logged only once the permit is held
            op.invoke(args.filterValues { it != null })
        } catch (x: OperationError) {
            if (x.code in site.tolerate) {
                run.tolerated += linkedMapOf("line" to e.pos.line, "operation" to op.id, "code" to x.code, "element" to env.eachElement)
                log.info("[towl] call <- {} tolerated {}", op.id, x.code)
                return null
            }
            val cls = if (mutate) "mutation" else catalog.errorClass(op, x.code).name.lowercase()
            run.fail(Stop(cls, x.code, x.message ?: x.code, op, e.pos, env.eachElement, env.inEach))
        } catch (x: Stop) { throw x } catch (x: Exception) {
            run.fail(Stop(if (mutate) "mutation" else "other", x.javaClass.simpleName, x.message ?: x.toString(), op, e.pos, env.eachElement, env.inEach))
        } finally {
            if (mutate) run.inFlightMutations.updateAndGet { if (it > 0) it - 1 else 0 }
            run.calls.release()
        }
        log.info("[towl] call <- {}", op.id)
        if (mutate) run.mutations += linkedMapOf("line" to e.pos.line, "operation" to op.id, "element" to env.eachElement,
            "params" to args, "options" to linkedMapOf("tolerate" to site.tolerate), "response" to raw)
        return Types.normalize(raw, op.output)
    }

    private fun method(e: MethodCall, env: Env, run: Run): Any? {
        val target = eval(e.target, env, run)
        val name = e.name
        if (name == "for") return forExpr(e, target, env, run)
        if (name in Stdlib.STR) return stringFn(name, target as? String, e, env, run)
        if (name in Stdlib.TIME) return timeFn(name, target as? String, e, env, run)
        if (target == null && name in setOf("count")) return 0
        val list = (target as? List<*>) ?: emptyList<Any?>()
        val n = run.node(name, e.pos)
        run.bump(n, "in", list.size.toLong())
        fun path(i: Int) = (e.args[i] as ExprArg).expr
        fun pathOf(el: Any?, i: Int = 0) = if (i >= e.args.size) el else eval(path(i), env.element(el), run) // no path: a list of scalars
        fun pred(el: Any?, i: Int = 0) = predicate((e.args[i] as PredArg).pred, env.element(el), run)
        val out: Any? = when (name) {
            "project" -> list.map { pathOf(it) }
            "flat" -> list.flatMap { (pathOf(it) as? List<*>) ?: emptyList<Any?>() }
            "flatten" -> list.flatMap { (it as? List<*>) ?: emptyList<Any?>() }
            "where" -> list.filter { pred(it) }
            "compact" -> list.filterNotNull()
            "distinct" -> if (e.args.isEmpty()) list.distinctBy(::canonical) else list.map { pathOf(it) }.distinctBy(::canonical)
            "concat" -> list + ((eval((e.args[0] as ExprArg).expr, env, run) as? List<*>) ?: emptyList<Any?>())
            "group" -> list.groupBy { canonical(pathOf(it)) }.values.map { g -> linkedMapOf("key" to pathOf(g[0]), "items" to g) }
            "single" -> when (list.size) { 0 -> null; 1 -> list[0]; else -> run.fail(Stop("cardinality", "NotSingle", "single() found ${list.size} elements", null, e.pos)) }
            "count" -> list.size
            "sum" -> list.mapNotNull { pathOf(it) as? Number }.let { ns -> if (ns.all { it is Int || it is Long }) ns.sumOf { it.toLong() }.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it } else ns.sumOf { it.toDouble() } }
            "min" -> list.mapNotNull { pathOf(it) }.minWithOrNull(::compareValues)
            "max" -> list.mapNotNull { pathOf(it) }.maxWithOrNull(::compareValues)
            "avg" -> list.mapNotNull { pathOf(it) as? Number }.let { ns -> if (ns.isEmpty()) null else ns.sumOf { it.toDouble() } / ns.size }
            "collect" -> list.mapNotNull { pathOf(it) }
            "top", "bottom" -> {
                val count = (eval(path(0), env, run) as? Number)?.toInt() ?: 0
                val keyed = list.map { (if (e.args.size > 1) pathOf(it, 1) else it) to it }.filter { it.first != null }
                val byKey = Comparator<Pair<Any?, Any?>> { a, b -> compareValues(a.first, b.first) }.let { if (name == "top") it.reversed() else it }
                keyed.sortedWith(byKey.thenBy { canonical(it.second) }) // ties broken by canonical element order
                    .take(count).mapIndexed { i, (_, el) -> linkedMapOf("rank" to i + 1, "value" to el) }
            }
            "any" -> list.any { pred(it) }
            "all" -> list.all { pred(it) }
            else -> run.fail(Stop("other", "Internal", "unknown function $name", null, e.pos))
        }
        if (out is List<*>) run.bump(n, "out", out.size.toLong()) else if (out != null) n["out"] = if (out is Boolean || out is Number) out else 1
        return out
    }

    private fun stringFn(name: String, s: String?, e: MethodCall, env: Env, run: Run): String? {
        if (s == null) return null
        fun arg() = eval((e.args[0] as ExprArg).expr, env, run) as? String ?: ""
        return when (name) {
            "after_last" -> { val sep = arg(); val i = s.lastIndexOf(sep); if (i < 0 || sep.isEmpty()) s else s.substring(i + sep.length) }
            "before_first" -> { val sep = arg(); val i = s.indexOf(sep); if (i < 0 || sep.isEmpty()) s else s.substring(0, i) }
            "lower" -> s.lowercase()
            "upper" -> s.uppercase()
            else -> s
        }
    }

    private fun timeFn(name: String, s: String?, e: MethodCall, env: Env, run: Run): String? {
        if (s == null) return null
        val t = try { java.time.OffsetDateTime.parse(s).withOffsetSameInstant(java.time.ZoneOffset.UTC) } catch (x: java.time.format.DateTimeParseException) { return null }
        val n = (e.args.firstOrNull()?.let { eval((it as ExprArg).expr, env, run) } as? Number)?.toLong() ?: 0L
        val r = when (name) {
            "minus_days" -> t.minusDays(n)
            "minus_hours" -> t.minusHours(n)
            "minus_minutes" -> t.minusMinutes(n)
            "start_of_day" -> t.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
            "start_of_month" -> t.withDayOfMonth(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
            "date" -> return t.toLocalDate().toString()
            else -> t
        }
        return r.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME).replace("+00:00", "Z")
    }

    // ── for: waves ──────────────────────────────────────────────────────────

    private fun forExpr(e: MethodCall, target: Any?, env: Env, run: Run): Any? {
        val lam = e.args[0] as LambdaArg
        val items = (target as? List<*>) ?: emptyList<Any?>()
        val isWave = e in run.c.waves
        val n = run.node("for", e.pos)
        run.bump(n, "in", items.size.toLong())
        if (!isWave) {
            val out = items.map { eval(lam.body, env.forElement(lam.param, it), run) }
            run.bump(n, "out", out.size.toLong()); return out
        }
        if (items.size > maxWidth) run.fail(Stop("budget", "MaxWidth", "for over ${items.size} elements exceeds the width limit of $maxWidth", null, e.pos))
        if (run.stopped.get()) throw Stop("cancelled", "Stopped", "stopped before wave started", null, e.pos)
        run.waves.incrementAndGet()
        log.info("[towl] for over {} element(s), concurrency {}", items.size, maxConcurrency)
        val started = Array(items.size) { false }
        val results = arrayOfNulls<Any?>(items.size)
        val done = Array(items.size) { false }
        val failures = arrayOfNulls<Stop>(items.size)
        val futures = items.mapIndexed { i, item ->
            run.pool.submit {
                if (run.stopped.get()) return@submit
                started[i] = true
                try { results[i] = eval(lam.body, env.forElement(lam.param, item), run); done[i] = true } catch (s: Stop) { failures[i] = s }
            }
        }
        futures.forEach { runCatching { it.get() } }
        val firstFail = failures.indexOfFirst { it != null }
        if (firstFail >= 0) {
            // an element whose body was stopped mid-way (its later call was never dispatched) has no
            // failure of its own: it is interrupted, not failed
            val own = items.indices.filter { failures[it] != null && failures[it]!!.cls != "cancelled" }
            run.fanouts[e.pos.toString()] = linkedMapOf(
                "line" to e.pos.line,
                "completed" to items.indices.filter { done[it] }.map { linkedMapOf("element" to items[it], "value" to results[it]) },
                "failed" to own.map { linkedMapOf("element" to items[it], "code" to failures[it]!!.code, "message" to failures[it]!!.message) },
                "interrupted" to items.indices.filter { started[it] && !done[it] && it !in own }.map { items[it] },
                "not_started" to items.indices.filter { !started[it] }.map { items[it] },
            )
            val f = failures[own.firstOrNull() ?: firstFail]!!
            throw f
        }
        if (run.stopped.get()) throw Stop("cancelled", "Stopped", "stopped during wave", null, e.pos)
        run.bump(n, "out", items.size.toLong())
        return results.toList()
    }

    // ── predicates ──────────────────────────────────────────────────────────

    private fun predicate(pr: Pred, env: Env, run: Run): Boolean = when (pr) {
        is AndP -> pr.terms.all { predicate(it, env, run) }
        is OrP -> pr.terms.any { predicate(it, env, run) }
        is NotP -> !predicate(pr.term, env, run)
        is Cmp -> {
            val l = eval(pr.left, env, run); val r = eval(pr.right, env, run)
            when (pr.op) {
                "==" -> eq(l, r); "!=" -> !eq(l, r)
                else -> if (l == null || r == null) false else { val c = compareValues(l, r); when (pr.op) { "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; else -> c >= 0 } }
            }
        }
        is InP -> { val l = eval(pr.left, env, run); ((eval(pr.right, env, run) as? List<*>) ?: emptyList<Any?>()).any { eq(it, l) } }
        is TestP -> {
            val v = eval(pr.operand, env, run)
            when (pr.fn) {
                "present" -> v != null; "absent" -> v == null; "empty" -> (v as? List<*>)?.isEmpty() ?: true
                else -> { val s = v as? String; val a = pr.arg?.let { eval(it, env, run) as? String } ?: ""
                    s != null && when (pr.fn) { "contains" -> s.contains(a); "starts_with" -> s.startsWith(a); else -> s.endsWith(a) } }
            }
        }
        is QuantP -> {
            val l = (eval(pr.operand, env, run) as? List<*>) ?: emptyList<Any?>()
            if (pr.all) l.all { predicate(pr.inner, env.element(it), run) } else l.any { predicate(pr.inner, env.element(it), run) }
        }
    }

    private fun eq(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        a is List<*> && b is List<*> -> a.size == b.size && a.map(::canonical).sorted() == b.map(::canonical).sorted()
        a is Map<*, *> && b is Map<*, *> -> canonical(a) == canonical(b)
        else -> a == b
    }

    private fun compareValues(a: Any?, b: Any?): Int = when {
        a is Number && b is Number -> a.toDouble().compareTo(b.toDouble())
        a is String && b is String -> a.compareTo(b)
        else -> canonical(a).compareTo(canonical(b))
    }

    // ── canonical form and envelopes ────────────────────────────────────────

    private fun canonical(v: Any?): String = mapper.writeValueAsString(sortKeys(v))

    private fun sortKeys(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.sortedBy { it.key.toString() }.associate { it.key.toString() to sortKeys(it.value) }
        is List<*> -> v.map(::sortKeys)
        else -> v
    }

    /** Lists are bags: serialize elements in canonical order so results are scheduling-independent. */
    private fun normalizeOrder(v: Any?): Any? = when (v) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> v.forEach { (k, x) -> m[k.toString()] = normalizeOrder(x) } }
        is List<*> -> v.map(::normalizeOrder).sortedBy(::canonical)
        else -> v
    }

    private fun success(run: Run, value: Any?, ms: Long): Map<String, Any?> = linkedMapOf(
        "status" to "ok",
        "type" to run.c.resultType.toString(),
        "value" to value, // already normalized
        "tolerated" to run.tolerated.sortedBy { it["line"] as Int },
        "effects" to run.effects.values.sortedBy { it["line"] as Int },
        "nodes" to run.nodes.values.sortedBy { it["line"] as Int },
        "accounting" to linkedMapOf("calls" to run.callCount.get(), "waves" to run.waves.get(), "wall_ms" to ms, "result_bytes" to run.resultBytes,
            "tolerated" to run.tolerated.size),
    )

    private fun failure(run: Run, s: Stop, ms: Long): Map<String, Any?> = linkedMapOf(
        "status" to "error",
        "error" to linkedMapOf(
            "class" to s.cls, "code" to s.code, "message" to s.message, "operation" to s.op?.id,
            "line" to s.pos?.line, "element" to (if (s.hasElement) s.element else null), "action" to action(s.cls),
        ),
        "errors_also" to run.failures.filter { it !== s && it.cls != "cancelled" }.map { linkedMapOf("class" to it.cls, "code" to it.code, "message" to it.message, "operation" to it.op?.id, "line" to it.pos?.line, "element" to (if (it.hasElement) it.element else null)) },
        "completed" to run.completedBindings.entries.sortedBy { it.key }.associate { (k, v) -> k to linkedMapOf("type" to run.c.bindingTypes[k].toString(), "value" to normalizeOrder(v)) },
        "fanout" to run.fanouts.values.sortedBy { it["line"] as Int },
        "mutations" to run.mutations.toList(),
        "accounting" to linkedMapOf("calls" to run.callCount.get(), "waves" to run.waves.get(), "wall_ms" to ms),
    )

    private fun action(cls: String) = when (cls) {
        "transient", "cancelled" -> "rerun"
        "authorization", "availability", "absence" -> "tolerate-candidate"
        "budget" -> "budget"
        "mutation" -> "mutation"
        else -> "rewrite"
    }

    private fun truncate(s: String, max: Int = 200) = if (s.length <= max) s else s.take(max) + "…[${s.length} chars]"
}
