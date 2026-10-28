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
import link.socket.ampere.agents.domain.reasoning.defaultPerceptionContext
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.api.AmpereExperimentalApi
import link.socket.ampere.api.service.FieldService
import link.socket.ampere.goal.GoalBranch
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.SeatTool

/**
 * The shipped [RunHost] (AMPR-385 rows H1, H6, H9).
 *
 * Builds one agent per seat from its [HostedAgent], charges a [RosterHostedRun] over
 * them, and keeps the open runs so [openSeats] can report who is filling what and so
 * no two of them share a run id.
 *
 * Seat construction is the whole of what this class does that the run does not: a
 * seat is a [SparkBasedAgent] with its own event door, its own reasoning unit, its
 * declared sparks stacked, its role's tools namespaced to it, and the run id on every
 * event it publishes. One agent class, differentiated per seat — a roster does not
 * need six agent *types*, which is the whole bet of the Spark system.
 */
@OptIn(ExperimentalAtomicApi::class, AmpereExperimentalApi::class)
internal class RosterRunHost(
    private val createEventApi: (AgentId) -> AgentEventApi,
    private val agentScope: CoroutineScope,
    private val executor: Executor?,
    private val heldRuns: HeldRunStore? = null,
    private val goalBranch: ((AgentEventApi) -> GoalBranch)? = null,
    private val field: FieldService? = null,
) : RunHost {

    /**
     * The runs charged and not yet closed, by run id, oldest first.
     *
     * An [AtomicReference] over an immutable map rather than a lock: the writes are
     * three per run and the reads are a status pane's, so the contention that would
     * justify a mutex does not exist, and [openSeats] has to answer without
     * suspending.
     *
     * An id is in the map from the moment [register] admits it, with no run against it
     * until its seats are built. That reservation is what makes the uniqueness check
     * hold under concurrency (AMPR-385 row H27): two `open` calls naming one id race on
     * one compare-and-set, and the loser is refused before it builds a seat.
     */
    private val openRuns = AtomicReference<Map<RunId, RosterHostedRun?>>(emptyMap())

    override suspend fun open(
        roster: Roster,
        seats: Map<RoleId, HostedAgent>,
        goal: Task,
        tools: Set<Tool<*>>,
        policy: RunPolicy,
        runId: RunId?,
        workspace: ExecutionWorkspace?,
    ): HostedRun {
        validate(roster, seats, policy, runId)

        val id = runId ?: generateUUID("hosted-run", roster.host.value)
        register(id)

        val run = try {
            val built = buildSeats(id, roster, seats, tools, workspace)
            RosterHostedRun(
                runId = id,
                roster = roster,
                seats = built,
                goal = goal,
                policy = policy,
                onClosed = ::forget,
                heldRuns = heldRuns,
                goalBranch = branchFor(roster, built),
                field = field,
                workspace = workspace,
            )
        } catch (throwable: Throwable) {
            release(id, holder = null)
            throw throwable
        }

        install(run)
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

    /**
     * The resume of a held run (AMPR-431). Everything that can refuse it does so before the
     * claim — the same validation [open] runs, the store, the id this host has open, and
     * whether the roster's host is the one the run held under, and whether a workspace the
     * caller names is the one it held in — and nothing is built or published until the
     * claim has landed. A refusal after the claim cannot happen short of a record replaced
     * between the read and the claim; that one leaves the record claimed, as an interrupted
     * resume does.
     */
    override suspend fun resume(
        runId: RunId,
        roster: Roster,
        seats: Map<RoleId, HostedAgent>,
        resolution: HoldResolution,
        tools: Set<Tool<*>>,
        policy: RunPolicy,
        workspace: ExecutionWorkspace?,
    ): HostedRun {
        validate(roster, seats, policy, runId)
        val store = heldRuns ?: throw HeldRunUnavailableException(runId, HeldRunUnavailableException.Why.NoStore)
        if (!tryRegister(runId)) throw HeldRunUnavailableException(runId, HeldRunUnavailableException.Why.StillOpen)

        val record = try {
            // Read first, so a roster that could not carry the run on is refused while the
            // record is still there for one that can. Claimed second: from here it is ours.
            val stored = store.get(runId).getOrThrow()
                ?: throw HeldRunUnavailableException(runId, HeldRunUnavailableException.Why.NotHeld)
            requireHost(stored, roster)
            requireWorkspace(stored, workspace)
            when (val claim = store.claim(runId).getOrThrow()) {
                is HeldRunClaim.Claimed -> claim.run.also {
                    requireHost(it, roster)
                    requireWorkspace(it, workspace)
                }
                HeldRunClaim.NotHeld ->
                    throw HeldRunUnavailableException(runId, HeldRunUnavailableException.Why.NotHeld)
                is HeldRunClaim.AlreadyClaimed ->
                    throw HeldRunUnavailableException(runId, HeldRunUnavailableException.Why.AlreadyClaimed)
            }
        } catch (throwable: Throwable) {
            release(runId, holder = null)
            throw throwable
        }

        // The caller's when it named one — equal to the record's by now, or the first one a run
        // that held without a checkout is given — and otherwise the one the run held in.
        val carriedOn = workspace ?: record.workspace
        val run = try {
            val built = buildSeats(runId, roster, seats, tools, carriedOn)
            RosterHostedRun(
                runId = runId,
                roster = roster,
                seats = built,
                goal = record.goal,
                policy = policy,
                onClosed = ::forget,
                heldRuns = store,
                goalBranch = branchFor(roster, built),
                field = field,
                workspace = carriedOn,
            )
        } catch (throwable: Throwable) {
            release(runId, holder = null)
            throw throwable
        }

        install(run)
        return try {
            run.resume(record, resolution)
            run
        } catch (throwable: Throwable) {
            forget(run)
            throw throwable
        }
    }

    override suspend fun heldRuns(): List<HeldRunSummary> =
        heldRuns?.list()?.getOrThrow() ?: emptyList()

    override fun openRunSeats(): List<OpenSeat> =
        openRuns.load().values.filterNotNull().flatMap { it.openSeats() }

    /**
     * The run's Goal branch, built over the host seat's own door (AMPR-473), or null when this
     * host was given none. Built per run, inside [open] and [resume] and never at composition,
     * so composing an instance touches no store.
     */
    private fun branchFor(roster: Roster, seats: Map<RoleId, HostedSeat>): GoalBranch? =
        goalBranch?.invoke(seats.getValue(roster.host).door)

    /** Refuse a roster whose host is not the role that planned the held run. */
    private fun requireHost(record: HeldRun, roster: Roster) {
        require(record.hostRole == roster.host.value) {
            "run '${record.runId}' held with '${record.hostRole}' as its host, and this roster's " +
                "host is '${roster.host.value}'; a held run carries on with the plan its own host made"
        }
    }

    /**
     * Refuse a [workspace] the caller names that is not the one the run held in (AMPR-496).
     *
     * The steps so far were made in the recorded checkout, and the steps left were planned
     * against it; carrying them on in another is a mix nothing can reconcile. Compared as a
     * whole value — directory, repository, branch and lease — because a workspace is
     * compared textually everywhere else too. A caller with nothing to add passes null and
     * gets the recorded one; a record with none takes the caller's.
     */
    private fun requireWorkspace(record: HeldRun, workspace: ExecutionWorkspace?) {
        val recorded = record.workspace ?: return
        if (workspace == null) return
        require(workspace == recorded) {
            "run '${record.runId}' held working in $recorded, and the resume names $workspace; " +
                "a held run carries on in the checkout it held in, so pass that workspace or none"
        }
    }

    /** Every seat of a run, built under [runId], each pinned to the run's [workspace]. */
    private fun buildSeats(
        runId: RunId,
        roster: Roster,
        seats: Map<RoleId, HostedAgent>,
        tools: Set<Tool<*>>,
        workspace: ExecutionWorkspace?,
    ): Map<RoleId, HostedSeat> = seats.mapValues { (role, descriptor) ->
        buildSeat(
            runId = runId,
            descriptor = descriptor,
            config = requireNotNull(roster.byId(role)) { "role '${role.value}' is not on the roster" },
            tools = tools,
            isHost = role == roster.host,
            workspace = workspace,
        )
    }

    /**
     * Reserve [runId] for a run about to be built, or refuse it.
     *
     * The check is inside the loop, against the same snapshot the compare-and-set
     * replaces, so an id admitted here was not open at the instant it was admitted. In
     * memory and per host: it says nothing about ids a closed run used, or ids another
     * host or another process holds.
     */
    private fun register(runId: RunId) {
        require(tryRegister(runId)) {
            "the run id '$runId' names a run this host still has open; a run id is " +
                "accepted again once the run holding it has closed"
        }
    }

    /** [register], answering false rather than throwing when [runId] is already open. */
    private fun tryRegister(runId: RunId): Boolean {
        while (true) {
            val current = openRuns.load()
            if (runId in current) return false
            if (openRuns.compareAndSet(current, current + (runId to null))) return true
        }
    }

    /** Put [run] against the id [register] reserved for it, keeping its place in order. */
    private fun install(run: RosterHostedRun) {
        while (true) {
            val current = openRuns.load()
            if (openRuns.compareAndSet(current, current + (run.runId to run))) return
        }
    }

    private fun forget(run: RosterHostedRun) = release(run.runId, holder = run)

    /**
     * Drop [runId], but only while [holder] is what holds it — the run itself, or null
     * for a reservation whose seats never got built. A run that closes late can never
     * release an id a newer run with the same id has since taken.
     */
    private fun release(runId: RunId, holder: RosterHostedRun?) {
        while (true) {
            val current = openRuns.load()
            if (runId !in current || current[runId] !== holder) return
            if (openRuns.compareAndSet(current, current - runId)) return
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
     *
     * The next two are the half-configured decision call (AMPR-421). A run that asks a
     * question on a seat with no decision transport would charge four phases and then
     * throw `MissingUpstreamDecisionClientException`
     * out of the first cycle's `assess` — except that `assess` drops what it catches,
     * so in fact it would ask nothing, silently, for the whole run. Refusing at `open`
     * is what makes "I asked for records and got none" impossible to reach by
     * configuration. A *non*-verifier seat's [HostedAgent.questions] are ignored rather
     * than refused, exactly as its [HostedAgent.probes] are: OBSERVE is one phase of the
     * run, not one per seat, so a question declared on a seat nobody asks is the same
     * kind of unused declaration.
     *
     * The receptor's cadences are refused on the same ground (AMPR-481, row N7; AMPR-483;
     * AMPR-484): a `Record` value of [PhaseCadence], [PerceiveCadence.WhenDrifted] and
     * [LearnCadence.WhenLesson] ask on the host seat, so they need that seat's transport —
     * under `WhenLesson` a missing answer would store nothing, silently, for the whole run;
     * [LearnCadence.EveryCycle] asks nothing and needs nothing. [PerceiveCadence.Record] and
     * [PerceiveCadence.WhenDrifted] ask what moved in the Field, so they need a Field to have
     * moved — a [FieldScope.Off] policy or a host built with no `FieldService` would leave
     * the drift question unasked every cycle, silently, for the same reason, and under
     * `WhenDrifted` would re-perceive every cycle as unchecked. [PerceiveCadence.WhenMoved]
     * (AMPR-487) reads what moved with no judge, so it needs the Field and not the transport:
     * without a world to move, every cycle would re-perceive as unchecked, which is
     * [PerceiveCadence.EveryCycle] under another name. [PerceiveCadence.EveryCycle] asks nothing
     * and needs nothing.
     *
     * The last is a blank caller-supplied run id (AMPR-385 row H27). The id is the only
     * thing that joins the run's events, its task lifecycle and its outcome, and a blank
     * one joins them to nothing a reader can query by. Whether the id is already *open*
     * is not checked here but in [register], where the check and the reservation are
     * one step.
     */
    private fun validate(
        roster: Roster,
        seats: Map<RoleId, HostedAgent>,
        policy: RunPolicy,
        runId: RunId?,
    ) {
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
        if (policy.goalCheck == GoalCheck.Record) {
            require(seats.getValue(roster.host).upstreamDecisionClient != null) {
                "GoalCheck.Record asks the host seat '${roster.host.value}' one question per " +
                    "executed cycle, and that seat carries no upstreamDecisionClient; a " +
                    "decision transport is opted into, never inherited"
            }
        }
        val perceiveCadence = policy.cadence.perceive
        // The drift cadences that ask the transport, and the ones that read the Field: every
        // drift question needs a world, and only a judged one needs a judge (AMPR-487).
        val driftCadence = when (perceiveCadence) {
            PerceiveCadence.Record -> "PerceiveCadence.Record"
            is PerceiveCadence.WhenDrifted -> "PerceiveCadence.WhenDrifted"
            PerceiveCadence.OncePerRun, PerceiveCadence.EveryCycle, PerceiveCadence.WhenMoved -> null
        }
        val fieldCadence = driftCadence ?: "PerceiveCadence.WhenMoved".takeIf {
            perceiveCadence == PerceiveCadence.WhenMoved
        }
        val lessonCadence = when (policy.cadence.learn) {
            LearnCadence.Record -> "LearnCadence.Record"
            is LearnCadence.WhenLesson -> "LearnCadence.WhenLesson"
            LearnCadence.AtClose, LearnCadence.EveryCycle -> null
        }
        if (driftCadence != null || lessonCadence != null) {
            val asked = listOfNotNull(driftCadence, lessonCadence).joinToString(" and ")
            require(seats.getValue(roster.host).upstreamDecisionClient != null) {
                "$asked asks the host seat '${roster.host.value}' after each executed cycle, " +
                    "and that seat carries no upstreamDecisionClient; a decision transport is " +
                    "opted into, never inherited"
            }
        }
        if (fieldCadence != null) {
            require(policy.observe.field != FieldScope.Off) {
                "$fieldCadence asks whether the world drifted since PERCEIVE, and " +
                    "ObservePolicy.field is FieldScope.Off; a drift question needs a world to " +
                    "have drifted, so observe the Field (FieldScope.Goal or Full) or keep the " +
                    "cadence at OncePerRun"
            }
            require(field != null) {
                "$fieldCadence asks whether the world drifted since PERCEIVE, and " +
                    "this host has no FieldService to read it from; a drift question needs a " +
                    "world to have drifted, so build the host with a Field (rosterRunHost(field = …)) " +
                    "or keep the cadence at OncePerRun"
            }
        }
        val verifier = roster.verifier?.let { seats[it] }
        if (verifier != null && verifier.questions.isNotEmpty()) {
            require(verifier.upstreamDecisionClient != null) {
                "the verifier seat '${verifier.role.value}' declares the questions " +
                    "${verifier.questions.keys.joinToString()} and carries no " +
                    "upstreamDecisionClient; a decision transport is opted into, never inherited"
            }
        }
        require(runId == null || runId.isNotBlank()) {
            "a supplied run id is what every event of the run is filed under, so it cannot " +
                "be blank; pass null to have one minted"
        }
    }

    /**
     * One seat, ready to think.
     *
     * The agent is built with phase handling on (AMPR-385 row H3: brackets are on by
     * default *inside* a hosted run, where the default elsewhere stays off), with the
     * run id so every event it publishes carries it (H2), with the executor so a
     * step naming a tool can actually run it (AMPR-405), and with the run's workspace so
     * every step request it builds carries the checkout a code tool or a harness works in
     * (AMPR-496). The workspace is the run's, never the seat's: [HostedAgent] has no field
     * for one, because two seats of one run naming two checkouts is a split nothing could
     * reconcile.
     *
     * [HostedAgent.charter] is stacked as the seat's first spark rather than passed as
     * a prompt preamble: `AutonomousAgent.currentSystemPrompt` builds from the stack
     * alone, and a charter that is a spark is also a charter that is on the record as
     * a `SparkAppliedEvent` — what a seat was told is part of why it did what it did.
     *
     * The host seat's PERCEIVE renders the default context and then the "# World" section
     * (AMPR-480, row N10), read off a [PerceivedWorld] the run fills before PERCEIVE — a seat
     * is built before its run exists, so the builder closes over the holder, not the run. The
     * hook *replaces* `defaultPerceptionContext` (AMPR-403), which is why the default is called
     * here by hand; with the holder empty the output is the default's, byte for byte. Every
     * other seat is built with no builder, and perceives nothing of the Field.
     */
    private fun buildSeat(
        runId: RunId,
        descriptor: HostedAgent,
        config: RoleConfig,
        tools: Set<Tool<*>>,
        isHost: Boolean,
        workspace: ExecutionWorkspace?,
    ): HostedSeat {
        val door = createEventApi(descriptor.id)
        val world = PerceivedWorld()
        val perceptionContextBuilder: ((AgentState) -> String)? = if (isHost) {
            { state -> defaultPerceptionContext(state) + (world.section ?: "") }
        } else {
            null
        }
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
            // AMPR-385 row H21: the seat's decision transport, or null — which is inert
            // until something asks, and is what every seat of a run that asks nothing has.
            _upstreamDecisionClient = descriptor.upstreamDecisionClient,
            // AMPR-385 row H26: this seat's own consent, or null — which leaves the
            // engine's provider in place, and that grants nothing. Never another seat's.
            _userGrantProvider = descriptor.userGrants,
            _observabilityScope = agentScope,
            _cognitiveRelay = descriptor.cognitiveRelay,
            _executor = executor,
            _runId = runId,
            // AMPR-496: the run's checkout, stamped on every step request this seat builds —
            // or null, which stays null (AMPR-300: a code tool then refuses, never guesses).
            _workspace = workspace,
            // Phases on, prompt injection on: a hosted run brackets all six phases and
            // lets each spark's `## When <Phase>` section reach the phase it names.
            _cognitiveConfig = CognitiveConfig(phaseSparks = PhaseSparkConfig(enabled = true)),
            _outcomeRepository = descriptor.memory?.outcomes,
            _perceptionContextBuilder = perceptionContextBuilder,
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
            world = world,
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
 * @property world Where this seat's PERCEIVE reads the "# World" section from (AMPR-480).
 *   Only the host seat's builder reads it; on every other seat it stays empty.
 */
internal class HostedSeat(
    val descriptor: HostedAgent,
    val config: RoleConfig,
    val agent: SparkBasedAgent<AgentState>,
    val door: AgentEventApi,
    val memory: AgentMemoryService?,
    val phases: PhaseSparkManager<AgentState>,
    val world: PerceivedWorld = PerceivedWorld(),
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
