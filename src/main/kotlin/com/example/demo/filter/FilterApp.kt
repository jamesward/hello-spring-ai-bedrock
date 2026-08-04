package com.example.demo.filter

import com.example.demo.AppConfig
import com.example.demo.report
import com.example.demo.runScenario
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Group A — response-filtering scenarios. A single tool call returns a large collection; we shrink
 * that response (field projection and/or row selection, LLM-derived) before it reaches the model,
 * and compare token usage against plain tool calling and the fixed "tools present, no call" floor.
 */
@SpringBootApplication
@Import(AppConfig::class)
class FilterApp {

    @Bean
    fun filterRunner(chatClient: ChatClient, secondaryChatClient: ChatClient) = CommandLineRunner {
        val listPrompt = """
            list class names in com.fasterxml.jackson.core:jackson-databind:2.22.1 that are related to polymorphic type validation
        """.trimIndent()

        // Scenario 0: tools are registered (their definitions are sent) but the prompt triggers no
        // tool call — this measures the fixed per-call floor (system prompt + tool definitions).
        val floorPrompt = "Reply with the single word: READY. Do not call any tools."
        val a0 = runScenario("0. tools, no tool call", chatClient, floorPrompt) { emptyList() }
        val a1 = runScenario("1. plain tool calling", chatClient, listPrompt) { emptyList() }
        val a2 = runScenario("2. schema (field) filter", chatClient, listPrompt) { overhead ->
            listOf(SchemaFilterAdvisor(secondaryChatClient, overhead))
        }
        val a3 = runScenario("3. data (row) filter", chatClient, listPrompt) { overhead ->
            listOf(DataFilterAdvisor(secondaryChatClient, overhead))
        }
        val a4 = runScenario("4. field + row filter", chatClient, listPrompt) { overhead ->
            listOf(FieldAndRowFilterAdvisor(secondaryChatClient, overhead))
        }
        report("GROUP A - RESPONSE FILTERING (single tool call)", listOf(a0, a1, a2, a3, a4))
    }
}

fun main(args: Array<String>) {
    runApplication<FilterApp>(*args)
}
