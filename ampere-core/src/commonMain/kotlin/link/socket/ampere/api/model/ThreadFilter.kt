package link.socket.ampere.api.model

/**
 * Filter criteria for listing threads.
 *
 * All fields are optional. `null` means "no filter on this dimension".
 */
@link.socket.ampere.api.AmpereStableApi
data class ThreadFilter(
    val participantId: String? = null,
    val hasEscalations: Boolean? = null,
)
