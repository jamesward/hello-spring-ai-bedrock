package com.example.demo

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.ChatClientRequest
import org.springframework.ai.chat.client.ChatClientResponse
import org.springframework.ai.chat.client.advisor.api.AdvisorChain
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.core.Ordered

/**
 * Base class for advisors that intercept a large tool response *inside* the ToolCallingAdvisor
 * loop (order 300, so this uses 400) and shrink it before it reaches the main LLM.
 *
 * There is no hardcoded tool name. A tool response is considered filterable purely by shape:
 * [ContentListJson.describe] must find a collection of records in it, and that collection must
 * have at least [MIN_RECORDS] rows (below that the derivation call costs more than it saves).
 * Subclasses decide *how* to shrink an applicable collection by asking a secondary (tool-free) LLM.
 */
abstract class AbstractContentFilterAdvisor(
    protected val secondaryClient: ChatClient,
    /** accumulates the token cost AND call count of the internal "derive the filter" LLM calls. */
    val overhead: TokenTracker,
) : BaseAdvisor {

    protected val log = LoggerFactory.getLogger(javaClass)!!
    private val cache = mutableMapOf<String, String?>() // responseData -> filtered (or null = leave as-is)

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 400

    override fun after(chatClientResponse: ChatClientResponse, advisorChain: AdvisorChain): ChatClientResponse =
        chatClientResponse

    override fun before(chatClientRequest: ChatClientRequest, advisorChain: AdvisorChain): ChatClientRequest {
        val messages = chatClientRequest.prompt().instructions
        if (messages.none { it is ToolResponseMessage }) return chatClientRequest

        val question = chatClientRequest.prompt().userMessage.text ?: ""
        var changed = false
        val rewritten = messages.map { msg ->
            if (msg is ToolResponseMessage) {
                val newResponses = msg.responses.map { r ->
                    val filtered = cache.getOrPut(r.responseData()) { computeFilter(question, r.name(), r.responseData()) }
                    if (filtered == null || filtered == r.responseData()) {
                        r
                    } else {
                        changed = true
                        log.info(
                            "[{}] filtered '{}' payload {} -> {} chars",
                            filterName(), r.name(), r.responseData().length, filtered.length,
                        )
                        ToolResponseMessage.ToolResponse(r.id(), r.name(), filtered)
                    }
                }
                ToolResponseMessage.builder().responses(newResponses).metadata(msg.metadata).build()
            } else {
                msg
            }
        }
        if (!changed) return chatClientRequest

        val mutatedPrompt = chatClientRequest.prompt().mutate().messages(rewritten).build()
        return chatClientRequest.mutate().prompt(mutatedPrompt).build()
    }

    /** Heuristic gate: only filter responses that structurally contain a big-enough record collection. */
    private fun computeFilter(question: String, toolName: String, responseData: String): String? {
        val schema = ContentListJson.describe(responseData) ?: return null // not a record collection
        if (schema.count < MIN_RECORDS) return null // too small to be worth a derivation call
        return deriveFilteredPayload(question, toolName, responseData, schema)
    }

    /** @return the shrunken payload, or null to leave the tool response unchanged. */
    protected abstract fun deriveFilteredPayload(
        question: String,
        toolName: String,
        responseData: String,
        schema: ContentListJson.Schema,
    ): String?

    protected abstract fun filterName(): String

    /**
     * Internal LLM call that asks for a JSON filter spec and returns the parsed object.
     * Uses [secondaryClient], which has NO tools registered, and records the call's token usage
     * and count into [overhead].
     */
    protected fun deriveSpec(system: String, user: String): Map<String, Any?>? {
        val response = secondaryClient.prompt()
            .system(system)
            .user(user)
            .call()
            .chatResponse()
        overhead.record(response)
        val text = response?.result?.output?.text ?: return null
        return ContentListJson.parseObject(text)
    }

    protected fun stringList(spec: Map<String, Any?>?, key: String): List<String> =
        (spec?.get(key) as? List<*>)?.map { it.toString() }?.filter { it.isNotBlank() }.orEmpty()

    companion object {
        /** Minimum records in a collection before filtering is worth an extra LLM call. */
        const val MIN_RECORDS = 8
    }
}

/** Scenario 2 — schema filter: LLM picks which FIELDS to keep; project each record (reduces width). */
class SchemaFilterAdvisor(secondaryClient: ChatClient, overhead: TokenTracker) :
    AbstractContentFilterAdvisor(secondaryClient, overhead) {

    override fun filterName() = "schema-filter"

    override fun deriveFilteredPayload(
        question: String,
        toolName: String,
        responseData: String,
        schema: ContentListJson.Schema,
    ): String? {
        val spec = deriveSpec(
            system = "You minimize tool payload size. Pick the SMALLEST set of fields needed to answer. " +
                "Output ONLY raw JSON, no markdown/code fences.",
            user = """
                User question: $question
                Tool '$toolName' returned ${schema.count} records (in field '${schema.collectionField}');
                each record has these fields: ${schema.fields}.
                Sample record: ${schema.sample}
                Which fields are needed to answer? Return {"keepFields":[...]}.
            """.trimIndent(),
        )
        val keep = stringList(spec, "keepFields")
        if (keep.isEmpty()) return null
        log.info("[schema-filter] LLM chose keepFields={} (from {})", keep, schema.fields)
        return ContentListJson.projectFields(responseData, keep)
    }
}

/** Scenario 3 — data filter: LLM picks substrings identifying the ROWS it needs (reduces row count). */
class DataFilterAdvisor(secondaryClient: ChatClient, overhead: TokenTracker) :
    AbstractContentFilterAdvisor(secondaryClient, overhead) {

    override fun filterName() = "data-filter"

    override fun deriveFilteredPayload(
        question: String,
        toolName: String,
        responseData: String,
        schema: ContentListJson.Schema,
    ): String? {
        val spec = deriveSpec(
            system = "You minimize tool payload size by keeping ONLY the rows needed to answer. " +
                "Output ONLY raw JSON, no markdown/code fences.",
            user = """
                User question: $question
                Tool '$toolName' returned ${schema.count} records (in field '${schema.collectionField}')
                with fields ${schema.fields}.
                Sample record: ${schema.sample}
                Give case-insensitive substrings; a record is KEPT if any string field contains any substring.
                Keep only records relevant to the question. Return {"matchSubstrings":[...]}.
            """.trimIndent(),
        )
        val subs = stringList(spec, "matchSubstrings")
        if (subs.isEmpty()) return null
        log.info("[data-filter] LLM chose matchSubstrings={}", subs)
        return ContentListJson.selectRows(responseData, subs)
    }
}

/**
 * Scenario 4 — field + row filter: ONE internal LLM call returns BOTH the rows to keep (substrings)
 * and the fields to keep, then applies row-selection followed by field-projection.
 */
class FieldAndRowFilterAdvisor(secondaryClient: ChatClient, overhead: TokenTracker) :
    AbstractContentFilterAdvisor(secondaryClient, overhead) {

    override fun filterName() = "field+row-filter"

    override fun deriveFilteredPayload(
        question: String,
        toolName: String,
        responseData: String,
        schema: ContentListJson.Schema,
    ): String? {
        val spec = deriveSpec(
            system = "You minimize tool payload size two ways at once: keep only the relevant ROWS and only the " +
                "needed FIELDS. Output ONLY raw JSON, no markdown/code fences.",
            user = """
                User question: $question
                Tool '$toolName' returned ${schema.count} records (in field '${schema.collectionField}');
                each record has these fields: ${schema.fields}.
                Sample record: ${schema.sample}
                Return {"matchSubstrings":[...],"keepFields":[...]} where:
                - matchSubstrings: case-insensitive substrings; keep a record if any string field contains any substring.
                - keepFields: the minimal fields needed to answer.
            """.trimIndent(),
        )
        val subs = stringList(spec, "matchSubstrings")
        val keep = stringList(spec, "keepFields")
        if (subs.isEmpty() && keep.isEmpty()) return null
        log.info("[field+row-filter] LLM chose matchSubstrings={} keepFields={}", subs, keep)

        var payload: String? = responseData
        if (subs.isNotEmpty()) payload = ContentListJson.selectRows(payload!!, subs) ?: payload
        if (keep.isNotEmpty()) payload = ContentListJson.projectFields(payload!!, keep) ?: payload
        return payload
    }
}
