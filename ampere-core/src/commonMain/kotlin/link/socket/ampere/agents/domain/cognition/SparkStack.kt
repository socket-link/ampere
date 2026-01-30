package link.socket.ampere.agents.domain.cognition

import kotlinx.serialization.Serializable

/**
 * The SparkStack manages the accumulation of Spark layers and computes the
 * effective cognitive context.
 *
 * Think of it as a call stack for cognition: each Spark is a frame that adds
 * context and potentially narrows capabilities. The SparkStack never expands
 * capabilities—it can only narrow them. This is an architectural invariant that
 * ensures child contexts can never exceed parent permissions.
 *
 * Key behaviors:
 * - **Immutable push/pop**: Operations return new stacks rather than mutating
 *   in place, enabling safe concurrency and easy undo
 * - **Prompt composition**: The system prompt is built by concatenating
 *   affinity + all Spark contributions
 * - **Capability narrowing**: Tool access is the intersection of all Spark
 *   constraints; file access uses intersection for read/write and union for
 *   forbidden patterns
 *
 * @property affinity The foundational cognitive type, set at creation
 * @property sparks The list of Sparks on the stack, in order of application
 */
@Serializable
class SparkStack private constructor(
    val affinity: CognitiveAffinity,
    val sparks: List<Spark>,
) {
    /**
     * The number of Sparks currently on the stack.
     */
    val depth: Int get() = sparks.size

    /**
     * Creates a new SparkStack with the given Spark pushed onto it.
     *
     * This operation is immutable—the original stack is not modified.
     *
     * @param spark The Spark to push
     * @return A new SparkStack with the Spark added
     */
    fun push(spark: Spark): SparkStack = SparkStack(
        affinity = affinity,
        sparks = sparks + spark,
    )

    /**
     * Creates a new SparkStack with the top Spark removed.
     *
     * This operation is immutable—the original stack is not modified.
     *
     * @return A new SparkStack with the top Spark removed, or null if empty
     */
    fun pop(): SparkStack? {
        if (sparks.isEmpty()) return null
        return SparkStack(
            affinity = affinity,
            sparks = sparks.dropLast(1),
        )
    }

    /**
     * Returns the top Spark on the stack, or null if empty.
     */
    fun peek(): Spark? = sparks.lastOrNull()

    /**
     * Builds the complete system prompt from the affinity and all Spark contributions.
     *
     * The prompt is structured as:
     * 1. Affinity header and prompt fragment
     * 2. Each Spark's prompt contribution, separated by horizontal rules
     *
     * @return The complete system prompt as markdown
     */
    fun buildSystemPrompt(): String = buildString {
        // Start with affinity
        appendLine("# Cognitive Context")
        appendLine()
        appendLine("**Affinity:** ${affinity.name}")
        appendLine()
        appendLine(affinity.promptFragment)

        // Add each Spark's contribution
        for (spark in sparks) {
            appendLine()
            appendLine("---")
            appendLine()
            appendLine(spark.promptContribution)
        }
    }

    /**
     * Computes the effective set of allowed tools given all Spark constraints.
     *
     * Uses intersection semantics: adding Sparks can only remove tools, never add them.
     *
     * @return The set of allowed tool IDs, or null if no Sparks constrain tools
     *         (meaning all tools are available)
     */
    fun effectiveAllowedTools(): Set<ToolId>? {
        val constrainingTools = sparks.mapNotNull { it.allowedTools }
        if (constrainingTools.isEmpty()) return null

        return constrainingTools.reduce { acc, tools -> acc.intersect(tools) }
    }

    /**
     * Computes the effective file access scope given all Spark constraints.
     *
     * - Read patterns: intersection (can only read what ALL allow)
     * - Write patterns: intersection (can only write what ALL allow)
     * - Forbidden patterns: union (blocked by ANY means blocked)
     *
     * @return The effective file access scope
     */
    fun effectiveFileAccess(): FileAccessScope {
        val constrainingScopes = sparks.mapNotNull { it.fileAccessScope }
        if (constrainingScopes.isEmpty()) return FileAccessScope.Permissive

        return constrainingScopes.reduce { acc, scope -> acc.intersect(scope) }
    }

    /**
     * Returns a human-readable description of the current cognitive state.
     *
     * Format: `[AFFINITY] → [Spark1] → [Spark2] → ...`
     *
     * @return A string representation of the stack
     */
    fun describe(): String = buildString {
        append("[${affinity.name}]")
        for (spark in sparks) {
            append(" → [${spark.name}]")
        }
    }

    /**
     * Checks if a Spark of the specified type is present on the stack.
     */
    inline fun <reified T : Spark> contains(): Boolean {
        return sparks.any { it is T }
    }

    /**
     * Finds the first Spark of the specified type on the stack.
     */
    inline fun <reified T : Spark> findSpark(): T? {
        return sparks.filterIsInstance<T>().firstOrNull()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as SparkStack
        return affinity == other.affinity && sparks == other.sparks
    }

    override fun hashCode(): Int {
        var result = affinity.hashCode()
        result = 31 * result + sparks.hashCode()
        return result
    }

    override fun toString(): String = describe()

    companion object {
        /**
         * Creates a new SparkStack with the given affinity and no Sparks.
         *
         * This is the primary factory method for creating SparkStacks.
         *
         * @param affinity The cognitive affinity for this stack
         * @return A new SparkStack with only the affinity set
         */
        fun withAffinity(affinity: CognitiveAffinity): SparkStack = SparkStack(
            affinity = affinity,
            sparks = emptyList(),
        )
    }
}

/**
 * Extension function for fluent Spark application.
 *
 * Allows chaining multiple Sparks in a single expression:
 * ```
 * SparkStack.withAffinity(ANALYTICAL)
 *     .with(projectSpark, roleSpark, languageSpark)
 * ```
 *
 * @param sparks The Sparks to push onto the stack
 * @return A new SparkStack with all Sparks applied in order
 */
fun SparkStack.with(vararg sparks: Spark): SparkStack {
    return sparks.fold(this) { stack, spark -> stack.push(spark) }
}
