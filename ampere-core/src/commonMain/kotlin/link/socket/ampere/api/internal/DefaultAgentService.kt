// The deprecated AgentTeam surface (AMPR-399) is named throughout this file — in the
// imports, in the deprecated members and in their return types — so the warning is
// suppressed for the whole file rather than nine times inside it.
@file:Suppress("DEPRECATION")

package link.socket.ampere.api.internal

import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.service.AgentActionService
import link.socket.ampere.api.model.AgentSnapshot
import link.socket.ampere.api.model.AgentState
import link.socket.ampere.api.service.AgentService
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.AgentTeamBuilder
import link.socket.ampere.dsl.team.TeamMemberStatus

/**
 * The shipped [AgentService].
 *
 * [team], [pursue] and [wake] carry their interface's deprecation (AMPR-399): the team this
 * service holds is a DSL value and the two publishers put one task event on the record that
 * nothing consumes as work. [inspect], [listAll] and [pause] read and mutate that value and
 * are the only methods here whose effect is observable.
 *
 * The file-level `DEPRECATION` suppression at the top is for that: every member below either
 * implements the deprecated surface or reads the deprecated [AgentTeam] it produced. Lifting
 * it is part of re-pointing this service at `RunHost` (AMPR-393).
 */
internal class DefaultAgentService(
    private val agentActionService: AgentActionService,
    private val eventApi: AgentEventApi,
) : AgentService {

    private var currentTeam: AgentTeam? = null

    @Deprecated(
        message = "team {} builds a roster that nothing runs: no agent is constructed from " +
            "it and AgentTeam.pursue only emits UI markers. Run agents through the CLI or " +
            "AmpereRuntime; this method is re-pointed at RunHost when AMPR-393 ships.",
    )
    override fun team(configure: AgentTeamBuilder.() -> Unit): AgentTeam {
        val team = AgentTeam.create(configure)
        currentTeam = team
        return team
    }

    /**
     * Publishes one `TaskCreated` for [goal] and returns its id. Nothing opens a run from it;
     * see the deprecation on [AgentService.pursue].
     */
    @Deprecated(
        message = "pursue starts no work: it publishes one TaskCreated(assignedTo = null) " +
            "that nothing consumes as work. Drive a goal through the CLI or AmpereRuntime; " +
            "this method is re-pointed at RunHost when AMPR-393 ships.",
    )
    override suspend fun pursue(goal: String): Result<String> {
        return try {
            val taskId = "goal-${Clock.System.now().toEpochMilliseconds()}"
            eventApi.publishTaskCreated(
                taskId = taskId,
                urgency = Urgency.HIGH,
                description = goal,
                assignedTo = null,
            )
            Result.success(taskId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @Deprecated(
        message = "wake wakes nothing: it publishes one TaskCreated for the agent and no " +
            "handler turns that into a cycle. This method is re-pointed at RunHost when " +
            "AMPR-393 ships.",
    )
    override suspend fun wake(agentId: AgentId): Result<Unit> =
        agentActionService.wakeAgent(agentId)

    override suspend fun inspect(agentId: AgentId): Result<AgentSnapshot> {
        val team = currentTeam ?: return Result.failure(
            IllegalStateException("No team configured. Call team {} first."),
        )
        val member = team.getMembers().find { it.role == agentId }
            ?: return Result.failure(IllegalArgumentException("Agent not found: $agentId"))

        return Result.success(
            AgentSnapshot(
                id = agentId,
                role = member.role,
                state = stateOf(member),
                // Always null: no method on this service runs a task (AMPR-399). `RunHost`
                // (AMPR-393) is what gives a seat a task to report.
                currentTask = null,
                sparkStack = member.capabilities,
                lastActivity = Clock.System.now(),
            ),
        )
    }

    override suspend fun listAll(): List<AgentSnapshot> {
        val team = currentTeam ?: return emptyList()
        return team.getMembers().map { member ->
            AgentSnapshot(
                id = member.role,
                role = member.role,
                state = stateOf(member),
                currentTask = null,
                sparkStack = member.capabilities,
                lastActivity = Clock.System.now(),
            )
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

    private fun stateOf(member: TeamMemberStatus): AgentState = when {
        member.isPaused -> AgentState.Paused
        member.isActive -> AgentState.Active
        else -> AgentState.Idle
    }
}
