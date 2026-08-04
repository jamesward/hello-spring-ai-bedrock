package com.example.demo

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.api.Advisor

/** Prints each scenario's answer for a group, then the token/time comparison table. */
fun report(title: String, results: List<ScenarioResult>) {
    println("\n\n########## $title ##########")
    results.forEach { r ->
        println("\n----- ${r.main.label} -----")
        println(r.answer?.trim())
    }
    printComparison(results)
}

/**
 * Runs one chat-client scenario: builds a fresh main-token tracker + overhead tracker, attaches the
 * scenario's advisor(s) plus the token tracker for this call, times it, and captures the answer.
 */
fun runScenario(
    label: String,
    chatClient: ChatClient,
    prompt: String,
    advisorsFor: (overhead: TokenTracker) -> List<Advisor>,
): ScenarioResult {
    val main = TokenTracker(label)
    val overhead = TokenTracker("$label (derivation)")
    val advisors = advisorsFor(overhead) + TokenTrackingAdvisor(main)
    val start = System.nanoTime()
    val answer = chatClient.prompt()
        .user(prompt)
        .advisors(advisors)
        .call()
        .content()
    val durationMs = (System.nanoTime() - start) / 1_000_000
    return ScenarioResult(main, overhead, answer, durationMs)
}
