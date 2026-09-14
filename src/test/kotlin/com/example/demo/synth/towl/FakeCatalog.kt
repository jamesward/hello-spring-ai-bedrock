package com.example.demo.synth.towl

/**
 * In-memory catalog mirroring the SYNTH javadoc tools plus a fake `llm.summarize`: no MCP, no
 * network, no model. Records every invocation so tests can assert call counts, order, and args.
 */
class FakeCatalog : TowlCatalog {
    val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Map<String, Any?>>>())
    var failOn: ((String, Map<String, Any?>) -> OperationError?) = { _, _ -> null }
    var inFlight = java.util.concurrent.atomic.AtomicInteger(0)
    var maxInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    var latencyMs = 0L

    private val symbols = listOf(
        mapOf("fqn" to "com.x.PolymorphicTypeValidator", "link" to "ptv.html", "kind" to "class"),
        mapOf("fqn" to "com.x.BasicPolymorphicTypeValidator", "link" to "bptv.html", "kind" to "class"),
        mapOf("fqn" to "com.x.ObjectMapper", "link" to "om.html", "kind" to "class"),
        mapOf("fqn" to "com.x.SubTypeValidator", "link" to "stv.html", "kind" to "interface"),
    )

    private fun op(ns: String, name: String, input: TRecord?, output: Type, effect: Effect = Effect.READ, body: (Map<String, Any?>) -> Any?) =
        OperationSpec(ns, name, "fake $name", input, output, effect, setOf("NotFound", "AccessDenied"), true) { args ->
            calls += "$ns.$name" to args
            val n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n, ::maxOf)
            try {
                if (latencyMs > 0) Thread.sleep(latencyMs)
                failOn("$ns.$name", args)?.let { throw it }
                body(args)
            } finally { inFlight.decrementAndGet() }
        }

    private fun rec(vararg f: Pair<String, Type>) = TRecord(f.toMap())

    val symbolType = rec("fqn" to TString, "link" to TString, "kind" to Types.nullable(TString))

    private val ops = listOf(
        op("javadocs", "get_latest_version", rec("groupId" to TString, "artifactId" to TString), rec("result" to TString)) { mapOf("result" to "2.22.2") },
        op("javadocs", "list_javadoc_symbols", rec("groupId" to TString, "artifactId" to TString, "version" to TString), rec("result" to TList(symbolType))) { mapOf("result" to symbols) },
        op("javadocs", "get_javadoc_symbol", rec("groupId" to TString, "artifactId" to TString, "version" to TString, "link" to TString), TString) { a -> "documentation for ${a["link"]} — long body text here" },
        op("javadocs", "count_symbols", rec("version" to TString), rec("count" to TInt, "sizes" to TList(TInt))) { mapOf("count" to 4, "sizes" to listOf(1, 2, 3)) },
        op("llm", "summarize", rec("text" to TString, "focus" to Types.nullable(TString)), TString) { a -> "summary(${(a["text"] as String).take(12)})" },
        op("store", "put", rec("key" to TString, "value" to TString), rec("ok" to TBool), Effect.MUTATE) { mapOf("ok" to true) },
    ).associateBy { it.id }

    override val namespaces = setOf("javadocs", "llm", "store")
    override fun operation(namespace: String, name: String) = ops["$namespace.$name"]
    override fun operations() = ops.values.toList()
}
