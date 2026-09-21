package link.socket.ampere.dsl.events

import kotlinx.datetime.Instant

/**
 * View-layer projection of [link.socket.ampere.agents.domain.event.Event] for the
 * `AgentTeam` DSL.
 *
 * **Never persisted. Never an input to the Field fold.** The Field (Ampere's world
 * state) is a deterministic fold over persisted `Event`s; a `TeamEvent` is a
 * read-only rendering of one of those events for DSL consumers and carries no
 * state of its own. It is not an `Event`, is never written to `EventRepository`,
 * and nothing outside `link.socket.ampere.dsl` may construct or consume it except
 * the public `AmpereConfig.onEscalation` callback, which receives an [Escalated]
 * that the adapter derived from a published `Event`.
 *
 * Produced by [TeamEventAdapter.adapt] from a published `Event`. Any variant not
 * derived from an `Event` is UI-only, has no bus counterpart, and is listed here:
 *
 * - [GoalSet] — emitted by `AgentTeam.pursue` when a goal is assigned to the team.
 * - [AgentInitialized] — emitted by `AgentTeam.initializeAgents` once per member.
 * - [Planned] — emitted by `AgentTeam.delegateGoalToTeam` as a placeholder
 *   "analyzing goal" marker until the DSL is wired to real agents. The adapter also
 *   produces [Planned] from bus events; only the `AgentTeam` marker is UI-only.
 *
 * The boundary is test-enforced by `TeamEventBoundaryTest` (jvmTest): a
 * `TeamEvent` is never an `Event`, and no `commonMain` file outside `dsl/` refers
 * to `TeamEvent`.
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
