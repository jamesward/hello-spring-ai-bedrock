package com.example.demo.synth.towl

import com.example.demo.synth.McpToolCatalog
import io.modelcontextprotocol.spec.McpSchema
import tools.jackson.databind.json.JsonMapper

/**
 * The operation-catalog contract TOWL is parameterized by. The interpreter executes only through
 * [ResolvedOperation.invoke]; a fake catalog makes every phase unit-testable without MCP.
 */

enum class Cardinality { ONE, OPTIONAL, MANY }

sealed interface ResolveOutcome
data class Resolved(val op: ResolvedOperation) : ResolveOutcome
data class Unknown(val didYouMean: List<String>) : ResolveOutcome
data class Ambiguous(val qualifiers: List<String>) : ResolveOutcome

class ResolvedOperation(
    val id: String,
    val description: String?,
    val inputSchema: Map<String, Any?>?,
    val outputSchema: Map<String, Any?>?,
    /** MANY streams are exposed as the JSON list at [subjectPath] of the raw output. */
    val cardinality: Cardinality,
    val subjectPath: Path?,
    val effects: Set<String>, // e.g. "read", "mutate", "unknown"
    val paged: Boolean, // MCP tools do not page; paginate is capability-gated on this
    private val invoker: (Map<String, Any?>) -> Any?,
) {
    fun invoke(args: Map<String, Any?>): Any? = invoker(args)

    /** Required property names from the declared JSON Schema, for validation. */
    fun requiredArgs(): Set<String> =
        ((inputSchema?.get("required") as? List<*>)?.filterIsInstance<String>() ?: emptyList()).toSet()

    fun declaredArgs(): Set<String>? =
        (inputSchema?.get("properties") as? Map<*, *>)?.keys?.filterIsInstance<String>()?.toSet()
}

interface TowlCatalog {
    val name: String
    fun resolve(service: String?, operation: String): ResolveOutcome
    fun operations(): List<ResolvedOperation>
}

/**
 * Adapts the startup [McpToolCatalog] to the TOWL catalog contract. MCP carries no service
 * qualifier, so operations resolve unqualified; a supplied qualifier must match the catalog name.
 *
 * Cardinality: an MCP tool result is One<document> unless its output schema designates a subject —
 * here, a single top-level array property (e.g. list_javadoc_symbols -> {result: [...]}) is treated
 * as the record stream. A list member alone never implies MANY; this rule is this catalog's
 * explicit metadata decision.
 */
class McpTowlCatalog(private val mcp: McpToolCatalog) : TowlCatalog {
    private val mapper = JsonMapper.builder().build()
    override val name = "mcp"

    private val ops: Map<String, ResolvedOperation> = mcp.tools.associate { t ->
        val subject = singleArrayProperty(t.outputSchema)
        t.name to ResolvedOperation(
            id = t.name,
            description = t.description,
            inputSchema = t.inputSchema,
            outputSchema = t.outputSchema,
            cardinality = if (subject != null) Cardinality.MANY else Cardinality.ONE,
            subjectPath = subject?.let { Path.parse("$it[]", "catalog:${t.name}") },
            effects = setOf("unknown"),
            paged = false,
            invoker = { args -> call(t.name, args) },
        )
    }

    override fun resolve(service: String?, operation: String): ResolveOutcome {
        if (service != null && service != name) return Unknown(listOf("omit service or use '$name'"))
        val op = ops[operation] ?: return Unknown(nearest(operation))
        return Resolved(op)
    }

    override fun operations(): List<ResolvedOperation> = ops.values.toList()

    private fun nearest(operation: String): List<String> =
        ops.keys.filter { it.contains(operation.take(4), ignoreCase = true) }.take(3)

    private fun singleArrayProperty(outputSchema: Map<String, Any?>?): String? {
        val props = outputSchema?.get("properties") as? Map<*, *> ?: return null
        if (props.size != 1) return null
        val (k, v) = props.entries.first()
        val type = (v as? Map<*, *>)?.get("type")
        return if (type == "array") k as? String else null
    }

    private fun call(tool: String, args: Map<String, Any?>): Any? {
        val client = mcp.client(tool)
        val req = McpSchema.CallToolRequest.builder().name(tool).arguments(args).build()
        val res = client.callTool(req)
        val text = res.content()
            .filterIsInstance<McpSchema.TextContent>()
            .joinToString("") { it.text() }
        return runCatching { mapper.readValue(text, Any::class.java) }.getOrElse { text }
    }
}
