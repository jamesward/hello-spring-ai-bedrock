package com.example.demo.synth

import com.dashjoin.jsonata.Jsonata
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Executes a [Workflow] by calling real MCP tools and threading each tool's output into the next
 * tool's input via JSONata — a sandboxed, data-only expression language (no reflection, I/O, or host
 * method access), so an LLM-authored expression cannot escape into the JVM.
 *
 * The LLM is NOT in this loop: it planned the workflow up front; here we just run it. Fan-out
 * concurrency is fixed by [maxConcurrency] (controlled by us, not the LLM). Each finished step's
 * parsed output is exposed to later JSONata as `$<stepId>`; inside a map step the element is `$item`.
 */
class WorkflowInterpreter(
    private val catalog: McpToolCatalog,
    private val maxConcurrency: Int = 6,
) {
    private val log = LoggerFactory.getLogger(WorkflowInterpreter::class.java)
    private val mapper = JsonMapper.builder().build()

    /** Runs the workflow and returns the final result as a JSON string (the single result for the LLM). */
    fun run(workflow: Workflow): String {
        val ctx = HashMap<String, Any?>()
        for (step in workflow.steps) {
            if (step.items == null) {
                val args = resolveArgs(step.args, ctx, item = null)
                ctx[step.id] = callTool(step.tool, args)
            } else {
                val collection = evalJsonata(step.items, ctx, item = null)
                val items = (collection as? List<*>) ?: listOfNotNull(collection)
                log.info(
                    "[workflow] map step '{}' fans out over {} item(s) (maxConcurrency={})",
                    step.id, items.size, maxConcurrency,
                )
                ctx[step.id] = fanOut(step, items, ctx)
            }
        }
        val result = if (workflow.result != null) evalJsonata(workflow.result, ctx, null)
        else ctx[workflow.steps.lastOrNull()?.id]
        return mapper.writeValueAsString(result)
    }

    private fun fanOut(step: Workflow.Step, items: List<*>, ctx: Map<String, Any?>): List<Any?> {
        val pool = Executors.newFixedThreadPool(maxConcurrency.coerceAtLeast(1))
        try {
            val tasks = items.map { item ->
                Callable {
                    val args = resolveArgs(step.args, ctx, item)
                    val output = runCatching { callTool(step.tool, args) }
                        .getOrElse { e -> mapOf("error" to (e.message ?: e.toString())) }
                    linkedMapOf<String, Any?>("item" to item, "output" to output)
                }
            }
            return pool.invokeAll(tasks).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun resolveArgs(args: Map<String, Any?>, ctx: Map<String, Any?>, item: Any?): Map<String, Any?> =
        args.mapValues { (_, v) ->
            val expr = asJsonataArg(v)
            if (expr != null) evalJsonata(expr, ctx, item) else v
        }

    /** If the value is a string `${ ... }`, return the inner JSONata; else null (treat as literal). */
    private fun asJsonataArg(v: Any?): String? {
        if (v !is String) return null
        val t = v.trim()
        return if (t.startsWith("\${") && t.endsWith("}")) t.substring(2, t.length - 1).trim() else null
    }

    private fun evalJsonata(expr: String, ctx: Map<String, Any?>, item: Any?): Any? {
        // Fresh instance per evaluation => safe to run concurrently during fan-out.
        val jn = Jsonata.jsonata(expr)
        val root = HashMap<String, Any?>(ctx)
        ctx.forEach { (k, value) -> jn.assign(k, value) }
        if (item != null) {
            jn.assign("item", item)
            root["item"] = item
        }
        return jn.evaluate(root)
    }

    private fun callTool(name: String, args: Map<String, Any?>): Any? {
        log.info("[workflow] tool -> {} args={}", name, truncate(mapper.writeValueAsString(args)))
        val client = catalog.client(name)
        val req = McpSchema.CallToolRequest.builder().name(name).arguments(args).build()
        val res = client.callTool(req)
        val text = res.content()
            .filterIsInstance<McpSchema.TextContent>()
            .joinToString("") { it.text() }
        log.info("[workflow] tool <- {} ({} chars)", name, text.length)
        return runCatching { mapper.readValue(text, Any::class.java) }.getOrElse { text }
    }

    private fun truncate(s: String, max: Int = 200): String =
        if (s.length <= max) s else s.take(max) + "…[${s.length} chars]"
}
