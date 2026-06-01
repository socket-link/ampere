package link.socket.ampere.agents.domain.emission

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import link.socket.ampere.agents.domain.event.EmissionEvent

/**
 * Correlates suspended DSL calls with incoming [EmissionEvent.Resolved] replies.
 *
 * When the DSL's `ask` builder publishes an [EmissionEvent.BaseProduced], it
 * registers a [CompletableDeferred] here keyed by the emission's ID and suspends.
 * When the matching [EmissionEvent.Resolved] arrives on the bus (delivered by
 * a bus subscriber wired to [deliver]), the deferred completes and the suspended
 * call resumes with the reply.
 *
 * Both plain [EmissionEvent.BaseResolved] events and the specialised
 * [link.socket.ampere.agents.domain.event.HumanInteractionEvent.InputProvided]
 * implement [EmissionEvent.Resolved], so a single subscriber on
 * [EmissionEvent.Resolved.EVENT_TYPE] delivers all resolved events here
 * via polymorphic dispatch.
 */
class EmissionReplyRegistry {

    private val pending = mutableMapOf<EmissionId, CompletableDeferred<EmissionEvent.Resolved>>()

    /**
     * Suspend until a matching [EmissionEvent.Resolved] arrives or [timeout] expires.
     *
     * @return The resolved event, or `null` on timeout.
     */
    suspend fun awaitReply(
        emissionId: EmissionId,
        timeout: Duration = 30.minutes,
    ): EmissionEvent.Resolved? {
        val deferred = CompletableDeferred<EmissionEvent.Resolved>()
        pending[emissionId] = deferred
        return try {
            withTimeoutOrNull(timeout) { deferred.await() }
        } catch (e: CancellationException) {
            null
        } finally {
            pending.remove(emissionId)
        }
    }

    /**
     * Complete a pending suspension with the given [resolved] event.
     *
     * @return `true` if a matching suspension was found and completed.
     */
    fun deliver(resolved: EmissionEvent.Resolved): Boolean {
        val deferred = pending[resolved.emissionId] ?: return false
        return deferred.complete(resolved)
    }

    fun getPendingEmissionIds(): Set<EmissionId> = pending.keys.toSet()
}

/** Process-wide singleton [EmissionReplyRegistry]. */
object GlobalEmissionReplyRegistry {
    val instance: EmissionReplyRegistry = EmissionReplyRegistry()
}
