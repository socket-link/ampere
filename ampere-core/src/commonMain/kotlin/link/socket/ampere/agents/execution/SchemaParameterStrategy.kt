package link.socket.ampere.agents.execution

import kotlinx.serialization.json.JsonObject
import link.socket.ampere.agents.domain.reasoning.LLMResponseParser
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool

/** How many recalled-knowledge entries [SchemaParameterStrategy] renders into its prompt. */
private const val MAX_RECALLED_ENTRIES = 10

private const val DEFAULT_SYSTEM_MESSAGE =
    "You are a parameter generation system. You are given a tool's argument schema and the " +
        "step it is about to run. Respond only with a valid JSON object holding that tool's " +
        "arguments, using only the argument names the schema declares."

private const val DEFAULT_MAX_TOKENS = 1000

/**
 * A [ParameterStrategy] derived from a tool's declared argument schema (AMPR-411, H14).
 *
 * Every other strategy in the tree is written by hand per tool: `ProjectParams.IssueCreation`,
 * the code strategies, `PlanStepsStrategy`. Each owns a prompt, a parse and a promotion into
 * its tool's own
 * [ExecutionContext][link.socket.ampere.agents.execution.request.ExecutionContext] subtype,
 * which is the right shape for a tool whose parameters *are* a typed domain object. A
 * consumer that exposes dozens of typed capabilities as
 * [FunctionTool][link.socket.ampere.agents.execution.tools.FunctionTool]s has no such domain
 * object per tool — it has a schema — and writing the same strategy dozens of times is the
 * cost this removes.
 *
 * So this strategy promotes nothing. It renders [schema] into the prompt, validates the
 * model's answer against it, and hands the arguments on through
 * [ExecutionRequest.arguments] — leaving the context the agent built exactly as it was. A
 * tool reads its arguments off the request, which is the one value that reaches its
 * execution function.
 *
 * Reached automatically: [ToolExecutionEngine] builds one for any tool that declares an
 * `argumentSchema` and has no hand-written strategy, so a consumer registers nothing. Pass
 * one explicitly only to override [systemMessage] or [maxTokens].
 *
 * @property schema the tool's argument schema, in the subset [ToolArgumentSchema] reads.
 * @property systemMessage the system message for the parameter call.
 * @property maxTokens the token ceiling for the parameter call. Lower than the interface
 *   default, because the answer is one flat object of named arguments rather than a work
 *   breakdown or a file's contents.
 */
class SchemaParameterStrategy(
    private val schema: JsonObject,
    override val systemMessage: String = DEFAULT_SYSTEM_MESSAGE,
    override val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : ParameterStrategy {

    /**
     * The parameter prompt: what the tool is, what this step is for, what the earlier steps
     * produced, what Recall found, and the arguments to fill.
     *
     * The schema section is [ToolArgumentSchema.describe]'s rendering, so the names, types,
     * required flags, defaults and permitted values the model is shown are read from the same
     * place [parseAndEnrichRequest] checks its answer against.
     */
    override fun buildPrompt(
        tool: Tool<*>,
        request: ExecutionRequest<*>,
        intent: String,
    ): String = buildString {
        appendLine("# Tool Arguments")
        appendLine()
        appendLine("Fill in the call arguments for one tool invocation.")
        appendLine()
        appendLine("## Tool")
        appendLine("- id: `${tool.id}`")
        appendLine("- name: ${tool.name}")
        appendLine("- purpose: ${tool.description}")
        appendLine()
        appendLine("## What this step is for")
        appendLine(intent.ifBlank { "No step intent was supplied." })
        appendLine()

        // AMPR-412 (H17): what the earlier steps of this plan produced, rendered by the same
        // function every hand-written strategy uses, so a schema-described tool is told the
        // same things in the same words as a typed one.
        val earlierSteps = priorResultsSection(request.priorResults)
        if (earlierSteps.isNotBlank()) {
            appendLine("## Results of earlier steps")
            appendLine(earlierSteps)
        }

        val recalled = recalledContext(request)
        if (recalled.isNotEmpty()) {
            appendLine("## Recalled context")
            recalled.forEach { appendLine("- $it") }
            appendLine()
        }

        appendLine("## Arguments")
        appendLine(ToolArgumentSchema.describe(schema))
        appendLine()
        appendLine("## Output Format")
        appendLine("Respond with ONLY a JSON object whose keys are argument names from the list above.")
        appendLine("Do not add a key the list does not name; it will be discarded.")
        appendLine("Omit an optional argument rather than guessing a value for it.")
        appendLine("Use exactly the values a listed argument permits, where it lists them.")
    }

    /**
     * Validates the model's answer against [schema] and returns [originalRequest] carrying
     * the arguments.
     *
     * A *copy*, not a fresh request: unlike the hand-written strategies there is no context
     * to promote into, so rebuilding would drop the workspace, the run id and the file access
     * scope the dispatcher stamped for nothing.
     *
     * @throws SchemaArgumentException when the answer misses a required argument, types one
     *   in a way that cannot be read, or uses a value outside a declared `enum`.
     *   [ToolExecutionEngine] turns it into a typed `ExecutionOutcome` failure naming the
     *   offending arguments, in place of dispatching a tool with parameters it cannot use.
     */
    override fun parseAndEnrichRequest(
        jsonResponse: String,
        originalRequest: ExecutionRequest<*>,
    ): ExecutionRequest<*> {
        val cleaned = LLMResponseParser.cleanJsonResponse(jsonResponse)
        val generated = LLMResponseParser.parseJsonObject(cleaned)

        return when (val validation = ToolArgumentSchema.validate(schema, generated)) {
            is SchemaValidation.Valid -> originalRequest.withArguments(validation.arguments)
            is SchemaValidation.Invalid -> throw SchemaArgumentException(validation.violations)
        }
    }

    /**
     * What Recall put within reach of this step, as one line per entry.
     *
     * Read off `knowledgeFromPastMemory`, the context's carrier for what an earlier *phase*
     * retrieved. What the earlier *steps* produced is a different thing and rides a different
     * field — [ExecutionRequest.priorResults], rendered above through
     * [priorResultsSection] (AMPR-408, AMPR-412) — because a strategy rebuilds the context
     * and the dispatch funnel re-applies the request-level fields it must not lose. Capped,
     * because the parameter call's budget is for the answer and a long recall would
     * otherwise crowd the schema out of its own prompt.
     */
    private fun recalledContext(request: ExecutionRequest<*>): List<String> =
        request.context.knowledgeFromPastMemory
            .take(MAX_RECALLED_ENTRIES)
            .map { knowledge ->
                listOf(knowledge.approach, knowledge.learnings)
                    .filter { it.isNotBlank() }
                    .joinToString(" — ")
            }
            .filter { it.isNotBlank() }
}

/**
 * A set of tool arguments that did not pass the tool's schema.
 *
 * An [IllegalStateException] so that it travels the path every other strategy's refusal
 * already travels — [ToolExecutionEngine] catches it around the parameter call and reports
 * `ExecutionOutcome.NoChanges.Failure` — while still carrying [violations] typed, for a
 * caller that wants to know which argument was at fault rather than parse a message.
 */
class SchemaArgumentException(
    val violations: List<SchemaViolation>,
) : IllegalStateException(violations.joinToString("; ") { it.message })
