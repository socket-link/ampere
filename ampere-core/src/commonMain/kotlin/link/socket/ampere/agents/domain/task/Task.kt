package link.socket.ampere.agents.domain.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.domain.status.TaskStatus

typealias TaskId = String

@Serializable
sealed interface Task {

    val id: TaskId
    val status: TaskStatus

    @Serializable
    data object Blank : Task {

        override val id: TaskId = ""
        override val status: TaskStatus = TaskStatus.Pending
    }

    @Serializable
    data class CodeChange(
        override val id: TaskId,
        override val status: TaskStatus,
        val description: String,
        val assignedTo: AssignedTo? = null,
        /**
         * Tool id this step nominates for execution. Populated by the planning
         * pipeline from the LLM's `toolToUse` field; `null` denotes a
         * reasoning step — one whose work is thinking rather than acting.
         *
         * The executor routes strictly on this id with no keyword fallback. A
         * null id is not a no-op: the executing agent carries the step out as
         * one model call and its text becomes the step's result (AMPR-407).
         */
        val toolId: String? = null,
    ) : Task

    /**
     * A plan step that is not a code change (AMPR-410, AMPR-385 row H11).
     *
     * [CodeChange] is what every plan step was before this type existed, whether
     * or not it changed any code: `DefaultTaskFactory` emitted one per step, so a
     * step that read a calendar or posted a message still said "code change" to
     * every `when` that looked at it, and to every reader of the trace. `Step` is
     * the generic shape — the same description-and-tool pair, plus the three
     * things a consumer hosting a roster of seats needs — and `CodeChange` stays
     * for code agents, which is why the planner picks between them by what it was
     * asked to plan for rather than by a flag (see
     * [DefaultTaskFactory][link.socket.ampere.agents.domain.reasoning.DefaultTaskFactory]).
     *
     * Dispatch is identical to [CodeChange]'s: strict routing on [toolId] against
     * the agent's effective tool set, `null` meaning a reasoning step that invokes
     * nothing.
     *
     * @property description What this step accomplishes. Load-bearing, not a
     *   label: it is the step's intent for the nominated tool's
     *   [ParameterStrategy][link.socket.ampere.agents.execution.ParameterStrategy]
     *   and the `stepDescription` of the step's
     *   [PlanStepStarted][link.socket.ampere.agents.domain.event.PlanEvent.PlanStepStarted].
     * @property toolId Tool id this step nominates, under the same strict-dispatch
     *   contract as [CodeChange.toolId].
     * @property assignedTo Which seat runs this step. Set by the planner when it
     *   was given seats to plan over, and resolved against those seats — a step
     *   never names a seat the run does not have (see
     *   [PlanSeat][link.socket.ampere.agents.domain.reasoning.PlanSeat]). Null
     *   means the plan did not say, and the dispatching agent runs it itself.
     * @property arguments The nominated tool's call arguments, inline, for a tool
     *   that declares an
     *   [argumentSchema][link.socket.ampere.agents.execution.tools.FunctionTool.argumentSchema]
     *   the planner could fill. `SparkBasedAgent.buildPlanStepRequest` copies them
     *   onto [ExecutionRequest.arguments][link.socket.ampere.agents.execution.request.ExecutionRequest.arguments],
     *   where [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]
     *   validates them against that schema and, if they pass, dispatches without
     *   spending a parameter model call (AMPR-411). Null means the step's
     *   parameters are a strategy's to generate, as they always were.
     * @property execution How the step should be run — model and effort (AMPR-369).
     *   Taken from the seat the step was assigned to, not asked of the planner: a
     *   model does not get to choose which model answers it.
     */
    @Serializable
    data class Step(
        override val id: TaskId,
        override val status: TaskStatus,
        val description: String,
        val toolId: String? = null,
        val assignedTo: AssignedTo? = null,
        val arguments: JsonElement? = null,
        val execution: ExecutionAssignment? = null,
    ) : Task

    companion object {

        val blank: Task = Blank
    }
}

/**
 * The description this task carries, or null for a kind that carries none
 * (AMPR-410).
 *
 * [Task.CodeChange] and [Task.Step] are the two plan-step types and the only
 * two with a description; `Blank` has nothing to describe, and the open
 * families (`PMTask`, `TicketTask`, `MeetingTask`) share no description field.
 * It exists because every reader of a step's intent — the Perceive prompt, the
 * `MemoryContext` Recall queries with, the step's own `PlanStepStarted`, what an
 * MCP tool is handed — had a `Task.CodeChange` cast in it, and each one would
 * otherwise have silently started reporting a `Task.Step` by its id.
 */
val Task.planStepDescription: String?
    get() = when (this) {
        is Task.CodeChange -> description
        is Task.Step -> description
        else -> null
    }

/**
 * The tool this task nominates, or null when it nominates none (AMPR-393).
 *
 * The third reader of the two plan-step types, beside [planStepDescription] and
 * [planStepSeat], and here for the same reason: a `when` written over
 * `Task.CodeChange` alone would read a `Task.Step`'s nominated tool as "no tool"
 * and carry the step out as a reasoning step — a silent substitution of thinking
 * for acting. Null is a real answer, not a missing one: it is what a reasoning
 * step says (AMPR-407).
 */
val Task.planStepToolId: String?
    get() = when (this) {
        is Task.CodeChange -> toolId
        is Task.Step -> toolId
        else -> null
    }

/**
 * The seat this task is assigned to, or null when nothing assigned it
 * (AMPR-410). Null for a kind that cannot name a seat, which is the same
 * answer: the plan did not say.
 */
val Task.planStepSeat: AssignedTo?
    get() = when (this) {
        is Task.CodeChange -> assignedTo
        is Task.Step -> assignedTo
        else -> null
    }
