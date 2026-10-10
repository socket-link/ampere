package link.socket.ampere.agents.domain.cognition

import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase

/**
 * Type alias for tool identifiers.
 */
typealias ToolId = String

/**
 * A Spark is a specialization layer that narrows an agent's cognitive focus.
 *
 * Rather than discrete agent types, the Spark system uses a cellular differentiation model
 * where a single Agent class accumulates specialization through Spark layers. Each Spark
 * is a filter that:
 * - Adds context to the system prompt (always and/or per-PROPEL-phase)
 * - Declares which tools it needs to function ([requestedToolIds]) and/or narrows access
 * - Potentially constrains file access patterns
 * - Contributes a fragment of the agent's effective role label
 *
 * Sparks can narrow capability (allowedTools/fileAccessScope intersect) AND request additive
 * capability (requestedToolIds union). The architectural invariant is that *child* contexts
 * never exceed *parent* permissions, but a parent stack composes additively.
 *
 * The system prompt is dynamically rebuilt from the accumulated Spark stack before
 * each LLM interaction, allowing agents to specialize their behavior without
 * requiring separate class implementations.
 *
 * Sparks are pure data: no Kotlin lambdas. Behavioral guidance (how to perceive, plan,
 * execute, learn) is expressed as markdown in [phaseContributions], and the LLM follows
 * those instructions during the corresponding PROPEL phase. This keeps Sparks shareable
 * as `.spark.md` artifacts.
 *
 * Note: This is not a sealed interface to allow implementations in subpackages.
 * Concrete implementations should be serializable with appropriate @SerialName annotations.
 */
interface Spark {
    /**
     * Human-readable identifier for observability and debugging.
     * Format: "Type:Subtype" (e.g., "Role:Code", "Project:ampere")
     */
    val name: String

    /**
     * The markdown content this Spark always adds to the system prompt, regardless
     * of the agent's current cognitive phase.
     */
    val promptContribution: String

    /**
     * Per-phase markdown sections. The runtime selects the entry matching the
     * agent's current cognitive phase and appends it to the system prompt.
     *
     * Empty by default. Declarative `.spark.md` documents populate this via the six
     * `## When Perceiving / Recalling / Observing / Planning / Executing / Learning`
     * sections, whose rules are [fromMarkdown]'s.
     */
    val phaseContributions: Map<CognitivePhase, String>
        get() = emptyMap()

    /**
     * Fragment of the agent's effective role label this Spark contributes.
     *
     * Composed across the stack by concatenation (e.g. "Code Writer + Cooking Domain").
     * Null means this Spark does not contribute to the role label.
     */
    val agentRole: String?
        get() = null

    /**
     * Tool IDs this Spark requests be available for the agent to function.
     *
     * Composed additively across the stack (union). Distinct from [allowedTools]:
     * `requestedToolIds` says "I need these to do my job"; `allowedTools` says
     * "of the tools available, only these are permitted."
     */
    val requestedToolIds: Set<ToolId>
        get() = emptySet()

    /**
     * Optional set of tool IDs this Spark permits.
     *
     * - If null: inherits from parent context (no change to available tools)
     * - If specified: narrows the available tools to this set
     *
     * When multiple Sparks specify tools, the effective set is the intersection
     * of all specified sets.
     */
    val allowedTools: Set<ToolId>?

    /**
     * Optional file access scope this Spark permits.
     *
     * - If null: inherits from parent context (no change to file access)
     * - If specified: narrows file access to these patterns
     *
     * Uses intersection semantics for read/write and union for forbidden.
     */
    val fileAccessScope: FileAccessScope?

    companion object {

        /**
         * Builds a [Spark] from a markdown spark body — the public entry point for
         * consumers with their own `.spark.md` catalogue.
         *
         * Exactly these six level-two headings, matched case-insensitively, become
         * [Spark.phaseContributions] entries:
         *
         * ```
         * ## When Perceiving
         * ## When Recalling
         * ## When Observing
         * ## When Planning
         * ## When Executing
         * ## When Learning
         * ```
         *
         * A section runs from its heading to the next level-two heading or end of body.
         * A level-*three* heading (`### …`) is structure inside the open section and does
         * not close it. Every other level-two heading — `## When nothing matches` included —
         * closes the open section and stays in the body verbatim. What is left after the
         * phase sections are lifted out, in source order, is [Spark.promptContribution].
         *
         * The result narrows nothing: `allowedTools` and `fileAccessScope` are null, because
         * a body of prose cannot declare a permission. Narrowing is frontmatter's job and
         * [SparkStack]'s to compose.
         *
         * This is the same splitter the bundled `.spark.md` parser uses, so a consumer's
         * document and an Ampere fixture split identically.
         *
         * @param id Identity for the spark; becomes its [Spark.name]. Trace projections
         *   bucket on a `Type:Subtype` prefix (`"Role:Code"`, `"PhaseSpark:cooking-domain"`),
         *   so prefer an id already in that shape.
         * @param body The markdown body, frontmatter already stripped.
         * @param frontmatter Optional flat key/value frontmatter. `tools` is read as a
         *   comma- or whitespace-separated list of [ToolId] into [Spark.requestedToolIds];
         *   `role` is read into [Spark.agentRole] only when [contributeRole] is true.
         *   Unrecognised keys are ignored.
         * @param contributeRole Whether a `role` key should become [Spark.agentRole]. Off by
         *   default: a role fragment lands in the agent's effective role label, which is a
         *   visible identity change, so the caller opts in.
         */
        fun fromMarkdown(
            id: String,
            body: String,
            frontmatter: Map<String, String> = emptyMap(),
            contributeRole: Boolean = false,
        ): Spark {
            val split = splitMarkdownSparkBody(body)
            return MarkdownSpark(
                name = id,
                promptContribution = split.base,
                phaseContributions = split.phaseContributions,
                agentRole = if (contributeRole) {
                    frontmatter[MARKDOWN_SPARK_ROLE_KEY]?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                },
                requestedToolIds = frontmatter[MARKDOWN_SPARK_TOOLS_KEY]
                    ?.let(::parseMarkdownSparkToolIds)
                    .orEmpty(),
            )
        }
    }
}
