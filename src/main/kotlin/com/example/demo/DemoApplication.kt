package com.example.demo

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class DemoApplication {

    /** Tool-enabled client used for all four scenarios (tool calls are logged, responses truncated). */
    @Bean
    fun chatClient(toolCallbackProvider: ToolCallbackProvider, builder: ChatClient.Builder): ChatClient =
        builder.defaultTools(LoggingToolCallback.wrap(toolCallbackProvider)).build()

    /** Plain, tool-free client the filter advisors use to derive filters (independent prototype builder). */
    @Bean
    fun secondaryChatClient(builder: ChatClient.Builder): ChatClient = builder.build()

    @Bean
    fun commandLineRunner(chatClient: ChatClient, secondaryChatClient: ChatClient) = CommandLineRunner {
        val prompt = """
            list class names in com.fasterxml.jackson.core:jackson-databind:2.22.1 that are related to polymorphic type validation
        """.trimIndent()

        // Scenario 0: tools are registered (their definitions are sent) but the prompt triggers
        // no tool call — this measures the fixed per-call floor (system prompt + tool definitions).
        val floorPrompt = "Reply with the single word: READY. Do not call any tools."
        val s0 = run("0. tools, no tool call", chatClient, floorPrompt) { emptyList() }

        // Scenario 1: plain tool calling (baseline) — the full tool payload goes to the LLM.
        val s1 = run("1. plain tool calling", chatClient, prompt) { emptyList() }

        // Scenario 2: schema filter — LLM-derived field projection on the tool response.
        val s2 = run("2. schema (field) filter", chatClient, prompt) { overhead ->
            listOf(SchemaFilterAdvisor(secondaryChatClient, overhead))
        }

        // Scenario 3: agent-side data filter — LLM-derived row selection on the tool response.
        val s3 = run("3. data (row) filter", chatClient, prompt) { overhead ->
            listOf(DataFilterAdvisor(secondaryChatClient, overhead))
        }

        // Scenario 4: field + row filter — LLM-derived projection AND row selection in one internal call.
        val s4 = run("4. field + row filter", chatClient, prompt) { overhead ->
            listOf(FieldAndRowFilterAdvisor(secondaryChatClient, overhead))
        }

        val results = listOf(s0, s1, s2, s3, s4)
        results.forEach { r ->
            println("\n----- ${r.main.label} -----")
            println(r.answer?.trim())
        }
        printComparison(results)
    }

    /**
     * Runs one scenario: builds a fresh main-token tracker + overhead tracker, attaches the
     * scenario's filter advisor(s) plus the token tracker for this call, and captures the answer.
     */
    private fun run(
        label: String,
        chatClient: ChatClient,
        prompt: String,
        filterAdvisors: (overhead: TokenTracker) -> List<org.springframework.ai.chat.client.advisor.api.Advisor>,
    ): ScenarioResult {
        val main = TokenTracker(label)
        val overhead = TokenTracker("$label (derivation)")
        val advisors = filterAdvisors(overhead) + TokenTrackingAdvisor(main)
        val answer = chatClient.prompt()
            .user(prompt)
            .advisors(advisors)
            .call()
            .content()
        return ScenarioResult(main, overhead, answer)
    }
}

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
