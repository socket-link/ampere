package link.socket.ampere.agents.execution.request

import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.FileAccessScope
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
}
