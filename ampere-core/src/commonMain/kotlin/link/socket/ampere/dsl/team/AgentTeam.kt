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
 * A coordinated team of AI agents working toward shared goals.
 *
 * AgentTeam provides:
 * - Coordinated goal pursuit across multiple specialized agents
 * - Real-time event stream of agent activities
 * - Automatic task delegation based on agent capabilities
 *
 * Example:
 * ```kotlin
 * val team = AgentTeam.create {
 *     agent(ProductManager) { personality { directness = 0.8 } }
 *     agent(Engineer) { personality { creativity = 0.7 } }
 *     agent(QATester)
 * }
 *
 * team.pursue("Build a user authentication system")
 *
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
class AgentTeam private constructor(
    private val config: AgentTeamConfig,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<TeamEvent>(replay = 100)

    /**
     * Flow of simplified team events.
     * Subscribe to observe agent activities in real-time.
     */
    val events: Flow<TeamEvent> = _events.asSharedFlow()

    private val eventAdapter = TeamEventAdapter()
    private var isRunning = false
    private var currentGoal: String? = null

    /**
     * Assign a goal to the team and begin collaborative work.
     *
     * The team will:
     * 1. Break down the goal into tasks
     * 2. Assign tasks to appropriate agents based on capabilities
     * 3. Execute tasks and emit progress events
     * 4. Coordinate between agents as needed
     *
     * @param goal High-level description of what to accomplish
     */
    fun pursue(goal: String) {
        require(!isRunning) { "Team is already pursuing a goal. Call stop() first." }
        isRunning = true
        currentGoal = goal

        scope.launch {
            // Emit goal set event
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
     */
    fun pause() {
        isRunning = false
    }

    /**
     * Resume paused team activity.
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
    }

    /**
     * Get the current team members and their status.
     */
    fun getMembers(): List<TeamMemberStatus> {
        return config.members.map { member ->
            TeamMemberStatus(
                role = member.role.name,
                capabilities = member.role.capabilities.map { it.name },
                isActive = isRunning,
            )
        }
    }

    private suspend fun initializeAgents() {
        config.members.forEach { member ->
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
            _events.emit(
                Planned(
                    agent = coordinator.role.name,
                    plan = "Analyzing goal and creating task breakdown: $goal",
                    timestamp = Clock.System.now(),
                ),
            )

            // TODO: Wire up to actual agent infrastructure
            // This is where we would:
            // 1. Create agent instances from config.members using KoreAgentFactory
            // 2. Subscribe to EventRelayService to bridge internal events
            // 3. Invoke the coordinator agent to break down and delegate the goal
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
         *     config(AnthropicConfig(model = Claude.Sonnet4))
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
)
