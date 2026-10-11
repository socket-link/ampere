package link.socket.ampere.api.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder
import link.socket.ampere.dsl.team.TeamMemberStatus
import link.socket.ampere.field.Ref
import link.socket.ampere.field.Subject
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.propel.HostedAgent
import link.socket.ampere.propel.OpenSeat
import link.socket.ampere.propel.RunHost
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.subject.SubjectKind
import link.socket.ampere.subject.SubjectRegistry

/**
 * The shipped [AgentService].
 *
 * [team] declares the roster and [pursue] opens a hosted run over it — AMPR-393's
 * re-point of row H13. Before it, this service's whole team-and-goal surface was
 * dormant: the roster was a DSL value no agent was built from, and `pursue` published
 * one `TaskCreated` nothing consumed (AMPR-399). [inspect] and [listAll] now read the
 * seats of open runs, with the declared roster as the fallback for an instance that
 * has none.
 *
 * [wake] keeps its deprecation, because nothing re-points it: a seat exists for the
 * length of a run, so there is no dormant agent for a wake to reach.
 *
 * With a [subjectRegistry] (AMPR-416, F30) the fallback is the Subject registry rather
 * than the declared team's role strings: [team] declares, and the first [pursue],
 * [inspect] or [listAll] after it registers the team as a `Subject.Group` under
 * [AgentTeam.id] with one `MemberOf` edge per role, each role a `Subject.Agent` under
 * its existing id. [listAll] then reports every registered agent, and [inspect] resolves
 * an id against the store — an id nothing registered is not an agent. Without a
 * registry the service keeps reading the declared team, which is the composition the
 * surface tests build.
 *
 * @param runHost Where [pursue] opens its run. Null leaves [pursue] reporting that
 *   this instance cannot host — which is a composition the stub path can produce and
 *   `fromEnvironment` cannot.
 * @param subjectRegistry Where declared teams are registered and listed agents are read
 *   from. Null keeps the pre-F30 behaviour of reporting the declared team.
 * @param upstreamLlmClient The transport every seat [pursue] builds is given. Null
 *   fails the call rather than building a seat whose first model call throws: a
 *   transport is opted into (AMPR-236), and the clearer place to say so is here.
 * @param aiConfiguration The provider and model those seats default to.
 * @param cognitiveRelay Routing for those seats, when the instance carries one.
 * @param memoryStore What those seats recall from and learn into. Null gives a run
 *   that closes its loop with nothing to write to.
 * @param tools What a seat may dispatch, subject to its role declaring the id. A DSL
 *   role declares no tools, so a run [pursue] opens plans and reasons and dispatches
 *   nothing unless the roster is authored directly.
 */
internal class DefaultAgentService(
    private val agentActionService: AgentActionService,
    private val runHost: RunHost? = null,
    private val subjectRegistry: SubjectRegistry? = null,
    private val upstreamLlmClient: UpstreamLlmClient? = null,
    private val aiConfiguration: AIConfiguration = AIConfigurationFactory.getDefaultConfiguration(),
    private val cognitiveRelay: CognitiveRelay? = null,
    private val memoryStore: MemoryStore? = null,
    private val tools: Set<Tool<*>> = emptySet(),
) : AgentService {

    private var currentTeam: AgentTeam? = null

    /** The team [registerTeamIfNeeded] last wrote, by identity: a new [team] call re-registers. */
    private var registeredTeam: AgentTeam? = null
    private val registrationLock = Mutex()

    override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam {
        val team = AgentTeam.create(configure)
        currentTeam = team
        return team
    }

    /**
     * One hosted run over the declared roster, driven to completion.
     *
     * `close` is in a `finally`, which is the shape every hosted run wants: LEARN runs
     * under `NonCancellable`, so a run abandoned part-way still records its outcome
     * rather than leaving the task mid-flight (row H6).
     */
    override suspend fun pursue(goal: String): Result<String> {
        val host = runHost ?: return Result.failure(
            IllegalStateException(
                "This AMPERE instance cannot host a run (AmpereInstance.runs is null), so " +
                    "there is nothing for pursue to open.",
            ),
        )
        val team = currentTeam ?: return Result.failure(
            IllegalStateException("No team configured. Call team {} first."),
        )
        val transport = upstreamLlmClient ?: return Result.failure(
            IllegalStateException(
                "No UpstreamLlmClient was supplied to this instance, so its seats have " +
                    "nowhere to send a model call. Pass one to Ampere.fromEnvironment — a " +
                    "transport is opted into, never inherited (AMPR-236).",
            ),
        )

        return runCatching {
            registerTeamIfNeeded().getOrThrow()
            val roster = team.roster()
            val run = host.open(
                roster = roster,
                seats = roster.seats(transport),
                goal = Task.Step(
                    id = generateUUID("goal"),
                    status = TaskStatus.Pending,
                    description = goal,
                ),
                tools = tools,
            )
            try {
                run.execute()
            } finally {
                run.close()
            }
            run.runId
        }
    }

    @Deprecated(
        message = "wake wakes nothing: it publishes one TaskCreated for the agent and no " +
            "handler turns that into a cycle. A RunHost seat exists for the length of a " +
            "run, so there is nothing dormant to wake: use pursue, or AmpereInstance.runs " +
            "(AMPR-393).",
        replaceWith = ReplaceWith("pursue(goal)"),
    )
    override suspend fun wake(agentId: AgentId): Result<Unit> =
        agentActionService.wakeAgent(agentId)

    override suspend fun inspect(agentId: AgentId): Result<AgentSnapshot> {
        openSeats().firstOrNull { it.agentId == agentId || it.role.value == agentId }
            ?.let { return Result.success(it.asSnapshot()) }

        val registry = subjectRegistry ?: return inspectDeclared(agentId)
        registerTeamIfNeeded().onFailure { return Result.failure(it) }

        val subject = registry.resolve(Ref<Subject>(agentId)).getOrElse { cause ->
            return Result.failure(IllegalArgumentException("Agent not found: $agentId", cause))
        }
        if (subject !is Subject.Agent) {
            return Result.failure(
                IllegalArgumentException(
                    "Subject '$agentId' is registered as a ${SubjectKind.of(subject)}, not an agent",
                ),
            )
        }
        return Result.success(subject.asSnapshot(currentTeam?.getMembers()?.find { it.role == agentId }))
    }

    override suspend fun listAll(): List<AgentSnapshot> {
        val seats = openSeats()
        if (seats.isNotEmpty()) {
            return seats.map { it.asSnapshot() }
        }
        val registry = subjectRegistry ?: return listDeclared()
        // A registry that cannot be read falls back to the declaration rather than to an empty
        // list: an empty list reads as "no agents configured" to StatusService.health, which
        // would turn a store failure into a different, wrong diagnosis.
        registerTeamIfNeeded().onFailure { return listDeclared() }
        val agents = registry.agents().getOrElse { return listDeclared() }
        val members = currentTeam?.getMembers()?.associateBy { it.role }.orEmpty()
        return agents.map { agent -> agent.asSnapshot(members[agent.id.value]) }
    }

    /** The pre-F30 fallback: the declared team's roles, with the role name as the id. */
    private fun inspectDeclared(agentId: AgentId): Result<AgentSnapshot> {
        val team = currentTeam ?: return Result.failure(
            IllegalStateException("No team configured and no run open. Call team {} first."),
        )
        val member = team.getMembers().find { it.role == agentId }
            ?: return Result.failure(IllegalArgumentException("Agent not found: $agentId"))

        return Result.success(member.asSnapshot(agentId))
    }

    private fun listDeclared(): List<AgentSnapshot> =
        currentTeam?.getMembers()?.map { it.asSnapshot(it.role) }.orEmpty()

    /**
     * Register the current team once (AMPR-416, F30): each role as a `Subject.Agent` under its
     * role name — the id the public API has always used for it (C44) — then the team as a
     * `Subject.Group` under [AgentTeam.id] with one `MemberOf` edge per role. Every row's
     * provenance is the registration event the registry published through the door first.
     *
     * Idempotent per team instance; a later [team] call registers the new team on its next use.
     * Nothing to do without a registry or without a team.
     */
    private suspend fun registerTeamIfNeeded(): Result<Unit> {
        val registry = subjectRegistry ?: return Result.success(Unit)
        val team = currentTeam ?: return Result.success(Unit)
        return registrationLock.withLock {
            if (registeredTeam === team) return@withLock Result.success(Unit)
            runCatching {
                val members = team.getMembers().map { member ->
                    registry.registerAgent(member.role).getOrThrow().id
                }
                registry.registerGroup(id = team.id, displayName = "team", members = members).getOrThrow()
                registeredTeam = team
            }
        }
    }

    override suspend fun pause(agentId: AgentId): Result<Unit> {
        val team = currentTeam ?: return Result.failure(
            IllegalStateException("No team configured. Call team {} first."),
        )
        if (!team.pauseMember(agentId)) {
            return Result.failure(IllegalArgumentException("Agent not found: $agentId"))
        }
        return Result.success(Unit)
    }

    private fun openSeats(): List<OpenSeat> = runHost?.openRunSeats().orEmpty()

    /**
     * One seat per role of [this], each filled by an agent named after its role.
     *
     * The ids are derived from the role rather than minted, so two runs over the same
     * declared team read the same way in a trace — the seat is the thing being
     * reported on, and a fresh UUID per run would make every run look like a new cast.
     */
    private fun RosterConfig.seats(transport: UpstreamLlmClient): Map<RoleId, HostedAgent> =
        all().associate { role ->
            role.id to HostedAgent(
                id = "seat-${role.id.value}",
                role = role.id,
                aiConfiguration = aiConfiguration,
                cognitiveRelay = cognitiveRelay,
                upstreamLlmClient = transport,
                memory = memoryStore,
                affinity = CognitiveAffinity.INTEGRATIVE,
            )
        }

    private fun OpenSeat.asSnapshot(): AgentSnapshot = AgentSnapshot(
        id = agentId,
        role = role.value,
        // A seat of an open run is an agent at work, which is the one state on this
        // enum that was never reachable before AMPR-393.
        state = AgentState.Active,
        currentTask = goal,
        sparkStack = sparkNames,
        lastActivity = Clock.System.now(),
    )

    /**
     * A registered agent, with the declared team's flags when it is a member of the current
     * team and `Idle` with no capabilities when it is not: a registration says who exists, and
     * the team says who is at work.
     */
    private fun Subject.Agent.asSnapshot(member: TeamMemberStatus?): AgentSnapshot = AgentSnapshot(
        id = id.value,
        role = member?.role ?: name,
        state = when {
            member == null -> AgentState.Idle
            member.isPaused -> AgentState.Paused
            member.isActive -> AgentState.Active
            else -> AgentState.Idle
        },
        currentTask = null,
        sparkStack = member?.capabilities.orEmpty(),
        lastActivity = Clock.System.now(),
    )

    private fun TeamMemberStatus.asSnapshot(id: AgentId): AgentSnapshot = AgentSnapshot(
        id = id,
        role = role,
        state = when {
            isPaused -> AgentState.Paused
            isActive -> AgentState.Active
            else -> AgentState.Idle
        },
        // A declared role nobody is filling has no task: a task belongs to a run.
        currentTask = null,
        sparkStack = capabilities,
        lastActivity = Clock.System.now(),
    )
}
