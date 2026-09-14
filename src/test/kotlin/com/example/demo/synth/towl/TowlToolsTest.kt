package com.example.demo.synth.towl

import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the agent tool belt — text in, JSON out, against the fake catalog; no LLM, no MCP. */
class TowlToolsTest {

    private val mapper = JsonMapper.builder().build()

    private fun tools(catalog: FakeCatalog = FakeCatalog()) = catalog to TowlTools(TowlService(catalog, maxConcurrency = 2))

    @Suppress("UNCHECKED_CAST")
    private fun json(s: String): Map<String, Any?> = mapper.readValue(s, Map::class.java) as Map<String, Any?>

    /** Mirrors the framework's conversion of tool-call arguments into the structured program. */
    private fun ir(json: String): ProgramIr = mapper.readValue(json, ProgramIr::class.java)

    private val PROGRAM = ir("""
        { "towl": 3, "description": "docs",
          "bindings": [
            { "name": "ver",  "value": "javadocs.get_latest_version({ groupId: \"g\", artifactId: \"a\" }).result" },
            { "name": "syms", "value": "javadocs.list_javadoc_symbols({ groupId: \"g\", artifactId: \"a\", version: ver }).result.where(.fqn.contains(\"Validator\"))" }
          ],
          "result": "syms.each(s => { doc = javadocs.get_javadoc_symbol({ groupId: \"g\", artifactId: \"a\", version: ver, link: s.link })  { class: s.fqn.after_last(\".\"), summary: llm.summarize({ text: doc }) } })" }
    """)

    @Test fun `towlPlanHelper returns the v3 language guide plus matching operations with types`() {
        val (_, t) = tools()
        val out = json(t.towlPlanHelper(listOf("javadoc symbols", "summarize text"), null, null))
        val guide = out["language"] as String
        assertTrue(guide.contains("\"bindings\"") && guide.contains(".each(x => body)") && guide.contains("tolerate"), guide)
        assertTrue(!guide.contains("truncate"), "no truncation anywhere in the guide")
        @Suppress("UNCHECKED_CAST") val matched = out["matched"] as List<Map<String, Any?>>
        val names = matched.map { it["operation"] }
        assertTrue("javadocs.list_javadoc_symbols" in names && "llm.summarize" in names, names.toString())
        val list = matched.single { it["operation"] == "javadocs.list_javadoc_symbols" }
        assertEquals("{ result: list[{ fqn: string, link: string, kind: string | Null }] }", list["returns"])
        assertEquals("{ groupId: string, artifactId: string, version: string }", list["params"])
        assertTrue(guide.contains("never invent a wrapper member"), guide)
        val doc = json(t.towlPlanHelper(listOf("javadoc symbol"), null, null))
        @Suppress("UNCHECKED_CAST") val docEntry = (doc["matched"] as List<Map<String, Any?>>).single { it["operation"] == "javadocs.get_javadoc_symbol" }
        assertEquals("string  (a bare value, not a record)", docEntry["returns"])
        assertTrue((docEntry["note"] as String).contains("no wrapper member such as .result"))
        @Suppress("UNCHECKED_CAST") val others = out["otherOperations"] as List<String>
        assertEquals(6, (names + others).toSet().size, "every catalog operation is visible somewhere")
    }

    /** A catalog without any summarize operation: the javadoc tools only. */
    private fun withoutLlm(base: FakeCatalog = FakeCatalog()) = object : TowlCatalog {
        override val namespaces = setOf("javadocs")
        override fun operation(namespace: String, name: String) = if (namespace == "javadocs") base.operation(namespace, name) else null
        override fun operations() = base.operations().filter { it.namespace == "javadocs" }
    }

    @Test fun `without a summarize operation the guide never mentions one and unmatched queries are reported`() {
        val t = TowlTools(TowlService(withoutLlm()))
        val out = json(t.towlPlanHelper(listOf("resolve latest version", "summarize text"), null, null))
        val guide = out["language"] as String
        assertTrue(!guide.contains("summarize") && !guide.contains("llm."), guide)
        assertTrue(guide.contains("has no operation that shortens text"), guide)
        assertEquals(listOf("summarize text"), out["unmatched"])
        assertTrue((out["note"] as String).contains("searching again will not find more"), out["note"].toString())
        @Suppress("UNCHECKED_CAST") val matched = out["matched"] as List<Map<String, Any?>>
        val doc = json(t.towlPlanHelper(listOf("javadoc symbol"), null, null))
        @Suppress("UNCHECKED_CAST") val entry = (doc["matched"] as List<Map<String, Any?>>).single { it["operation"] == "javadocs.get_javadoc_symbol" }
        assertTrue((entry["note"] as String).contains("no operation that shortens text") && !(entry["note"] as String).contains("llm"), entry["note"].toString())
        assertTrue(matched.isNotEmpty())
    }

    @Test fun `with a summarize operation the guide names the real one`() {
        val (_, t) = tools()
        val guide = json(t.towlPlanHelper(listOf("x"), null, null))["language"] as String
        assertTrue(guide.contains("call llm.summarize on it") && guide.contains("\"call\": \"llm.summarize\""), guide)
        assertEquals(emptyList<Any?>(), json(t.towlPlanHelper(listOf("summarize text"), null, null))["unmatched"])
    }

    @Test fun `calling an operation in a namespace the catalog lacks is diagnosed as such, in both forms`() {
        val t = TowlTools(TowlService(withoutLlm()))
        // structured form: caught before parsing
        val s = json(t.validateTowlPlan(ir("""{ "towl": 3, "bindings": [ { "name": "x", "call": "llm.summarize", "params": { "text": "t" } } ], "result": "x" }""")))
        @Suppress("UNCHECKED_CAST") val sd = (s["diagnostics"] as List<Map<String, Any?>>).single()
        assertEquals("catalog.unknownNamespace", sd["code"]); assertTrue((sd["fix"] as String).contains("namespaces: javadocs"), sd.toString())
        // text form: the parser explains it is not a namespace rather than "not a function"
        val v = json(t.validateTowlPlan(ir("""{ "towl": 3, "bindings": [ { "name": "x", "value": "llm.summarize({ text: \"t\" })" } ], "result": "x" }""")))
        @Suppress("UNCHECKED_CAST") val vd = (v["diagnostics"] as List<Map<String, Any?>>).single()
        assertEquals("catalog.unknownNamespace", vd["code"]); assertTrue((vd["message"] as String).contains("'llm' is not a namespace"), vd.toString())
    }

    @Test fun `the guide can be omitted on repeat searches`() {
        val (_, t) = tools()
        val out = json(t.towlPlanHelper(listOf("javadoc symbol"), null, false))
        assertTrue(!out.containsKey("language") && (out["matched"] as List<*>).isNotEmpty())
    }

    @Test fun `towlPlanHelper respects the per-query limit and bounds the remainder`() {
        val (_, t) = tools()
        val out = json(t.towlPlanHelper(listOf("javadoc version symbol"), 1, null))
        assertEquals(1, (out["matched"] as List<*>).size)
        assertEquals(5, (out["otherOperations"] as List<*>).size)
    }

    @Test fun `validateTowlPlan returns the typed report without executing`() {
        val (catalog, t) = tools()
        val out = json(t.validateTowlPlan(PROGRAM))
        assertEquals(true, out["valid"])
        assertEquals("list[{ class: string, summary: string }]", out["result_type"])
        val typed = out["typed"] as String
        assertTrue(typed.contains("// ver: string") && typed.contains("llm.summarize read ×dynamic"), typed)
        @Suppress("UNCHECKED_CAST") val effects = out["effects"] as List<Map<String, Any?>>
        assertEquals(4, effects.size)
        assertEquals(0, catalog.calls.size, "validate must not invoke operations")
    }

    @Test fun `the generated tool input schema teaches the program skeleton`() {
        val method = TowlTools::class.java.methods.single { it.name == "runTowlPlan" }
        val schema = org.springframework.ai.util.json.schema.JsonSchemaGenerator.generateForMethodInput(method)
        for (key in listOf("\"towl\"", "\"description\"", "\"inputs\"", "\"bindings\"", "\"name\"", "\"value\"", "\"call\"", "\"params\"", "\"then\"", "\"each\"", "\"over\"", "\"tolerate\"", "\"result\""))
            assertTrue(schema.contains(key), "$key missing from generated schema:\n$schema")
        assertTrue(schema.contains("one TOWL expression in text"), "field descriptions must reach the schema:\n$schema")
        assertTrue(!schema.contains("\"extras\""), "captured unknowns must not leak into the schema")
    }

    @Test fun `diagnostics come back as structured data with fixes and a location, not exceptions`() {
        val (catalog, t) = tools()
        val out = json(t.validateTowlPlan(ir("""{ "towl": 3, "bindings": [ { "name": "v", "value": "javadocs.get_version({ group: \"g\" })" } ], "result": "v.result" }""")))
        assertEquals(false, out["valid"])
        @Suppress("UNCHECKED_CAST") val d = out["diagnostics"] as List<Map<String, Any?>>
        val err = d.single { it["severity"] == "error" }
        assertEquals("catalog.unknownOperation", err["code"])
        assertTrue((err["fix"] as String).contains("get_latest_version"))
        assertEquals("binding 'v'", err["where"])
        assertEquals(0, catalog.calls.size)
    }

    /** The same program in the fully structured form: calls with JSON params and an each node. */
    private val STRUCTURED = ir("""
        { "towl": 3, "description": "docs",
          "bindings": [
            { "name": "ver",  "call": "javadocs.get_latest_version", "params": { "groupId": "g", "artifactId": "a" }, "then": ".result" },
            { "name": "syms", "call": "javadocs.list_javadoc_symbols", "params": { "groupId": "g", "artifactId": "a", "version": {"$": "ver"} },
              "then": ".result.where(.fqn.contains(\"Validator\"))" },
            { "name": "digests", "each": { "over": "syms", "as": "s",
              "bindings": [
                { "name": "doc", "call": "javadocs.get_javadoc_symbol", "params": { "groupId": "g", "artifactId": "a", "version": {"$": "ver"}, "link": {"$": "s.link"} },
                  "options": { "tolerate": ["NotFound"] } },
                { "name": "sum", "call": "llm.summarize", "params": { "text": {"$": "doc.or(\"\")"}, "focus": "class purpose" } }
              ],
              "result": "{ class: s.fqn.after_last(\".\"), summary: sum }" } }
          ],
          "result": "digests" }
    """)

    @Test fun `structured call and each forms render, check and run like the text form`() {
        val (catalog, t) = tools()
        val rendered = STRUCTURED.render()
        assertTrue(rendered.source.contains("""syms = javadocs.list_javadoc_symbols({ groupId: "g", artifactId: "a", version: ver }).result.where(.fqn.contains("Validator"))"""), rendered.source)
        assertTrue(rendered.source.contains("""{ tolerate: ["NotFound"] }"""), rendered.source)
        assertEquals("binding 'doc' in each 'digests'", rendered.whereByLine.entries.single { it.value.startsWith("binding 'doc'") }.value)
        val out = json(t.runTowlPlan(STRUCTURED, null))
        assertEquals(true, out["valid"]); assertEquals("ok", out["status"])
        assertEquals(3, (out["value"] as List<*>).size)
        assertEquals("list[{ class: string, summary: string }]", out["type"])
        assertEquals(8, catalog.calls.size)
        val listArgs = catalog.calls.single { it.first == "javadocs.list_javadoc_symbols" }.second
        assertEquals("2.22.2", listArgs["version"], "the {\"$\": \"ver\"} reference resolved to the binding's value")
    }

    @Test fun `a plain string that names a binding inside params is warned about`() {
        val (_, t) = tools()
        val out = json(t.validateTowlPlan(ir("""{ "towl": 3, "bindings": [
            { "name": "ver", "call": "javadocs.get_latest_version", "params": { "groupId": "g", "artifactId": "a" }, "then": ".result" },
            { "name": "syms", "call": "javadocs.list_javadoc_symbols", "params": { "groupId": "g", "artifactId": "a", "version": "ver" }, "then": ".result" } ],
            "result": "syms" }""")))
        // 'ver' is then unreferenced (an error) and the literal is flagged: together they point at the mistake
        assertEquals(false, out["valid"])
        @Suppress("UNCHECKED_CAST") val d = out["diagnostics"] as List<Map<String, Any?>>
        assertTrue(d.any { it["code"] == "names.unreferenced" && it["where"] == "binding 'ver'" }, d.toString())
        assertTrue(d.any { it["code"] == "catalog.literalLooksLikeName" && (it["fix"] as String).contains("{\"$\": \"ver\"}") && it["where"] == "binding 'syms'" }, d.toString())
    }

    @Test fun `semicolons between block bindings are accepted`() {
        // the live turn-2 failure: the model separated block bindings with ';'
        val (_, t) = tools()
        val out = json(t.runTowlPlan(ir("""{ "towl": 3, "bindings": [
            { "name": "syms", "call": "javadocs.list_javadoc_symbols", "params": { "groupId": "g", "artifactId": "a", "version": "1" }, "then": ".result" },
            { "name": "out", "value": "syms.each(s => { a = s.fqn.after_last(\".\"); b = s.link; { a: a, b: b } })" } ],
            "result": "out" }"""), null))
        assertEquals("ok", out["status"], out.toString())
    }

    @Test fun `node forms are validated structurally`() {
        val (_, t) = tools()
        val out = json(t.validateTowlPlan(ir("""{ "towl": 3, "bindings": [
            { "name": "a", "value": "1", "call": "x.y" },
            { "name": "b", "params": { "k": 1 } },
            { "name": "c", "call": "not-an-op" },
            { "name": "d", "each": { "over": "xs" } } ],
            "result": "a" }""")))
        assertEquals(false, out["valid"])
        @Suppress("UNCHECKED_CAST") val msgs = (out["diagnostics"] as List<Map<String, Any?>>).map { it["message"] as String }
        assertTrue(msgs.any { it.contains("exactly one of value, call, each") }, msgs.toString())
        assertTrue(msgs.any { it.contains("params/options/then belong to a call binding") }, msgs.toString())
        assertTrue(msgs.any { it.contains("call must be namespace.operation") }, msgs.toString())
        assertTrue(msgs.any { it.contains("each.as") } && msgs.any { it.contains("each.result") }, msgs.toString())
    }

    @Test fun `structural slips are reported with a corrective message before parsing`() {
        val (_, t) = tools()
        // the v1-era failure mode: a binding placed at the program level, and no result
        val out = json(t.validateTowlPlan(ir("""{ "towl": 3, "bindings": [], "syms": "javadocs.list_javadoc_symbols({})" }""")))
        assertEquals(false, out["valid"])
        @Suppress("UNCHECKED_CAST") val d = out["diagnostics"] as List<Map<String, Any?>>
        assertTrue(d.any { it["code"] == "syntax.unknownMember" && (it["fix"] as String).contains("inside bindings") }, d.toString())
        assertTrue(d.any { it["code"] == "syntax.noResult" }, d.toString())
    }

    @Test fun `runTowlPlan checks first and returns the success envelope with node counts`() {
        val (catalog, t) = tools()
        val out = json(t.runTowlPlan(PROGRAM, null))
        assertEquals(true, out["valid"]); assertEquals("ok", out["status"])
        assertEquals(3, (out["value"] as List<*>).size)
        @Suppress("UNCHECKED_CAST") val nodes = out["nodes"] as List<Map<String, Any?>>
        val where = nodes.single { it["node"] == "where" }
        assertEquals(4, (where["in"] as Number).toInt()); assertEquals(3, (where["out"] as Number).toInt())
        assertEquals(8, catalog.calls.size) // 1 version + 1 list + 3 docs + 3 summaries
        @Suppress("UNCHECKED_CAST") val effects = out["effects"] as List<Map<String, Any?>>
        assertEquals(3, (effects.single { it["operation"] == "llm.summarize" }["calls"] as Number).toInt())
    }

    @Test fun `runTowlPlan on an invalid program performs zero invocations`() {
        val (catalog, t) = tools()
        val out = json(t.runTowlPlan(ir("""{ "towl": 3, "result": "javadocs.no_such_operation({})" }"""), null))
        assertEquals(false, out["valid"])
        assertEquals(0, catalog.calls.size)
    }

    @Test fun `runTowlPlan reports a runtime failure as the failure envelope`() {
        val (catalog, t) = tools()
        catalog.failOn = { id, _ -> if (id == "javadocs.get_latest_version") OperationError("AccessDenied", "nope") else null }
        val out = json(t.runTowlPlan(PROGRAM, null))
        assertEquals(true, out["valid"]); assertEquals("error", out["status"])
        @Suppress("UNCHECKED_CAST") val err = out["error"] as Map<String, Any?>
        assertEquals("authorization", err["class"]); assertEquals("tolerate-candidate", err["action"])
        assertEquals(1, catalog.calls.size, "nothing after the failing call was dispatched")
    }

    @Test fun `inputs are passed to a resume program`() {
        val (_, t) = tools()
        val out = json(t.runTowlPlan(ir("""{ "towl": 3, "inputs": { "done": "list[string]" }, "result": "done.concat([\"z\"])" }"""), mapOf("done" to listOf("a", "b"))))
        assertEquals("ok", out["status"])
        assertEquals(listOf("a", "b", "z"), out["value"])
    }

    @Test fun `agent system prompt teaches capability searching, plain text programs and the error loop`() {
        assertTrue(TowlTools.AGENT_SYSTEM.contains("never by the task's subject"))
        assertTrue(TowlTools.AGENT_SYSTEM.contains("STRUCTURED OBJECT"))
        assertTrue(TowlTools.AGENT_SYSTEM.contains("error.action"))
        assertTrue(TowlTools.AGENT_SYSTEM.contains("do not search for it again"))
    }
}
