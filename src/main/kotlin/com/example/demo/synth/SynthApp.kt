package com.example.demo.synth

import com.example.demo.AppConfig
import com.example.demo.report
import com.example.demo.runScenario
import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Group B — synthetic tool system. An open task that naturally takes many turns. B1 is plain
 * multi-turn tool calling (the baseline); B2 plans a JSONata workflow once, runs it against the real
 * MCP tools with controlled parallelism (no LLM in the loop), then summarizes the single result.
 */
@SpringBootApplication
@Import(AppConfig::class)
class SynthApp {

    /** Startup catalog of MCP tools (with output schemas) read directly from the MCP SDK clients. */
    @Bean
    fun mcpToolCatalog(mcpSyncClients: List<McpSyncClient>): McpToolCatalog = McpToolCatalog(mcpSyncClients)

    /** Runs an LLM-planned JSONata workflow against the real MCP tools (controlled parallelism). */
    @Bean
    fun workflowInterpreter(mcpToolCatalog: McpToolCatalog): WorkflowInterpreter = WorkflowInterpreter(mcpToolCatalog)

    @Bean
    fun synthRunner(
        chatClient: ChatClient,
        secondaryChatClient: ChatClient,
        mcpToolCatalog: McpToolCatalog,
        workflowInterpreter: WorkflowInterpreter,
    ) = CommandLineRunner {
        val summarizePrompt = """
            summarize the classes in the latest jackson-databind library that are related to polymorphic type validation
        """.trimIndent()

        val b1 = runScenario("B1. plain multi-turn", chatClient, summarizePrompt) { emptyList() }
        val b2 = runSynthetic(
            "B2. synthetic (JSONata)", secondaryChatClient, mcpToolCatalog, workflowInterpreter, summarizePrompt,
        )
        report("GROUP B - SYNTHETIC TOOL SYSTEM (multi-turn)", listOf(b1, b2))
    }
}

fun main(args: Array<String>) {
    runApplication<SynthApp>(*args)
}
