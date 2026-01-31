package link.socket.ampere.agents.domain

/**
 * Interface for types that can describe themselves to LLM agents.
 *
 * Implementations provide structured information about their type, properties,
 * and purpose that can be formatted for use in prompts. This enables agents
 * to understand and work with typed data without hardcoded descriptions.
 *
 * For nested types that also implement [AgentDescribable], agents can request
 * additional details after selecting a top-level type.
 */
interface AgentDescribable {

    /**
     * Simple name of this type suitable for LLM reference.
     * Defaults to the class simple name.
     */
    val typeName: String
        get() = this::class.simpleName ?: "Unknown"

    /**
     * Human-readable description of this type's purpose.
     */
    val description: String

    /**
     * Returns a map of property names to their values for LLM context.
     *
     * Properties that implement [AgentDescribable] will have their [typeName]
     * shown, allowing agents to request more details on those nested types.
     * Other properties show their string representation.
     *
     * Override to include relevant properties. Default returns empty map.
     */
    fun describeProperties(): Map<String, String> = emptyMap()

    /**
     * Formats this type for inclusion in an LLM prompt.
     */
    fun toPromptString(): String = buildString {
        append("- $typeName: $description")
        val props = describeProperties()
        if (props.isNotEmpty()) {
            append(" [${props.entries.joinToString(", ") { "${it.key}: ${it.value}" }}]")
        }
    }
}

/**
 * Helper object for formatting collections of [AgentDescribable] types
 * for LLM prompts.
 */
object AgentTypeDescriber {

    /**
     * Formats a list of describable types grouped by their parent sealed class.
     *
     * @param types The list of types to format
     * @param title Optional title for the output
     * @return Formatted string suitable for LLM prompts
     */
    fun <T : AgentDescribable> formatGroupedByHierarchy(
        types: List<T>,
        title: String = "Available types:",
    ): String = buildString {
        appendLine(title)
        appendLine()

        types.groupBy { type ->
            // Extract parent class name from qualified name
            // e.g., "...Escalation.Discussion.CodeReview" -> "Discussion"
            type::class.qualifiedName?.let { qualifiedName ->
                val parts = qualifiedName.split('.')
                if (parts.size >= 2) {
                    parts[parts.size - 2] // Parent class name
                } else {
                    type::class.simpleName ?: "Other"
                }
            } ?: type::class.simpleName ?: "Other"
        }.forEach { (category, items) ->
            appendLine("## $category")
            items.forEach { item ->
                appendLine(item.toPromptString())
            }
            appendLine()
        }
    }

    /**
     * Formats a list of describable types with custom grouping.
     *
     * @param types The list of types to format
     * @param grouper Function to extract group name from each type
     * @param title Optional title for the output
     * @return Formatted string suitable for LLM prompts
     */
    fun <T : AgentDescribable> formatGrouped(
        types: List<T>,
        grouper: (T) -> String,
        title: String = "Available types:",
    ): String = buildString {
        appendLine(title)
        appendLine()

        types.groupBy(grouper).forEach { (category, items) ->
            appendLine("## $category")
            items.forEach { item ->
                appendLine(item.toPromptString())
            }
            appendLine()
        }
    }

    /**
     * Formats a single type with full details including all properties.
     * Useful when an agent wants more information about a specific type.
     *
     * @param type The type to describe in detail
     * @return Detailed description string
     */
    fun <T : AgentDescribable> describeInDetail(type: T): String = buildString {
        appendLine("Type: ${type.typeName}")
        appendLine("Description: ${type.description}")
        val props = type.describeProperties()
        if (props.isNotEmpty()) {
            appendLine("Properties:")
            props.forEach { (name, value) ->
                appendLine("  - $name: $value")
            }
        }
    }
}
