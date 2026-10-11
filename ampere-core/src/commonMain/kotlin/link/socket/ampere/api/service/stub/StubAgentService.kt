package link.socket.ampere.api.service.stub

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder

/**
 * Stub implementation of [AgentService] for testing and parallel development.
 *
 * Returns sensible defaults without requiring real infrastructure.
 *
 * [pursue] is the one member whose stub is *not* what the shipped service does: there is no
 * infrastructure here to open a run on, so it hands back a counted id rather than a run id.
 * A consumer coding against the stub gets the shape and must not read the result as a run
 * that happened. [wake] carries its interface's deprecation, as the shipped service does.
 */
class StubAgentService : AgentService {

    private var goalCounter = 0
    private var currentTeam: AgentTeam? = null

    override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam =
        AgentTeam.create(configure).also { currentTeam = it }

    /** A counted id. No run is opened: the stub has no `RunHost` to open one on. */
    override suspend fun pursue(goal: String): Result<String> {
        goalCounter++
        return Result.success("stub-goal-$goalCounter")
    }

    @Deprecated(
        message = "wake wakes nothing: it publishes one TaskCreated for the agent and no " +
            "handler turns that into a cycle. A RunHost seat exists for the length of a " +
            "run, so there is nothing dormant to wake: use pursue, or AmpereInstance.runs " +
            "(AMPR-393).",
        replaceWith = ReplaceWith("pursue(goal)"),
    )
    override suspend fun wake(agentId: AgentId): Result<Unit> =
        Result.success(Unit)

    override suspend fun inspect(agentId: AgentId): Result<AgentSnapshot> =
        Result.success(
            AgentSnapshot(
                id = agentId,
                role = agentId,
                state = AgentState.Idle,
                currentTask = null,
                sparkStack = emptyList(),
                lastActivity = Clock.System.now(),
            ),
        )

    override suspend fun listAll(): List<AgentSnapshot> = emptyList()

    /**
     * Checked against the team from [team] so the stub agrees with the documented
     * contract: an unknown agent or a missing team is a failure, not a silent success.
     * The snapshots from [inspect] and [listAll] stay static, as elsewhere in this stub.
     */
    override suspend fun pause(agentId: AgentId): Result<Unit> {
        val team = currentTeam ?: return Result.failure(
            IllegalStateException("No team configured. Call team {} first."),
        )
        if (!team.pauseMember(agentId)) {
            return Result.failure(IllegalArgumentException("Agent not found: $agentId"))
        }
        return Result.success(Unit)
    }
}
