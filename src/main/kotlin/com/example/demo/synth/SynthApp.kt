package com.example.demo.synth

import com.example.demo.AppConfig
import com.example.demo.report
import com.example.demo.runScenario
import com.example.demo.synth.towl.McpTowlCatalog
import com.example.demo.synth.towl.TowlService
import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Group B — synthetic tool system. B2 plans ONE TOWL document, statically validates and explains
 * it, runs it against the real MCP tools with runtime-owned parallelism (no LLM in the loop),
 * then summarizes the single compact result.
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

    @Bean
    fun synthRunner(
        chatClient: ChatClient,
        secondaryChatClient: ChatClient,
        towlService: TowlService,
    ) = CommandLineRunner {
        val summarizePrompt = """
            summarize the classes in the latest jackson-databind library that are related to polymorphic type validation
        """.trimIndent()

        val b1 = runScenario("B1. plain multi-turn", chatClient, summarizePrompt) { emptyList() }

        println(towlService.plannerSystemPrompt())
        val b2 = runSynthetic("B2. synthetic (TOWL)", secondaryChatClient, towlService, summarizePrompt)

        report("GROUP B - SYNTHETIC TOOL SYSTEM (multi-turn)", listOf(b1, b2))
    }
}

fun main(args: Array<String>) {
    runApplication<SynthApp>(*args)
}
