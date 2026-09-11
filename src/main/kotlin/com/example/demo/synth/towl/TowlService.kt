package com.example.demo.synth.towl

import tools.jackson.databind.json.JsonMapper

/**
 * The stateless phase pipeline: schema -> parse -> validate -> explain -> run. Nothing is cached
 * or persisted; run always revalidates from source.
 */
class TowlService(
    val catalog: TowlCatalog,
    val registry: TowlRegistry = TowlRegistry.default(),
    env: Map<String, Any?> = emptyMap(),
    maxConcurrency: Int = 6,
) {
    private val parser = TowlParser(registry)
    private val validator = TowlValidator(registry, catalog, env.keys)
    private val explainer = TowlExplainer()
    private val interpreter = TowlInterpreter(registry, env, maxConcurrency)
    private val mapper = JsonMapper.builder().build()

    fun parse(text: String): Plan = parser.parse(text)

    fun validate(plan: Plan): ValidatedPlan = validator.validate(plan)

    fun explain(validated: ValidatedPlan): String = explainer.explain(validated)

    fun run(validated: ValidatedPlan, inputs: Map<String, Any?> = emptyMap()): Any? =
        interpreter.run(validated, inputs)

    /** Runs and returns the execution envelope: status, per-binding diagnostics, warnings, result. */
    fun execute(validated: ValidatedPlan, inputs: Map<String, Any?> = emptyMap()): ExecutionResult =
        interpreter.execute(validated, inputs)

    /** parse + validate + run from source, returning the result as compact JSON for the LLM. */
    fun runToJson(source: String, inputs: Map<String, Any?> = emptyMap()): String {
        val validated = validate(parse(source))
        return mapper.writeValueAsString(run(validated, inputs))
    }
}
