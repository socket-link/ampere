package link.socket.ampere.agents.events.surface

import kotlinx.serialization.Serializable

/**
 * The renderer's reply to an [AgentSurface] request.
 *
 * Every variant carries the same [correlationId] as the originating
 * [AgentSurface], which is what allows
 * [link.socket.ampere.agents.events.surface.awaitSurfaceResponse]
 * to resume the awaiting coroutine.
 */
@Serializable
sealed interface AgentSurfaceResponse {

    /** Echo of the originating [AgentSurface.correlationId]. */
    val correlationId: CorrelationId

    /**
     * The user submitted the surface. For [AgentSurface.Form] variants the
     * [values] map is keyed by [AgentSurfaceField.id]. For
     * [AgentSurface.Choice] variants the response is encoded as a single
     * [AgentSurfaceFieldValue.SelectionValue] under the well-known key
     * [SELECTION_KEY]. For [AgentSurface.Confirmation] and [AgentSurface.Card]
     * the [chosenAction] carries the action id and [values] is empty.
     */
    @Serializable
    data class Submitted(
        override val correlationId: CorrelationId,
        val values: Map<String, AgentSurfaceFieldValue> = emptyMap(),
        val chosenAction: String? = null,
    ) : AgentSurfaceResponse

    /** The user explicitly dismissed the surface. */
    @Serializable
    data class Cancelled(
        override val correlationId: CorrelationId,
        val reason: String? = null,
    ) : AgentSurfaceResponse

    /** The renderer did not produce a response within the allotted window. */
    @Serializable
    data class TimedOut(
        override val correlationId: CorrelationId,
        val timeoutMillis: Long,
    ) : AgentSurfaceResponse

    companion object {
        /** Key used for [AgentSurface.Choice] selections inside [Submitted.values]. */
        const val SELECTION_KEY: String = "selection"
    }
}
