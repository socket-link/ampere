package link.socket.ampere.agents.execution.executor.acp

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.status.ExecutionStatus
import link.socket.ampere.agents.environment.EnvironmentFileOperation
import link.socket.ampere.agents.execution.executor.ExecutorId

/**
 * Bookkeeping for one prompt turn (AMPR-492): turns the agent's `session/update`
 * notifications into non-terminal [ExecutionStatus]es and keeps what the agent reported
 * doing, for the outcome.
 *
 * A `plan` update becomes a `Planning` status. A tool call that edits, deletes or moves
 * files, or that carries a diff, becomes one `Writing` status per path not seen before.
 * Any other new tool call becomes an `Analyzing` status with its title. Message chunks
 * accumulate into [finalMessage] and produce no status.
 *
 * The paths recorded here are what the agent *said* it touched. They are the fallback for
 * the outcome's changed files when the workspace cannot be observed, and the source of each
 * file's operation when it can. They are not the fence: an unreported write is caught by the
 * post-turn audit.
 */
class HarnessTurn(
    private val executorId: ExecutorId,
    private val fence: HarnessFence,
    private val now: () -> Instant = { Clock.System.now() },
) {

    private class CallState(var kind: String?, var title: String?)

    private val message = StringBuilder()
    private val calls = mutableMapOf<String, CallState>()
    private val edited = linkedMapOf<String, EnvironmentFileOperation>()
    private val served = linkedSetOf<String>()
    private val recorded = mutableListOf<Refusal>()

    /** What a refused permission request was refused for, so the outcome can say which error it is. */
    enum class RefusalKind {
        /** A path outside the workspace or the spark's file access scope. */
        PATH,

        /** A shell command the harness policy denies. */
        SHELL,

        /** No answer the client accepts was offered, such as no `allow_once`. */
        PROTOCOL,
    }

    /** One refused permission request: why, and of which kind. */
    data class Refusal(val reason: String, val kind: RefusalKind)

    /** Everything the agent said during the turn, trimmed. */
    val finalMessage: String
        get() = message.toString().trim()

    /** Paths the agent reported editing (workspace-relative when inside it), with the operation. */
    val reportedEdits: Map<String, EnvironmentFileOperation>
        get() = edited

    /** Paths written on the agent's behalf through `fs/write_text_file`, workspace-relative. */
    val servedWrites: Set<String>
        get() = served

    /** The permission requests this turn refused, each with its reason. */
    val refusedRequests: List<String>
        get() = refusals.map { it.reason }

    /** The permission requests this turn refused, each with its reason and kind. */
    val refusals: List<Refusal>
        get() = recorded

    /** The operation the agent reported for [relativePath], or null when it reported none. */
    fun operationFor(relativePath: String): EnvironmentFileOperation? = edited[relativePath]

    fun recordServedWrite(relativePath: String, operation: EnvironmentFileOperation) {
        served += relativePath
        if (relativePath !in edited) edited[relativePath] = operation
    }

    fun recordRefusal(reason: String, kind: RefusalKind) {
        recorded += Refusal(reason, kind)
    }

    /** The statuses [update] produces, in order; often none. */
    fun collect(update: SessionUpdate): List<ExecutionStatus> = when (update) {
        is SessionUpdate.AgentMessageChunk -> {
            update.content.text?.let { message.append(it) }
            emptyList()
        }
        is SessionUpdate.Plan -> listOf(
            ExecutionStatus.Planning(
                executorId = executorId,
                timestamp = now(),
                strategy = update.entries.joinToString("; ") { entry ->
                    listOfNotNull(entry.status?.let { "[$it]" }, entry.content).joinToString(" ")
                }.ifBlank { "plan" },
            ),
        )
        is SessionUpdate.ToolCall -> {
            val state = CallState(kind = update.kind, title = update.title)
            calls[update.toolCallId] = state
            statusesFor(state, update.locations, update.content, isNew = true)
        }
        is SessionUpdate.ToolCallUpdate -> {
            val state = calls.getOrPut(update.toolCallId) { CallState(kind = null, title = null) }
            update.kind?.let { state.kind = it }
            update.title?.let { state.title = it }
            statusesFor(state, update.locations.orEmpty(), update.content.orEmpty(), isNew = false)
        }
        is SessionUpdate.Other -> emptyList()
    }

    private fun statusesFor(
        state: CallState,
        locations: List<ToolCallLocation>,
        content: List<ToolCallContent>,
        isNew: Boolean,
    ): List<ExecutionStatus> {
        val kind = state.kind
        val writing = kind != null && kind in ToolKind.WRITING
        val fromDiffs = content
            .filter { it.type == ToolCallContent.DIFF }
            .mapNotNull { diff ->
                diff.path?.let { path ->
                    val operation =
                        if (diff.oldText == null) EnvironmentFileOperation.CREATE else EnvironmentFileOperation.MODIFY
                    path to operation
                }
            }
        val fromLocations = if (writing) locations.map { it.path to operationOf(kind) } else emptyList()

        val statuses = mutableListOf<ExecutionStatus>()
        for ((path, operation) in fromDiffs + fromLocations) {
            val relative = fence.relativize(path) ?: path
            if (relative in edited) continue
            edited[relative] = operation
            statuses += ExecutionStatus.Writing(
                executorId = executorId,
                timestamp = now(),
                filePath = relative,
                operation = operation,
            )
        }
        if (statuses.isEmpty() && isNew) {
            statuses += ExecutionStatus.Analyzing(
                executorId = executorId,
                timestamp = now(),
                description = state.title ?: state.kind ?: "tool call",
            )
        }
        return statuses
    }

    private fun operationOf(kind: String?): EnvironmentFileOperation = when (kind) {
        ToolKind.DELETE -> EnvironmentFileOperation.DELETE
        ToolKind.MOVE -> EnvironmentFileOperation.RENAME
        else -> EnvironmentFileOperation.MODIFY
    }
}
