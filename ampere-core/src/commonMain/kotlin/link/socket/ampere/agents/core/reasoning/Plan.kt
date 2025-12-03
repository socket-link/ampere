package link.socket.ampere.agents.core.reasoning

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.core.expectations.Expectations
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.meetings.Meeting
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.utils.generateUUID

typealias PlanId = String

/**
 * Represents a high-level plan produced by an agent for any given type of task.
 */
@Serializable
sealed interface Plan {

    /** Unique identifier for the plan. */
    val id: PlanId

    /** Ordered list of steps the agent intends to take. */
    val tasks: List<Task>

    /** A rough, relative measure of complexity (e.g., 1–10). */
    val estimatedComplexity: Int

    /** A set of expectations the agent expects to be met during execution. */
    val expectations: Expectations

    @Serializable
    data object Blank : Plan {
        override val id: PlanId = ""
        override val tasks: List<Task> = emptyList()
        override val estimatedComplexity: Int = 0
        override val expectations: Expectations = Expectations.blank
    }

    @Serializable
    data class ForIdea(
        val idea: Idea,
        override val tasks: List<Task> = emptyList(),
        override val estimatedComplexity: Int = 0,
        override val expectations: Expectations = Expectations.blank,
    ) : Plan {

        override val id: PlanId =
            generateUUID(idea.id)
    }

    @Serializable
    data class ForMeeting(
        val meeting: Meeting,
        override val tasks: List<Task> = emptyList(),
        override val estimatedComplexity: Int = 0,
        override val expectations: Expectations = Expectations.blank,
    ) : Plan {

        override val id: PlanId =
            generateUUID(meeting.id)
    }

    @Serializable
    data class ForTask(
        val task: Task,
        override val tasks: List<Task> = emptyList(),
        override val estimatedComplexity: Int = 0,
        override val expectations: Expectations = Expectations.blank,
    ) : Plan {

        override val id: PlanId =
            generateUUID(task.id)
    }

    @Serializable
    data class ForTicket(
        val ticket: Ticket,
        override val tasks: List<Task> = emptyList(),
        override val estimatedComplexity: Int = 0,
        override val expectations: Expectations = Expectations.blank,
    ) : Plan {

        override val id: PlanId =
            generateUUID(ticket.id)
    }

    companion object {
        val blank: Plan = Blank
    }
}
