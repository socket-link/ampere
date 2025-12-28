package link.socket.ampere.agents.execution.tools.human

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

typealias HumanRequestId = String

/**
 * Registry for managing pending human interaction requests.
 *
 * When tools need human input, they register a request here and suspend
 * until a human provides a response through the CLI or other interface.
 *
 * This enables:
 * - Blocking tool execution until human responds
 * - Multiple concurrent human requests
 * - Timeout handling
 * - Request cancellation
 */
class HumanResponseRegistry {

    private val pendingRequests = mutableMapOf<HumanRequestId, CompletableDeferred<String?>>()

    /**
     * Register a new human interaction request and wait for response.
     *
     * Suspends until:
     * - A human provides a response via [provideResponse]
     * - The timeout expires
     * - The request is cancelled
     *
     * @param requestId Unique identifier for this request
     * @param timeout Maximum time to wait for response (default: 30 minutes)
     * @return Human response text, or null if timeout/cancelled
     */
    suspend fun waitForResponse(
        requestId: HumanRequestId,
        timeout: Duration = 30.minutes
    ): String? {
        val deferred = CompletableDeferred<String?>()
        pendingRequests[requestId] = deferred

        return try {
            withTimeoutOrNull(timeout) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    /**
     * Provide a human response to a pending request.
     *
     * This completes the corresponding [waitForResponse] suspension point,
     * allowing the agent tool to continue execution.
     *
     * @param requestId The request ID to respond to
     * @param response The human's response text
     * @return true if request was found and completed, false if not found
     */
    fun provideResponse(requestId: HumanRequestId, response: String): Boolean {
        val deferred = pendingRequests[requestId]
        return if (deferred != null) {
            deferred.complete(response)
            true
        } else {
            false
        }
    }

    /**
     * Cancel a pending request without providing a response.
     *
     * @param requestId The request ID to cancel
     * @return true if request was found and cancelled, false if not found
     */
    fun cancelRequest(requestId: HumanRequestId): Boolean {
        val deferred = pendingRequests.remove(requestId)
        return if (deferred != null) {
            deferred.complete(null)
            true
        } else {
            false
        }
    }

    /**
     * Get all currently pending request IDs.
     *
     * Useful for displaying active human interaction requests in the CLI.
     */
    fun getPendingRequestIds(): Set<HumanRequestId> {
        return pendingRequests.keys.toSet()
    }

    /**
     * Get the number of pending requests.
     */
    fun getPendingCount(): Int {
        return pendingRequests.size
    }

    /**
     * Clear all pending requests (typically used during shutdown).
     *
     * All waiting requests will receive null responses.
     */
    fun clearAll() {
        pendingRequests.values.forEach { it.complete(null) }
        pendingRequests.clear()
    }
}
