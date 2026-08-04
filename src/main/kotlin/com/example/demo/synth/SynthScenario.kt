package com.example.demo.synth

import com.example.demo.ScenarioResult
import com.example.demo.TokenTracker
import com.example.demo.TokenTrackingAdvisor
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient

private val log = LoggerFactory.getLogger("com.example.demo.synth.SynthScenario")

// Planner instructions for the synthetic scenario. '§' stands in for a literal '$' so Kotlin's string
// templating leaves the JSONata '$vars' (e.g. $item, $symbols) untouched; we swap it back below.
private val plannerSystemTemplate = """
    You are a workflow planner. Given a user task and a catalog of MCP tools (each with input AND
    output JSON schemas), output a JSON workflow that chains tool calls to accomplish the task.
    Respond with ONLY the JSON workflow — no prose, no markdown fences.

    Workflow JSON shape:
    {
      "steps": [
        { "id": "<name>", "tool": "<toolName>", "args": { "<arg>": <literal, or "§{ <JSONata> }"> } },
        { "id": "<name>", "tool": "<toolName>", "items": "<JSONata collection>",
          "args": { "<arg>": "§{ §item.<field> }" } }
      ],
      "result": "<JSONata expression producing the final, COMPACT result to summarize>"
    }

    Rules:
    - A step with "items" is a fan-out: the tool runs once per element of the JSONata collection;
      refer to the current element as §item. Never specify concurrency.
    - Refer to a previous step's parsed output as §<stepId> (e.g. §symbols). A tool's output is
      parsed JSON matching that tool's outputSchema.
    - An arg value is a literal unless wrapped in §{ ... }, whose inside is a JSONata expression.
    - Use JSONata for ALL data shaping: filtering ([predicate], §contains(str, /regex/)), projection
      (array.{...} or array.expr), string ops (&, §join), etc.
    - Filter BEFORE you fan out. Make "result" as SMALL as possible: include only what is needed to
      answer the task (e.g. a class name + a one-line description), never whole documents.

    JSONata quick reference (this is JSONata, NOT JSONPath — do NOT use '?' filters or '[?(...)]'):
    - Filter an array:            §arr[field = 'x']            (a bare predicate in brackets)
    - Regex test in a filter:     §arr[§contains(fqn, /Polymorphic|Validator/)]
    - First element:              §arr[0]      (so first match: §arr[field='x'][0])
    - Project each item to an object:  §arr.{ "name": §split(fqn, '.')[-1], "fqn": fqn }
    - Reach into a map step's items:   §pages.{ "fqn": item.fqn, "doc": output }
    - String ops:                 a & b (concat),  §join(§arr, ', '),  §substring(s, 0, 200)

    Example workflow (structure only — always use the real tool + arg names from the catalog):
    {
      "steps": [
        { "id": "ver", "tool": "get_latest_version",
          "args": { "groupId": "com.google.guava", "artifactId": "guava" } },
        { "id": "syms", "tool": "list_javadoc_symbols",
          "args": { "groupId": "com.google.guava", "artifactId": "guava", "version": "§{ §ver.result }" } },
        { "id": "docs", "tool": "get_javadoc_symbol", "items": "§syms.result[§contains(fqn, /Cache/)]",
          "args": { "groupId": "com.google.guava", "artifactId": "guava",
                    "version": "§{ §ver.result }", "link": "§{ §item.link }" } }
      ],
      "result": "§docs.{ \"class\": §split(item.fqn, '.')[-1], \"summary\": §substring(output, 0, 200) }"
    }

    Tool catalog (JSON):
    <CATALOG>
""".trimIndent()

/**
 * The synthetic tool system. Three phases, only TWO of which touch the LLM:
 *  1. plan: a tool-free LLM turns the task + full tool catalog into a JSONata workflow (tracked as
 *     the internal "planning" call);
 *  2. execute: the interpreter runs the workflow against real MCP tools with controlled parallelism
 *     — NO LLM in this loop, so the many tool calls cost zero model tokens;
 *  3. summarize: the single, compact workflow result is handed to the LLM to produce the answer.
 */
fun runSynthetic(
    label: String,
    planner: ChatClient,
    catalog: McpToolCatalog,
    interpreter: WorkflowInterpreter,
    task: String,
): ScenarioResult {
    val main = TokenTracker(label)
    val planning = TokenTracker("$label (planning)")
    val start = System.nanoTime()

    val workflowJson = planner.prompt()
        .system(plannerSystemTemplate.replace("§", "$").replace("<CATALOG>", catalog.promptJson()))
        .user(task)
        .advisors(TokenTrackingAdvisor(planning))
        .call()
        .content() ?: error("planner returned no content")
    log.info("[synth] LLM produced workflow:\n{}", workflowJson)
    val workflow = Workflow.parse(workflowJson)

    val resultJson = interpreter.run(workflow)
    log.info("[synth] workflow result: {} chars", resultJson.length)

    val answer = planner.prompt()
        .system("Answer the user's task using ONLY the provided workflow result JSON. Be concise.")
        .user("User task:\n$task\n\nWorkflow result (JSON):\n$resultJson")
        .advisors(TokenTrackingAdvisor(main))
        .call()
        .content()

    val durationMs = (System.nanoTime() - start) / 1_000_000
    return ScenarioResult(main, planning, answer, durationMs)
}
