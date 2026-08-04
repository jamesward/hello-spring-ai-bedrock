package com.example.demo

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata

/**
 * Decorates a [ToolCallback] to log each invocation's tool name, input, and result.
 * Tool responses (and overly long inputs) are truncated in the log so a huge payload
 * doesn't flood the console — the full length is still reported.
 */
class LoggingToolCallback(private val delegate: ToolCallback) : ToolCallback {

    private val log = LoggerFactory.getLogger(LoggingToolCallback::class.java)

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

    override fun getToolMetadata(): ToolMetadata = delegate.toolMetadata

    override fun call(toolInput: String): String = logged(toolInput) { delegate.call(toolInput) }

    override fun call(toolInput: String, toolContext: ToolContext?): String =
        logged(toolInput) { delegate.call(toolInput, toolContext) }

    private inline fun logged(toolInput: String, invoke: () -> String): String {
        val name = delegate.toolDefinition.name()
        log.info("Tool call -> {} input={}", name, truncate(toolInput))
        try {
            val output = invoke()
            log.info("Tool call <- {} output={}", name, truncate(output))
            return output
        } catch (e: Exception) {
            log.error("Tool call !! {} failed: {}", name, e.message)
            throw e
        }
    }

    private fun truncate(s: String): String {
        val oneLine = s.replace('\n', ' ')
        return if (oneLine.length <= MAX_LOG_CHARS) {
            oneLine
        } else {
            oneLine.take(MAX_LOG_CHARS) + "…[truncated, ${s.length} chars total]"
        }
    }

    companion object {
        private const val MAX_LOG_CHARS = 300

        /** Wraps every tool of [provider] in a [LoggingToolCallback]. */
        fun wrap(provider: ToolCallbackProvider): ToolCallbackProvider =
            ToolCallbackProvider.from(provider.toolCallbacks.map { LoggingToolCallback(it) })
    }
}
