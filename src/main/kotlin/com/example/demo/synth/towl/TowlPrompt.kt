package com.example.demo.synth.towl

/**
 * The schema phase: everything a planner needs to author valid TOWL v3, generated from the RESOLVED
 * catalog — the same objects the checker and runtime use — rather than raw transport schemas.
 */
object TowlPrompt {

    /** An operation that reduces text, if this catalog has one (e.g. llm.summarize); never assumed. */
    fun summarizer(catalog: TowlCatalog): OperationSpec? =
        catalog.operations().firstOrNull { it.name.contains("summar", ignoreCase = true) && it.output == TString }

    /** One planner-facing catalog entry: exact parameter and result types in TOWL type syntax. */
    fun operationEntry(op: OperationSpec, catalog: TowlCatalog): Map<String, Any?> = linkedMapOf(
        "operation" to op.id,
        "description" to op.description,
        "params" to (op.input?.toString() ?: "{ } (no declared parameters)"),
        "returns" to returns(op.output),
        "effect" to op.effect.name.lowercase(),
        "note" to when (Types.stripNull(op.output)) {
            TString -> "the call's value IS the text (often long) — there is no wrapper member such as .result" +
                (summarizer(catalog)?.let { s -> if (s.id != op.id) "; pass the value to ${s.id} if the task needs a digest" else "" }
                    ?: "; this catalog has no operation that shortens text, so keep the number of documents small")
            TJson -> "the call's value is untyped json; use it whole, no member access"
            is TRecord -> "access members of the returned record, e.g. .${(Types.stripNull(op.output) as TRecord).fields.keys.first()}"
            else -> null
        },
    )

    /** Scalar results are spelled out so a model does not assume a record wrapper it saw on other tools. */
    private fun returns(t: Type): String = when (Types.stripNull(t)) {
        TString, TInt, TNumber, TBool, TTimestamp, TJson -> "$t  (a bare value, not a record)"
        else -> t.toString()
    }

    /** The TOWL v3 authoring guide: grammar, stdlib with types, rules, and one example. Generated from the catalog: it never names an operation the catalog lacks. */
    fun languageGuide(catalog: TowlCatalog): String {
        val sum = summarizer(catalog)
        val shortenRule = if (sum != null)
            "- Keep the result SMALL: project only the fields the answer needs. There is no truncation; to\n  shorten long text, call ${sum.id} on it (an ordinary, reported operation)."
        else
            "- Keep the result SMALL: project only the fields the answer needs. There is no truncation and this\n  catalog has no operation that shortens text: narrow with where(...) and return only what is asked."
        val exampleSum = if (sum != null) """,
        { "name": "sum", "call": "${sum.id}", "args": { "text": {"${'$'}": "doc"} } }""" else ""
        val exampleResult = if (sum != null) "{ class: s.fqn.after_last(\\\".\\\"), summary: sum }" else "{ class: s.fqn.after_last(\\\".\\\"), doc: doc }"
        return """
TOWL v3 is a small typed expression language for one workflow of catalog operations plus pure
transforms. You write ONE program; it is type-checked before anything runs and then executed with no
model in the loop.

PROGRAM (pass it to the tools as this structured object)
  { "towl": 3, "description": "what this does",
    "inputs":   { "name": "type" },              # optional; values the host supplies (list[string], { a: string })
    "bindings": [ <binding>, ... ],              # ordered; each name bound once; every binding must feed the result
    "result":   "expr" }                         # one result expression (text) — its value is the answer
  A <binding> is exactly one of:
    { "name": "x", "value": "expr" }                                   # any TOWL expression in text
    { "name": "x", "call": "ns.op", "args": { ...JSON... },             # an operation call; args is a real JSON object (omit if none):
      "options": { "tolerate": ["Code"] }, "then": ".result.where(...)" }   #   literal data as-is, references as {"$": "ver"} or {"$": "s.link"}
    { "name": "x", "for": { "over": "list expr", "as": "s", "bindings": [ <binding>, ... ], "result": "expr" } }   # fan-out with per-element calls

VALUES: "str"  12  1.5  true  null  [a, b]  { key: value }
CALLS (the ONLY effects) in expression text: call("namespace", "operation", { param: value, ... }, { tolerate: ["Code"] })
  Namespace and operation are string literals; args and options may be omitted. Args are checked against
  the operation's schema. tolerate makes the listed error codes yield null instead of stopping the program;
  Ordering comes from data: a call that uses another call's result runs after it. Calls may NOT appear inside paths, shapes, predicates, or
  another call's args: bind them first.
MEMBERS: x.Field   x?.Field (when x may be null; the result is nullable too)
FAN-OUT (the only binder), in expression text: for x in list <body>  -> list[body type]; bodies are independent and concurrent.
  One-line body (a record or any expression):  for s in syms { name: s.fqn }
  Multi-line body: bindings then the result, indented under the for line; the result is the LAST line. Braces are
  only records; bindings go on their own lines. There are no lambdas ('=>'), no .map/.each. In the structured
  form use the { name, for: {...} } node instead.
LIST FUNCTIONS take a PATH from the element (.Field.Sub) or a PREDICATE, never a function:
  .project(.Field) -> list[T]        .project({ name: .Field, n: .Items.count() }) -> list[record]
  .flat(.Items)    -> list[T]        flattens one list-typed member per element (use this, not project, for a flat list)
  .flatten()       -> list[T]        list[list[T]] -> list[T]
  .where(pred)     -> list[T]        pred: .A == "x"  .A != 1  .N < 5 (Null compares false)  .A in ["x","y"]  .A.present()  .A.absent()  .L.empty()
                                      .A.contains("s") .A.starts_with("s") .A.ends_with("s")  .L.any(pred)  .L.all(pred)
                                      combined with && || ! ( )
  .compact()       -> list[T]        drops nulls        .distinct() / .distinct(.Key)      .concat(otherList)
  .group(.Key)     -> list[{ key, items }]              .single() -> T | Null  (0 -> null, 2+ -> error)
AGGREGATES: .count() -> int   .sum(.N) .avg(.N)   .min(.N) .max(.N)   .collect(.Field) -> list   .any(pred) .all(pred)
  .top(5, .Size) / .bottom(5, .Size) -> list[{ rank: int, value: T }]  the n largest/smallest by a key (rank is data; lists are unordered)
  On a list of scalars the path may be omitted: xs.sum()  xs.max()  xs.top(3)
STRINGS: s.after_last(".")  s.before_first("/")  s.lower()  s.upper()
TIME: now (timestamp) and today ("YYYY-MM-DD") are predefined, no declaration needed: now.minus_days(4)  .minus_hours(6)  .minus_minutes(30)  .start_of_day()  .start_of_month()  .date() -> "YYYY-MM-DD"
TYPES you will see: string int number bool timestamp json list[T] { field: T } and T | Null (may be absent).
  T | Null values have no default operator. Pass them to args AS IS (a Null at runtime stops with a 'data' error
  naming the element), reach through them with ?., compare them (Null is never equal/less/greater), aggregate
  them (Null is skipped), and keep them nullable in results (Null is reported as Null). json is opaque: use it whole.

RULES
- Read each operation's "returns" type: if it is a record, access its members (.result, .items);
  if it is a bare string/json, the call's value IS the result — never invent a wrapper member.
- Search the catalog by what an operation DOES; task subjects (names, ids) are parameter values.
- Filter before you fan out; fan out (for) only when each element needs its own operation call.
- Any runtime error stops the program and you get a report of what completed; fix the program and
  run again, or declare tolerate for an error code the report showed. Do not guess error codes.
$shortenRule
- Only operations listed by the helper exist. If a capability you searched for is reported as
  unmatched, it does not exist in this catalog: do not search again; design the program without it.
- Order of list elements is not meaningful; there are no first/take/sort functions.

EXAMPLE (structure only — use real operation names and parameters from the catalog)
{ "towl": 3, "description": "classes about validation, with a digest of each",
  "bindings": [
    { "name": "ver",  "call": "docs.get_latest_version", "args": { "groupId": "g", "artifactId": "a" }, "then": ".result" },
    { "name": "syms", "call": "docs.list_symbols", "args": { "groupId": "g", "artifactId": "a", "version": {"$": "ver"} },
      "then": ".result.where(.fqn.contains(\"Validator\"))" },
    { "name": "digests", "for": { "over": "syms", "as": "s",
      "bindings": [
        { "name": "doc", "call": "docs.get_doc", "args": { "groupId": "g", "artifactId": "a", "version": {"$": "ver"}, "link": {"$": "s.link"} } }$exampleSum
      ],
      "result": "$exampleResult" } }
  ],
  "result": "digests" }
Never write a reference as a plain string in args ("version": "ver" is the LITERAL text ver); use {"$": "ver"}.
(docs.* above are placeholders; the only real namespaces are listed next.)

Namespaces in this catalog: ${catalog.namespaces.sorted().joinToString(", ")}
""".trim()
    }
}
