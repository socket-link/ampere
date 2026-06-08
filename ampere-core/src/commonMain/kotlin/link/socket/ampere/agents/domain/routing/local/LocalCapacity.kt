package link.socket.ampere.agents.domain.routing.local

import kotlinx.serialization.Serializable

/**
 * A snapshot of what an on-device [LocalInferenceEngine] can serve right now.
 *
 * Returned by [LocalInferenceEngine.probe] so callers can decide whether a
 * device-gated provider is currently usable without attempting a generation.
 * Kept deliberately small and SDK-free: the only thing routing needs today is
 * whether the engine is [available] at all; the optional fields are advisory
 * hints a richer availability gate (T4) can read.
 *
 * @property available Whether the engine can serve a generation right now.
 * @property modelId Identifier of the loaded on-device model, if any.
 * @property maxContextTokens Largest prompt the engine can accept, if known.
 */
@Serializable
data class LocalCapacity(
    val available: Boolean,
    val modelId: String? = null,
    val maxContextTokens: Int? = null,
) {
    companion object {
        /** Convenience for an engine that cannot currently serve generations. */
        val Unavailable: LocalCapacity = LocalCapacity(available = false)
    }
}
