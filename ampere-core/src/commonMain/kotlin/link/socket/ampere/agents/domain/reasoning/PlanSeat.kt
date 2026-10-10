package link.socket.ampere.agents.domain.reasoning

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.agents.execution.tools.ToolId

/**
 * One seat a plan may assign a step to: who runs it, and what that seat can run
 * (AMPR-410, AMPR-385 row H11).
 *
 * A consumer hosting a roster of roles hands [PlanGenerator.generate] one of
 * these per seat. The planning prompt then lists `seat: tools` instead of one
 * flat tool list, asks for a `seat` per step, and the parsed
 * [Task.Step.assignedTo][link.socket.ampere.agents.domain.task.Task.Step.assignedTo]
 * names the seat that will run it. With no seats declared the prompt and the plan
 * are what they were — a single implicit seat, the agent doing the planning.
 *
 * [seat] is deliberately a plain string rather than a
 * [RoleId][link.socket.ampere.roster.RoleId]: it is what the prompt shows and
 * the model echoes back, so it wants to be the role's *readable* name
 * ("Scout", "Inspector"), while [assignedTo] is the identity the dispatcher
 * resolves. A roster passes `seat = roleId.value`; an agent-per-seat caller can
 * use [forAgent], which makes the two the same string.
 *
 * @property seat The name the prompt lists this seat under and the planner
 *   answers with. Matched case-insensitively when a step names it.
 * @property assignedTo Who the step is dispatched to once the seat is resolved.
 * @property toolIds The tools this seat owns. A step assigned here may nominate
 *   only these; see [PlanGenerator]'s seat resolution for what happens when the
 *   planner pairs a tool with the wrong seat. Empty means the seat owns no tools
 *   and can only run reasoning steps.
 * @property execution How this seat's steps should be run — model and effort
 *   (AMPR-369). Stamped onto the step's `execution` for every step assigned here,
 *   so the run records the tier a step was meant to run at even where nothing
 *   honours it yet. Null declares no preference and the runner's default applies.
 */
@Serializable
data class PlanSeat(
    val seat: String,
    val assignedTo: AssignedTo,
    val toolIds: Set<ToolId> = emptySet(),
    val execution: ExecutionAssignment? = null,
) {

    /** Whether this seat owns [toolId]. A null [toolId] is a reasoning step, which any seat may run. */
    fun owns(toolId: String?): Boolean = toolId == null || toolId in toolIds

    companion object {

        /**
         * A seat that is one agent, named by its own id — the shape a caller
         * with an agent per seat and no separate role vocabulary wants.
         */
        fun forAgent(
            agentId: AgentId,
            toolIds: Set<ToolId> = emptySet(),
            execution: ExecutionAssignment? = null,
        ): PlanSeat = PlanSeat(
            seat = agentId,
            assignedTo = AssignedTo.Agent(agentId),
            toolIds = toolIds,
            execution = execution,
        )
    }
}
