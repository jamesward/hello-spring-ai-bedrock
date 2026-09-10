package com.example.demo.synth

import com.example.demo.ScenarioResult
import com.example.demo.TokenTracker
import com.example.demo.TokenTrackingAdvisor
import com.example.demo.synth.towl.TowlService
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient

private val log = LoggerFactory.getLogger("com.example.demo.synth.SynthScenario")

/**
 * The synthetic tool system on TOWL. Three phases, only TWO of which touch the LLM:
 *  1. plan: a tool-free LLM turns the task + tool catalog + generated TOWL vocabulary into ONE
 *     reviewable TOWL document (tracked as the internal "planning" call);
 *  2. execute: parse -> validate -> explain (logged dry run) -> run against real MCP tools with
 *     runtime-owned parallelism — NO LLM in this loop, zero model tokens for the many tool calls;
 *  3. summarize: the single, compact plan result is handed to the LLM to produce the answer.
 */
fun runSynthetic(
    label: String,
    planner: ChatClient,
    towl: TowlService,
    task: String,
): ScenarioResult {
    val main = TokenTracker(label)
    val planning = TokenTracker("$label (planning)")
    val start = System.nanoTime()

    val planJson = planner.prompt()
        .system(towl.plannerSystemPrompt())
        .user(task)
        .advisors(TokenTrackingAdvisor(planning))
        .call()
        .content() ?: error("planner returned no content")
    log.info("[synth] LLM produced TOWL plan:\n{}", planJson)

    val validated = towl.validate(towl.parse(planJson))
    log.info("[synth] explain:\n{}", towl.explain(validated))

    val execution = towl.execute(validated)
    val envelopeJson = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(execution.toMap())
    log.info("[synth] plan status={} envelope: {} chars, diagnostics: {}", execution.status, envelopeJson.length, execution.diagnostics)
    val bare = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(execution.result)
    if (bare == "[]" || bare == "null" || bare == "{}")
        log.warn("[synth] plan produced an EMPTY result — the diagnostics above show where the records disappeared")

    val answer = planner.prompt()
        .system("Answer the user's task using ONLY the provided workflow execution JSON. If \"result\" is empty or partial, use \"diagnostics\" to say why. Be concise.")
        .user("User task:\n$task\n\nWorkflow execution (JSON; \"diagnostics\" shows per-binding record counts — use it to explain anything unexpected, e.g. zero matches):\n$envelopeJson")
        .advisors(TokenTrackingAdvisor(main))
        .call()
        .content()

    val durationMs = (System.nanoTime() - start) / 1_000_000
    return ScenarioResult(main, planning, answer, durationMs)
}
