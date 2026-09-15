package com.example.demo.synth.towl

/**
 * TOWL v3 static semantics (TOWL_SPEC.md §11): names, catalog, types, effects. Every expression
 * gets exactly one type or a diagnostic; all diagnostics are collected in one pass so an author
 * fixes everything in one turn. Nothing here invokes an operation.
 */

class EffectSite(
    val op: OperationSpec,
    val call: OpCall,
    val tolerate: List<String>,
    /** wave multiplicity: a number when static, null when dynamic */
    val staticWidth: Int?,
    val depth: Int,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "line" to call.pos.line, "operation" to op.id, "effect" to op.effect.name.lowercase(),
        "tolerate" to tolerate, "multiplicity" to (staticWidth ?: "dynamic"), "depth" to depth,
    )
}

class Checked(
    val program: Program,
    val source: String,
    val resultType: Type,
    val bindingTypes: Map<String, Type>,
    /** types of every expression node, by identity */
    val types: Map<Expr, Type>,
    val effects: List<EffectSite>,
    val ops: Map<OpCall, OperationSpec>,
    /** for sites whose body contains operation calls (waves) */
    val waves: Map<MethodCall, Int?>,
    val warnings: List<Diagnostic>,
    /** predefined names the program uses without declaring (now, today) */
    val implicitInputs: List<String> = emptyList(),
) {
    val mutations get() = effects.count { it.op.effect != Effect.READ }
}

class Checker(private val catalog: TowlCatalog, private val maxWidth: Int = 200, private val maxDepth: Int = 2) {

    private class Scope(
        val bindings: Map<String, Type>,
        val implicit: Type?, // implicit element type inside Express arguments
        val pure: Boolean, // inside the pure layer: no calls, no for, no blocks
        val depth: Int, // enclosing for nesting with calls
    ) {
        fun bind(name: String, t: Type) = Scope(bindings + (name to t), implicit, pure, depth)
        fun withImplicit(t: Type) = Scope(bindings, t, true, depth)
        fun asPure() = Scope(bindings, implicit, true, depth)
        fun deeper() = Scope(bindings, implicit, pure, depth + 1)
    }

    private val diags = ArrayList<Diagnostic>()
    private val types = HashMap<Expr, Type>()
    private val ops = HashMap<OpCall, OperationSpec>()
    private val effects = ArrayList<EffectSite>()
    private val waves = HashMap<MethodCall, Int?>()
    private val referenced = HashSet<String>()
    private lateinit var src: String

    fun check(program: Program, source: String): Checked {
        src = source
        val bindingTypes = LinkedHashMap<String, Type>()
        var scope = Scope(emptyMap(), null, false, 0)
        // predefined inputs (TOWL §5): usable without a declaration; a declaration or binding of the same name wins
        declared = program.inputs.map { it.name }.toSet() + program.bindings.map { it.name }
        for ((name, t) in PREDEFINED_INPUTS) if (name !in declared) scope = scope.bind(name, t)
        val seen = HashSet<String>()
        for (inp in program.inputs) {
            if (!seen.add(inp.name)) error("names", "names.duplicate", inp.pos, "input '${inp.name}' is declared twice")
            bindingTypes[inp.name] = inp.type
            scope = scope.bind(inp.name, inp.type)
        }
        for (b in program.bindings) {
            val t = binding(b, seen, scope)
            bindingTypes[b.name] = t
            staticLength(b.expr)?.let { lengths[b.name] = it }
            scope = scope.bind(b.name, t)
        }
        val resultType = typeOf(program.result, scope)
        for (b in program.bindings) if (b.name !in referenced)
            error("names", "names.unreferenced", b.pos, "binding '${b.name}' is never used; every value must flow into the result",
                "reference it from the result (e.g. include it in the result record) or remove it")
        for (inp in program.inputs) if (inp.name !in referenced)
            warn("names", "names.unusedInput", inp.pos, "input '${inp.name}' is never used")
        val errors = diags.filter { it.severity == "error" }
        if (errors.isNotEmpty()) throw TowlException(errors + diags.filter { it.severity == "warning" })
        return Checked(program, source, resultType, bindingTypes, types, effects, ops, waves, diags.toList(), implicitUsed.sorted())
    }

    private fun binding(b: Binding, seen: MutableSet<String>, scope: Scope): Type {
        if (!seen.add(b.name)) error("names", "names.duplicate", b.pos, "'${b.name}' is bound twice; names are bound once (no shadowing)")
        if (b.name in Stdlib.KEYWORDS) error("names", "names.keyword", b.pos, "'${b.name}' is a keyword")
        return typeOf(b.expr, scope)
    }

    // ── diagnostics ─────────────────────────────────────────────────────────

    private fun error(phase: String, code: String, pos: Pos?, msg: String, fix: String? = null, type: Type? = null): Type {
        diags += Diagnostic("error", phase, code, pos, msg, fix, type?.toString())
        return TError
    }

    private fun warn(phase: String, code: String, pos: Pos?, msg: String, fix: String? = null) {
        diags += Diagnostic("warning", phase, code, pos, msg, fix)
    }

    // ── expressions ─────────────────────────────────────────────────────────

    private fun typeOf(e: Expr, s: Scope, expected: Type? = null): Type {
        val t = compute(e, s, expected)
        types[e] = t
        return t
    }

    private fun compute(e: Expr, s: Scope, expected: Type?): Type = when (e) {
        is Lit -> when (val v = e.value) {
            null -> TNull
            is Boolean -> TBool
            is Int, is Long -> TInt
            is Number -> TNumber
            is String -> TString
            else -> TJson
        }
        is Ref -> {
            referenced += e.name
            if (e.name in PREDEFINED_INPUTS && e.name !in declared) implicitUsed += e.name
            s.bindings[e.name] ?: error("names", "names.undefined", e.pos, "'${e.name}' is not defined",
                "define it with '${e.name} = ...' before use, or check the spelling; known names: ${s.bindings.keys.joinToString(", ").ifEmpty { "(none)" }}")
        }
        is Implicit -> s.implicit ?: error("syntax", "syntax.pathOutsideElement", e.pos,
            "a path starting with '.' refers to the element of a list function (project, where, flat, ...) and is not valid here",
            "name the value instead, e.g. x.field, or move this into a list function")
        is RecordE -> {
            val exp = Types.stripNull(expected ?: TJson) as? TRecord
            if (e.fields.isEmpty() && exp == null && expected != TJson) error("types", "type.emptyLiteral", e.pos, "'{}' has no type here; only call parameters may be an empty record")
            val fields = LinkedHashMap<String, Type>()
            for ((k, v) in e.fields) {
                if (k in fields) error("syntax", "syntax.duplicateField", v.pos, "field '$k' appears twice")
                fields[k] = typeOf(v, s, exp?.fields?.get(k))
            }
            TRecord(fields)
        }
        is ListE -> {
            val expElem = (Types.stripNull(expected ?: TJson) as? TList)?.element
            if (e.items.isEmpty()) {
                if (expElem != null) TList(expElem)
                else error("types", "type.emptyLiteral", e.pos, "'[]' has no element type here", "an empty list is only valid where a list type is expected (a parameter, concat, in)")
            } else {
                var t = typeOf(e.items[0], s, expElem)
                for (it in e.items.drop(1)) {
                    val u = typeOf(it, s, expElem)
                    t = Types.join(t, u) ?: return error("types", "type.listElements", it.pos, "list elements have different types: $t and $u")
                }
                TList(t)
            }
        }
        is BlockE -> {
            if (s.pure) error("syntax", "syntax.pureLayer", e.pos, "a block is not allowed inside a path, shape, predicate, or call parameters")
            var inner = s
            val seen = HashSet<String>(s.bindings.keys)
            for (b in e.bindings) {
                if (!seen.add(b.name)) error("names", "names.duplicate", b.pos, "'${b.name}' shadows or repeats a name in scope")
                staticLength(b.expr)?.let { lengths[b.name] = it }
                inner = inner.bind(b.name, typeOf(b.expr, inner))
            }
            val t = typeOf(e.result, inner)
            for (b in e.bindings) if (b.name !in referenced) error("names", "names.unreferenced", b.pos, "binding '${b.name}' is never used")
            t
        }
        is Member -> member(e, s)
        is OpCall -> opCall(e, s)
        is MethodCall -> method(e, s)
    }

    private fun member(e: Member, s: Scope): Type {
        val target = typeOf(e.target, s)
        if (target == TError) return TError
        if (Types.stripNull(target) == TJson) return error("types", "type.opaque", e.pos,
            "'.${e.name}' on an opaque json value (its operation declares no schema for it)", "use the value whole, or pass it to an operation that accepts json")
        val nullable = Types.isNullable(target)
        val rec = Types.stripNull(target) as? TRecord
            ?: return error("types", "type.notRecord", e.pos, "'.${e.name}' needs a record but the value is $target",
                when (val src = e.target) {
                    is OpCall -> "${src.namespace}.${src.operation} returns the $target itself, not a record; use the call's value directly and drop '.${e.name}'"
                    is Ref -> "'${src.name}' is already a $target; use it directly and drop '.${e.name}'"
                    else -> "the value is already a $target; use it directly and drop '.${e.name}'"
                }, target)
        val ft = rec.fields[e.name]
            ?: return error("catalog", "catalog.unknownMember", e.pos, "'${e.name}' is not a member of $rec",
                "members: ${rec.fields.keys.sorted().joinToString(", ")}")
        if (nullable && !e.nullSafe) return error("types", "type.nullableAccess", e.pos,
            "'.${e.name}' on a nullable value ($target)", "use '?.${e.name}' to propagate Null; the result stays nullable", target)
        if (!nullable && e.nullSafe) warn("types", "type.needlessNullSafe", e.pos, "'?.${e.name}' on a non-nullable value; '.${e.name}' is canonical")
        return if (nullable) Types.nullable(ft) else ft
    }

    private fun opCall(e: OpCall, s: Scope): Type {
        if (s.pure) error("syntax", "syntax.pureLayer", e.pos, "an operation call is not allowed inside a path, shape, predicate, or another call's parameters",
            "bind it first: name = call(\"${e.namespace}\", \"${e.operation}\", ...), then reference the name")
        if (e.namespace !in catalog.namespaces)
            return error("catalog", "catalog.unknownNamespace", e.pos, "'${e.namespace}' is not a namespace in this catalog",
                "namespaces: ${catalog.namespaces.sorted().joinToString(", ").ifEmpty { "(none)" }}; the first argument of call is one of them")
        val op = catalog.operation(e.namespace, e.operation)
            ?: return error("catalog", "catalog.unknownOperation", e.pos, "unknown operation ${e.namespace}.${e.operation}",
                "operations in ${e.namespace}: " + catalog.operations().filter { it.namespace == e.namespace }.map { it.name }.sorted().joinToString(", "))
        ops[e] = op
        val pt = typeOf(e.params, s.asPure(), op.input ?: TJson)
        checkParams(pt, op, e)
        (e.params as? RecordE)?.fields?.forEach { (k, v) ->
            if (v is Lit && v.value is String && v.value in s.bindings)
                warn("catalog", "catalog.literalLooksLikeName", v.pos, "parameter '$k' is the literal string \"${v.value}\", which is also a name in scope",
                    "if you meant the value of ${v.value}, write ${v.value} (in the structured form: {\"\$\": \"${v.value}\"})")
        }
        var tolerate = emptyList<String>()
        e.options?.let { o ->
            val rec = o as? RecordE ?: return@let error("syntax", "syntax.options", o.pos, "call options must be a record literal { tolerate: [...] }")
            for ((k, v) in rec.fields) when (k) {
                "tolerate" -> {
                    val codes = (v as? ListE)?.items?.map { (it as? Lit)?.value as? String }
                    if (codes == null || codes.any { it == null }) error("syntax", "syntax.options", v.pos, "tolerate takes a list of error-code strings")
                    else {
                        tolerate = codes.filterNotNull()
                        for (c in tolerate) {
                            val cls = catalog.errorClass(op, c)
                            val allowed = setOf(ErrorClass.ABSENCE, ErrorClass.AUTHORIZATION, ErrorClass.AVAILABILITY, ErrorClass.STATE)
                            if (cls !in allowed)
                                error("catalog", "catalog.tolerateClass", v.pos, "'$c' is classified ${cls.name.lowercase()} and cannot be tolerated; only absence, authorization, availability, and state codes can" +
                                    (if (cls == ErrorClass.TRANSIENT) " (the runtime retries transient errors)" else if (cls == ErrorClass.VALIDATION) " (a validation error is a program bug)" else ""))
                            else if (c !in op.errorCodes && !op.openErrorCodes)
                                error("catalog", "catalog.unknownErrorCode", v.pos, "'$c' is not an error code of ${op.id}")
                            else if (c !in op.errorCodes) warn("catalog", "catalog.unmodeledErrorCode", v.pos, "'$c' is not a modeled error code of ${op.id}; accepted as class ${cls.name.lowercase()}")
                        }
                    }
                }
                "after" -> error("syntax", "syntax.options", v.pos, "'after' was removed: ordering comes from data",
                    "make the second call depend on the first's result; mutations without a data dependency run one at a time in source order")
                else -> error("syntax", "syntax.options", v.pos, "unknown call option '$k'", "options: tolerate")
            }
        }
        effects += EffectSite(op, e, tolerate, if (s.depth == 0) 1 else null, s.depth)
        return if (tolerate.isNotEmpty()) Types.nullable(op.output) else op.output
    }

    private fun checkParams(pt: Type, op: OperationSpec, e: OpCall) {
        val rec = pt as? TRecord ?: run { if (pt != TError) error("types", "type.params", e.params.pos, "parameters must be a record, got $pt"); return }
        val input = op.input ?: return
        val exprs = (e.params as? RecordE)?.fields?.toMap() ?: emptyMap()
        for ((k, t) in rec.fields) {
            val expected = input.fields[k]
            val x = exprs[k]
            if (x is ListE && x.items.isEmpty()) {
                error("catalog", "catalog.emptyListParameter", x.pos, "'$k' is []; an empty list is never a meaningful parameter and providers reject it",
                    "omit optional parameters; only the required ones must be present"); continue
            }
            if (expected == null) { error("catalog", "catalog.unknownParameter", e.params.pos, "'$k' is not a parameter of ${op.id}", "parameters: ${input.fields.keys.sorted().joinToString(", ")}"); continue }
            if (!Types.assignable(t, expected)) {
                if (Types.assignable(t, expected, relaxed = true))
                    warn("types", "type.nullableToRequired", e.params.pos, "'$k' is $t but ${op.id} requires $expected; passing it as is is correct: a Null at runtime stops with a 'data' error naming the element",
                        "there is no default operator; a Null here is a real absence and must be visible")
                else error("types", "type.parameter", e.params.pos, "'$k' is $t but ${op.id} expects $expected", null, t)
            }
        }
        for ((k, t) in input.fields) if (k !in rec.fields && !Types.isNullable(t) && t !is TList)
            error("catalog", "catalog.missingParameter", e.params.pos, "${op.id} requires parameter '$k' ($t)")
    }

    // ── stdlib ──────────────────────────────────────────────────────────────

    private fun method(e: MethodCall, s: Scope): Type {
        val target = typeOf(e.target, s)
        val name = e.name
        if (name in Stdlib.TEST) return error("syntax", "syntax.predicateOutside", e.pos, "'$name' is a predicate test and is only valid inside where/any/all")
        if (name == "for") return forExpr(e, target, s)
        if (name in Stdlib.STR) return stringFn(e, target, s)
        if (name in Stdlib.TIME) return timeFn(e, target, s)
        // list functions
        if (target == TError) return TError
        val list = target as? TList ?: return error("types", "type.notList", e.pos, "'.$name()' needs a list but the value is $target",
            if (Types.isNullable(target)) "the list may be Null (optional member or tolerated call); keep it nullable in a shape, or compact() the outer list first" else null, target)
        val elem = list.element
        val es = s.withImplicit(elem)
        fun path(i: Int, what: String = "a path from the element (e.g. .Name)", optionalForScalars: Boolean = false): Type? {
            if (e.args.size <= i && optionalForScalars) {
                if (Types.isScalar(Types.stripNull(elem))) return elem // identity path: the element itself
                return error("syntax", "syntax.arity", e.pos, "'$name' needs $what when the elements are $elem; omit the path only for a list of scalars").let { null }
            }
            val a = e.args.getOrNull(i) ?: return error("syntax", "syntax.arity", e.pos, "'$name' needs $what" + if (optionalForScalars) " (optional for a list of scalars)" else "").let { null }
            val x = (a as? ExprArg)?.expr ?: return error("syntax", "syntax.argKind", a.pos, "'$name' needs $what").let { null }
            if (!isPathLike(x)) return error("syntax", "syntax.argKind", a.pos, "'$name' needs $what; a path starts with '.' and refers to the element").let { null }
            return typeOf(x, es)
        }
        fun countArg(): Boolean {
            val x = (e.args.getOrNull(0) as? ExprArg)?.expr
            if (x !is Lit && x !is Ref) { error("syntax", "syntax.argKind", e.pos, "'$name' needs a count first: $name(5, .path)"); return false }
            val t = typeOf(x, s.asPure())
            if (t != TInt && t != TError) error("types", "type.count", x.pos, "'$name' count must be an int, got $t")
            return true
        }
        fun pred(i: Int): Boolean {
            val a = e.args.getOrNull(i) ?: run { error("syntax", "syntax.arity", e.pos, "'$name' needs a predicate"); return false }
            val pr = (a as? PredArg)?.pred ?: run { error("syntax", "syntax.argKind", a.pos, "'$name' needs a predicate such as .State == \"running\""); return false }
            predicate(pr, es); return true
        }
        fun noArgs() { if (e.args.isNotEmpty()) error("syntax", "syntax.arity", e.pos, "'$name()' takes no argument") }
        return when (name) {
            "project" -> {
                val a = e.args.singleOrNull() ?: return error("syntax", "syntax.arity", e.pos, "'project' takes one path or one shape")
                val x = (a as? ExprArg)?.expr ?: return error("syntax", "syntax.argKind", a.pos, "'project' takes a path or a shape { field: .path }")
                if (x is RecordE) shape(x, es)
                val t = typeOf(x, es)
                if (x !is RecordE && !isPathLike(x)) error("syntax", "syntax.argKind", a.pos, "'project' takes a path (.field) or a shape ({ field: .path }); found another expression")
                if (t is TList) warn("types", "type.nestedList", e.pos, "project yields list[$t]; use flat(...) if you want one list", null)
                TList(t)
            }
            "flat" -> {
                val t = path(0) ?: return TError
                if (t == TError) TList(TError)
                else (t as? TList)?.let { TList(it.element) } ?: error("types", "type.flatNotList", e.pos,
                    "flat needs a list-typed path but ${pathText(e.args[0])} is $t", if (Types.isNullable(t)) "the member is optional; use a defaulted member or ?. plus compact()" else "use project(...) for a non-list path", t)
            }
            "flatten" -> { noArgs(); (elem as? TList)?.let { TList(it.element) } ?: error("types", "type.flattenNotNested", e.pos, "flatten needs list[list[T]] but the value is $target", null, target) }
            "where" -> { if (pred(0)) list else TError }
            "compact" -> { noArgs(); if (Types.isNullable(elem)) TList(Types.stripNull(elem)) else { warn("types", "type.needlessCompact", e.pos, "compact on $target changes nothing"); list } }
            "distinct" -> {
                if (e.args.isEmpty()) { if (!Types.isEquatable(elem)) error("types", "type.notEquatable", e.pos, "distinct needs equatable elements, not $elem"); list }
                else { val t = path(0) ?: return TError; if (!Types.isEquatable(t)) error("types", "type.notEquatable", e.pos, "distinct path must be equatable, not $t"); TList(t) }
            }
            "concat" -> {
                val a = e.args.singleOrNull() ?: return error("syntax", "syntax.arity", e.pos, "'concat' takes one list")
                val x = (a as? ExprArg)?.expr ?: return error("syntax", "syntax.argKind", a.pos, "'concat' takes a list expression")
                val t = typeOf(x, s, list)
                if (t == TError) list else Types.join(list, t) as? TList ?: error("types", "type.concat", e.pos, "cannot concat $list with $t", null, t)
            }
            "group" -> {
                val k = path(0) ?: return TError
                val kb = Types.stripNull(k)
                if (!(Types.isScalar(kb) || kb == TError)) error("types", "type.groupKey", e.pos, "group key must be a scalar, not $k")
                TList(TRecord(mapOf("key" to k, "items" to list)))
            }
            "single" -> { noArgs(); Types.nullable(elem) }
            "count" -> { noArgs(); TInt }
            "sum" -> { val t = path(0, "a numeric path", true) ?: return TError; val b = Types.stripNull(t); if (!Types.isNumeric(b) && b != TError) error("types", "type.numeric", e.pos, "sum needs a numeric path, not $t"); if (b == TInt) TInt else TNumber }
            "min", "max" -> { val t = path(0, "an ordered scalar path", true) ?: return TError; val b = Types.stripNull(t); if (!Types.isOrdered(b) && b != TError) error("types", "type.ordered", e.pos, "$name needs an ordered scalar path, not $t"); Types.nullable(b) }
            "avg" -> { val t = path(0, "a numeric path", true) ?: return TError; val b = Types.stripNull(t); if (!Types.isNumeric(b) && b != TError) error("types", "type.numeric", e.pos, "avg needs a numeric path, not $t"); Types.nullable(TNumber) }
            "top", "bottom" -> {
                if (!countArg()) return TError
                val kt = if (e.args.size == 1 && Types.isScalar(Types.stripNull(elem))) elem else path(1, "an ordered scalar path to rank by") ?: return TError
                val kb = Types.stripNull(kt)
                if (!Types.isOrdered(kb) && kb != TError) error("types", "type.ordered", e.pos, "$name needs an ordered scalar key, not $kt")
                TList(TRecord(mapOf("rank" to TInt, "value" to elem)))
            }
            "collect" -> {
                val t = path(0, "a path", true) ?: return TError
                if (t is TList) warn("types", "type.nestedList", e.pos, "collect adds one list level: list[$t]; use flat(...) to flatten first")
                TList(Types.stripNull(t))
            }
            "any", "all" -> { if (pred(0)) TBool else TError }
            else -> error("syntax", "syntax.unknownFunction", e.pos, "'$name' is not a TOWL function")
        }
    }

    private fun isPathLike(x: Expr): Boolean = when (x) {
        is Implicit -> true
        is Member -> isPathLike(x.target)
        is MethodCall -> isPathLike(x.target)
        else -> false
    }

    private fun pathText(a: Arg) = (a as? ExprArg)?.expr?.let { render(it) } ?: "the argument"

    private fun render(x: Expr): String = when (x) {
        is Implicit -> ""
        is Member -> render(x.target) + (if (x.nullSafe) "?." else ".") + x.name
        is MethodCall -> render(x.target) + "." + x.name + "(...)"
        is Ref -> x.name
        else -> "..."
    }

    /** A shape: record whose leaves are paths, refs/literals, or nested shapes — no calls. */
    private fun shape(r: RecordE, es: Scope) {
        for ((_, v) in r.fields) when (v) {
            is RecordE -> shape(v, es)
            is OpCall, is BlockE -> error("syntax", "syntax.pureLayer", v.pos, "a shape leaf must be a path, a name, a literal, or a nested shape")
            is MethodCall -> if (v.name == "for") error("syntax", "syntax.pureLayer", v.pos, "for is not allowed inside a shape")
            else -> Unit
        }
    }

    private fun forExpr(e: MethodCall, target: Type, s: Scope): Type {
        if (s.pure) error("syntax", "syntax.pureLayer", e.pos, "for is not allowed inside a path, shape, predicate, or call arguments")
        val lam = e.args.singleOrNull() as? LambdaArg ?: return error("syntax", "syntax.argKind", e.pos, "'for' takes exactly one binder: for x in xs")
        if (target == TError) { typeOf(lam.body, s.bind(lam.param, TError).deeper()); return TList(TError) }
        val list = target as? TList ?: return error("types", "type.notList", e.pos, "'for' needs a list but the value is $target", null, target)
        if (lam.param in s.bindings) error("names", "names.duplicate", lam.pos, "'${lam.param}' shadows a name in scope")
        val before = effects.size
        val inner = s.bind(lam.param, list.element).deeper()
        val bodyT = typeOf(lam.body, inner)
        val hasCalls = effects.size > before
        if (hasCalls) {
            val width = staticLength(e.target)
            waves[e] = width
            if (inner.depth > maxDepth) error("effects", "effects.depth", e.pos, "for with calls nested ${inner.depth} deep exceeds the limit of $maxDepth")
            if (width != null && width > maxWidth) error("effects", "for.staticWidthExceeded", e.pos, "for over $width elements with calls exceeds the width limit of $maxWidth")
        }
        if (lam.param !in referenced) warn("names", "names.unusedElement", lam.pos, "'${lam.param}' is unused; the body does not depend on the element")
        return TList(bodyT)
    }

    /** TOWL §7: static length is inductive over literals, project, for, concat. */
    private fun staticLength(x: Expr): Int? = when (x) {
        is ListE -> x.items.size
        is Ref -> lengths[x.name]
        is MethodCall -> when (x.name) {
            "project", "for" -> staticLength(x.target)
            "concat" -> staticLength(x.target)?.let { a -> ((x.args[0] as? ExprArg)?.expr?.let(::staticLength))?.let { a + it } }
            else -> null
        }
        else -> null
    }

    private val lengths = HashMap<String, Int>() // binding name -> static length when known
    private var declared: Set<String> = emptySet()
    private val implicitUsed = HashSet<String>()

    private fun timeFn(e: MethodCall, target: Type, s: Scope): Type {
        val base = Types.stripNull(target)
        if (base != TTimestamp && base != TError) return error("types", "type.timestamp", e.pos, "'${e.name}' needs a timestamp but the value is $target",
            "timestamps come from the well-known input now (input now: timestamp) or from a timestamp-typed member", target)
        if (e.name.startsWith("minus_")) {
            val a = (e.args.singleOrNull() as? ExprArg)?.expr ?: return error("syntax", "syntax.arity", e.pos, "'${e.name}' takes one int argument")
            val t = typeOf(a, s.asPure()); if (t != TInt && t != TError) error("types", "type.int", a.pos, "'${e.name}' argument must be an int, not $t")
        } else if (e.args.isNotEmpty()) error("syntax", "syntax.arity", e.pos, "'${e.name}()' takes no argument")
        val out = if (e.name == "date") TString else TTimestamp
        return if (Types.isNullable(target)) Types.nullable(out) else out
    }

    private fun stringFn(e: MethodCall, target: Type, s: Scope): Type {
        val base = Types.stripNull(target)
        if (base != TString && base != TError) return error("types", "type.string", e.pos, "'${e.name}' needs a string but the value is $target", null, target)
        val needsArg = e.name in setOf("after_last", "before_first")
        if (needsArg) {
            val a = (e.args.singleOrNull() as? ExprArg)?.expr ?: return error("syntax", "syntax.arity", e.pos, "'${e.name}' takes one string argument")
            val t = typeOf(a, s.asPure()); if (Types.stripNull(t) != TString) error("types", "type.string", a.pos, "'${e.name}' argument must be a string, not $t")
        } else if (e.args.isNotEmpty()) error("syntax", "syntax.arity", e.pos, "'${e.name}()' takes no argument")
        return if (Types.isNullable(target)) Types.nullable(TString) else TString
    }

    // ── predicates ──────────────────────────────────────────────────────────

    private fun predicate(pr: Pred, es: Scope) {
        when (pr) {
            is AndP -> pr.terms.forEach { predicate(it, es) }
            is OrP -> pr.terms.forEach { predicate(it, es) }
            is NotP -> predicate(pr.term, es)
            is Cmp -> {
                val l = typeOf(pr.left, es); val r = typeOf(pr.right, es)
                if (l == TError || r == TError) return
                val lb = Types.stripNull(l); val rb = Types.stripNull(r)
                if (pr.op == "==" || pr.op == "!=") {
                    if (!Types.isEquatable(l) || !Types.isEquatable(r)) error("types", "type.notEquatable", pr.pos, "cannot compare $l with $r")
                    else if (l != TNull && r != TNull && Types.join(lb, rb) == null) error("types", "type.compare", pr.pos, "cannot compare $l with $r", null, l)
                } else {
                    if (!Types.isOrdered(lb) || !Types.isOrdered(rb) || Types.join(lb, rb) == null) error("types", "type.ordered", pr.pos, "'${pr.op}' needs two ordered values of one type, got $l and $r")
                }
            }
            is InP -> {
                val l = typeOf(pr.left, es); val r = typeOf(pr.right, es, TList(Types.stripNull(l)))
                if (l == TError || r == TError) return
                val rl = r as? TList ?: return error("types", "type.in", pr.pos, "'in' needs a list on the right, got $r").let { }
                if (Types.join(Types.stripNull(l), rl.element) == null) error("types", "type.in", pr.pos, "'in' compares $l against list[${rl.element}]")
            }
            is TestP -> {
                val t = typeOf(pr.operand, es)
                if (pr.fn in setOf("present", "absent")) {
                    if (!Types.isNullable(t)) warn("types", "type.needlessPresent", pr.pos, "'${pr.fn}()' on a non-nullable value is always ${pr.fn == "present"}")
                } else if (pr.fn == "empty") {
                    if (Types.stripNull(t) !is TList && t != TError) error("types", "type.notList", pr.pos, "'empty()' needs a list operand, got $t")
                } else {
                    if (Types.stripNull(t) != TString && t != TError) error("types", "type.string", pr.pos, "'${pr.fn}' needs a string operand, got $t")
                    val at = pr.arg?.let { typeOf(it, es) }
                    if (at != null && Types.stripNull(at) != TString && at != TError) error("types", "type.string", pr.pos, "'${pr.fn}' argument must be a string, got $at")
                }
            }
            is QuantP -> {
                val t = typeOf(pr.operand, es)
                if (t == TError) return
                val l = Types.stripNull(t) as? TList ?: return error("types", "type.notList", pr.pos, "'${if (pr.all) "all" else "any"}' needs a list operand, got $t").let { }
                predicate(pr.inner, es.withImplicit(l.element))
            }
        }
    }
}

private val PREDEFINED_INPUTS = mapOf<String, Type>("now" to TTimestamp, "today" to TString)
