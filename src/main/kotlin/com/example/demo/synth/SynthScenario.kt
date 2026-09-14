package com.example.demo.synth

import com.example.demo.LoggingToolCallback
import com.example.demo.ScenarioResult
import com.example.demo.TokenTracker
import com.example.demo.TokenTrackingAdvisor
import com.example.demo.synth.towl.TowlTools
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallbackProvider
import java.util.concurrent.atomic.AtomicReference

/**
 * The scenario currently running publishes its inner-call tracker here so that model calls made
 * from INSIDE a TOWL program (llm.summarize) are attributed to that scenario's "tooling" column.
 * Scenarios run sequentially; the runtime's worker threads read this, so it is not a ThreadLocal.
 */
object InnerModelCalls {
    val tracker = AtomicReference<TokenTracker?>()
}

/**
 * The synthetic tool system as an AGENT TOOL BELT (SkillsTool / dynamic-tool-search style): the
 * agent's only tools are the three TOWL tools — it discovers operations through towlPlanHelper's
 * catalog search (no full catalog in the prompt), authors ONE plan, and executes it through
 * runTowlPlan. The many data-fetching calls happen inside the deterministic TOWL runtime and cost
 * zero model tokens; the model pays only for discovery, the program, and the compact envelope —
 * plus any llm.summarize calls the program itself makes, which are reported as inner turns.
 */
fun runSynthetic(
    label: String,
    agent: ChatClient.Builder,
    towlTools: TowlTools,
    task: String,
): ScenarioResult {
    val main = TokenTracker(label)
    val overhead = TokenTracker("$label (tooling)")
    val start = System.nanoTime()

    val callbacks = LoggingToolCallback.wrap(ToolCallbackProvider.from(ToolCallbacks.from(towlTools).toList()))
    InnerModelCalls.tracker.set(overhead)
    val answer = try {
        agent.build().prompt()
            .system(TowlTools.AGENT_SYSTEM)
            .user(task)
            .tools(callbacks.toolCallbacks)
            .advisors(TokenTrackingAdvisor(main))
            .call()
            .content()
    } finally {
        InnerModelCalls.tracker.set(null)
    }

    val durationMs = (System.nanoTime() - start) / 1_000_000
    return ScenarioResult(main, overhead, answer, durationMs)
}
