package com.example.demo.synth.towl

import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.json.JsonMapper

/**
 * Strict-JSON parser producing the closed block union. Duplicate keys are rejected by the JSON
 * layer; role mixtures match no variant and fail here, not in a later semantic pass. The registry
 * is required because expression/aggregator application keys are data in the registry, not
 * productions of the language.
 */
class TowlParser(private val registry: TowlRegistry) {

    private val mapper = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .build()

    private val namePattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun parse(text: String): Plan {
        val preprocessing = mutableListOf<String>()
        val stripped = stripFence(text, preprocessing)
        val root = runCatching { mapper.readValue(stripped, Any::class.java) }
            .getOrElse { e -> fail("document", "not strict JSON: ${e.message}") }
        val m = root as? Map<*, *> ?: fail("document", "top level must be a JSON object")
        val doc = m.entries.associate { (k, v) -> k.toString() to v }

        if (doc["towl"] != "v1") fail("document", "unsupported or missing \"towl\" version (expected \"v1\")")
        val description = doc["description"] as? String ?: fail("document", "\"description\" (string) is required")
        val inputs = parseInputs(doc["inputs"])
        val blockKeys = doc.keys - setOf("towl", "description", "inputs")
        val block = parseBlock(blockKeys.associateWith { doc[it] }, "plan")
        return Plan(description, inputs, block, preprocessing)
    }

    private fun stripFence(text: String, notes: MutableList<String>): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        notes += "stripped a Markdown fence around the document (preprocessing; not TOWL syntax)"
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun parseInputs(v: Any?): Map<String, InputDecl> {
        if (v == null) return emptyMap()
        val m = v as? Map<*, *> ?: fail("inputs", "must be an object")
        return m.entries.associate { (k, decl) ->
            val name = binderName(k, "inputs")
            val d = decl as? Map<*, *> ?: fail("inputs.$name", "must be an object with \"type\"")
            val extra = d.keys.map { it.toString() } - setOf("type", "default")
            if (extra.isNotEmpty()) fail("inputs.$name", "unknown members $extra")
            val type = d["type"] as? String ?: fail("inputs.$name", "\"type\" (string) is required")
            if (type !in setOf("string", "number", "integer", "boolean", "array", "document"))
                fail("inputs.$name", "unknown input type '$type'")
            name to InputDecl(type, d["default"], d.containsKey("default"))
        }
    }

    // ── blocks ──────────────────────────────────────────────────────────────

    fun parseBlock(raw: Map<String, Any?>, where: String): Block = when {
        "forEach" in raw -> parseTraverse(raw, where)
        "source" in raw -> parseExpress(raw, where)
        else -> parseAssemble(raw, where)
    }

    private fun closed(raw: Map<String, Any?>, allowed: Set<String>, variant: String, where: String) {
        val foreign = raw.keys - allowed
        if (foreign.isNotEmpty())
            fail(where, "$variant block admits only $allowed; foreign members $foreign match no block shape")
    }

    private fun parseTraverse(raw: Map<String, Any?>, where: String): Traverse {
        closed(raw, setOf("forEach", "onError"), "Traverse", where)
        val fe = raw["forEach"] as? Map<*, *> ?: fail(where, "\"forEach\" must be an object")
        if (fe.size != 1) fail(where, "\"forEach\" declares exactly one element variable (Map1); found ${fe.keys}")
        val (k, v) = fe.entries.first()
        val name = binderName(k, "$where.forEach")
        val elem = v as? Map<*, *> ?: fail("$where.forEach.$name", "element must be an object with \"from\" plus one block shape")
        val elemMap = elem.entries.associate { (ek, ev) -> ek.toString() to ev }
        if ("from" !in elemMap) fail("$where.forEach.$name", "\"from\" is required")
        val from = parseExpr(elemMap["from"], "$where.forEach.$name.from")
        val body = parseBlock(elemMap - "from", "$where.forEach.$name")
        val onError = parseOnError(raw["onError"], where, traverse = true)
        return Traverse(name, from, body, onError)
    }

    private fun parseExpress(raw: Map<String, Any?>, where: String): Express {
        closed(raw, setOf("source", "filter", "dedup", "result", "onError"), "Express", where)
        val producer = parseProducer(raw["source"], "$where.source")
        val filter = raw["filter"]?.let { parseExpr(it, "$where.filter") }
        val dedup = raw["dedup"]?.let { d ->
            val list = d as? List<*> ?: fail("$where.dedup", "must be a non-empty array of key expressions")
            if (list.isEmpty()) fail("$where.dedup", "must be non-empty; deduplication is an explicit reduction over named keys")
            list.mapIndexed { i, e -> parseExpr(e, "$where.dedup[$i]") }
        }
        val result = raw["result"]?.let { parseResult(it, "$where.result") }
        val onError = parseOnError(raw["onError"], where, traverse = false)
        if (onError == OnError.COLLECT) fail(where, "onError \"collect\" is valid only on Traverse")
        return Express(producer, filter, dedup, result, onError)
    }

    private fun parseAssemble(raw: Map<String, Any?>, where: String): Assemble {
        closed(raw, setOf("let", "result"), "Assemble", where)
        if (raw.isEmpty()) fail(where, "Assemble block needs at least one of \"let\", \"result\"")
        val let = (raw["let"] as? Map<*, *> ?: if ("let" in raw) fail("$where.let", "must be an object") else emptyMap<Any?, Any?>())
            .entries.associate { (k, v) ->
                val name = binderName(k, "$where.let")
                val b = v as? Map<*, *> ?: fail("$where.let.$name", "binding must be a block object")
                name to parseBlock(b.entries.associate { (bk, bv) -> bk.toString() to bv }, "$where.let.$name")
            }
        val result = raw["result"]?.let { parseResult(it, "$where.result") }
        if (let.isEmpty() && result == null) fail(where, "Assemble block needs at least one of \"let\", \"result\"")
        return Assemble(let, result)
    }

    private fun parseOnError(v: Any?, where: String, traverse: Boolean): OnError = when (v) {
        null -> if (traverse) OnError.SKIP else OnError.FAIL
        "fail" -> OnError.FAIL
        "skip" -> OnError.SKIP
        "collect" -> OnError.COLLECT
        else -> fail("$where.onError", "must be \"fail\", \"skip\", or \"collect\"")
    }

    private fun binderName(k: Any?, where: String): String {
        val name = k.toString()
        if (!namePattern.matches(name)) fail(where, "'$name' is not a legal name")
        if (name in RESERVED_KEYS) fail(where, "'$name' is a reserved key and cannot be a name")
        return name
    }

    // ── producers and invocations ───────────────────────────────────────────

    private fun parseProducer(v: Any?, where: String): Producer {
        val m = v as? Map<*, *> ?: fail(where, "source takes a call or an expression")
        if (m.size == 1 && m.keys.first() == "call") {
            val inv = m.values.first() as? Map<*, *> ?: fail("$where.call", "invocation must be an object")
            return PCall(parseInvocation(inv.entries.associate { (k, x) -> k.toString() to x }, "$where.call"))
        }
        return PExpr(parseExpr(v, where))
    }

    private fun parseInvocation(raw: Map<String, Any?>, where: String): Invocation {
        val extra = raw.keys - setOf("operation", "service", "args", "paginate")
        if (extra.isNotEmpty()) fail(where, "unknown call members $extra (never silently ignored)")
        val operation = raw["operation"] as? String ?: fail(where, "\"operation\" (string) is required")
        val service = raw["service"]?.let { it as? String ?: fail(where, "\"service\" must be a string") }
        val args = (raw["args"] as? Map<*, *>)?.entries?.associate { (k, v) ->
            k.toString() to parseInputValue(v, "$where.args.$k")
        } ?: if (raw["args"] != null && raw["args"] !is Map<*, *>) fail(where, "\"args\" must be an object") else emptyMap()
        val paginate = raw["paginate"]?.let { p ->
            val pm = p as? Map<*, *> ?: fail("$where.paginate", "must be an object")
            val bad = pm.keys.map { it.toString() } - setOf("maxItems", "maxPages", "pageSize")
            if (bad.isNotEmpty()) fail("$where.paginate", "unknown members $bad")
            Paginate(posInt(pm["maxItems"], "$where.paginate.maxItems"),
                posInt(pm["maxPages"], "$where.paginate.maxPages"),
                posInt(pm["pageSize"], "$where.paginate.pageSize"))
        }
        return Invocation(service, operation, args, paginate)
    }

    private fun posInt(v: Any?, where: String): Int? = when (v) {
        null -> null
        is Int -> if (v > 0) v else fail(where, "must be a positive integer")
        is Long -> if (v > 0) v.toInt() else fail(where, "must be a positive integer")
        else -> fail(where, "must be a positive integer")
    }

    private fun parseInputValue(v: Any?, where: String): InputValue = when (v) {
        is List<*> -> IList(v.mapIndexed { i, e -> parseInputValue(e, "$where[$i]") })
        is Map<*, *> -> {
            if (isExprShaped(v)) IExpr(parseExpr(v, where))
            else IMap(v.entries.associate { (k, e) -> k.toString() to parseInputValue(e, "$where.$k") })
        }
        else -> IExpr(Lit(v))
    }

    // ── expressions ─────────────────────────────────────────────────────────

    private fun isExprShaped(m: Map<*, *>): Boolean {
        val keys = m.keys.map { it.toString() }.toSet()
        return when {
            "ref" in keys -> keys.all { it in setOf("ref", "path") }
            "input" in keys -> keys.all { it in setOf("input", "path") }
            "env" in keys -> keys.all { it in setOf("env", "path") }
            keys.size == 1 -> keys.first() == "path" || keys.first() in registry.functions
            else -> false
        }
    }

    fun parseExpr(v: Any?, where: String): Expr = when (v) {
        null, is String, is Boolean, is Number -> Lit(v)
        is List<*> -> Lit(v) // raw arrays are literal data in expression positions
        is Map<*, *> -> parseExprObject(v, where)
        else -> fail(where, "unsupported JSON value")
    }

    private fun parseExprObject(m: Map<*, *>, where: String): Expr {
        val keys = m.keys.map { it.toString() }.toSet()
        fun path(): Path = Path.parse((m["path"] as? String) ?: if ("path" in keys) fail(where, "\"path\" must be a string") else "", where)
        return when {
            "ref" in keys && keys.all { it in setOf("ref", "path") } ->
                RefE(m["ref"] as? String ?: fail(where, "\"ref\" must be a name"), path())
            "input" in keys && keys.all { it in setOf("input", "path") } ->
                InputE(m["input"] as? String ?: fail(where, "\"input\" must be a name"), path())
            "env" in keys && keys.all { it in setOf("env", "path") } ->
                EnvE(m["env"] as? String ?: fail(where, "\"env\" must be a name"), path())
            keys.size == 1 && keys.first() == "path" -> ImplicitE(path())
            keys.size == 1 && keys.first() in registry.functions -> {
                val name = keys.first()
                val args = m.values.first() as? List<*>
                    ?: fail(where, "application '{$name: [...]}' takes an array of arguments")
                ApplyE(name, args.mapIndexed { i, a -> parseExpr(a, "$where.$name[$i]") })
            }
            keys.size == 1 && keys.first() in registry.aggregators ->
                fail(where, "'${keys.first()}' is an aggregator; aggregators are legal only as result leaves")
            else -> {
                wrappedCallHint(m, where)
                fail(where, "not an expression: object with keys $keys (unknown names never become dynamic calls)")
            }
        }
    }

    // ── results ─────────────────────────────────────────────────────────────

    fun parseResult(v: Any?, where: String): ResultNode = when (v) {
        null, is String, is Boolean, is Number -> RExpr(Lit(v))
        is List<*> -> RExpr(Lit(v))
        is Map<*, *> -> {
            val keys = m2keys(v)
            when {
                keys.size == 1 && keys.first() in registry.aggregators ->
                    RAgg(parseAggApply(keys.first(), v.values.first(), where))
                isExprShaped(v) -> RExpr(parseExpr(v, where))
                keys.size == 1 && (keys.first() in RESERVED_KEYS || keys.first() in registry.functions) ->
                    fail(where, "single-member product '$${keys.first()}' collides with a reserved or registered name; rename the member")
                else -> RRecord(v.entries.associate { (k, e) -> k.toString() to parseResult(e, "$where.$k") })
            }
        }
        else -> fail(where, "unsupported JSON value")
    }

    private fun m2keys(m: Map<*, *>) = m.keys.map { it.toString() }

    /** Detects the common hallucination {"fn": ["or", args...]} and names the correct spelling. */
    private fun wrappedCallHint(m: Map<*, *>, where: String) {
        if (m.size != 1) return
        val wrapper = m.keys.first().toString()
        val v = m.values.first() as? List<*> ?: return
        val first = v.firstOrNull() as? String ?: return
        if (first in registry.functions || first in registry.aggregators)
            fail(where, "'$wrapper' wraps a call of '$first', but the function name IS the object key: " +
                "write {\"$first\": [...]} with the ${v.size - 1} argument(s) only — there is no wrapper key")
    }

    private fun parseAggApply(name: String, rawArgs: Any?, where: String): AggApply {
        val args = rawArgs as? List<*> ?: fail("$where.$name", "aggregator '{$name: [...]}' takes an array of arguments")
        val parsed = args.mapIndexed { i, a ->
            val aw = "$where.$name[$i]"
            if (a is Map<*, *>) {
                val keys = m2keys(a)
                when {
                    keys.size == 1 && keys.first() in registry.aggregators -> AAgg(parseAggApply(keys.first(), a.values.first(), aw))
                    keys.isNotEmpty() && keys.all { it in registry.aggregators } && !isExprShaped(a) ->
                        AProduct(a.entries.associate { (k, e) -> k.toString() to parseAggApply(k.toString(), e, aw) })
                    else -> AExpr(parseExpr(a, aw))
                }
            } else AExpr(parseExpr(a, aw))
        }
        return AggApply(name, parsed)
    }
}
