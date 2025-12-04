package link.socket.ampere.agents.execution.tools.invoke

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import link.socket.ampere.agents.domain.event.EventId
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.execution.tools.ToolId

typealias ToolInvocationId = String

/**
 * Events emitted during tool execution lifecycle.
 *
 * These events provide observability into individual tool invocations,
 * distinct from the higher-level ExecutionStatus events emitted by
 * system-level Executors.
 *
 * ToolExecutionEvents are emitted by ToolInvoker when agents directly
 * invoke tools during their cognitive loop. They enable:
 * - Debugging tool invocation issues
 * - Performance analysis of individual tools
 * - Learning from tool usage patterns
 * - Auditing agent actions
 *
 * Note: These events can be published through AgentEventApi but are not
 * part of the main Event sealed hierarchy since they live in a different package.
 */
@Serializable
sealed interface ToolExecutionEvent {

    /**
     * Unique identifier for this event instance
     */
    val eventId: EventId

    /**
     * When the event occurred
     */
    val timestamp: Instant

    /**
     * Who triggered the event
     */
    val eventSource: EventSource

    /**
     * Event type discriminator
     */
    val eventType: EventType

    /**
     * Event urgency level
     */
    val urgency: Urgency

    /**
     * Unique identifier for this specific tool invocation.
     * Multiple invocations of the same tool will have different invocation IDs.
     */
    val invocationId: ToolInvocationId

    /**
     * The ID of the tool being executed
     */
    val toolId: ToolId

    /**
     * The name of the tool being executed
     */
    val toolName: String

    /**
     * A tool invocation has started.
     *
     * Emitted immediately before the tool's execute() method is called.
     * Paired with ToolExecutionEvent.Completed.
     *
     * @property invocationId Unique ID for this invocation
     * @property toolId ID of the tool being invoked
     * @property toolName Name of the tool being invoked
     */
    @Serializable
    data class Started(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        override val invocationId: ToolInvocationId,
        override val toolId: ToolId,
        override val toolName: String,
    ) : ToolExecutionEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "ToolExecutionStarted"
        }
    }

    /**
     * A tool invocation has completed (successfully or with failure).
     *
     * Emitted after the tool's execute() method returns, regardless of
     * whether execution succeeded or failed. Contains timing and outcome
     * metadata for observability.
     *
     * @property invocationId Unique ID for this invocation (matches Started event)
     * @property toolId ID of the tool that was invoked
     * @property toolName Name of the tool that was invoked
     * @property success Whether the tool execution succeeded
     * @property durationMs How long the execution took in milliseconds
     * @property errorMessage If execution failed, the error message; null if succeeded
     */
    @Serializable
    data class Completed(
        override val eventId: EventId,
        override val timestamp: Instant,
        override val eventSource: EventSource,
        override val urgency: Urgency,
        override val invocationId: ToolInvocationId,
        override val toolId: ToolId,
        override val toolName: String,
        val success: Boolean,
        val durationMs: Long,
        val errorMessage: String? = null,
    ) : ToolExecutionEvent {

        override val eventType: EventType = EVENT_TYPE

        companion object {
            const val EVENT_TYPE: EventType = "ToolExecutionCompleted"
        }
    }
}
