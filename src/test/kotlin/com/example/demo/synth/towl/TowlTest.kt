package com.example.demo.synth.towl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun service(catalog: TowlCatalog = FakeCatalog(), env: Map<String, Any?> = emptyMap()) =
    TowlService(catalog, TowlRegistry.default(), env, maxConcurrency = 2)

/** The full SYNTH scenario as one TOWL document; used by several phases below. */
private val SYNTH_PLAN = """
{
  "towl": "v1",
  "description": "summarize polymorphic type validation classes",
  "let": {
    "ver": {"source": {"call": {"operation": "get_latest_version",
                                "args": {"groupId": "com.x", "artifactId": "y"}}}},
    "syms": {
      "source": {"call": {"operation": "list_javadoc_symbols",
                          "args": {"version": {"ref": "ver", "path": "result"}}}},
      "filter": {"contains": [{"path": "fqn"}, "Validator"]}
    },
    "docs": {
      "forEach": {"sym": {
        "from": {"ref": "syms"},
        "source": {"call": {"operation": "get_javadoc_symbol",
                            "args": {"version": {"ref": "ver", "path": "result"},
                                     "link": {"ref": "sym", "path": "link"}}}},
        "result": {"class": {"afterLast": [{"ref": "sym", "path": "fqn"}, "."]},
                   "summary": {"path": ""}}
      }},
      "onError": "collect"
    }
  },
  "result": {"ref": "docs"}
}
"""

class TowlParserTest {
    private val parser = TowlParser(TowlRegistry.default())

    @Test fun `parses the closed union`() {
        val plan = parser.parse(SYNTH_PLAN)
        val root = assertIs<Assemble>(plan.block)
        assertEquals(setOf("ver", "syms", "docs"), root.let.keys)
        assertIs<Express>(root.let["ver"])
        val docs = assertIs<Traverse>(root.let["docs"])
        assertEquals("sym", docs.elementName)
        assertEquals(OnError.COLLECT, docs.onError)
        assertIs<Express>(docs.body)
    }

    @Test fun `role mixtures match no block shape`() {
        val e = assertFailsWith<TowlException> {
            parser.parse("""{"towl":"v1","description":"x","source":{"ref":"a"},"let":{"b":{"result":1}}}""")
        }
        assertTrue(e.message!!.contains("match no block shape"), e.message)
    }

    @Test fun `duplicate keys are rejected`() {
        assertFailsWith<TowlException> {
            parser.parse("""{"towl":"v1","description":"x","result":1,"result":2}""")
        }
    }

    @Test fun `version gate`() {
        assertFailsWith<TowlException> { parser.parse("""{"towl":"v2","description":"x","result":1}""") }
    }

    @Test fun `markdown fence is stripped and reported as preprocessing`() {
        val plan = parser.parse("```json\n{\"towl\":\"v1\",\"description\":\"x\",\"result\":\"ok\"}\n```")
        assertEquals(1, plan.preprocessing.size)
        assertEquals("ok", ((plan.block as Assemble).result as RExpr).let { (it.expr as Lit).value })
    }

    @Test fun `unknown function names never become dynamic calls`() {
        val e = assertFailsWith<TowlException> {
            parser.parse("""{"towl":"v1","description":"x","source":{"ref":"a"},"filter":{"frobnicate":[1]}}""")
        }
        assertTrue(e.message!!.contains("frobnicate"), e.message)
    }

    @Test fun `a single unknown key in result position is a single-member product`() {
        val plan = parser.parse("""{"towl":"v1","description":"x","result":{"frobnicate":[1]}}""")
        val r = assertIs<RRecord>((plan.block as Assemble).result)
        assertEquals(setOf("frobnicate"), r.members.keys)
    }

    @Test fun `reserved keys cannot be binder names`() {
        assertFailsWith<TowlException> {
            parser.parse("""{"towl":"v1","description":"x","let":{"ref":{"result":1}}}""")
        }
    }

    @Test fun `onError collect is Traverse-only`() {
        val e = assertFailsWith<TowlException> {
            parser.parse("""{"towl":"v1","description":"x","source":{"ref":"a"},"onError":"collect"}""")
        }
        assertTrue(e.message!!.contains("collect"), e.message)
    }

    @Test fun `dedup must be non-empty`() {
        assertFailsWith<TowlException> {
            parser.parse("""{"towl":"v1","description":"x","source":{"ref":"a"},"dedup":[]}""")
        }
    }

    @Test fun `the fn-wrapper hallucination gets a corrective diagnostic`() {
        val e = assertFailsWith<TowlException> {
            parser.parse("""{"towl":"v1","description":"x","source":{"ref":"a"},
                "filter":{"fn":["or",{"contains":[{"path":"fqn"},"A"]},{"contains":[{"path":"fqn"},"B"]}]}}""")
        }
        assertTrue(e.message!!.contains("the function name IS the object key"), e.message)
        assertTrue(e.message!!.contains("{\"or\": [...]}"), e.message)
    }

    @Test fun `paths reject positional indexes`() {
        assertFailsWith<TowlException> {
            parser.parse("""{"towl":"v1","description":"x","result":{"ref":"a","path":"items[0].name"}}""")
        }
    }
}

class TowlValidatorTest {
    private val svc = service()

    @Test fun `synth plan validates and freezes resolutions`() {
        val validated = svc.validate(svc.parse(SYNTH_PLAN))
        assertEquals(3, validated.resolutions.size)
        assertTrue(validated.warnings.isEmpty(), validated.warnings.toString())
    }

    @Test fun `unknown operation with did-you-mean`() {
        val e = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "source":{"call":{"operation":"list_javadoc_symbolz","args":{"version":"1"}}}}"""))
        }
        assertTrue(e.message!!.contains("unknown operation"), e.message)
        assertTrue(e.message!!.contains("list_javadoc_symbols"), e.message)
    }

    @Test fun `argument schema is enforced`() {
        val missing = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "source":{"call":{"operation":"get_latest_version","args":{"groupId":"g"}}}}"""))
        }
        assertTrue(missing.message!!.contains("artifactId"), missing.message)
        val unknown = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "source":{"call":{"operation":"get_latest_version",
                                  "args":{"groupId":"g","artifactId":"a","bogus":1}}}}"""))
        }
        assertTrue(unknown.message!!.contains("bogus"), unknown.message)
    }

    @Test fun `paginate is capability-gated`() {
        val e = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "source":{"call":{"operation":"list_javadoc_symbols","args":{"version":"1"},
                                  "paginate":{"maxItems":10}}}}"""))
        }
        assertTrue(e.message!!.contains("capability-gated"), e.message)
    }

    @Test fun `bare paths are scoped to Express stages and result`() {
        val e = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "let":{"a":{"result":1}},
                "result":{"path":"nope"}}"""))
        }
        assertTrue(e.message!!.contains("bare path"), e.message)
    }

    @Test fun `implicit paths never reach call args`() {
        val e = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "source":{"call":{"operation":"get_latest_version",
                                  "args":{"groupId":{"path":"g"},"artifactId":"a"}}}}"""))
        }
        assertTrue(e.message!!.contains("bare path"), e.message)
    }

    @Test fun `shadowing is invalid`() {
        val e = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "let":{"a":{"result":1},
                       "b":{"forEach":{"a":{"from":{"ref":"a"},"result":2}}}}, "result":{"ref":"b"}}"""))
        }
        assertTrue(e.message!!.contains("shadows"), e.message)
    }

    @Test fun `traversal depth is bounded at two`() {
        val e = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "forEach":{"a":{"from":{"input":"xs"},
                  "forEach":{"b":{"from":{"ref":"a"},
                    "forEach":{"c":{"from":{"ref":"b"},"result":1}}}}}},
                "inputs":{"xs":{"type":"array","default":[]}}}"""))
        }
        assertTrue(e.message!!.contains("depth"), e.message)
    }

    @Test fun `grouping rule rejects element-dependent pure leaves beside aggregates`() {
        val e = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "source":{"call":{"operation":"list_javadoc_symbols","args":{"version":"1"}}},
                "result":{"n":{"count":[]},"fqn":{"path":"fqn"}}}"""))
        }
        assertTrue(e.message!!.contains("non-grouped-column"), e.message)
    }

    @Test fun `ambiguous sinks require an explicit result`() {
        val e = assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x",
                "let":{"a":{"result":1},"b":{"result":2}}}"""))
        }
        assertTrue(e.message!!.contains("sink"), e.message)
    }

    @Test fun `unused forEach element variable warns but validates`() {
        val v = svc.validate(svc.parse("""{"towl":"v1","description":"x",
            "forEach":{"item":{"from":{"input":"xs"},"result":"constant"}},
            "inputs":{"xs":{"type":"array","default":[1]}}}"""))
        assertTrue(v.warnings.single().contains("never referenced"), v.warnings.toString())
    }
}

class TowlRunTest {
    @Test fun `runs the synth scenario end to end against the fake catalog`() {
        val catalog = FakeCatalog()
        val svc = service(catalog)
        val result = svc.run(svc.validate(svc.parse(SYNTH_PLAN)))

        @Suppress("UNCHECKED_CAST") val docs = result as List<Map<String, Any?>>
        assertEquals(3, docs.size) // ObjectMapper filtered out before fan-out
        val first = docs[0]["ok"] as Map<*, *>
        assertEquals("PolymorphicTypeValidator", first["class"])
        assertTrue((first["summary"] as String).startsWith("documentation for ptv.html"), first.toString())

        // one version call, one list call, one doc call per filtered symbol — order preserved
        assertEquals(listOf("get_latest_version", "list_javadoc_symbols"), catalog.calls.take(2).map { it.first })
        assertEquals(3, catalog.calls.count { it.first == "get_javadoc_symbol" })
        assertEquals("2.22.2", catalog.calls[1].second["version"])
    }

    @Test fun `collect turns per-element failures into data`() {
        val catalog = FakeCatalog()
        catalog.failOn = { id, args -> id == "get_javadoc_symbol" && args["link"] == "bptv.html" }
        val svc = service(catalog)

        @Suppress("UNCHECKED_CAST")
        val docs = svc.run(svc.validate(svc.parse(SYNTH_PLAN))) as List<Map<String, Any?>>
        assertEquals(3, docs.size)
        val err = docs[1]["error"] as Map<*, *>
        assertTrue((err["message"] as String).contains("boom"), err.toString())
        assertEquals("bptv.html", (err["item"] as Map<*, *>)["link"])
        assertTrue(docs[0].containsKey("ok") && docs[2].containsKey("ok"))
    }

    @Test fun `skip drops failed elements, fail aborts`() {
        fun plan(onError: String) = """
            {"towl":"v1","description":"x",
             "forEach":{"sym":{
               "from":{"input":"links"},
               "source":{"call":{"operation":"get_javadoc_symbol",
                                 "args":{"version":"1","link":{"ref":"sym"}}}},
               "result":{"path":""}}},
             "onError":"$onError",
             "inputs":{"links":{"type":"array","default":["a.html","b.html","c.html"]}}}
        """
        val skipCat = FakeCatalog().also { it.failOn = { _, args -> args["link"] == "b.html" } }
        val skipSvc = service(skipCat)
        val kept = skipSvc.run(skipSvc.validate(skipSvc.parse(plan("skip")))) as List<*>
        assertEquals(2, kept.size)

        val failCat = FakeCatalog().also { it.failOn = { _, args -> args["link"] == "b.html" } }
        val failSvc = service(failCat)
        assertFailsWith<RuntimeException> { failSvc.run(failSvc.validate(failSvc.parse(plan("fail")))) }
    }

    @Test fun `express skip yields an explicitly absent value`() {
        val catalog = FakeCatalog().also { it.failOn = { id, _ -> id == "get_latest_version" } }
        val svc = service(catalog)
        val result = svc.run(svc.validate(svc.parse("""
            {"towl":"v1","description":"x",
             "source":{"call":{"operation":"get_latest_version","args":{"groupId":"g","artifactId":"a"}}},
             "onError":"skip"}""")))
        assertNull(result)
    }

    @Test fun `aggregate result leaves fold the stream and preserve empty groups`() {
        val svc = service()
        val stats = svc.run(svc.validate(svc.parse("""
            {"towl":"v1","description":"x",
             "source":{"call":{"operation":"list_javadoc_symbols","args":{"version":"1"}}},
             "filter":{"contains":[{"path":"fqn"},"Validator"]},
             "result":{"n":{"count":[]},"names":{"collect":[{"afterLast":[{"path":"fqn"},"."]}]}}}"""))) as Map<*, *>
        assertEquals(3L, stats["n"])
        assertEquals(listOf("PolymorphicTypeValidator", "BasicPolymorphicTypeValidator", "SubTypeValidator"), stats["names"])

        val empty = svc.run(svc.validate(svc.parse("""
            {"towl":"v1","description":"x",
             "source":{"call":{"operation":"list_javadoc_symbols","args":{"version":"1"}}},
             "filter":{"contains":[{"path":"fqn"},"NoSuchThing"]},
             "result":{"n":{"count":[]},"names":{"collect":[{"path":"fqn"}]}}}"""))) as Map<*, *>
        assertEquals(0L, empty["n"])
        assertEquals(emptyList<Any?>(), empty["names"])
    }

    @Test fun `dedup preserves first occurrence order`() {
        val svc = service()
        val out = svc.run(svc.validate(svc.parse("""
            {"towl":"v1","description":"x",
             "source":{"input":"xs"},
             "dedup":[{"path":"k"}],
             "result":{"path":"k"},
             "inputs":{"xs":{"type":"array","default":[{"k":"b"},{"k":"a"},{"k":"b"},{"k":"c"}]}}}""")))
        assertEquals(listOf("b", "a", "c"), out)
    }

    @Test fun `required inputs without defaults must be supplied`() {
        val svc = service()
        val v = svc.validate(svc.parse("""
            {"towl":"v1","description":"x","result":{"input":"name"},
             "inputs":{"name":{"type":"string"}}}"""))
        assertFailsWith<TowlException> { svc.run(v) }
        assertEquals("towl", svc.run(v, mapOf("name" to "towl")))
    }

    @Test fun `env is a closed schema`() {
        val svc = service(env = mapOf("runId" to "r-1"))
        val v = svc.validate(svc.parse("""{"towl":"v1","description":"x","result":{"env":"runId"}}"""))
        assertEquals("r-1", svc.run(v))
        assertFailsWith<TowlException> {
            svc.validate(svc.parse("""{"towl":"v1","description":"x","result":{"env":"nope"}}"""))
        }
    }

    @Test fun `document order does not matter — dependencies do`() {
        val svc = service(FakeCatalog())
        // 'second' is declared before 'first' but depends on it
        val out = svc.run(svc.validate(svc.parse("""
            {"towl":"v1","description":"x",
             "let":{
               "second":{"source":{"call":{"operation":"list_javadoc_symbols",
                                           "args":{"version":{"ref":"first","path":"version"}}}}},
               "first":{"source":{"call":{"operation":"get_latest_version",
                                          "args":{"groupId":"g","artifactId":"a"}}}}},
             "result":{"n":{"size":[{"ref":"second"}]}}}"""))) as Map<*, *>
        assertEquals(4, out["n"])
    }
}

class TowlExecutionResultTest {
    private fun diag(r: ExecutionResult, binding: String): Map<String, Any?> =
        r.diagnostics.single { it["binding"] == binding }

    @Test fun `envelope carries per-binding accounting`() {
        val svc = service(FakeCatalog())
        val r = svc.execute(svc.validate(svc.parse(SYNTH_PLAN)))
        assertEquals("ok", r.status)

        val syms = diag(r, "syms")
        assertEquals("list_javadoc_symbols", syms["operation"])
        assertEquals(1L, syms["calls"])
        assertEquals(4L, syms["streamed"])
        assertEquals(3L, syms["filterKept"])
        assertEquals(1L, syms["filterDropped"])

        val docs = diag(r, "docs")
        assertEquals(3L, docs["elements"])
        assertEquals(3L, docs["ok"])

        val body = diag(r, "docs.sym")
        assertEquals("get_javadoc_symbol", body["operation"])
        assertEquals(3L, body["calls"])
    }

    @Test fun `collected failures make the status partial`() {
        val catalog = FakeCatalog()
        catalog.failOn = { id, args -> id == "get_javadoc_symbol" && args["link"] == "bptv.html" }
        val svc = service(catalog)
        val r = svc.execute(svc.validate(svc.parse(SYNTH_PLAN)))
        assertEquals("partial", r.status)
        val docs = diag(r, "docs")
        assertEquals(2L, docs["ok"])
        assertEquals(1L, docs["failed"])
    }

    @Test fun `skipped elements make the status partial`() {
        val catalog = FakeCatalog()
        catalog.failOn = { id, args -> id == "get_javadoc_symbol" && args["link"] == "bptv.html" }
        val svc = service(catalog)
        val plan = SYNTH_PLAN.replace("\"collect\"", "\"skip\"")
        val r = svc.execute(svc.validate(svc.parse(plan)))
        assertEquals("partial", r.status)
        assertEquals(1L, diag(r, "docs")["skipped"])
        assertEquals(2, (r.result as List<*>).size)
    }

    @Test fun `run equals the envelope result`() {
        val svc = service(FakeCatalog())
        val v = svc.validate(svc.parse(SYNTH_PLAN))
        assertEquals(svc.execute(v).result, svc.run(v))
    }

    @Test fun `an empty filter is visible in the envelope, not just absent from the result`() {
        val svc = service(FakeCatalog())
        val r = svc.execute(svc.validate(svc.parse(SYNTH_PLAN.replace("Validator", "NoSuchThing"))))
        assertEquals("ok", r.status)
        assertEquals(emptyList<Any?>(), r.result)
        val syms = diag(r, "syms")
        assertEquals(4L, syms["streamed"])
        assertEquals(0L, syms["filterKept"])
        assertEquals(0L, diag(r, "docs")["elements"])
    }
}

class TowlExplainAndSchemaTest {
    @Test fun `explain is a dry run — no invocations`() {
        val catalog = FakeCatalog()
        val svc = service(catalog)
        val text = svc.explain(svc.validate(svc.parse(SYNTH_PLAN)))
        assertEquals(0, catalog.calls.size)
        assertTrue(text.contains("wave 1: ver"), text)
        assertTrue(text.contains("wave 2: syms"), text)
        assertTrue(text.contains("wave 3: docs"), text)
        assertTrue(text.contains("forEach sym (onError=collect)"), text)
        assertTrue(text.contains("call get_latest_version [ONE]"), text)
        assertTrue(text.contains("call list_javadoc_symbols [MANY]"), text)
        assertTrue(text.contains("once per sym"), text)
    }

    @Test fun `planner prompt is generated from registry and the RESOLVED catalog`() {
        val svc = service()
        val prompt = svc.plannerSystemPrompt()
        assertTrue(prompt.contains("\"towl\":\"v1\"") || prompt.contains("\"towl\": \"v1\""))
        for (fn in listOf("contains", "afterLast")) assertTrue(prompt.contains("$fn("), fn)
        assertTrue(!prompt.contains("take("), "removed function must not be advertised")
        assertTrue(prompt.contains("count()"))
        assertTrue(prompt.contains("closed shapes"))
        // the TOWL/catalog connection is stated, not implied
        assertTrue(prompt.contains("How TOWL connects to the operation catalog"))
        assertTrue(prompt.contains("ALREADY removed"))
        assertTrue(prompt.contains("may be empty"))
        // application syntax is stated with the name-as-key rule; no literal-looking "fn" metavariable
        assertTrue(prompt.contains("the function name IS the single object key"))
        assertTrue(prompt.contains("never write {\"fn\": [...]}"))
        assertTrue(prompt.contains("legal ONLY when the plan declares it"))
        for (op in listOf("get_latest_version", "list_javadoc_symbols", "get_javadoc_symbol"))
            assertTrue(prompt.contains(op), op)
    }

    @Test fun `catalog json states stream, value, and text return kinds with unwrapped element schemas`() {
        val svc = service()
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        @Suppress("UNCHECKED_CAST")
        val entries = mapper.readValue(svc.catalogJson(), List::class.java) as List<Map<String, Any?>>
        val byOp = entries.associateBy { it["operation"] }

        val stream = byOp.getValue("list_javadoc_symbols")["returns"] as Map<*, *>
        assertEquals("stream", stream["kind"])
        val element = stream["element"] as Map<*, *>
        val props = element["properties"] as Map<*, *>
        assertTrue("fqn" in props && "link" in props, props.keys.toString())
        assertTrue((stream["note"] as String).contains("wrapper"), stream["note"] as String)

        val value = byOp.getValue("get_latest_version")["returns"] as Map<*, *>
        assertEquals("value", value["kind"])
        assertTrue((value["schema"] as Map<*, *>).containsKey("properties"))

        val text = byOp.getValue("get_javadoc_symbol")["returns"] as Map<*, *>
        assertEquals("text", text["kind"])
        assertTrue((text["note"] as String).contains("{\"path\": \"\"}"), text["note"] as String)

        // args schema is carried verbatim, including required
        val args = byOp.getValue("get_latest_version")["args"] as Map<*, *>
        assertEquals(listOf("groupId", "artifactId"), args["required"])
    }

    @Test fun `registry rejects reserved and overlapping names`() {
        assertFailsWith<IllegalArgumentException> {
            TowlRegistry(listOf(FunctionDef("ref", 1, 1, "bad") { it }), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            TowlRegistry(
                listOf(FunctionDef("count", 1, 1, "clash") { it }),
                listOf(AggregatorDef("count", 0, "clash", 0L, { 1L }, { a, _ -> a })),
            )
        }
    }
}
