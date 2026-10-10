// This file declares the deprecated AgentTeam (AMPR-399) and constructs it in its own
// companion factories, so the warning is suppressed for the whole file.
@file:Suppress("DEPRECATION")

package link.socket.ampere.dsl.team

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.dsl.agent.Capability
import link.socket.ampere.dsl.events.AgentInitialized
import link.socket.ampere.dsl.events.GoalSet
import link.socket.ampere.dsl.events.Planned
import link.socket.ampere.dsl.events.TeamEvent
import link.socket.ampere.dsl.events.TeamEventAdapter

/**
 * A declared team of AI agents. **Nothing in this class runs an agent (AMPR-399).**
 *
 * What it does provide:
 * - the roles you declared, as [TeamMemberStatus] values from [getMembers]
 * - two flags over those roles: team-wide [pause]/[resume] and per-member
 *   [pauseMember]/[resumeMember]
 * - a [Flow] of [TeamEvent] markers this class emits about itself
 *
 * What it does not provide, despite the names: no agent is instantiated from a member, no
 * spark stack is built, no model is called, no task is scheduled, and nothing is published
 * to the event bus. [pursue] emits a marker per declared member plus two of its own and
 * returns; its delegation step is a `TODO`. The flags it keeps are therefore a description of what [getMembers] reports, not
 * of any running work.
 *
 * The entry point that does charge PERCEIVE → … → LEARN over a roster is `RunHost`
 * (AMPR-393); this class is re-pointed at it when that ships. Until then the paths that run
 * agents are the CLI (`ampere --goal`, `ampere --issues`) and `AmpereRuntime`.
 *
 * Example of what is observable today:
 * ```kotlin
 * val team = AgentTeam.create {
 *     agent(ProductManager) { personality { directness = 0.8 } }
 *     agent(Engineer) { personality { creativity = 0.7 } }
 *     agent(QATester)
 * }
 *
 * team.pursue("Build a user authentication system")
 *
 * // GoalSet, then one AgentInitialized per member, then one Planned marker. No more.
 * team.events.collect { event -> println(event) }
 * ```
 */
@Deprecated(
    message = "AgentTeam declares a team that does not run: no agent is constructed from a " +
        "member and pursue only emits UI markers (AMPR-399). Run agents through the CLI or " +
        "AmpereRuntime; this class is re-pointed at RunHost when AMPR-393 ships.",
)
class AgentTeam private constructor(
    private val config: AgentTeamConfig,
    private val scope: CoroutineScope,
) {
    /**
     * Replay buffer for late UI subscribers; not an event log.
     *
     * Holds [TeamEvent] projections only. Nothing here is persisted or folded into
     * world state; the durable record is the `Event` stream on the bus.
     */
    private val _events = MutableSharedFlow<TeamEvent>(replay = 100)

    /**
     * Flow of simplified team events.
     * Subscribe to observe agent activities in real-time.
     *
     * This is a view of the bus, not the bus. To subscribe to the persisted
     * `Event` stream that feeds the Field fold, use `EventRelayService` /
     * `EventSerialBus` instead.
     */
    val events: Flow<TeamEvent> = _events.asSharedFlow()

    private val eventAdapter = TeamEventAdapter()
    private var isRunning = false
    private var currentGoal: String? = null

    /**
     * Roles paused one at a time by [pauseMember], tracked separately from the team-wide
     * [isRunning] flag so that pausing one agent does not stop the others.
     */
    private val pausedMembers = mutableSetOf<String>()

    /**
     * Record a goal against the team. **No work begins.**
     *
     * What happens, in the scope this team was created with: a [GoalSet] marker, one
     * [AgentInitialized] marker per declared member, and one [Planned] marker attributed to
     * whichever member holds [Capability.DELEGATION] (or the first member) whose text names
     * the goal. All of them are [TeamEvent] projections for a UI; none is an `Event`, so none
     * reaches the bus, the event store or a trace. No task is created, no plan is generated,
     * no agent is constructed or invoked.
     *
     * The flag it sets is what makes [getMembers] report its members as active, which is in
     * turn what `AgentService.inspect` and `AgentService.listAll` read.
     *
     * @param goal High-level description of what to accomplish
     */
    fun pursue(goal: String) {
        require(!isRunning) { "Team is already pursuing a goal. Call stop() first." }
        isRunning = true
        currentGoal = goal

        scope.launch {
            // Emit goal set event. UI-only marker: no bus Event corresponds to a DSL
            // goal assignment, so this is constructed directly (listed in TeamEvent KDoc).
            _events.emit(
                GoalSet(
                    goal = goal,
                    timestamp = Clock.System.now(),
                ),
            )

            // Initialize agents and emit initialization events
            initializeAgents()

            // Start goal execution
            delegateGoalToTeam(goal)
        }
    }

    /**
     * Pause all team activity.
     *
     * Agents paused individually by [pauseMember] are unaffected; they stay paused.
     */
    fun pause() {
        isRunning = false
    }

    /**
     * Pause a single team member, leaving the rest of the team running.
     *
     * A member paused this way reports `isActive = false` and `isPaused = true` from
     * [getMembers] while the team as a whole keeps running. Lift it with [resumeMember]:
     * the team-wide [resume] deliberately leaves per-member pauses alone.
     *
     * @param role The name of the member's [TeamMember.role]
     * @return true if [role] names a member of this team, false if it does not, in which
     *   case nothing was paused
     */
    fun pauseMember(role: String): Boolean {
        if (config.members.none { it.role.name == role }) return false
        pausedMembers.add(role)
        return true
    }

    /**
     * Resume a member that [pauseMember] paused.
     *
     * @param role The name of the member's [TeamMember.role]
     * @return true if [role] names a member of this team, false if it does not
     */
    fun resumeMember(role: String): Boolean {
        if (config.members.none { it.role.name == role }) return false
        pausedMembers.remove(role)
        return true
    }

    /**
     * Resume paused team activity.
     *
     * Members paused individually by [pauseMember] stay paused; use [resumeMember] on those.
     */
    fun resume() {
        require(currentGoal != null) { "No goal to resume. Call pursue() first." }
        isRunning = true
    }

    /**
     * Stop all team activity and clean up resources.
     */
    fun stop() {
        isRunning = false
        currentGoal = null
        pausedMembers.clear()
    }

    /**
     * Get the current team members and their status.
     */
    fun getMembers(): List<TeamMemberStatus> {
        return config.members.map { member ->
            val isPaused = member.role.name in pausedMembers
            TeamMemberStatus(
                role = member.role.name,
                capabilities = member.role.capabilities.map { it.name },
                isActive = isRunning && !isPaused,
                isPaused = isPaused,
            )
        }
    }

    private suspend fun initializeAgents() {
        config.members.forEach { member ->
            // UI-only marker: no bus Event corresponds to DSL member initialization
            // (listed in TeamEvent KDoc).
            _events.emit(
                AgentInitialized(
                    agent = member.role.name,
                    capabilities = member.role.capabilities.map { it.name },
                    timestamp = Clock.System.now(),
                ),
            )
        }
    }

    private suspend fun delegateGoalToTeam(goal: String) {
        // Find the coordinator (agent with DELEGATION capability, or first agent)
        val coordinator = config.members.find {
            it.role.capabilities.contains(Capability.DELEGATION)
        } ?: config.members.firstOrNull()

        if (coordinator != null) {
            // UI-only placeholder; the DSL is never wired to real agents (see TODO below).
            // No bus Event is published here, so there is nothing to route through
            // TeamEventAdapter.adapt (listed in TeamEvent KDoc).
            _events.emit(
                Planned(
                    agent = coordinator.role.name,
                    plan = "Analyzing goal and creating task breakdown: $goal",
                    timestamp = Clock.System.now(),
                ),
            )

            // TODO(AMPR-393): delegate to a hosted run instead of marking one.
            // This placeholder is why `AgentTeam` and `AgentService.team`/`pursue` are
            // deprecated (AMPR-399): a real delegation would construct agents from
            // config.members, bridge their events through the door, and hand the goal to the
            // coordinator. `RunHost.open(roster, seats, goal, tools, policy)` is the shape
            // that does it, over the `roster/` types rather than this DSL.
        }
    }

    /**
     * Internal method to bridge internal events to DSL events.
     * Called by the event system when internal events are published.
     */
    internal suspend fun onInternalEvent(event: Event) {
        val teamEvent = eventAdapter.adapt(event)
        if (teamEvent != null) {
            _events.emit(teamEvent)
        }
    }

    companion object {
        /**
         * Create a new AgentTeam using the DSL builder.
         *
         * Example:
         * ```kotlin
         * val team = AgentTeam.create {
         *     config(AnthropicConfig(model = Claude.Sonnet5))
         *     agent(ProductManager) { personality { directness = 0.8 } }
         *     agent(Engineer) { personality { creativity = 0.7 } }
         *     agent(QATester)
         * }
         * ```
         */
        fun create(block: AgentTeamBuilder.() -> Unit): AgentTeam {
            val config = AgentTeamBuilder().apply(block).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return AgentTeam(config, scope)
        }

        /**
         * Create a team with a specific coroutine scope.
         * Useful for testing or when you need control over the scope lifecycle.
         */
        fun create(
            scope: CoroutineScope,
            block: AgentTeamBuilder.() -> Unit,
        ): AgentTeam {
            val config = AgentTeamBuilder().apply(block).build()
            return AgentTeam(config, scope)
        }
    }
}

/**
 * Status information about a team member.
 */
data class TeamMemberStatus(
    val role: String,
    val capabilities: List<String>,
    val isActive: Boolean,
    /** True when this member was paused on its own by [AgentTeam.pauseMember]. */
    val isPaused: Boolean = false,
)
