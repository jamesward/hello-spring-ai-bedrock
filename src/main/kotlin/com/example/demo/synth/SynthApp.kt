package com.example.demo.synth

import com.example.demo.AppConfig
import com.example.demo.report
import com.example.demo.runScenario
import com.example.demo.synth.towl.McpTowlCatalog
import com.example.demo.synth.towl.TowlService
import com.example.demo.synth.towl.TowlTools
import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Group B — synthetic tool system. B1 is plain multi-turn tool calling over the raw MCP tools.
 * B2 REPLACES those tools with the three TOWL tools (towlPlanHelper / validateTowlPlan /
 * runTowlPlan): the agent searches the catalog, authors one TOWL plan, and executes it with no
 * model in the data loop.
 */
@SpringBootApplication
@Import(AppConfig::class)
class SynthApp {

    /** Startup catalog of MCP tools (with output schemas) read directly from the MCP SDK clients. */
    @Bean
    fun mcpToolCatalog(mcpSyncClients: List<McpSyncClient>): McpToolCatalog = McpToolCatalog(mcpSyncClients)

    /** The TOWL phase pipeline (parse/validate/explain/run) over the MCP-backed operation catalog. */
    @Bean
    fun towlService(mcpToolCatalog: McpToolCatalog): TowlService =
        TowlService(McpTowlCatalog(mcpToolCatalog))

    /** TOWL as an agent tool belt; for B2 these REPLACE the raw MCP tools. */
    @Bean
    fun towlTools(towlService: TowlService): TowlTools = TowlTools(towlService)

    @Bean
    fun synthRunner(
        chatClient: ChatClient,
        agentClientBuilder: ChatClient.Builder,
        towlTools: TowlTools,
    ) = CommandLineRunner {
        val summarizePrompt = """
            summarize the classes in the latest jackson-databind library that are related to polymorphic type validation
        """.trimIndent()

        val b1 = runScenario("B1. plain multi-turn", chatClient, summarizePrompt) { emptyList() }
        val b2 = runSynthetic("B2. synthetic (TOWL tools)", agentClientBuilder, towlTools, summarizePrompt)

        report("GROUP B - SYNTHETIC TOOL SYSTEM (multi-turn)", listOf(b1, b2))
//        report("GROUP B - SYNTHETIC TOOL SYSTEM (multi-turn)", listOf(b2))
    }
}

fun main(args: Array<String>) {
    runApplication<SynthApp>(*args)
}
