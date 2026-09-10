package com.example.demo.synth.towl

import tools.jackson.databind.json.JsonMapper

/**
 * The schema phase: everything a planner needs to author valid TOWL, generated from the live
 * registry and the RESOLVED catalog — the same objects validation and execution use — rather
 * than raw transport schemas. Each catalog entry states how sourcing it behaves in TOWL:
 * "stream" (elements of `returns.element`, wrapper already removed), "value" (one structured
 * value), or "text" (one plain string).
 */
object TowlPrompt {

    private val mapper = JsonMapper.builder().build()

    /** Planner-facing catalog derived from resolved operations, not raw tool JSON. */
    fun catalogJson(catalog: TowlCatalog): String {
        val entries = catalog.operations().map { op ->
            linkedMapOf(
                "operation" to op.id,
                "description" to op.description,
                "args" to op.inputSchema,
                "returns" to returns(op),
            )
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries)
    }

    private fun returns(op: ResolvedOperation): Map<String, Any?> = when {
        op.cardinality == Cardinality.MANY -> {
            val subject = op.subjectPath!!.segments.first().name
            val props = op.outputSchema?.get("properties") as? Map<*, *>
            val element = (props?.get(subject) as? Map<*, *>)?.get("items")
            linkedMapOf(
                "kind" to "stream",
                "element" to element,
                "note" to "sourcing this streams one element at a time; a bare {\"path\": ...} reads " +
                    "fields of returns.element — the '$subject' wrapper is ALREADY removed, never mention it",
            )
        }
        op.outputSchema == null -> linkedMapOf(
            "kind" to "text",
            "note" to "one plain text string (often long); {\"path\": \"\"} is the string itself",
        )
        else -> linkedMapOf("kind" to "value", "schema" to op.outputSchema)
    }

    fun plannerSystem(catalog: TowlCatalog, registry: TowlRegistry): String {
        val functions = registry.functions.values.joinToString("\n") { "    - ${it.description}" }
        val aggregators = registry.aggregators.values.joinToString("\n") { "    - ${it.description}" }
        val catalogJson = catalogJson(catalog)
        return """
You are a workflow planner. Given a user task and a catalog of operations, respond with ONLY a
TOWL v1 JSON document — no prose, no markdown fences.

TOWL is strict JSON. A plan is {"towl":"v1", "description":"...", ...one block}. A block is EXACTLY
one of three closed shapes (never mix their keys):

  Assemble  {"let": {name: <block>, ...}, "result": <result>}
            Binds values; each let value is itself a block. "result" shapes the plan/block value;
            without it the single unconsumed binding is the value.
  Express   {"source": <producer>, "filter": <boolExpr>?, "dedup": [<expr>,...]?, "result": <result>?, "onError": "fail"|"skip"?}
            "source" is {"call": {"operation": "<op>", "args": {...}}} or an expression such as
            {"ref": "name"} referencing an earlier binding whose value is a list.
  Traverse  {"forEach": {"<elem>": {"from": <expr>, ...body block}}, "onError": "fail"|"skip"|"collect"?}
            Runs the body once per element of "from" (a list). The element is NAMED: reference it
            as {"ref": "<elem>", "path": "..."} inside the body — e.g. in a nested call's args.
            Output is the list of body values; "collect" wraps each as {"ok": ...} or
            {"error": {"item":..., "code":..., "message":...}}.

How TOWL connects to the operation catalog (the JSON at the end):
- "operation" in a call MUST be a catalog "operation" name, and "args" keys MUST come from that
  entry's "args" schema; every name in its "required" list must be present. Validation rejects
  unknown operations, unknown arguments, and missing required arguments — never invent either.
- Each entry's "returns" says exactly what sourcing it produces in TOWL:
  - kind "stream": the source streams ELEMENTS shaped like returns.element. Inside that block's
    filter/dedup/result the current element is IMPLICIT: {"path": "fqn"} reads a field of ONE
    element. Any transport wrapper (such as a top-level "result" member) is ALREADY removed —
    never write it in a path. The binding's value is the list of surviving elements: use it as
    "from" in a Traverse, or fold it with aggregate result leaves.
  - kind "value": ONE structured value. "filter", "dedup", and aggregate results are ILLEGAL on
    it; an optional "result" maps it once, with bare paths reading its schema's fields.
  - kind "text": ONE plain string. {"path": ""} is the whole string; always bound it with take.
- Data flows only by reference: an output field feeds a later input as
  {"ref": "<binding or elem>", "path": "<field>"}. There are no string templates.

Expressions (typed applications; unknown names are rejected, never guessed):
  {"ref": "name", "path": "a.b"}   earlier binding or forEach element (path optional)
  {"path": "a.b"}                  the implicit source element (Express filter/dedup/result only)
  {"input": "name"}                a plan input; legal ONLY when the plan declares it in a top-level
                                   "inputs": {"name": {"type": "string", "default": ...}} member.
                                   When the task states concrete values, write them as literals.
  {"<functionName>": [arg1, ...]}  function application: the function name IS the single object key,
                                   e.g. {"contains": [{"path": "fqn"}, "Cache"]} or
                                   {"or": [{"contains": [...]}, {"contains": [...]}]}.
                                   There is NO wrapper key: never write {"fn": [...]} and never put
                                   the function name inside the argument array.
                                   Available functions:
$functions

Aggregators (legal only as result leaves in an Express block over a stream; any aggregate leaf
folds the whole stream to one value, and pure leaves beside it must not read the element):
$aggregators

Paths are member navigation only: "a.b", one flatten "items[].name". No indexes, no expressions in
paths.

Rules:
- Filter BEFORE you traverse; fan out with Traverse only for per-element operation calls.
- Filters must be ROBUST: if a schema field's description says it may be empty or optional, never
  demand it with eq() — an over-strict filter silently yields an empty result. Match on the
  identifying string fields (like fqn or name) with contains instead, and add conjuncts only when
  the task requires them.
- Make the final "result" as SMALL as possible: project just the fields needed to answer the task
  (e.g. afterLast(fqn, ".") for class names) and keep traversals narrow. Never include whole documents.

Example shape (structure only — use the real operation and argument names from the catalog; here
list_symbols returns kind "stream" with element {fqn, link}, get_doc returns kind "text" and its
whole string is {"path": ""}):
{
  "towl": "v1",
  "description": "inspect matching items",
  "let": {
    "ver": {"source": {"call": {"operation": "get_latest_version", "args": {"g": "x", "a": "y"}}}},
    "syms": {
      "source": {"call": {"operation": "list_symbols", "args": {"version": {"ref": "ver", "path": "result"}}}},
      "filter": {"contains": [{"path": "fqn"}, "Cache"]}
    },
    "docs": {
      "forEach": {"sym": {
        "from": {"ref": "syms"},
        "source": {"call": {"operation": "get_doc", "args": {"link": {"ref": "sym", "path": "link"}}}},
        "result": {"class": {"afterLast": [{"ref": "sym", "path": "fqn"}, "."]},
                   "doc": {"path": ""}}
      }},
      "onError": "collect"
    }
  },
  "result": {"ref": "docs"}
}

Operation catalog (JSON):
$catalogJson
""".trim()
    }
}
