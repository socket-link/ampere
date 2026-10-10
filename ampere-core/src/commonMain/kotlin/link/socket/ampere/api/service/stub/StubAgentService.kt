// The deprecated AgentTeam surface (AMPR-399) is named throughout this file — in the
// imports, in the deprecated members and in their return types — so the warning is
// suppressed for the whole file rather than nine times inside it.
@file:Suppress("DEPRECATION")

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
 * [team], [pursue] and [wake] carry their interface's deprecation (AMPR-399), so a consumer
 * coding against the stub sees the same signal it will see against the shipped service: those
 * three start no work. The file-level `DEPRECATION` suppression at the top is for that: every
 * member here either implements that surface or reads the deprecated [AgentTeam] it produced.
 */
class StubAgentService : AgentService {

    private var goalCounter = 0
    private var currentTeam: AgentTeam? = null

    @Deprecated(
        message = "team {} builds a roster that nothing runs: no agent is constructed from " +
            "it and AgentTeam.pursue only emits UI markers. Run agents through the CLI or " +
            "AmpereRuntime; this method is re-pointed at RunHost when AMPR-393 ships.",
    )
    override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam =
        AgentTeam.create(configure).also { currentTeam = it }

    @Deprecated(
        message = "pursue starts no work: it publishes one TaskCreated(assignedTo = null) " +
            "that nothing consumes as work. Drive a goal through the CLI or AmpereRuntime; " +
            "this method is re-pointed at RunHost when AMPR-393 ships.",
    )
    override suspend fun pursue(goal: String): Result<String> {
        goalCounter++
        return Result.success("stub-goal-$goalCounter")
    }

    @Deprecated(
        message = "wake wakes nothing: it publishes one TaskCreated for the agent and no " +
            "handler turns that into a cycle. This method is re-pointed at RunHost when " +
            "AMPR-393 ships.",
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
