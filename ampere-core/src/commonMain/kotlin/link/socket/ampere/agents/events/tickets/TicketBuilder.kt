package link.socket.ampere.agents.events.tickets

import kotlinx.datetime.Instant
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.events.messages.MessageThread

/**
 * Data class containing all parameters needed to create a ticket.
 *
 * This is the validated output of [TicketBuilder.build], containing
 * all required and optional fields ready for submission to the orchestrator.
 */
data class TicketSpecification(
    val title: String,
    val description: String,
    val type: TicketType,
    val priority: TicketPriority,
    val createdByAgentId: AgentId,
    val assignedToAgentId: AgentId? = null,
    val dueDate: Instant? = null,
)

/**
 * Fluent builder for creating ticket specifications.
 *
 * This builder provides a fluent interface for constructing [TicketSpecification]
 * instances, making it easier for PM agents and humans to create tickets with
 * proper validation.
 *
 * Example usage:
 * ```kotlin
 * val spec = TicketBuilder()
 *     .withTitle("Add user authentication")
 *     .withDescription("Implement OAuth2 login flow")
 *     .ofType(TicketType.FEATURE)
 *     .withPriority(TicketPriority.HIGH)
 *     .createdBy("pm-agent-1")
 *     .assignedTo("dev-agent-1")
 *     .build()
 * ```
 *
 * Or using the DSL function:
 * ```kotlin
 * val spec = ticket {
 *     withTitle("Fix login bug")
 *     withDescription("Users cannot log in with special characters")
 *     ofType(TicketType.BUG)
 *     withPriority(TicketPriority.CRITICAL)
 *     createdBy("pm-agent-1")
 * }
 * ```
 */
class TicketBuilder {
    private var title: String? = null
    private var description: String? = null
    private var type: TicketType? = null
    private var priority: TicketPriority? = null
    private var createdByAgentId: AgentId? = null
    private var assignedToAgentId: AgentId? = null
    private var dueDate: Instant? = null

    /**
     * Sets the ticket title.
     *
     * @param title Brief summary of the work item.
     * @return This builder for method chaining.
     */
    fun withTitle(title: String): TicketBuilder {
        this.title = title
        return this
    }

    /**
     * Sets the ticket description.
     *
     * @param description Detailed description of requirements and acceptance criteria.
     * @return This builder for method chaining.
     */
    fun withDescription(description: String): TicketBuilder {
        this.description = description
        return this
    }

    /**
     * Sets the ticket type.
     *
     * @param type Category of work item (FEATURE, BUG, TASK, SPIKE).
     * @return This builder for method chaining.
     */
    fun ofType(type: TicketType): TicketBuilder {
        this.type = type
        return this
    }

    /**
     * Sets the ticket priority.
     *
     * @param priority Priority level (LOW, MEDIUM, HIGH, CRITICAL).
     * @return This builder for method chaining.
     */
    fun withPriority(priority: TicketPriority): TicketBuilder {
        this.priority = priority
        return this
    }

    /**
     * Sets the agent assigned to work on this ticket.
     *
     * @param agentId ID of the agent to assign.
     * @return This builder for method chaining.
     */
    fun assignedTo(agentId: AgentId): TicketBuilder {
        this.assignedToAgentId = agentId
        return this
    }

    /**
     * Sets the due date for ticket completion.
     *
     * @param deadline Target completion date.
     * @return This builder for method chaining.
     */
    fun dueBy(deadline: Instant): TicketBuilder {
        this.dueDate = deadline
        return this
    }

    /**
     * Sets the agent that created this ticket.
     *
     * @param agentId ID of the creating agent.
     * @return This builder for method chaining.
     */
    fun createdBy(agentId: AgentId): TicketBuilder {
        this.createdByAgentId = agentId
        return this
    }

    /**
     * Builds and validates the ticket specification.
     *
     * @return A validated [TicketSpecification] ready for submission.
     * @throws IllegalStateException if required fields are missing.
     */
    fun build(): TicketSpecification {
        val missingFields = mutableListOf<String>()

        if (title == null) missingFields.add("title")
        if (description == null) missingFields.add("description")
        if (type == null) missingFields.add("type")
        if (priority == null) missingFields.add("priority")
        if (createdByAgentId == null) missingFields.add("createdBy")

        if (missingFields.isNotEmpty()) {
            throw IllegalStateException(
                "Cannot build ticket specification: missing required fields: ${missingFields.joinToString(", ")}",
            )
        }

        return TicketSpecification(
            title = title!!,
            description = description!!,
            type = type!!,
            priority = priority!!,
            createdByAgentId = createdByAgentId!!,
            assignedToAgentId = assignedToAgentId,
            dueDate = dueDate,
        )
    }
}

/**
 * DSL function for creating ticket specifications.
 *
 * Provides a clean, Kotlin-idiomatic way to construct tickets using
 * a lambda with receiver.
 *
 * Example:
 * ```kotlin
 * val spec = ticket {
 *     withTitle("Add login feature")
 *     withDescription("Implement user authentication")
 *     ofType(TicketType.FEATURE)
 *     withPriority(TicketPriority.HIGH)
 *     createdBy(pmAgentId)
 * }
 * ```
 *
 * @param block Configuration block for the ticket builder.
 * @return A validated [TicketSpecification].
 * @throws IllegalStateException if required fields are missing.
 */
fun ticket(block: TicketBuilder.() -> Unit): TicketSpecification {
    return TicketBuilder().apply(block).build()
}

/**
 * Creates a ticket from a specification.
 *
 * This extension function on [TicketOrchestrator] allows creating tickets
 * directly from a [TicketSpecification], which is the output of the
 * fluent builder API.
 *
 * Example:
 * ```kotlin
 * val spec = ticket {
 *     withTitle("Fix bug")
 *     withDescription("Description")
 *     ofType(TicketType.BUG)
 *     withPriority(TicketPriority.HIGH)
 *     createdBy(pmAgentId)
 * }
 *
 * val (ticket, thread) = orchestrator.create(spec).getOrThrow()
 * ```
 *
 * @param spec The ticket specification containing all parameters.
 * @return Result containing the created [Ticket] and associated [MessageThread].
 */
suspend fun TicketOrchestrator.create(
    spec: TicketSpecification,
): Result<Pair<Ticket, MessageThread>> {
    val result = createTicket(
        title = spec.title,
        description = spec.description,
        type = spec.type,
        priority = spec.priority,
        createdByAgentId = spec.createdByAgentId,
    )

    // If ticket was created and assignment is specified, assign it
    if (result.isSuccess && spec.assignedToAgentId != null) {
        val (ticket, _) = result.getOrNull()!!
        assignTicket(
            ticketId = ticket.id,
            targetAgentId = spec.assignedToAgentId,
            assignerAgentId = spec.createdByAgentId,
        )
    }

    return result
}
