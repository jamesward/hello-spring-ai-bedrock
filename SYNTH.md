# Synth — TOWL v3 over MCP tools

The synthetic tool system (Group B) gives the agent exactly three tools — `towlPlanHelper`,
`validateTowlPlan`, `runTowlPlan` — over a catalog built from the connected MCP servers plus the
`llm` namespace (`llm.summarize`). The agent discovers operations by capability, writes ONE TOWL v3
program as plain text, and the deterministic runtime executes it. Language: `aws-cli/TOWL_SPEC.md`
(v3). The v1 JSON plans this module used to accept are gone; `towl 3` text is the only input.

## Example program (what the LLM writes)

The tools take the program in TOWL's structured form (§3.1). A binding is `{ name, value }` (an
expression), `{ name, call, args?, options?, then? }` (an operation call whose `args` is a real
JSON object; `{"$": "ver"}` marks a reference), or `{ name, for: { over, as, bindings, result } }`
(a fan-out with its own structured bindings). The validator renders it to the text below and
annotates it:

```text
towl 3 "classes in the latest jackson-databind that involve polymorphic type validation"
ver  = call("javadocs", "get_latest_version", { groupId: "com.fasterxml.jackson.core", artifactId: "jackson-databind" }).result
syms = call("javadocs", "list_javadoc_symbols", { groupId: "com.fasterxml.jackson.core", artifactId: "jackson-databind", version: ver })
         .result.where(.fqn.contains("Polymorphic"))
for s in syms
  doc = call("javadocs", "get_javadoc_symbol", { groupId: "com.fasterxml.jackson.core", artifactId: "jackson-databind",
                                                version: ver, link: s.link })
  { class: s.fqn.after_last("."), summary: call("llm", "summarize", { text: doc }) }
```

`validateTowlPlan` returns the typed rendering the reviewer sees:

```text
ver  = call("javadocs", "get_latest_version", { ... }).result        // ver: string; javadocs.get_latest_version read ×1
syms = call("javadocs", "list_javadoc_symbols", { ... })             // syms: list[{ fqn: string, link: string, ... }]; ... read ×1
         .result.where(.fqn.contains("Polymorphic"))
for s in syms                                                          // wave ×dynamic; result: list[{ class: string, summary: string }]
  doc = call("javadocs", "get_javadoc_symbol", { ... })              // javadocs.get_javadoc_symbol read ×dynamic
  { class: s.fqn.after_last("."), summary: call("llm", "summarize", { text: doc }) }   // llm.summarize read ×dynamic
```

The same program as the tool receives it:

```json
{ "towl": 3, "description": "…",
  "bindings": [
    { "name": "ver",  "call": "javadocs.get_latest_version", "args": { "groupId": "com.fasterxml.jackson.core", "artifactId": "jackson-databind" }, "then": ".result" },
    { "name": "syms", "call": "javadocs.list_javadoc_symbols", "args": { "groupId": "…", "artifactId": "…", "version": {"$": "ver"} },
      "then": ".result.where(.fqn.contains(\"Polymorphic\"))" },
    { "name": "digests", "for": { "over": "syms", "as": "s",
      "bindings": [
        { "name": "doc", "call": "javadocs.get_javadoc_symbol", "args": { "groupId": "…", "artifactId": "…", "version": {"$": "ver"}, "link": {"$": "s.link"} } },
        { "name": "sum", "call": "llm.summarize", "args": { "text": {"$": "doc"} } } ],
      "result": "{ class: s.fqn.after_last(\".\"), summary: sum }" } } ],
  "result": "digests" }
```

## What changed from v1

| v1 (JSON plans) | v3 (text programs) |
|---|---|
| `{"let": {...}, "source": {"call": ...}, "forEach": ...}` with tagged nodes | `{ bindings: [{ name, value }], result }` skeleton; each value is for x in `ns.op({...}).where(pred) ...` text — types inferred, no tags |
| registry of functions/aggregators, `{"afterLast": [...]}` | fixed stdlib: `project flat flatten where compact distinct concat group single count sum avg min max collect any all after_last before_first lower upper` |
| `onError: fail | skip | collect`, `status: partial` | every error stops the program; `{ tolerate: ["Code"] }` on a call yields `null`; failure envelope lists `completed`, `fanout[].completed/failed/interrupted/not_started`, `mutations`; no partial success |
| `{"path": ""}` text truncated with `take` | no truncation anywhere; `call("llm", "summarize", { text })` is an explicit, reported operation |
| cardinality metadata (ONE/MANY, subject path) | JSON Schema → TOWL types; a list is just a `list[T]` member (`.result`) |
| `explain` waves text | typed rendering + effect table (`Render`) |

## Metrics

Model calls made *inside* a program (`llm.summarize`) run on the tool-free client with the scenario's
inner-call tracker attached (`InnerModelCalls`), so the comparison table's "inner turns" and "total
tokens" include them. The run envelope's `effects` also lists `llm.summarize` with its call count.

## Envelope (success)

```json
{ "valid": true, "status": "ok", "type": "list[{ class: string, summary: string }]",
  "value": [ ... ],
  "tolerated": [], "effects": [ { "operation": "javadocs.get_javadoc_symbol", "effect": "read", "calls": 7 }, ... ],
  "nodes": [ { "node": "where", "line": 3, "in": 618, "out": 7 }, { "node": "for", "line": 5, "in": 7, "out": 7 } ],
  "accounting": { "calls": 16, "waves": 1, "wall_ms": 4210 } }
```

## Envelope (failure)

```json
{ "valid": true, "status": "error",
  "error": { "class": "authorization", "code": "AccessDenied", "operation": "javadocs.get_javadoc_symbol",
             "line": 6, "element": { "fqn": "...", "link": "..." }, "action": "tolerate-candidate" },
  "completed": { "ver": { "type": "string", "value": "2.22.2" }, "syms": { "type": "list[...]", "value": [ ... ] } },
  "fanout": [ { "line": 5, "completed": [ ... ], "failed": [ ... ], "interrupted": [ ... ], "not_started": [ ... ] } ],
  "mutations": [], "accounting": { ... } }
```

The agent's next program declares `input done: list[...]` and `input remaining: list[...]`, and
`runTowlPlan` binds them from these sections, so finished work is not repeated.

## Layout

| File | Role |
|---|---|
| `towl/TowlIr.kt` | the structured program form (`ProgramIr`): skeleton object → text, with diagnostic `where` mapping |
| `towl/TowlSyntax.kt` | lexer, AST, parser for the v3 grammar |
| `towl/TowlTypes.kt` | types, assignability, JSON Schema → type mapping |
| `towl/TowlCatalog.kt` | catalog contract, error classes, MCP adapter |
| `towl/TowlCheck.kt` | names / catalog / types / effects, all diagnostics in one pass |
| `towl/TowlRuntime.kt` | dependency scheduling, waves, tolerate, stop-on-error, envelopes |
| `towl/TowlRender.kt` | typed rendering and validation report |
| `towl/TowlService.kt` | pipeline, `CompositeCatalog`, `LlmCatalog` (`llm.summarize`) |
| `towl/TowlPrompt.kt` | the language guide the helper tool returns |
| `towl/TowlTools.kt` | the three agent tools and the agent system prompt |
