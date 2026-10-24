package link.socket.ampere.agents.execution.executor

import kotlinx.coroutines.flow.Flow
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolId
import link.socket.ampere.agents.health.ExecutorSystemHealth
import link.socket.ampere.roster.SeatTool

/**
 * One executor that sends each tool to the executor bound for it (AMPR-491, AMPR-444 D6).
 *
 * The engine dispatches every `FunctionTool` through its one injected `Executor`
 * (`ToolExecutionEngine.dispatch`), and that executor is handed in once per seat from
 * `rosterRunHost(executor = …)` and `Ampere.fromEnvironment(executor = …)`. A harness such as
 * an ACP agent can run one kind of tool and no other, so binding it *as* that executor would
 * send `ask_human` and every other tool somewhere that cannot run them. This routes instead:
 * a tool whose id is in [bindings] goes to its bound executor, every other tool to [default].
 *
 * Attribution follows the route. [performerFor] answers with the bound executor's own
 * performer, so the tool-event pair, the dispatched context and the outcome row the engine
 * stamps (AMPR-443) name the executor that actually ran the tool — never this router.
 *
 * Seat-namespaced ids are understood: a hosted run offers a seat's tool as `<seat>/<tool>`
 * (`SeatTool`), and a binding on the bare id matches it. A binding on the exact id is tried
 * first, so a consumer may also bind one seat's copy of a tool and not another's.
 *
 * This is an `Executor` *inside* the engine's one dispatch route, not a second route beside
 * it: the engine still runs the permission gate, writes the outcome row and publishes the one
 * pair per dispatch. The engine's `RetiredToolDispatchPathsTest` guards against the latter.
 *
 * @property default Where every tool with no binding goes — `FunctionExecutor.create()` for
 *   the in-process tools a consumer's actions are.
 * @property bindings Tool id to the executor that runs it. Keys are bare tool ids
 *   (`harness_code_change`) or exact seat-namespaced ones (`scout/harness_code_change`).
 */
class RoutedExecutor(
    private val default: Executor,
    private val bindings: Map<ToolId, Executor>,
    override val id: ExecutorId = DEFAULT_ID,
    override val displayName: String = DEFAULT_DISPLAY_NAME,
) : Executor {

    /** The default's: a router has no languages or frameworks of its own. */
    override val capabilities: ExecutorCapabilities
        get() = default.capabilities

    /** The executor [tool] is routed to: its exact id's binding, then its bare id's, then [default]. */
    fun executorFor(tool: Tool<*>): Executor =
        bindings[tool.id] ?: bindings[bareIdOf(tool.id)] ?: default

    override fun performerFor(tool: Tool<*>): ExecutorId = executorFor(tool).performerFor(tool)

    /**
     * The default's health and every binding's, folded: available only when all are, with
     * each unavailable binding's issues listed under its tool id.
     */
    override suspend fun performHealthCheck(): Result<ExecutorSystemHealth> {
        val defaultHealth = default.performHealthCheck().getOrElse { error ->
            return Result.failure(error)
        }
        val issues = defaultHealth.issues.toMutableList()
        var available = defaultHealth.isAvailable
        for ((toolId, executor) in bindings) {
            val checked = executor.performHealthCheck()
            val health = checked.getOrNull()
            if (health == null) {
                available = false
                issues += "$toolId (${executor.id}): health check failed: ${checked.exceptionOrNull()?.message}"
            } else {
                if (!health.isAvailable) available = false
                issues += health.issues.map { issue -> "$toolId (${executor.id}): $issue" }
            }
        }
        return Result.success(
            ExecutorSystemHealth(
                version = null,
                isAvailable = available,
                issues = issues,
            ),
        )
    }

    override suspend fun execute(
        request: ExecutionRequest<*>,
        tool: Tool<*>,
    ): Flow<ExecutionStatus> = executorFor(tool).execute(request, tool)

    companion object {
        const val DEFAULT_ID: ExecutorId = "routed"
        const val DEFAULT_DISPLAY_NAME: String = "Routed Executor"

        /** `tool` for `<seat>/tool`; the id itself when it names no seat. */
        fun bareIdOf(toolId: ToolId): ToolId = SeatTool.parse(toolId)?.tool ?: toolId
    }
}
