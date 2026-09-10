  package com.example.demo.synth.towl

/**
 * In-memory catalog mirroring the SYNTH javadoc tools: no MCP, no network. Records every
 * invocation so tests can assert call counts, ordering, and arguments.
 */
class FakeCatalog : TowlCatalog {
    override val name = "fake"
    val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
    var failOn: ((String, Map<String, Any?>) -> Boolean) = { _, _ -> false }

    private val symbols = listOf(
        mapOf("fqn" to "com.x.PolymorphicTypeValidator", "link" to "ptv.html"),
        mapOf("fqn" to "com.x.BasicPolymorphicTypeValidator", "link" to "bptv.html"),
        mapOf("fqn" to "com.x.ObjectMapper", "link" to "om.html"),
        mapOf("fqn" to "com.x.SubTypeValidator", "link" to "stv.html"),
    )

    private fun op(
        id: String,
        input: Map<String, Any?>,
        output: Map<String, Any?>?,
        cardinality: Cardinality,
        subject: String?,
        body: (Map<String, Any?>) -> Any?,
    ) = ResolvedOperation(
        id = id,
        description = "fake $id",
        inputSchema = input,
        outputSchema = output,
        cardinality = cardinality,
        subjectPath = subject?.let { Path.parse(it, "fake") },
        effects = setOf("read"),
        paged = false,
        invoker = { args ->
            calls += id to args
            if (failOn(id, args)) throw RuntimeException("boom from $id")
            body(args)
        },
    )

    private fun schema(required: List<String>, vararg names: String): Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to names.associateWith { mapOf("type" to "string") },
        "required" to required,
    )

    // outputs mirror the real MCP tools: get_latest_version -> {result: string} (ONE value),
    // list_javadoc_symbols -> {result: [...]} designated as the stream, get_javadoc_symbol -> plain text
    private val ops = listOf(
        op("get_latest_version", schema(listOf("groupId", "artifactId"), "groupId", "artifactId"),
            mapOf("type" to "object", "properties" to mapOf("result" to mapOf("type" to "string")), "required" to listOf("result")),
            Cardinality.ONE, null) { mapOf("result" to "2.22.2") },
        op("list_javadoc_symbols", schema(listOf("version"), "version"),
            mapOf("type" to "object",
                "properties" to mapOf("result" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "object", "properties" to mapOf(
                        "fqn" to mapOf("type" to "string"), "link" to mapOf("type" to "string"))))),
                "required" to listOf("result")),
            Cardinality.MANY, "result[]") { mapOf("result" to symbols) },
        op("get_javadoc_symbol", schema(listOf("version", "link"), "version", "link"),
            null, Cardinality.ONE, null) { args -> "documentation for ${args["link"]} — long body text here" },
    ).associateBy { it.id }

    override fun resolve(service: String?, operation: String): ResolveOutcome {
        if (service != null && service != name) return Unknown(listOf("omit service"))
        val op = ops[operation] ?: return Unknown(ops.keys.filter { it.take(4) == operation.take(4) })
        return Resolved(op)
    }

    override fun operations(): List<ResolvedOperation> = ops.values.toList()
}
