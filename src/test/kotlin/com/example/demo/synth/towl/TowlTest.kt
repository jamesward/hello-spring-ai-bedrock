package com.example.demo.synth.towl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun service(catalog: FakeCatalog = FakeCatalog(), maxWidth: Int = 200, maxCalls: Int = 500) =
    TowlService(catalog, maxConcurrency = 4, maxWidth = maxWidth, maxCalls = maxCalls)

/** TOWL §14 example 7 against the fake catalog: the whole SYNTH scenario as one program. */
private val SYNTH = """
towl 3 "classes about polymorphic type validation, with a digest of each"
ver  = call("javadocs", "get_latest_version", { groupId: "com.x", artifactId: "y" }).result
syms = call("javadocs", "list_javadoc_symbols", { groupId: "com.x", artifactId: "y", version: ver })
         .result.where(.fqn.contains("Validator"))
for s in syms
  doc = call("javadocs", "get_javadoc_symbol", { groupId: "com.x", artifactId: "y", version: ver, link: s.link })
  { class: s.fqn.after_last("."), summary: call("llm", "summarize", { text: doc }) }
"""

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.list(key: String) = this[key] as List<Any?>
@Suppress("UNCHECKED_CAST")
private fun Any?.rec() = this as Map<String, Any?>

private fun diagnostics(catalog: FakeCatalog, src: String): List<Diagnostic> =
    assertFailsWith<TowlException> { service(catalog).validate(src) }.diagnostics

private fun codes(catalog: FakeCatalog, src: String) = diagnostics(catalog, src).filter { it.severity == "error" }.map { it.code }

class ParserTest {
    private val ns = setOf("javadocs", "llm", "store")

    @Test fun `parses the synth program`() {
        val p = Parser(SYNTH, ns).program()
        assertEquals(listOf("ver", "syms"), p.bindings.map { it.name })
        val m = assertIs<MethodCall>(p.result)
        assertEquals("for", m.name)
        val lam = assertIs<LambdaArg>(m.args.single())
        val body = assertIs<BlockE>(lam.body)
        assertEquals("doc", body.bindings.single().name)
        assertIs<OpCall>(body.bindings.single().expr)
    }

    @Test fun `braces are records and blocks are laid out`() {
        assertIs<RecordE>(Parser("towl 3 { a: 1 }", ns).program().result)
        assertIs<RecordE>(Parser("towl 3 {}", ns).program().result)
        assertEquals("syntax.brace", assertFailsWith<TowlException> { Parser("towl 3 { a = 1  { b: a } }", ns).program() }.diagnostics.single().code)
        // a for body: bindings then the result on indented lines; the block ends at its result
        val p = Parser("towl 3\nxs = [1]\nfor x in xs\n  a = x\n  b = 2\n  { a: a, b: b }\n", ns).program()
        val body = assertIs<BlockE>((p.result as MethodCall).args.single().let { (it as LambdaArg).body })
        assertEquals(listOf("a", "b"), body.bindings.map { it.name })
        // same-line bodies: a record, or any single expression; ';' still separates one-line items
        assertIs<RecordE>(((Parser("towl 3 xs = [1]; for x in xs { a: x }", ns).program().result as MethodCall).args.single() as LambdaArg).body)
        assertIs<MethodCall>(((Parser("towl 3 for x in [[1]] x.count()", ns).program().result as MethodCall).args.single() as LambdaArg).body)
        // layout diagnostics
        for ((src, code) in listOf(
            "towl 3\nfor x in [1]\n  { a: x }\n  y = 1\ny" to "syntax.resultNotLast",
            "towl 3\nxs = [1]\nfor x in xs\n{ a: x }" to "syntax.indent",
            "towl 3\nfor x in [1]\n  a = 1\n   { a: a }" to "syntax.indent",
            "towl 3\nx = 1\n{ a: x }\ny = 2" to "syntax.trailing",
            "towl 3\nfor x in [1]\n\t{ a: x }" to "syntax.tab",
        )) assertEquals(code, assertFailsWith<TowlException> { Parser(src, ns).program() }.diagnostics.single().code, src)
        // the Python reflex `for x in xs:` is accepted; layout is not checked inside brackets
        Parser("towl 3 for x in [1]: { a: x }", ns).program()
        Parser("towl 3 { a: for x in [1]\n{ b: x } }", ns).program()
    }

    @Test fun `predicates parse with precedence and nested quantifiers`() {
        val p = Parser("""towl 3 xs.where(!.a.present() && .b == "x" || .items.any(.n > 3 && .k in ["p", "q"]))""", ns).program()
        val where = assertIs<MethodCall>(p.result)
        val pred = assertIs<PredArg>(where.args.single()).pred
        val or = assertIs<OrP>(pred)
        assertIs<AndP>(or.terms[0])
        val q = assertIs<QuantP>(or.terms[1])
        assertIs<AndP>(q.inner)
    }

    @Test fun `wrong version and general-purpose forms are syntax errors`() {
        for (bad in listOf("towl 2 \"x\" 1", "towl 3 xs.map(x => x)", "towl 3 xs.each(x => x)", "towl 3 xs.map@x { a: x }", "towl 3 for (x in xs) { a: x }", "towl 3 xs.first()", "towl 3 x = 1", "towl 3 1 2",
                           "towl 3 javadocs.get_latest_version({})", "towl 3 call(javadocs, \"get_latest_version\")", "towl 3 xs.where(.a.or(1) == 1)"))
            assertFailsWith<TowlException>(bad) { Parser(bad, ns).program() }
    }

    @Test fun `comments, trailing commas and single quotes are tolerated`() {
        val p = Parser("towl 3 'd' // note\n# another\nx = [1, 2,]\n{ a: x, }", ns).program()
        assertEquals(1, p.bindings.size)
    }
}

class CheckerTest {
    private val catalog = FakeCatalog()

    @Test fun `types the synth program end to end`() {
        val c = service(catalog).validate(SYNTH)
        assertEquals("string", c.bindingTypes["ver"].toString())
        assertEquals("list[${catalog.symbolType}]", c.bindingTypes["syms"].toString())
        assertEquals("list[{ class: string, summary: string }]", c.resultType.toString())
        assertEquals(listOf("javadocs.get_latest_version", "javadocs.list_javadoc_symbols", "javadocs.get_javadoc_symbol", "llm.summarize"), c.effects.map { it.op.id })
        assertEquals(listOf(1, 1, null, null), c.effects.map { it.staticWidth })
        assertEquals(1, c.waves.size)
        assertTrue(c.warnings.isEmpty(), c.warnings.toString())
    }

    @Test fun `flat versus project is a visible type difference`() {
        val src = """towl 3 call("javadocs", "count_symbols", { version: "1" }).sizes"""
        assertEquals("list[int]", service(catalog).validate(src).resultType.toString())
        val nested = """towl 3
            xs = [call("javadocs", "count_symbols", { version: "1" }), call("javadocs", "count_symbols", { version: "2" })]
            { nested: xs.project(.sizes), flat: xs.flat(.sizes), n: xs.sum(.count) }"""
        assertEquals("{ nested: list[list[int]], flat: list[int], n: int }", service(catalog).validate(nested).resultType.toString())
    }

    @Test fun `nullable members must go through null-safe access`() {
        assertEquals(listOf("type.nullableAccess"), codes(catalog, """towl 3 call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" }, { tolerate: ["NotFound"] }).result"""))
        val ok = service(catalog).validate("""towl 3 call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result.project(.kind)""")
        assertEquals("list[string | Null]", ok.resultType.toString())
        // string functions are total on string | Null and stay nullable
        val nullable = service(catalog).validate("""towl 3 call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result.project(.kind.upper())""")
        assertEquals("list[string | Null]", nullable.resultType.toString())
    }

    @Test fun `tolerate makes a call nullable and rejects transient codes`() {
        val c = service(catalog).validate("""towl 3 call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" }, { tolerate: ["NotFound"] })?.result""")
        assertEquals("string | Null", c.resultType.toString())
        assertEquals(listOf("catalog.tolerateClass"), codes(catalog, """towl 3 call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" }, { tolerate: ["Throttling"] })"""))
    }

    @Test fun `all diagnostics are reported in one pass`() {
        val diags = codes(catalog, """towl 3
            a = call("javadocs", "get_latest_version", { groupId: "g" })
            b = nothing.here
            unused = 1
            { a: a.result, b: b, c: call("javadocs", "get_javadoc_symbol", { groupId: "g", artifactId: "a", version: "1", link: call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" }).result }) }""")
        assertTrue("catalog.missingParameter" in diags, diags.toString())
        assertTrue("names.undefined" in diags, diags.toString())
        assertTrue("names.unreferenced" in diags, diags.toString())
        assertTrue("syntax.pureLayer" in diags, diags.toString())
    }

    @Test fun `catalog checks name unknown operations members and parameters with fixes`() {
        val d = diagnostics(catalog, """towl 3 call("javadocs", "get_version", { group: "g" })""")
        assertEquals("catalog.unknownOperation", d.single { it.severity == "error" }.code)
        assertTrue(d.single { it.severity == "error" }.fix!!.contains("get_latest_version"))
        val m = diagnostics(catalog, """towl 3 call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" }).version""")
        assertEquals("catalog.unknownMember", m.single { it.severity == "error" }.code)
    }

    @Test fun `a wrapper member on a bare-string result names the operation in the fix`() {
        // the live mistake: .result on get_javadoc_symbol, which returns the text itself
        val d = diagnostics(catalog, """towl 3 call("javadocs", "get_javadoc_symbol", { groupId: "g", artifactId: "a", version: "1", link: "x" }).result""")
        val err = d.single { it.severity == "error" }
        assertEquals("type.notRecord", err.code)
        assertTrue(err.fix!!.contains("javadocs.get_javadoc_symbol returns the string itself") && err.fix!!.contains("drop '.result'"), err.fix)
    }

    @Test fun `namespaces are ordinary names and paths need an element`() {
        assertEquals(1, service(catalog).execute(service(catalog).validate("towl 3 llm = 1\nllm"), emptyMap())["value"])
        val d = diagnostics(catalog, """towl 3 call("nope", "x")""")
        assertEquals("catalog.unknownNamespace", d.single().code); assertTrue(d.single().fix!!.contains("namespaces: javadocs, llm, store"), d.toString())
        assertEquals(listOf("syntax.pathOutsideElement"), codes(catalog, """towl 3 x = call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" })
            { v: .result, x: x }"""))
    }

    @Test fun `static width is checked against the budget before anything runs`() {
        val src = """towl 3 for v in ["a", "b", "c"] call("javadocs", "get_latest_version", { groupId: v, artifactId: "a" }).result"""
        assertEquals("list[string]", service(catalog).validate(src).resultType.toString())
        val errs = assertFailsWith<TowlException> { service(catalog, maxWidth = 2).validate(src) }.diagnostics
        assertEquals(listOf("for.staticWidthExceeded"), errs.filter { it.severity == "error" }.map { it.code })
        assertEquals(0, catalog.calls.size)
    }

    @Test fun `empty literals take their type from context or are rejected`() {
        assertEquals("list[string]", service(catalog).validate("""towl 3 ["a"].concat([])""").resultType.toString())
        assertEquals(listOf("type.emptyLiteral"), codes(catalog, "towl 3 []"))
        assertEquals(listOf("type.emptyLiteral"), codes(catalog, "towl 3 { a: {} }"))
    }

    @Test fun `tolerate accepts only absence, authorization, availability and state codes`() {
        for (c in listOf("NotFound", "AccessDenied", "OptInRequired", "InvalidState")) service(catalog).validate("""towl 3 call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" }, { tolerate: ["$c"] })""")
        for (c in listOf("Throttling", "ValidationException", "WeirdThing"))
            assertEquals(listOf("catalog.tolerateClass"), codes(catalog, """towl 3 call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" }, { tolerate: ["$c"] })"""), c)
    }

    @Test fun `opaque json cannot be navigated`() {
        val cat = object : TowlCatalog by catalog {
            override val namespaces = catalog.namespaces + "raw"
            override fun operation(namespace: String, name: String) =
                if (namespace == "raw") OperationSpec("raw", "blob", null, null, TJson, Effect.READ) { mapOf("a" to 1) } else catalog.operation(namespace, name)
        }
        val errs = assertFailsWith<TowlException> { TowlService(cat).validate("towl 3 call(\"raw\", \"blob\").a") }.diagnostics
        assertEquals("type.opaque", errs.single { it.severity == "error" }.code)
        assertEquals("json", TowlService(cat).validate("towl 3 call(\"raw\", \"blob\")").resultType.toString())
    }
}

class RuntimeTest {

    @Test fun `runs the synth program with a wave and reports nodes`() {
        val catalog = FakeCatalog()
        val s = service(catalog)
        val out = s.execute(s.validate(SYNTH))
        assertEquals("ok", out["status"])
        val value = out.list("value")
        assertEquals(3, value.size)
        assertEquals(setOf("PolymorphicTypeValidator", "BasicPolymorphicTypeValidator", "SubTypeValidator"), value.map { it.rec()["class"] }.toSet())
        assertTrue(value.all { (it.rec()["summary"] as String).startsWith("summary(") })
        assertEquals(1 + 1 + 3 + 3, catalog.calls.size)
        val nodes = out.list("nodes").map { it.rec() }
        val where = nodes.single { it["node"] == "where" }
        assertEquals(4L, (where["in"] as Number).toLong()); assertEquals(3L, (where["out"] as Number).toLong())
        assertEquals(1, (out["accounting"].rec()["waves"] as Number).toInt())
        assertEquals("list[{ class: string, summary: string }]", out["type"])
    }

    @Test fun `independent bindings run concurrently and join`() {
        val catalog = FakeCatalog().apply { latencyMs = 150 }
        val s = service(catalog)
        val src = """towl 3
            a = call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" })
            b = call("javadocs", "count_symbols", { version: "1" })
            { version: a.result, count: b.count }"""
        val started = System.nanoTime()
        val out = s.execute(s.validate(src))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals("ok", out["status"])
        assertEquals(mapOf("version" to "2.22.2", "count" to 4), out["value"])
        assertEquals(2, catalog.maxInFlight.get(), "both calls were in flight together")
        assertTrue(ms < 280, "took ${ms}ms; expected the two 150ms calls to overlap")
    }

    @Test fun `result lists are in canonical order regardless of scheduling`() {
        val catalog = FakeCatalog()
        val s = service(catalog)
        val src = """towl 3 for x in call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result call("javadocs", "get_javadoc_symbol", { groupId: "g", artifactId: "a", version: "1", link: x.link })"""
        val a = s.execute(s.validate(src))["value"]
        val b = TowlService(catalog, maxConcurrency = 1).let { it.execute(it.validate(src))["value"] }
        assertEquals(a, b)
    }

    @Test fun `group aggregate and predicates`() {
        val s = service()
        val src = """towl 3
            syms = call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result
            syms.group(.kind).project({ kind: .key, n: .items.count(), names: .items.collect(.fqn) })"""
        val out = s.execute(s.validate(src))
        val groups = out.list("value").map { it.rec() }
        assertEquals(setOf("class", "interface"), groups.map { it["kind"] }.toSet())
        assertEquals(3, groups.single { it["kind"] == "class" }["n"])
    }

    @Test fun `tolerate yields null and is reported`() {
        val catalog = FakeCatalog().apply { failOn = { id, a -> if (id == "javadocs.get_javadoc_symbol" && a["link"] == "om.html") OperationError("NotFound", "gone") else null } }
        val s = service(catalog)
        val src = """towl 3
            for x in call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result { class: x.fqn.after_last("."),
              doc: call("javadocs", "get_javadoc_symbol", { groupId: "g", artifactId: "a", version: "1", link: x.link }, { tolerate: ["NotFound"] }) }"""
        val c = s.validate(src)
        assertEquals("list[{ class: string, doc: string | Null }]", c.resultType.toString())
        val out = s.execute(c)
        assertEquals("ok", out["status"])
        assertNull(out.list("value").map { it.rec() }.single { it["class"] == "ObjectMapper" }["doc"])
        assertEquals(1, out.list("tolerated").size)
    }

    @Test fun `an untolerated failure stops the program and returns only complete values`() {
        val catalog = FakeCatalog().apply { failOn = { id, a -> if (id == "javadocs.get_javadoc_symbol" && a["link"] == "bptv.html") OperationError("AccessDenied", "no") else null } }
        val s = service(catalog)
        val out = s.execute(s.validate(SYNTH))
        assertEquals("error", out["status"])
        val err = out["error"].rec()
        assertEquals("authorization", err["class"]); assertEquals("AccessDenied", err["code"]); assertEquals("tolerate-candidate", err["action"])
        assertEquals("javadocs.get_javadoc_symbol", err["operation"])
        val completed = out["completed"].rec()
        assertEquals(setOf("ver", "syms"), completed.keys)
        assertEquals("2.22.2", completed["ver"].rec()["value"])
        val fan = out.list("fanout").single().rec()
        assertEquals(1, fan.list("failed").size)
        assertEquals("bptv.html", fan.list("failed").single().rec()["element"].rec()["link"])
        assertEquals(3, fan.list("completed").size + fan.list("failed").size + fan.list("interrupted").size + fan.list("not_started").size)
        assertNull(out["value"])
    }

    @Test fun `a resume program takes completed values as inputs`() {
        val s = service()
        val src = """towl 3 "resume"
            input done: list[{ class: string, summary: string }]
            input remaining: list[{ fqn: string, link: string, kind: string | Null }]
            done.concat(for x in remaining { class: x.fqn.after_last("."), summary: call("llm", "summarize", { text: x.link }) })"""
        val c = s.validate(src)
        val out = s.execute(c, mapOf(
            "done" to listOf(mapOf("class" to "A", "summary" to "s")),
            "remaining" to listOf(mapOf("fqn" to "com.x.B", "link" to "b.html", "kind" to null)),
        ))
        assertEquals("ok", out["status"])
        assertEquals(2, out.list("value").size)
        val bad = s.execute(c, mapOf("done" to "not a list"))
        assertEquals("error", bad["status"]); assertEquals("input", bad["error"].rec()["class"])
    }

    @Test fun `scalar aggregates, top and bottom, and time functions`() {
        val s = service()
        val src = """towl 3 "sizes"
            input now: timestamp
            input xs: list[int]
            sizes = call("javadocs", "count_symbols", { version: "1" }).sizes
            syms = call("javadocs", "list_javadoc_symbols", { groupId: "com.x", artifactId: "y", version: "1" }).result
            { total: sizes.sum(), biggest: sizes.max(), top2: xs.top(2), first: syms.bottom(1, .fqn),
              since: now.minus_days(4), day: now.start_of_day(), month: now.start_of_month().date() }"""
        val c = s.validate(src)
        assertTrue(c.resultType.toString().contains("top2: list[{ rank: int, value: int }]"), c.resultType.toString())
        val out = s.execute(c, mapOf("now" to "2026-09-14T23:06:17Z", "xs" to listOf(3, 9, 1)))
        assertEquals("ok", out["status"], out.toString())
        val v = out["value"].rec()
        assertEquals(6, v["total"]); assertEquals(3, v["biggest"])
        assertEquals(listOf(mapOf("rank" to 1, "value" to 9), mapOf("rank" to 2, "value" to 3)), v["top2"])
        assertEquals("com.x.BasicPolymorphicTypeValidator", v.list("first")[0].rec()["value"].rec()["fqn"])
        assertEquals("2026-09-10T23:06:17Z", v["since"]); assertEquals("2026-09-14T00:00:00Z", v["day"]); assertEquals("2026-09-01", v["month"])
        // the host binds `now` when the caller does not; `now`/`today` are predefined and need no declaration
        val auto = s.execute(s.validate("towl 3 input now: timestamp now.date()"), emptyMap())
        assertEquals("ok", auto["status"]); assertTrue((auto["value"] as String).matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        val implicit = s.validate("towl 3 { d: today, t: now.start_of_day() }")
        assertEquals(listOf("now", "today"), implicit.implicitInputs)
        assertEquals("ok", s.execute(implicit, emptyMap())["status"])
        assertEquals("{ t: string }", s.validate("towl 3 now = \"x\" { t: now }").resultType.toString())
        // a `.` line indented between a for line and its body applies to the for; a test may be compared with a boolean
        val laid = s.validate("""towl 3
            input xs: list[list[int]]
            ys = for x in xs
                   x.collect()
                 .flatten()
            { n: ys.count(), e: xs.where(.empty() == false).count() }""")
        assertEquals("{ n: int, e: int }", laid.resultType.toString())
        assertEquals(listOf("syntax.arity"), codes(FakeCatalog(), """towl 3 call("javadocs", "list_javadoc_symbols", { groupId: "a", artifactId: "b", version: "1" }).result.max()"""))
        assertEquals(listOf("type.timestamp"), codes(FakeCatalog(), """towl 3 ("x").minus_days(1)"""))
    }

    @Test fun `null has no default operator and compares false`() {
        val s = service()
        val c = s.validate("""towl 3 "n"
            input xs: list[{ n: int | Null }]
            { big: xs.where(.n > 1).count(), eq: xs.where(.n == 2).count(), absent: xs.where(.n.absent()).count() }""")
        val out = s.execute(c, mapOf("xs" to listOf(mapOf("n" to null), mapOf("n" to 2), mapOf("n" to 5))))
        assertEquals("ok", out["status"], out.toString())
        assertEquals(mapOf("big" to 2, "eq" to 1, "absent" to 1), out["value"])
        assertTrue("defaulted" !in out)
        val d = diagnostics(FakeCatalog(), """towl 3 input x: { a: string | Null } x.a.or("d")""")
        assertEquals("syntax.removed", d.single().code); assertTrue(d.single().message.contains(".or' was removed"))
        // a nullable list needs ?. and stays nullable; there is no .or([])
        val nullableList = s.validate("""towl 3 "t"
            v = call("javadocs", "list_javadoc_symbols", { groupId: "a", artifactId: "b", version: "1" }, { tolerate: ["NotFound"] })
            { syms: v?.result }""")
        assertTrue(nullableList.resultType.toString().endsWith("| Null }"), nullableList.resultType.toString())
        val bad = diagnostics(FakeCatalog(), """towl 3 "t"
            v = call("javadocs", "list_javadoc_symbols", { groupId: "a", artifactId: "b", version: "1" }, { tolerate: ["NotFound"] })
            { n: v?.result.count() }""")
        assertEquals("type.notList", bad.single { it.severity == "error" }.code); assertTrue(bad.single { it.severity == "error" }.fix!!.contains("compact()"))
        assertTrue("catalog.emptyListParameter" in codes(FakeCatalog(), """towl 3 call("javadocs", "count_symbols", { version: "1", tags: [] })"""))
    }

    @Test fun `nullable values are relaxed into required positions at any depth`() {
        val s = service()
        val c = s.validate("""towl 3 "deep"
            input syms: list[{ fqn: string, link: string | Null }]
            for x in syms call("javadocs", "get_javadoc_symbol", { groupId: "a", artifactId: "b", version: "1", link: x.link })""")
        assertTrue(c.warnings.any { it.code == "type.nullableToRequired" })
        val bad = s.execute(c, mapOf("syms" to listOf(mapOf("fqn" to "a", "link" to null))))
        assertEquals("error", bad["status"]); assertEquals("data", bad["error"].rec()["class"])
    }

    @Test fun `mutations are reported and their failures classed as mutation`() {
        val catalog = FakeCatalog()
        val s = service(catalog)
        val ok = s.execute(s.validate("""towl 3
            a = call("store", "put", { key: "k", value: "v" })
            b = call("store", "put", { key: "k2", value: "v2" })
            { a: a.ok, b: b.ok }"""))
        assertEquals("ok", ok["status"])
        assertEquals(2, ok.list("effects").size)
        assertEquals(listOf("syntax.options", "names.unreferenced"), codes(catalog, """towl 3
            a = call("store", "put", { key: "k", value: "v" })
            call("store", "put", { key: "k2", value: "v2" }, { after: a }).ok"""))
        catalog.failOn = { id, _ -> if (id == "store.put") OperationError("Conflict", "busy") else null }
        val bad = s.execute(s.validate("""towl 3 call("store", "put", { key: "k", value: "v" }).ok"""))
        assertEquals("mutation", bad["error"].rec()["class"]); assertEquals("mutation", bad["error"].rec()["action"])
    }

    @Test fun `budget stops before dispatching the wave`() {
        val catalog = FakeCatalog()
        val s = service(catalog, maxWidth = 2)
        val src = """towl 3 for x in call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result call("javadocs", "get_javadoc_symbol", { groupId: "g", artifactId: "a", version: "1", link: x.link })"""
        val out = s.execute(s.validate(src))
        assertEquals("error", out["status"]); assertEquals("budget", out["error"].rec()["class"])
        assertEquals(1, catalog.calls.size, "only the list call ran; no body call was dispatched")
    }

    @Test fun `a result over the byte budget is a budget stop, and result size is accounted`() {
        val catalog = FakeCatalog()
        val small = TowlService(catalog, maxResultBytes = 64)
        val src = """towl 3 call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result"""
        val out = small.execute(small.validate(src))
        assertEquals("error", out["status"]); assertEquals("MaxResultBytes", out["error"].rec()["code"]); assertEquals("budget", out["error"].rec()["action"])
        val ok = service(catalog).let { it.execute(it.validate(src)) }
        assertTrue((ok["accounting"].rec()["result_bytes"] as Int) > 64)
    }

    @Test fun `NotFoundError-style codes classify as absence and can be tolerated`() {
        assertEquals(ErrorClass.ABSENCE, TowlCatalog.defaultErrorClass("NotFoundError"))
        assertEquals(ErrorClass.ABSENCE, TowlCatalog.defaultErrorClass("NoSuchBucketPolicy"))
        assertEquals(ErrorClass.TRANSIENT, TowlCatalog.defaultErrorClass("ThrottlingException"))
        service(FakeCatalog()).validate("""towl 3 call("javadocs", "get_latest_version", { groupId: "g", artifactId: "a" }, { tolerate: ["NotFoundError"] })""")
    }

    @Test fun `single and string functions`() {
        val s = service()
        val out = s.execute(s.validate("""towl 3
            syms = call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result
            { om: syms.where(.fqn.ends_with("ObjectMapper")).single()?.fqn.after_last("."), none: syms.where(.fqn == "nope").single(), up: "ab".upper() }"""))
        assertEquals(mapOf("om" to "ObjectMapper", "none" to null, "up" to "AB"), out["value"])
        val many = s.execute(s.validate("""towl 3 call("javadocs", "list_javadoc_symbols", { groupId: "g", artifactId: "a", version: "1" }).result.single()"""))
        assertEquals("cardinality", many["error"].rec()["class"])
    }
}

class RenderTest {
    @Test fun `typed rendering annotates bindings effects and the result`() {
        val c = service().validate(SYNTH)
        val typed = Render.typed(c)
        assertTrue(typed.contains("// ver: string"), typed)
        assertTrue(typed.contains("javadocs.get_javadoc_symbol read ×dynamic"), typed)
        assertTrue(typed.contains("wave ×dynamic"), typed)
        assertTrue(typed.contains("result: list[{ class: string, summary: string }]"), typed)
        assertNotNull(Render.report(c)["effects"])
    }
}
