package com.example.demo

import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/**
 * Helpers to parse and rewrite an MCP tool response that contains a collection of records.
 *
 * The heuristic is purely structural: an MCP response is a JSON array of content blocks
 * (`[{"text":"<json>"}]`); inside a block's `text` we look for the FIRST field whose value is a
 * non-empty array of objects (e.g. `{"result":[{...},{...}]}`). That array is treated as the
 * filterable "record collection". No tool name is hardcoded — if no such collection exists,
 * these helpers return null and the caller leaves the response untouched.
 */
object ContentListJson {

    private val mapper: JsonMapper = JsonMapper.builder().build()
    private val listOfMaps = object : TypeReference<MutableList<MutableMap<String, Any?>>>() {}
    private val mapType = object : TypeReference<MutableMap<String, Any?>>() {}

    /** field names present on a record, a one-record JSON sample, the record count, and the collection field name. */
    data class Schema(val fields: List<String>, val sample: String, val count: Int, val collectionField: String)

    fun describe(responseData: String): Schema? {
        val loc = locate(responseData) ?: return null
        if (loc.entries.isEmpty()) return null
        val first = loc.entries.first()
        return Schema(
            fields = first.keys.toList(),
            sample = mapper.writeValueAsString(first),
            count = loc.entries.size,
            collectionField = loc.key,
        )
    }

    /** Keep only [keepFields] on each record (field projection / "schema filter"). */
    fun projectFields(responseData: String, keepFields: Collection<String>): String? =
        transformResults(responseData) { entries -> entries.map { e -> e.filterKeys { it in keepFields } } }

    /** Keep only records where SOME string field contains ANY of [substrings] (case-insensitive). */
    fun selectRows(responseData: String, substrings: Collection<String>): String? {
        val needles = substrings.map { it.lowercase() }.filter { it.isNotBlank() }
        if (needles.isEmpty()) return null
        return transformResults(responseData) { entries ->
            entries.filter { e ->
                e.values.any { v -> v is String && needles.any { n -> v.lowercase().contains(n) } }
            }
        }
    }

    /** Parse an object out of a possibly-fenced JSON string (used for LLM filter specs). */
    fun parseObject(text: String): Map<String, Any?>? = runCatching {
        mapper.readValue(stripFences(text), mapType)
    }.getOrNull()

    // --- internals ---

    /** The located record collection plus enough context to write a mutated version back. */
    private class Located(
        val outer: MutableList<MutableMap<String, Any?>>,
        val block: MutableMap<String, Any?>,
        val inner: MutableMap<String, Any?>,
        val key: String,
        val entries: List<Map<String, Any?>>,
    )

    private fun locate(responseData: String): Located? = runCatching {
        val outer = mapper.readValue(responseData, listOfMaps)
        for (block in outer) {
            val text = block["text"] as? String ?: continue
            val inner = runCatching { mapper.readValue(text, mapType) }.getOrNull() ?: continue
            for ((k, v) in inner) {
                if (v is List<*> && v.isNotEmpty() && v.all { it is Map<*, *> }) {
                    @Suppress("UNCHECKED_CAST")
                    val entries = v.map { it as Map<String, Any?> }
                    return@runCatching Located(outer, block, inner, k, entries)
                }
            }
        }
        null
    }.getOrNull()

    private fun transformResults(
        responseData: String,
        transform: (List<Map<String, Any?>>) -> List<Map<String, Any?>>,
    ): String? = runCatching {
        val loc = locate(responseData) ?: return@runCatching null
        loc.inner[loc.key] = transform(loc.entries)
        loc.block["text"] = mapper.writeValueAsString(loc.inner)
        mapper.writeValueAsString(loc.outer)
    }.getOrNull()

    private fun stripFences(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}
