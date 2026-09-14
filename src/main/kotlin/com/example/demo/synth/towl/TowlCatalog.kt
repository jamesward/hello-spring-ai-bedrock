package com.example.demo.synth.towl

import com.example.demo.synth.McpToolCatalog
import io.modelcontextprotocol.spec.McpSchema
import tools.jackson.databind.json.JsonMapper

/**
 * The catalog contract TOWL v3 is parameterized by (TOWL_SPEC.md §10). The runtime executes only
 * through [OperationSpec.invoke]; a fake catalog makes every phase unit-testable without MCP.
 */

enum class Effect { READ, MUTATE, UNKNOWN }

enum class ErrorClass { TRANSIENT, VALIDATION, AUTHORIZATION, AVAILABILITY, ABSENCE, STATE, OTHER }

/** Thrown by invokers; `code` is what `tolerate` matches against. */
class OperationError(val code: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class OperationSpec(
    val namespace: String,
    val name: String,
    val description: String?,
    /** Input record type; `null` means the operation declares no input schema (any record). */
    val input: TRecord?,
    val output: Type,
    val effect: Effect,
    val errorCodes: Set<String> = emptySet(),
    val openErrorCodes: Boolean = true,
    private val invoker: (Map<String, Any?>) -> Any?,
) {
    val id get() = "$namespace.$name"
    fun invoke(args: Map<String, Any?>): Any? = invoker(args)
}

interface TowlCatalog {
    val namespaces: Set<String>
    fun operation(namespace: String, name: String): OperationSpec?
    fun operations(): List<OperationSpec>

    /** Deterministic classification of a provider error code (TOWL §12.4). */
    fun errorClass(op: OperationSpec, code: String): ErrorClass = defaultErrorClass(code)

    companion object {
        fun defaultErrorClass(code: String): ErrorClass {
            val c = code.lowercase()
            return when {
                c.contains("throttl") || c.contains("timeout") || c.contains("toomany") || c.contains("unavailable") -> ErrorClass.TRANSIENT
                c.contains("validation") || c.contains("invalidparam") || c.contains("invalid_param") || c.contains("malformed") -> ErrorClass.VALIDATION
                c.contains("accessdenied") || c.contains("unauthorized") || c.contains("forbidden") || c.contains("authfailure") -> ErrorClass.AUTHORIZATION
                c.contains("optin") || c.contains("unsupported") -> ErrorClass.AVAILABILITY
                c.contains("notfound") || c.contains("nosuch") || c.contains("not_found") || c.contains("noresult") -> ErrorClass.ABSENCE
                c.contains("state") || c.contains("inuse") || c.contains("conflict") || c.contains("dependency") -> ErrorClass.STATE
                else -> ErrorClass.OTHER
            }
        }
    }
}

/**
 * Adapts the startup [McpToolCatalog] to the TOWL v3 catalog: one namespace per MCP server
 * (sanitized server name; `tools` when unavailable), JSON Schema mapped to TOWL types by
 * [Types.fromJsonSchema], `readOnlyHint` -> READ, no annotations -> UNKNOWN (treated as mutate),
 * text-only results -> `string`, structured results -> the output type.
 */
class McpTowlCatalog(private val mcp: McpToolCatalog) : TowlCatalog {
    private val mapper = JsonMapper.builder().build()

    private val ops: Map<String, OperationSpec> = mcp.tools.associate { t ->
        val ns = namespaceOf(t)
        val outputType = if (t.outputSchema.isNullOrEmpty()) TString else Types.fromJsonSchema(t.outputSchema)
        val inputType = t.inputSchema?.let { Types.fromJsonSchema(it) as? TRecord }
        val readOnly = runCatching { mcp.annotations(t.name)?.readOnlyHint() }.getOrNull()
        "$ns.${t.name}" to OperationSpec(
            namespace = ns,
            name = t.name,
            description = t.description,
            input = inputType,
            output = outputType,
            effect = if (readOnly == true) Effect.READ else Effect.UNKNOWN,
            invoker = { args -> call(t.name, args, outputType) },
        )
    }

    override val namespaces: Set<String> = ops.values.map { it.namespace }.toSet()
    override fun operation(namespace: String, name: String) = ops["$namespace.$name"]
    override fun operations(): List<OperationSpec> = ops.values.toList()

    private fun namespaceOf(t: McpToolCatalog.ToolInfo): String {
        val raw = runCatching { t.client.serverInfo?.name() }.getOrNull() ?: "tools"
        val cleaned = raw.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
        return if (cleaned.isEmpty() || !cleaned[0].isLetter()) "tools" else cleaned
    }

    private fun call(tool: String, args: Map<String, Any?>, output: Type): Any? {
        val client = mcp.client(tool)
        val req = McpSchema.CallToolRequest.builder().name(tool).arguments(args).build()
        val res = client.callTool(req)
        val text = res.content().filterIsInstance<McpSchema.TextContent>().joinToString("") { it.text() }
        if (res.isError() == true) throw OperationError(errorCodeOf(text), text.ifBlank { "tool '$tool' reported an error" })
        if (output == TString) return text
        val structured = res.structuredContent()
        val value = structured ?: runCatching { mapper.readValue(text, Any::class.java) }.getOrElse { text }
        return Types.normalize(value, output)
    }

    /** MCP carries no error code; take a leading `Code:` or `[Code]` token when present, else "ToolError". */
    private fun errorCodeOf(text: String): String =
        Regex("""^\s*\[?([A-Za-z][A-Za-z0-9_.]*)]?\s*:""").find(text)?.groupValues?.get(1) ?: "ToolError"
}
