package com.example.demo

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.core.Ordered

/** Mutable accumulator of token usage across (potentially many) model calls. */
class TokenTracker(val label: String) {
    var modelCalls: Int = 0
        private set
    var promptTokens: Int = 0
        private set
    var completionTokens: Int = 0
        private set

    val totalTokens: Int get() = promptTokens + completionTokens

    fun record(response: ChatResponse?) {
        val usage = response?.metadata?.usage ?: return
        modelCalls++
        promptTokens += usage.promptTokens ?: 0
        completionTokens += usage.completionTokens ?: 0
    }
}

/**
 * Accumulates the usage of every model call it sees. Placed at [Ordered.LOWEST_PRECEDENCE]
 * so it sits innermost — inside the ToolCallingAdvisor loop — and therefore its [after]
 * runs once per tool-calling iteration, summing the whole exchange (not just the last call).
 */
class TokenTrackingAdvisor(private val tracker: TokenTracker) : BaseAdvisor {

    private val log = LoggerFactory.getLogger(TokenTrackingAdvisor::class.java)

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    override fun before(chatClientRequest: ChatClientRequest, advisorChain: AdvisorChain): ChatClientRequest =
        chatClientRequest

    override fun after(chatClientResponse: ChatClientResponse, advisorChain: AdvisorChain): ChatClientResponse {
        val usage = chatClientResponse.chatResponse()?.metadata?.usage
        if (usage != null) {
            tracker.record(chatClientResponse.chatResponse())
            // Per-turn breakdown: the prompt tokens of the FIRST turn (before any tool result)
            // are the fixed prefix = system prompt + all tool definitions + the user message.
            log.info(
                "[{}] turn {}: prompt={} completion={}",
                tracker.label, tracker.modelCalls, usage.promptTokens ?: 0, usage.completionTokens ?: 0,
            )
        }
        return chatClientResponse
    }
}

/** Everything we captured for one scenario. */
data class ScenarioResult(
    val main: TokenTracker,
    val overhead: TokenTracker,
    val answer: String?,
) {
    val grandTotal: Int get() = main.totalTokens + overhead.totalTokens
}

/** Prints a side-by-side token comparison. 'vs base' is relative to the plain tool-calling scenario. */
fun printComparison(results: List<ScenarioResult>) {
    val baseline = (results.firstOrNull { it.main.label.contains("plain") } ?: results.first()).grandTotal
    val line = "-".repeat(80)
    println()
    println("=".repeat(80))
    println("TOKEN USAGE COMPARISON   (outer turns = app chat-client turns incl. all tool-loop")
    println("                          iterations; inner turns = tool-free filter-derivation calls)")
    println("=".repeat(80))
    println(
        "%-24s | %-11s | %-11s | %12s | %-9s".format(
            "scenario", "outer turns", "inner turns", "total tokens", "vs base",
        ),
    )
    println(line)
    results.forEach { r ->
        val delta = if (baseline > 0) 100.0 * (r.grandTotal - baseline) / baseline else 0.0
        println(
            "%-24s | %11d | %11d | %12d | %+8.1f%%".format(
                r.main.label,
                r.main.modelCalls,
                r.overhead.modelCalls,
                r.grandTotal,
                delta,
            ),
        )
    }
    println(line)
    println("Baseline = plain tool calling ($baseline tokens). Scenario 0 = fixed floor (tools present, no tool call).")
    println("'total tokens' = outer + inner. Negative 'vs base' = cheaper overall.")
    println("=".repeat(80))
}
