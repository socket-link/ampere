package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace

/** Platform-agnostic request for executing a tool */
@Serializable
data class ExecutionRequest<Context : ExecutionContext>(
    /** Additional context to help the executor understand how to execute the task */
    val context: Context,
    /** Constraints that bound the execution */
    val constraints: ExecutionConstraints,
    /**
     * Ambient Arc-run identity (AMPR-351) for the run this tool call belongs to.
     *
     * The request is the only value that reaches a tool's `executionFunction` at
     * dispatch time, so it is what carries the run to tools that produce
     * run-attributable output — today
     * [ToolAskHuman][link.socket.ampere.agents.execution.tools.ToolAskHuman], whose
     * Emissions would otherwise have `EmissionProvenance.runId == null`. Stamped by
     * [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]
     * from the reasoning unit's run; null for calls made outside a run.
     */
    val runId: RunId? = null,
    /**
     * The workspace the dispatching agent is pinned to (AMPR-300).
     *
     * An agent builds its plan-step requests around the generic
     * [ExecutionContext.NoChanges] and lets the nominated tool's
     * [ParameterStrategy][link.socket.ampere.agents.execution.ParameterStrategy]
     * promote them to a tool-specific context. A strategy that promotes into
     * [ExecutionContext.Code] must take the workspace from here (or from an
     * already-Code context); it must never invent one. Null means the agent was
     * built without a workspace, and code-file tools refuse to dispatch in that
     * state rather than writing into the process working directory.
     */
    val workspace: ExecutionWorkspace? = null,
    /**
     * The file access the dispatching agent's spark stack permits (AMPR-414).
     *
     * Stamped from
     * [AutonomousAgent.effectiveFileAccess][link.socket.ampere.agents.definition.AutonomousAgent.effectiveFileAccess]
     * where the agent builds the request, and read by the file-touching tools
     * (`read_code_file`, `write_code_file`) before they touch anything. Riding
     * on the request rather than being looked up at the tool is what keeps
     * `execution/tools` free of a dependency on `AutonomousAgent`: a tool is
     * handed its permissions, it does not go asking who dispatched it.
     *
     * Null means *unconstrained*, matching what a null
     * [AutonomousAgent.availableTools][link.socket.ampere.agents.definition.AutonomousAgent.availableTools]
     * means for tools: nothing narrowed this call. Every request a sparked
     * agent builds carries a scope — an unsparked stack composes to
     * [FileAccessScope.Permissive] rather than to null — so null is reached
     * only by callers that dispatch tools outside an agent (tests, direct
     * tool invocation), where there is no stack to honour.
     */
    val fileAccessScope: FileAccessScope? = null,
    /**
     * What the steps before this one produced, oldest first (AMPR-408).
     *
     * A plan step's parameters are *generated* by a model call a
     * [ParameterStrategy][link.socket.ampere.agents.execution.ParameterStrategy]
     * prompts for, and that prompt used to see the step's own description and
     * nothing else — so a plan whose second step consumes the first step's
     * output ("search for X, then summarise what you found") generated step
     * two's parameters with no idea what step one found. These are the results
     * the dispatching agent had in hand when it built this request, rendered
     * into the parameter prompt by
     * [priorResultsSection][link.socket.ampere.agents.execution.priorResultsSection].
     *
     * Rides on the request rather than on the
     * [ExecutionContext][link.socket.ampere.agents.execution.request.ExecutionContext]
     * for the same reason the workspace and the file access scope do: a
     * strategy rebuilds the context to carry its generated parameters, so what
     * the agent stamped has to live somewhere a rebuild cannot drop, and be
     * re-applied at the dispatch funnel.
     *
     * Empty is the honest value for a plan's first step, for a single-step plan,
     * and for a tool invoked outside a plan.
     */
    val priorResults: List<StepOutcome> = emptyList(),
) {

    /**
     * This request carrying [runId], or this request unchanged when [runId] is null
     * or already the one it carries.
     *
     * Null never clears a run that is already stated: the dispatch path rebuilds the
     * request more than once (every [ParameterStrategy][link.socket.ampere.agents.execution.ParameterStrategy]
     * constructs a fresh one to carry its generated parameters), and a rebuild that
     * dropped the run would silently un-attribute the call.
     */
    fun withRunId(runId: RunId?): ExecutionRequest<Context> =
        if (runId == null || runId == this.runId) this else copy(runId = runId)

    /**
     * This request carrying [fileAccessScope], or this request unchanged when
     * [fileAccessScope] is null or already the one it carries.
     *
     * Same rebuild problem as [withRunId], and the same reason null never
     * clears what is already stated: a [ParameterStrategy] builds a fresh
     * request to carry its generated parameters, so the scope the agent
     * stamped has to be re-applied at the dispatch funnel or the gate at the
     * tool would see nothing to enforce.
     */
    fun withFileAccessScope(fileAccessScope: FileAccessScope?): ExecutionRequest<Context> =
        if (fileAccessScope == null || fileAccessScope == this.fileAccessScope) {
            this
        } else {
            copy(fileAccessScope = fileAccessScope)
        }

    /**
     * This request carrying [priorResults], or this request unchanged when
     * [priorResults] is empty or already the list it carries.
     *
     * Same rebuild problem as [withRunId] and [withFileAccessScope], and the same
     * reason an empty list never clears what is already stated: the strategy that
     * *reads* the prior results to build its prompt then throws them away when it
     * constructs the request for its generated parameters, so the dispatch funnel
     * re-applies them. A tool that wants to see what came before it reads them off
     * the request it was dispatched with.
     */
    fun withPriorResults(priorResults: List<StepOutcome>): ExecutionRequest<Context> =
        if (priorResults.isEmpty() || priorResults == this.priorResults) {
            this
        } else {
            copy(priorResults = priorResults)
        }
}
