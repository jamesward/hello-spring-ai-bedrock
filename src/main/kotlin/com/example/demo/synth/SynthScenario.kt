package com.example.demo.synth

import com.example.demo.LoggingToolCallback
import com.example.demo.ScenarioResult
import com.example.demo.TokenTracker
import com.example.demo.TokenTrackingAdvisor
import com.example.demo.synth.towl.TowlTools
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallbackProvider

/**
 * The synthetic tool system as an AGENT TOOL BELT (SkillsTool / dynamic-tool-search style): the
 * agent's only tools are the three TOWL tools — it discovers operations through towlPlanHelper's
 * catalog search (no full catalog in the prompt), authors ONE plan, and executes it through
 * runTowlPlan. The many data-fetching calls happen inside the deterministic TOWL interpreter and
 * cost zero model tokens; the model pays only for discovery, the plan, and the compact envelope.
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
    val answer = agent.build().prompt()
        .system(TowlTools.AGENT_SYSTEM)
        .user(task)
        .tools(callbacks.toolCallbacks)
        .advisors(TokenTrackingAdvisor(main))
        .call()
        .content()

    val durationMs = (System.nanoTime() - start) / 1_000_000
    return ScenarioResult(main, overhead, answer, durationMs)
}
