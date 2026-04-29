package link.socket.ampere.pause

import kotlinx.serialization.Serializable

/**
 * The reply to an [AgentPause].
 *
 * Every variant carries the same [correlationId] as the originating
 * [AgentPause], which is what lets the awaiter pair the response with the
 * request that emitted it.
 */
@Serializable
sealed interface AgentPauseResponse {

    /** Echo of the originating [AgentPause.correlationId]. */
    val correlationId: PauseCorrelationId

    /**
     * The user approved the paused operation. [payload] carries any
     * additional structured data the responder supplied (e.g., a confirmation
     * note, a chosen option). It is opaque to the pause primitive — Plugin
     * code parses it.
     */
    @Serializable
    data class Approved(
        override val correlationId: PauseCorrelationId,
        val payload: String? = null,
    ) : AgentPauseResponse

    /** The user rejected the paused operation. */
    @Serializable
    data class Rejected(
        override val correlationId: PauseCorrelationId,
        val reason: String? = null,
    ) : AgentPauseResponse

    /**
     * No channel produced a response within [AgentPause.timeoutMillis]. The
     * agent should treat the pause as unanswered.
     */
    @Serializable
    data class TimedOut(
        override val correlationId: PauseCorrelationId,
    ) : AgentPauseResponse
}
