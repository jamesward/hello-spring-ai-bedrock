package com.example.demo.synth.towl

import tools.jackson.databind.json.JsonMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the agent tool belt — pure JSON in/out against the fake catalog, no LLM, no MCP. */
class TowlToolsTest {

    private val mapper = JsonMapper.builder().build()

    private fun tools(catalog: FakeCatalog = FakeCatalog()) =
        catalog to TowlTools(TowlService(catalog, TowlRegistry.default(), emptyMap(), maxConcurrency = 2))

    @Suppress("UNCHECKED_CAST")
    private fun json(s: String): Map<String, Any?> = mapper.readValue(s, Map::class.java) as Map<String, Any?>

    /** Mirrors the framework's conversion of tool-call arguments into the plan IR. */
    private fun ir(planJson: String): PlanIr = mapper.readValue(planJson, PlanIr::class.java)

    private val SYNTH_PLAN = """
        {"towl":"v1","description":"docs",
         "let":{
           "ver":{"source":{"call":{"operation":"get_latest_version","args":{"groupId":"g","artifactId":"a"}}}},
           "syms":{"source":{"call":{"operation":"list_javadoc_symbols","args":{"version":{"ref":"ver","path":"result"}}}},
                   "filter":{"contains":[{"path":"fqn"},"Validator"]}},
           "docs":{"forEach":{"sym":{"from":{"ref":"syms"},
                   "source":{"call":{"operation":"get_javadoc_symbol",
                                     "args":{"version":{"ref":"ver","path":"result"},"link":{"ref":"sym","path":"link"}}}},
                   "result":{"class":{"afterLast":[{"ref":"sym","path":"fqn"},"."]},"doc":{"path":""}}}},
                   "onError":"collect"}},
         "result":{"ref":"docs"}}
    """

    @Test fun `towlPlanHelper returns the language guide plus matching operations`() {
        val (_, t) = tools()
        val out = json(t.towlPlanHelper(listOf("javadoc symbols"), null))
        assertTrue((out["language"] as String).contains("the function name IS the single object key"))

        @Suppress("UNCHECKED_CAST") val matched = out["matched"] as List<Map<String, Any?>>
        val names = matched.map { it["operation"] }
        assertTrue("list_javadoc_symbols" in names && "get_javadoc_symbol" in names, names.toString())
        val listEntry = matched.single { it["operation"] == "list_javadoc_symbols" }
        assertEquals("stream", (listEntry["returns"] as Map<*, *>)["kind"])

        @Suppress("UNCHECKED_CAST") val others = out["otherOperations"] as List<String>
        assertTrue("get_latest_version" in others || "get_latest_version" in names)
        assertEquals(3, (names + others).size) // every catalog operation is visible somewhere
    }

    @Test fun `towlPlanHelper with a blank query lists names only`() {
        val (_, t) = tools()
        val out = json(t.towlPlanHelper(listOf(""), null))
        assertEquals(emptyList<Any?>(), out["matched"])
        @Suppress("UNCHECKED_CAST") val others = out["otherOperations"] as List<String>
        assertEquals(3, others.size)
    }

    @Test fun `multiple queries in one call union their matches`() {
        val (_, t) = tools()
        val out = json(t.towlPlanHelper(listOf("latest version", "javadoc symbol documentation"), null))
        @Suppress("UNCHECKED_CAST") val matched = out["matched"] as List<Map<String, Any?>>
        val names = matched.map { it["operation"] }
        for (op in listOf("get_latest_version", "list_javadoc_symbols", "get_javadoc_symbol"))
            assertTrue(op in names, "$op missing from $names")
        assertEquals(names.size, names.toSet().size, "no duplicates across queries")
        assertEquals(emptyList<Any?>(), out["otherOperations"])
    }

    @Test fun `noisy subject-laden queries still resolve via capability tokens`() {
        val (_, t) = tools()
        // the exact failure mode observed live: task subjects mixed into a capability search
        val out = json(t.towlPlanHelper(listOf(
            "list symbols jackson-databind",
            "read documentation jackson-databind polymorphic",
        ), null))
        @Suppress("UNCHECKED_CAST") val names = (out["matched"] as List<Map<String, Any?>>).map { it["operation"] }
        assertTrue("list_javadoc_symbols" in names, names.toString())
        assertTrue("get_javadoc_symbol" in names, names.toString())
    }

    @Test fun `hyphenated and plural terms tokenize`() {
        val (_, t) = tools()
        val out = json(t.towlPlanHelper(listOf("javadoc-symbols listing"), null))
        @Suppress("UNCHECKED_CAST") val names = (out["matched"] as List<Map<String, Any?>>).map { it["operation"] }
        assertTrue("list_javadoc_symbols" in names, names.toString())
    }

    @Test fun `otherOperations is bounded for large catalogs`() {
        val big = object : TowlCatalog {
            override val name = "big"
            val ops = (1..40).map { i ->
                ResolvedOperation("op_$i", "does thing $i", null, null, Cardinality.ONE, null, setOf("read"), false) { null }
            }
            override fun resolve(service: String?, operation: String) =
                ops.find { it.id == operation }?.let { Resolved(it) } ?: Unknown(emptyList())
            override fun operations() = ops
        }
        val t = TowlTools(TowlService(big, TowlRegistry.default(), emptyMap(), 2))
        val out = json(t.towlPlanHelper(listOf(""), null))
        assertEquals(25, (out["otherOperations"] as List<*>).size)
        assertEquals(15, (out["otherOperationsOmitted"] as Number).toInt())
    }

    @Test fun `the generated tool input schema exposes the plan IR structure`() {
        val method = TowlTools::class.java.methods.single { it.name == "runTowlPlan" }
        val schema = org.springframework.ai.util.json.schema.JsonSchemaGenerator.generateForMethodInput(method)
        // the model sees the plan as a structured object with the closed block skeleton
        for (key in listOf("\"let\"", "\"forEach\"", "\"source\"", "\"filter\"", "\"result\"", "\"onError\"", "\"inputs\""))
            assertTrue(schema.contains(key), "$key missing from generated schema:\n$schema")
        assertTrue(!schema.contains("\"extras\""), "captured unknowns must not leak into the schema")
    }

    @Test fun `the IR round-trips losslessly to the wire format`() {
        val wire = ir(SYNTH_PLAN).toWire()
        assertEquals(json(SYNTH_PLAN), wire)
    }

    @Test fun `unknown members are captured, so brace-slip diagnostics survive the IR boundary`() {
        // the live failure: "docs" fell out of "let" to the plan level
        val plan = ir("""{"towl":"v1","description":"x",
            "let":{"ver":{"source":{"ref":"a"}}},
            "docs":{"forEach":{"sym":{"from":{"ref":"ver"},"result":1}},"onError":"collect"},
            "result":{"ref":"docs"}}""")
        val (_, t) = tools()
        val out = json(t.validateTowlPlan(plan))
        assertEquals(false, out["valid"])
        @Suppress("UNCHECKED_CAST") val errors = out["errors"] as List<Map<String, Any?>>
        val msg = errors[0]["message"] as String
        assertTrue(msg.contains("INSIDE \"let\"") && msg.contains("misplaced closing brace"), msg)
    }

    @Test fun `agent system prompt teaches capability-not-subject searching`() {
        assertTrue(TowlTools.AGENT_SYSTEM.contains("never by the task's subject"))
        assertTrue(TowlTools.AGENT_SYSTEM.contains("STRUCTURED JSON OBJECT"))
        assertTrue(TowlTools.AGENT_SYSTEM.contains("call arguments, not operation names"))
    }

    @Test fun `towlPlanHelper respects the per-query limit`() {
        val (_, t) = tools()
        val out = json(t.towlPlanHelper(listOf("javadoc version symbol"), 1))
        assertEquals(1, (out["matched"] as List<*>).size)
    }

    @Test fun `validateTowlPlan returns explain without executing`() {
        val (catalog, t) = tools()
        val out = json(t.validateTowlPlan(ir(SYNTH_PLAN)))
        assertEquals(true, out["valid"])
        val explain = out["explain"] as String
        assertTrue(explain.contains("wave 1: ver") && explain.contains("forEach sym"), explain)
        assertEquals(0, catalog.calls.size, "validate must not invoke operations")
    }

    @Test fun `validation failures come back as corrective errors, not exceptions`() {
        val (catalog, t) = tools()
        val out = json(t.validateTowlPlan(ir("""{"towl":"v1","description":"x","source":{"ref":"a"},
            "filter":{"fn":["or",{"contains":[{"path":"f"},"A"]}]}}""")))
        assertEquals(false, out["valid"])
        @Suppress("UNCHECKED_CAST") val errors = out["errors"] as List<Map<String, Any?>>
        assertTrue((errors[0]["message"] as String).contains("the function name IS the object key"), errors.toString())
        assertEquals(0, catalog.calls.size)
    }

    @Test fun `runTowlPlan validates first and returns the execution envelope`() {
        val (catalog, t) = tools()
        val out = json(t.runTowlPlan(ir(SYNTH_PLAN)))
        assertEquals(true, out["valid"])
        assertEquals("ok", out["status"])
        assertEquals(3, (out["result"] as List<*>).size)
        @Suppress("UNCHECKED_CAST") val diags = out["diagnostics"] as List<Map<String, Any?>>
        val syms = diags.single { it["binding"] == "syms" }
        assertEquals(4, (syms["streamed"] as Number).toInt())
        assertEquals(3, (syms["filterKept"] as Number).toInt())
        assertEquals(5, catalog.calls.size) // 1 version + 1 list + 3 docs
    }

    @Test fun `runTowlPlan on an invalid plan performs zero invocations`() {
        val (catalog, t) = tools()
        val out = json(t.runTowlPlan(ir("""{"towl":"v1","description":"x",
            "source":{"call":{"operation":"no_such_operation"}}}""")))
        assertEquals(false, out["valid"])
        assertEquals(0, catalog.calls.size, "an invalid plan must never reach execution")
    }

    @Test fun `runTowlPlan reports runtime failure as a structured error result`() {
        val (catalog, t) = tools()
        catalog.failOn = { id, _ -> id == "get_latest_version" }
        val out = json(t.runTowlPlan(ir(SYNTH_PLAN))) // ver has default onError=fail
        assertEquals(true, out["valid"])
        assertEquals("error", out["status"])
        @Suppress("UNCHECKED_CAST") val errors = out["errors"] as List<Map<String, Any?>>
        assertTrue((errors[0]["message"] as String).contains("boom"), errors.toString())
    }
}
