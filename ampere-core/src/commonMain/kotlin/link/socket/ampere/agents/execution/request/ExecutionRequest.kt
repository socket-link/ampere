package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId

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
}
