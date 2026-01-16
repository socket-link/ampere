package link.socket.ampere.dsl.events

import kotlinx.datetime.Instant

/**
 * Simplified event types for the DSL layer.
 *
 * These events provide a user-friendly abstraction over the internal event system,
 * making it easy to observe agent activities in real-time.
 *
 * Example:
 * ```kotlin
 * team.events.collect { event ->
 *     when (event) {
 *         is Perceived -> println("${event.agent} noticed: ${event.signal}")
 *         is Recalled -> println("${event.agent} remembered: ${event.memory}")
 *         is Planned -> println("${event.agent} decided: ${event.plan}")
 *         is Executed -> println("${event.agent} did: ${event.action}")
 *         is Escalated -> println("${event.agent} needs help: ${event.reason}")
 *     }
 * }
 * ```
 */
sealed interface TeamEvent {
    val timestamp: Instant
}

/**
 * An agent perceived something in its environment.
 * Corresponds to the perception phase of the autonomous agent loop.
 */
data class Perceived(
    val agent: String,
    val signal: String,
    override val timestamp: Instant,
) : TeamEvent

/**
 * An agent recalled relevant knowledge from memory.
 */
data class Recalled(
    val agent: String,
    val memory: String,
    val relevance: Double = 1.0,
    override val timestamp: Instant,
) : TeamEvent

/**
 * An agent created a plan for action.
 */
data class Planned(
    val agent: String,
    val plan: String,
    override val timestamp: Instant,
) : TeamEvent

/**
 * An agent executed an action.
 */
data class Executed(
    val agent: String,
    val action: String,
    val result: String? = null,
    override val timestamp: Instant,
) : TeamEvent

/**
 * An agent escalated to human for help.
 */
data class Escalated(
    val agent: String,
    val reason: String,
    val context: Map<String, String> = emptyMap(),
    override val timestamp: Instant,
) : TeamEvent

/**
 * A goal was assigned to the team.
 */
data class GoalSet(
    val goal: String,
    override val timestamp: Instant,
) : TeamEvent

/**
 * An agent was initialized and ready to work.
 */
data class AgentInitialized(
    val agent: String,
    val capabilities: List<String>,
    override val timestamp: Instant,
) : TeamEvent

/**
 * A task was delegated from one agent to another.
 */
data class TaskDelegated(
    val fromAgent: String,
    val toAgent: String,
    val task: String,
    override val timestamp: Instant,
) : TeamEvent

/**
 * A task was completed.
 */
data class TaskCompleted(
    val agent: String,
    val task: String,
    val success: Boolean,
    override val timestamp: Instant,
) : TeamEvent

/**
 * The goal was achieved.
 */
data class GoalAchieved(
    val goal: String,
    val summary: String,
    override val timestamp: Instant,
) : TeamEvent
