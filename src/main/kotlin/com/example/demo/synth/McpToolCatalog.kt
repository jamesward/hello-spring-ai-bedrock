package com.example.demo.synth

import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/**
 * Startup catalog of every MCP tool exposed by the connected servers, INCLUDING output schemas.
 *
 * Spring AI's ToolDefinition does not surface a tool's outputSchema (see spring-ai#4620), so we go
 * straight to the MCP SDK client: listTools() -> McpSchema.Tool.outputSchema(). The orchestrator LLM
 * needs those output schemas to know how one tool's output can feed the next tool's input.
 *
 * Built once at startup (constructor calls listTools()) and held for later planning calls.
 */
class McpToolCatalog(clients: List<McpSyncClient>) {

    private val log = LoggerFactory.getLogger(McpToolCatalog::class.java)
    private val mapper = JsonMapper.builder().build()

    data class ToolInfo(
        val name: String,
        val description: String?,
        val inputSchema: Map<String, Any?>?,
        val outputSchema: Map<String, Any?>?,
        val client: McpSyncClient,
    )

    val tools: List<ToolInfo> = clients.flatMap { client ->
        client.listTools().tools().map { t ->
            ToolInfo(t.name(), t.description(), t.inputSchema(), t.outputSchema(), client)
        }
    }

    private val byName = tools.associateBy { it.name }

    init {
        val withOut = tools.count { !it.outputSchema.isNullOrEmpty() }
        log.info("MCP tool catalog: {} tool(s), {} with an outputSchema", tools.size, withOut)
        tools.forEach { t ->
            log.info("  tool '{}' outputSchema={}", t.name, if (t.outputSchema.isNullOrEmpty()) "ABSENT" else "present")
        }
    }

    fun client(toolName: String): McpSyncClient =
        (byName[toolName] ?: error("Unknown MCP tool: $toolName")).client

    /** Compact JSON catalog (name, description, input + output schema) to embed in the planner prompt. */
    fun promptJson(): String {
        val list = tools.map { t ->
            linkedMapOf(
                "name" to t.name,
                "description" to t.description,
                "inputSchema" to t.inputSchema,
                "outputSchema" to t.outputSchema,
            )
        }
        return mapper.writeValueAsString(list)
    }
}
