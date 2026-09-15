package com.example.demo.synth.towl

/**
 * TOWL v3 types (TOWL_SPEC.md §4). One collection type (list), closed records, the single union
 * `T | Null`, and an opaque `json` for values the catalog could not type.
 */
sealed interface Type

object TString : Type { override fun toString() = "string" }
object TInt : Type { override fun toString() = "int" }
object TNumber : Type { override fun toString() = "number" }
object TBool : Type { override fun toString() = "bool" }
object TTimestamp : Type { override fun toString() = "timestamp" }
object TNull : Type { override fun toString() = "Null" }
object TJson : Type { override fun toString() = "json" }
/** Poison type produced by a diagnostic; assignable anywhere so one error does not cascade. */
object TError : Type { override fun toString() = "?" }

data class TList(val element: Type) : Type {
    override fun toString() = "list[$element]"
}

/** Closed record; a field's type is `TNullable(...)` when the member is optional. */
data class TRecord(val fields: Map<String, Type>, val name: String? = null) : Type {
    override fun toString() = name ?: fields.entries.joinToString(", ", "{ ", " }") { (k, v) -> "$k: $v" }
}

/** `T | Null`; `inner` is never itself nullable or Null. */
data class TNullable(val inner: Type) : Type {
    override fun toString() = "$inner | Null"
}

object Types {
    fun nullable(t: Type): Type = when (t) {
        is TNullable, TNull -> t
        else -> TNullable(t)
    }

    fun stripNull(t: Type): Type = if (t is TNullable) t.inner else t
    fun isNullable(t: Type): Boolean = t is TNullable || t == TNull

    fun isScalar(t: Type) = t is TString || t is TInt || t is TNumber || t is TBool || t is TTimestamp
    fun isNumeric(t: Type) = t is TInt || t is TNumber
    fun isOrdered(t: Type) = t is TInt || t is TNumber || t is TString || t is TTimestamp

    /** Structural equality is defined on scalars, Null, and records/lists of such (§4). */
    fun isEquatable(t: Type): Boolean = when (t) {
        TJson -> false
        TError -> true
        is TNullable -> isEquatable(t.inner)
        is TList -> isEquatable(t.element)
        is TRecord -> t.fields.values.all(::isEquatable)
        else -> true
    }

    /**
     * `from` may be used where `to` is expected. The only coercion is int -> number.
     * [relaxed] is TOWL §6.1's parameter rule: `T | Null` may be supplied where `T` is required, at any depth,
     * with a runtime `data` check ([nullAtRequired]).
     */
    fun assignable(from: Type, to: Type, relaxed: Boolean = false): Boolean = when {
        from == to -> true
        from == TError || to == TError -> true
        to == TJson -> true
        from == TInt && to == TNumber -> true
        to is TNullable -> from == TNull || assignable(stripNull(from), to.inner, relaxed)
        from is TNullable -> relaxed && assignable(from.inner, to, relaxed)
        from is TList && to is TList -> assignable(from.element, to.element, relaxed)
        from is TRecord && to is TRecord ->
            to.fields.all { (k, tt) -> from.fields[k]?.let { assignable(it, tt, relaxed) } ?: (isNullable(tt) || tt is TList) } &&
                from.fields.keys.all { it in to.fields }
        else -> false
    }

    /** First path where a null sits in a non-nullable position of [t] (the runtime side of the relaxation). */
    fun nullAtRequired(value: Any?, t: Type, path: String): String? {
        if (value == null) return if (isNullable(t) || t == TJson || t == TError || t is TList) null else path
        return when {
            t is TNullable -> nullAtRequired(value, t.inner, path)
            t is TList && value is List<*> -> value.withIndex().firstNotNullOfOrNull { (i, v) -> nullAtRequired(v, t.element, "$path[$i]") }
            t is TRecord && value is Map<*, *> -> value.entries.firstNotNullOfOrNull { (k, v) -> t.fields[k]?.let { ft -> nullAtRequired(v, ft, if (path.isEmpty()) "$k" else "$path.$k") } }
            else -> null
        }
    }

    /** Join for list-literal elements and conditional branches: equal, or int/number. */
    fun join(a: Type, b: Type): Type? = when {
        a == b -> a
        a == TError -> b
        b == TError -> a
        a == TNull -> nullable(b)
        b == TNull -> nullable(a)
        isNumeric(a) && isNumeric(b) -> TNumber
        a is TNullable || b is TNullable -> join(stripNull(a), stripNull(b))?.let(::nullable)
        a is TList && b is TList -> join(a.element, b.element)?.let(::TList)
        a is TRecord && b is TRecord && a.fields.keys == b.fields.keys ->
            a.fields.mapValues { (k, t) -> join(t, b.fields.getValue(k)) ?: return null }.let { TRecord(it) }
        else -> null
    }

    // ── JSON Schema -> Type (TOWL §10 catalog mapping for MCP-style tools) ──────────────

    /**
     * Map a JSON Schema fragment to a TOWL type. Object properties absent from `required` are
     * `T | Null`, except list-typed properties, which this catalog normalizes to `[]` when absent
     * (the same defaulted-member policy the AWS profile uses). No schema -> `json`.
     */
    fun fromJsonSchema(schema: Map<String, Any?>?): Type {
        if (schema == null) return TJson
        if (schema.containsKey("oneOf") || schema.containsKey("anyOf") || schema.containsKey("allOf")) return TJson
        val type = schema["type"]
        val typeName = when (type) {
            is String -> type
            is List<*> -> type.filterIsInstance<String>().firstOrNull { it != "null" }
            else -> null
        }
        val nullable = type is List<*> && "null" in type
        val base: Type = when (typeName) {
            "string" -> if (schema["format"] == "date-time") TTimestamp else TString
            "integer" -> TInt
            "number" -> TNumber
            "boolean" -> TBool
            "null" -> TNull
            "array" -> TList(fromJsonSchema(schema["items"] as? Map<String, Any?>))
            "object" -> objectType(schema)
            null -> if (schema.containsKey("properties")) objectType(schema) else TJson
            else -> TJson
        }
        return if (nullable && base != TNull) nullable(base) else base
    }

    private fun objectType(schema: Map<String, Any?>): Type {
        val props = schema["properties"] as? Map<*, *>
        if (props == null) {
            val extra = schema["additionalProperties"]
            val valueType = if (extra is Map<*, *>) fromJsonSchema(extra as Map<String, Any?>) else TJson
            return TList(TRecord(mapOf("key" to TString, "value" to valueType)))
        }
        val required = (schema["required"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
        val fields = props.entries.associate { (k, v) ->
            val t = fromJsonSchema(v as? Map<String, Any?>)
            val name = k as String
            name to when {
                name in required -> t
                t is TList -> t // defaulted to []
                else -> nullable(t)
            }
        }
        return TRecord(fields)
    }

    // ── runtime helpers ─────────────────────────────────────────────────────────────────

    /** Normalize a decoded provider value against its type: absent list members become `[]`. */
    fun normalize(value: Any?, type: Type): Any? = when {
        value == null -> if (type is TList) emptyList<Any?>() else null
        type is TNullable -> normalize(value, type.inner)
        type is TList && value is List<*> -> value.map { normalize(it, type.element) }
        type is TRecord && value is Map<*, *> -> {
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in value) out[k as String] = type.fields[k]?.let { normalize(v, it) } ?: v
            for ((k, t) in type.fields) if (k !in out && t is TList) out[k] = emptyList<Any?>()
            out
        }
        else -> value
    }

    /** Best-effort runtime check that a host-supplied input matches its declared type. */
    fun conforms(value: Any?, type: Type): Boolean = when (type) {
        TJson, TError -> true
        TNull -> value == null
        is TNullable -> value == null || conforms(value, type.inner)
        TString, TTimestamp -> value is String
        TInt -> value is Int || value is Long || (value is Number && value.toDouble() % 1.0 == 0.0)
        TNumber -> value is Number
        TBool -> value is Boolean
        is TList -> value is List<*> && value.all { conforms(it, type.element) }
        is TRecord -> value is Map<*, *> && type.fields.all { (k, t) -> conforms(value[k], t) } &&
            value.keys.all { it in type.fields }
    }
}
