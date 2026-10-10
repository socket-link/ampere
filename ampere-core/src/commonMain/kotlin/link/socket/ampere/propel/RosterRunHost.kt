package link.socket.ampere.propel

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.config.PhaseSparkConfig
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.reasoning.PlanSeat
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.SeatTool

/**
 * The shipped [RunHost] (AMPR-385 rows H1, H6, H9).
 *
 * Builds one agent per seat from its [HostedAgent], charges a [RosterHostedRun] over
 * them, and keeps the open runs so [openSeats] can report who is filling what.
 *
 * Seat construction is the whole of what this class does that the run does not: a
 * seat is a [SparkBasedAgent] with its own event door, its own reasoning unit, its
 * declared sparks stacked, its role's tools namespaced to it, and the run id on every
 * event it publishes. One agent class, differentiated per seat — a roster does not
 * need six agent *types*, which is the whole bet of the Spark system.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class RosterRunHost(
    private val createEventApi: (AgentId) -> AgentEventApi,
    private val agentScope: CoroutineScope,
    private val executor: Executor?,
) : RunHost {

    /**
     * The runs charged and not yet closed.
     *
     * An [AtomicReference] over an immutable list rather than a lock: the writes are
     * two per run and the reads are a status pane's, so the contention that would
     * justify a mutex does not exist, and [openSeats] has to answer without
     * suspending.
     */
    private val openRuns = AtomicReference<List<RosterHostedRun>>(emptyList())

    override suspend fun open(
        roster: Roster,
        seats: Map<RoleId, HostedAgent>,
        goal: Task,
        tools: Set<Tool<*>>,
        policy: RunPolicy,
    ): HostedRun {
        validate(roster, seats)

        val runId = generateUUID("hosted-run", roster.host.value)
        val built = seats.mapValues { (role, descriptor) ->
            buildSeat(
                runId = runId,
                descriptor = descriptor,
                config = requireNotNull(roster.byId(role)) { "role '${role.value}' is not on the roster" },
                tools = tools,
            )
        }

        val run = RosterHostedRun(
            runId = runId,
            roster = roster,
            seats = built,
            goal = goal,
            policy = policy,
            onClosed = ::forget,
        )

        register(run)
        return try {
            // The run is registered before it charges so that a seat's PERCEIVE is
            // already reportable by `openRunSeats()` while the call is still in flight —
            // a run that is spending model calls is a run that has agents at work.
            run.charge()
            run
        } catch (throwable: Throwable) {
            forget(run)
            throw throwable
        }
    }

    override fun openRunSeats(): List<OpenSeat> = openRuns.load().flatMap { it.openSeats() }

    private fun register(run: RosterHostedRun) {
        while (true) {
            val current = openRuns.load()
            if (openRuns.compareAndSet(current, current + run)) return
        }
    }

    private fun forget(run: RosterHostedRun) {
        while (true) {
            val current = openRuns.load()
            if (run !in current) return
            if (openRuns.compareAndSet(current, current - run)) return
        }
    }

    /**
     * What [RunHost.open] refuses, and why each one is worth refusing rather than
     * working around.
     *
     * A run with no host has nobody to plan, so there is no run to charge. A seat
     * naming a role the roster does not hold is a seat the planner will never be
     * shown and a step will never be dispatched to, which is a silent no-op if it is
     * allowed through. Two seats sharing an agent id share an event door, so the
     * trace could no longer say which of them did what — and the door's identity is
     * the only provenance a seat has.
     */
    private fun validate(roster: Roster, seats: Map<RoleId, HostedAgent>) {
        require(seats.containsKey(roster.host)) {
            "the roster's host '${roster.host.value}' has no seat; filled: " +
                seats.keys.joinToString { it.value }
        }
        val roles = roster.all().mapTo(mutableSetOf()) { it.id }
        val unknown = seats.keys - roles
        require(unknown.isEmpty()) {
            "these seats name roles the roster does not hold: ${unknown.joinToString { it.value }}"
        }
        val sharedIds = seats.values
            .groupingBy { it.id }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(sharedIds.isEmpty()) {
            "two seats cannot share an agent id, which is their event door's identity; " +
                "shared: ${sharedIds.joinToString()}"
        }
    }

    /**
     * One seat, ready to think.
     *
     * The agent is built with phase handling on (AMPR-385 row H3: brackets are on by
     * default *inside* a hosted run, where the default elsewhere stays off), with the
     * run id so every event it publishes carries it (H2), and with the executor so a
     * step naming a tool can actually run it (AMPR-405).
     *
     * [HostedAgent.charter] is stacked as the seat's first spark rather than passed as
     * a prompt preamble: `AutonomousAgent.currentSystemPrompt` builds from the stack
     * alone, and a charter that is a spark is also a charter that is on the record as
     * a `SparkAppliedEvent` — what a seat was told is part of why it did what it did.
     */
    private fun buildSeat(
        runId: RunId,
        descriptor: HostedAgent,
        config: RoleConfig,
        tools: Set<Tool<*>>,
    ): HostedSeat {
        val door = createEventApi(descriptor.id)
        val memory = descriptor.memory?.let { store ->
            AgentMemoryService(
                agentId = descriptor.id,
                knowledgeRepository = store.knowledge,
                eventApi = door,
            )
        }
        val seatTools = tools.seatScoped(config)

        val agent = SparkBasedAgent(
            agentId = descriptor.id,
            cognitiveAffinity = descriptor.affinity,
            initialState = AgentState(),
            _additionalTools = seatTools,
            _eventApi = door,
            _memoryService = memory,
            _aiConfiguration = descriptor.aiConfiguration,
            _upstreamLlmClient = descriptor.upstreamLlmClient,
            _observabilityScope = agentScope,
            _cognitiveRelay = descriptor.cognitiveRelay,
            _executor = executor,
            _runId = runId,
            // Phases on, prompt injection on: a hosted run brackets all six phases and
            // lets each spark's `## When <Phase>` section reach the phase it names.
            _cognitiveConfig = CognitiveConfig(phaseSparks = PhaseSparkConfig(enabled = true)),
            _outcomeRepository = descriptor.memory?.outcomes,
        )

        descriptor.charterSpark()?.let { agent.spark<SparkBasedAgent<AgentState>>(it) }
        descriptor.sparks.forEach { agent.spark<SparkBasedAgent<AgentState>>(it) }

        return HostedSeat(
            descriptor = descriptor,
            config = config,
            agent = agent,
            door = door,
            memory = memory,
            phases = PhaseSparkManager(agent = agent, enabled = true, eventApi = door),
        )
    }
}

/**
 * One filled seat, and everything the run drives it through.
 *
 * @property descriptor What the consumer declared.
 * @property config What the roster says this seat is.
 * @property agent The agent itself, differentiated by [HostedAgent.sparks].
 * @property door This seat's event door. Every event about this seat's work leaves
 *   through it, so `source_id` names the seat (F1, H2).
 * @property memory This seat's RECALL and LEARN, or null when it was given no store.
 * @property phases This seat's phase brackets. One manager per seat, so a step run by
 *   seat A brackets EXECUTE on A's door, inside the run-level EXECUTE the host opened.
 */
internal class HostedSeat(
    val descriptor: HostedAgent,
    val config: RoleConfig,
    val agent: SparkBasedAgent<AgentState>,
    val door: AgentEventApi,
    val memory: AgentMemoryService?,
    val phases: PhaseSparkManager<AgentState>,
) {

    val id: AgentId get() = descriptor.id
    val role: RoleId get() = descriptor.role

    /** This seat as the planner sees it: a name, who steps go to, and what it can run. */
    fun asPlanSeat(): PlanSeat = PlanSeat(
        seat = role.value,
        assignedTo = AssignedTo.Agent(id),
        // Read live off the agent, so a tool the spark stack withdrew is not offered
        // to the planner either (AMPR-400) — one set, offered and dispatched against.
        toolIds = agent.effectiveTools.mapTo(mutableSetOf<ToolId>()) { it.id },
        execution = config.execution,
    )

    /** Whether this seat can run a tool with [toolId]. */
    fun owns(toolId: ToolId): Boolean = agent.effectiveTools.any { it.id == toolId }

    /** The tool with [toolId] this seat may dispatch, or null when it has none. */
    fun toolFor(toolId: ToolId): Tool<*>? = agent.effectiveTools.firstOrNull { it.id == toolId }
}

/**
 * The run's tools that [config] declares, each namespaced to the seat (AMPR-413).
 *
 * Two filters in one: a seat may run what its role declares and nothing else, and a
 * tool the run was never given cannot be wired by declaring it. What is left over on
 * either side is not an error — the roster states what a seat needs and the consumer
 * states what is wired, and the two are written at different times — so a declared
 * tool nobody supplied is simply a tool this seat does not have.
 *
 * The id becomes `<seat>/<tool>` so that two seats sharing one tool are two runnable
 * things: the planner is offered both, a step names one, and the run knows who to
 * dispatch it to without a second field to disagree with. An
 * [McpTool][link.socket.ampere.agents.execution.tools.McpTool] keeps its bare id,
 * because that id is the server's name for the tool and renaming it would break the
 * `tools/call` it turns into; such a seat's tool is offered to the planner under the
 * bare id, which is consistent because both come off the same tool objects.
 */
private fun Set<Tool<*>>.seatScoped(config: RoleConfig): Set<Tool<*>> =
    filter { it.id in config.tools }
        .mapTo(mutableSetOf<Tool<*>>()) { it.scopedTo(config.id) }

private fun Tool<*>.scopedTo(seat: RoleId): Tool<*> = when (this) {
    is FunctionTool<*> -> withId(this, SeatTool(seat, id).id)
    else -> this
}

private fun <C : ExecutionContext> withId(tool: FunctionTool<C>, id: ToolId): FunctionTool<C> =
    tool.copy(id = id)

/**
 * [HostedAgent.charter] as a spark, or null when the seat was given none.
 *
 * Through [Spark.fromMarkdown] rather than a bespoke `Spark` implementation, so a
 * charter that happens to carry `## When Planning` sections has them reach PLAN like
 * any other markdown spark's (AMPR-392).
 */
private fun HostedAgent.charterSpark(): Spark? =
    charter?.takeIf { it.isNotBlank() }?.let { body ->
        Spark.fromMarkdown(id = "Charter:${role.value}", body = body)
    }
