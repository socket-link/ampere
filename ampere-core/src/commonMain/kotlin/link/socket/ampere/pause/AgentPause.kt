package link.socket.ampere.pause

import kotlinx.serialization.Serializable

/**
 * Correlation identifier used to pair a [AgentPause] request with its
 * matching [AgentPauseResponse]. Plugins generate one per pause they emit.
 */
typealias PauseCorrelationId = String

/**
 * A typed, serializable description of an agent that has paused execution and
 * is awaiting human input.
 *
 * `AgentPause` is the OS-native escalation primitive. Where [link.socket.ampere
 * .agents.events.surface.AgentSurface] models a Plugin asking the platform to
 * render UI, `AgentPause` models the higher-level *intent* of "I am stuck and
 * need a person." Channel selection (push notification → voice prompt →
 * in-app card → public link) is handled by the W1.5 channel-selector against
 * [suggestedChannels]; this type fixes the contract so renderers and per-Arc
 * override UI can build against it now.
 *
 * The contract intentionally lives in commonMain and carries no platform
 * references so it can be expressed across every Ampere target.
 */
@Serializable
data class AgentPause(
    /** Stable identifier used to pair this pause with its response. */
    val correlationId: PauseCorrelationId,
    /** Human-readable explanation of why the agent paused. */
    val reason: String,
    /** How time-sensitive the pause is; drives default channel selection. */
    val urgency: PauseUrgency,
    /**
     * Ordered list of channels to attempt, highest priority first. The
     * channel selector walks this list and falls through to the next channel
     * on unavailability or non-response.
     */
    val suggestedChannels: List<EscalationChannel>,
    /** How long to wait before considering the pause [AgentPauseResponse.TimedOut]. */
    val timeoutMillis: Long,
    /**
     * Public URL the user can visit to respond, used as the lowest-priority
     * fallback when no native channel is available. Optional — pauses that
     * must not leak to a public surface should leave this null.
     */
    val fallbackUrl: String? = null,
)

/**
 * Urgency level of an [AgentPause].
 *
 * This is intentionally distinct from [link.socket.ampere.agents.domain.Urgency]
 * (which classifies bus events). Pause urgency drives channel-selection
 * defaults: [Routine] prefers in-app card, [Important] prefers push
 * notification, [Critical] prefers voice prompt.
 */
@Serializable
enum class PauseUrgency {
    /** Non-blocking; in-app card is the default channel. */
    Routine,

    /** Time-sensitive; push notification is the default channel. */
    Important,

    /** Blocking; voice prompt is the default channel. */
    Critical,
}
