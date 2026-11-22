package link.socket.ampere.agents.tools.mcp.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import link.socket.ampere.agents.tools.mcp.protocol.McpClient
import link.socket.ampere.agents.tools.mcp.protocol.McpRequest
import link.socket.ampere.agents.tools.mcp.protocol.McpServerMessage
import link.socket.ampere.agents.tools.mcp.protocol.ToolCallResult

/**
 * What a tool call reports while it runs, and how it ends (AMPR-515).
 *
 * A call collected as a stream yields any number of [Progress] and [Log]
 * events and then exactly one terminal event, [Completed] or [Failed], after
 * which the stream completes. A failure is an event, not an exception: the
 * only thing the stream throws is the collector's own cancellation.
 *
 * No event carries credential material. [Progress.message] and [Log] are text
 * a server chose to send: data for a trace or a screen, never a format string
 * and never an instruction.
 */
sealed interface McpCallEvent {

    /**
     * The server's `notifications/progress` about this call.
     *
     * @property progress How far along, in whatever unit the server counts. It
     *   only ever increases.
     * @property total What [progress] is counting towards, when the server knows.
     * @property message What is happening, in the server's words. Untrusted.
     */
    data class Progress(
        val progress: Double,
        val total: Double? = null,
        val message: String? = null,
    ) : McpCallEvent

    /**
     * A `notifications/message`: one log message the server sent while this
     * call was running.
     *
     * Over HTTP it arrived on this call's own response stream. Over stdio there
     * is one stream for everything, so it is whatever the server logged while
     * this call was the one in flight.
     *
     * @property level The server's severity for it (`debug` … `emergency`), as text.
     * @property logger The name of the server-side logger, if it gave one.
     * @property data The message itself: any JSON. Untrusted.
     */
    data class Log(
        val level: String,
        val logger: String? = null,
        val data: JsonElement,
    ) : McpCallEvent

    /** Terminal: the server answered. [result] may still be a tool error, as [ToolCallResult.isError] says. */
    data class Completed(val result: ToolCallResult) : McpCallEvent

    /**
     * Terminal: the call did not produce an answer. [cause] is what
     * `invokeTool` would have failed with: a transport failure, a protocol
     * error, [McpRequestTimeoutException], and so on.
     */
    data class Failed(val cause: Throwable) : McpCallEvent
}

/**
 * How one tool call differs from the connection's defaults.
 *
 * @property timeoutMs How long the call may take in all, from the request to
 *   its answer. Null takes the connection's own limit. This is a ceiling, not
 *   an idle timer: progress does not extend it. A tool expected to run for
 *   minutes is called with a limit of minutes.
 */
data class McpCallOptions(
    val timeoutMs: Long? = null,
) {
    init {
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be positive, was $timeoutMs" }
    }

    companion object {
        /** The connection's defaults. */
        val Default: McpCallOptions = McpCallOptions()
    }
}

/**
 * Collect a streamed call to its end and return how it ended, ignoring what it
 * reported on the way: the answer `invokeTool` gives, for a call made with
 * [McpCallOptions].
 */
suspend fun Flow<McpCallEvent>.awaitResult(): Result<ToolCallResult> {
    var ending: Result<ToolCallResult>? = null
    collect { event ->
        if (event is McpCallEvent.Completed) ending = Result.success(event.result)
        if (event is McpCallEvent.Failed) ending = Result.failure(event.cause)
    }
    return ending ?: Result.failure(McpConnectionException("The tool call ended without a result"))
}

/** The terminal event for [result]: how a call that reported nothing on the way is streamed. */
internal fun terminalEventOf(result: Result<ToolCallResult>): McpCallEvent =
    result.fold(
        onSuccess = { McpCallEvent.Completed(it) },
        onFailure = { McpCallEvent.Failed(it) },
    )

/**
 * The event [message] is for the caller of the tool call [request], or null
 * when it is for nobody: progress carrying another call's token or none, a
 * request of the server's own, a notification of a kind this client does not
 * surface.
 *
 * Shared by the connections so that what counts as "about this call" is decided
 * once.
 */
internal fun McpClient.callEventFor(request: McpRequest, message: McpServerMessage): McpCallEvent? {
    val notification = message as? McpServerMessage.Notification ?: return null

    progressFor(request, notification)?.let { progress ->
        return McpCallEvent.Progress(progress.progress, progress.total, progress.message)
    }
    logMessageIn(notification)?.let { log ->
        return McpCallEvent.Log(log.level, log.logger, log.data)
    }
    return null
}
