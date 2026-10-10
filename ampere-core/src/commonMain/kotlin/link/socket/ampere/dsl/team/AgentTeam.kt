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
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig

/**
 * A declared team of AI agents: the roles, and a UI's view of them.
 *
 * It declares; it does not run. [roster] is what makes the declaration useful — the
 * [RosterConfig] a hosted run is opened over (AMPR-393, row H13) — and
 * `AgentService.pursue` is what opens it, building one seat per role and handing the
 * lot to [RunHost][link.socket.ampere.propel.RunHost]. Nothing on this class
 * instantiates an agent, builds a spark stack, calls a model or publishes to the
 * event bus; [pursue] is deprecated for saying otherwise, and emits markers.
 *
 * What it provides:
 * - the roles you declared, as a roster ([roster]) and as [TeamMemberStatus] values
 *   from [getMembers]
 * - two flags over those roles: team-wide [pause]/[resume] and per-member
 *   [pauseMember]/[resumeMember]
 * - a [Flow] of [TeamEvent] markers this class emits about itself. A view for a UI,
 *   not the record: the durable record of a run is the `Event` stream on the bus,
 *   which a hosted run's seats publish through their own doors.
 *
 * ```kotlin
 * val team = ampere.agents.team {
 *     agent(ProductManager) { personality { directness = 0.8 } }
 *     agent(Engineer) { personality { creativity = 0.7 } }
 *     agent(QATester)
 * }
 *
 * // The run: PERCEIVE → … → LEARN over the roles above.
 * val runId = ampere.agents.pursue("Build a user authentication system").getOrThrow()
 * ```
 */
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
    @Deprecated(
        message = "AgentTeam.pursue emits UI markers and starts no work. The run that does " +
            "is AgentService.pursue, which opens a hosted run over roster() (AMPR-393).",
        replaceWith = ReplaceWith("ampere.agents.pursue(goal)"),
    )
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
     * This team's declared roles as a [RosterConfig] — the roster a hosted run is
     * opened over (AMPR-393, row H13).
     *
     * What re-points this class at `RunHost`: the DSL stays the place a consumer
     * *declares* a team, and the run is what happens to it.
     * `AgentService.pursue` builds one seat per role of this roster and hands the
     * lot to [RunHost][link.socket.ampere.propel.RunHost].
     *
     * The host is the member holding [Capability.DELEGATION], else the first one —
     * the same choice [pursue] makes when it decides who to attribute its `Planned`
     * marker to, because it is the same question. No verifier: a DSL member declares
     * capabilities, not Probes, so there is no seat to convict.
     *
     * Each role declares no tools. A role's tools are ids the consumer wires
     * (`RoleConfig.tools`), and this DSL has no vocabulary for them, so a run over
     * this roster plans and reasons and dispatches nothing — which is what the
     * declaration actually says. Author a [RosterConfig] directly to give a seat
     * tools.
     *
     * @throws IllegalStateException when the team declared no members; a roster needs
     *   a host, and there is nobody to be one.
     */
    fun roster(): RosterConfig {
        val members = config.members
        check(members.isNotEmpty()) { "an empty team has no host, so there is no roster to run" }
        val host = members.find { Capability.DELEGATION in it.role.capabilities } ?: members.first()
        return RosterConfig(
            host = RoleId(host.role.name),
            roles = members.map { member ->
                RoleConfig(
                    id = RoleId(member.role.name),
                    title = member.role.name,
                    instructions = PromptRef(id = "dsl-team/${member.role.name}", version = 1),
                )
            },
        )
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

            // Still a marker, and now a marker beside the real thing (AMPR-393): the
            // delegation this stood in for is `AgentService.pursue`, which builds one seat
            // per role of `roster()` and opens `RunHost.open(roster, seats, goal, tools,
            // policy)` over them. This method stays because its `TeamEvent` projections are
            // what a UI subscribed to `events` renders, and those are not events a hosted
            // run publishes — the run's record is the `Event` stream on the bus.
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
