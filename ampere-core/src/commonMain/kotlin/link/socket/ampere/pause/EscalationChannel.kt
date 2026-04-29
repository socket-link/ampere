package link.socket.ampere.pause

import kotlinx.serialization.Serializable

/**
 * A channel through which an [AgentPause] can solicit a human response.
 *
 * Each variant declares the minimal renderer parameters needed for a platform
 * implementation to dispatch the channel without reaching back into Ampere.
 * The channel-selector (W1.5) walks [AgentPause.suggestedChannels] in order;
 * the per-Arc override UI (W2.2) reorders or filters the list.
 */
@Serializable
sealed interface EscalationChannel {

    /**
     * Native push notification. The renderer maps [notificationCategory] onto
     * the platform-specific category/channel id (Android notification channel,
     * iOS `UNNotificationCategory`).
     */
    @Serializable
    data class Push(
        val notificationCategory: String,
        val title: String,
        val body: String,
        val deeplink: String? = null,
    ) : EscalationChannel

    /**
     * Voice prompt — typically a synthesized read-out followed by a
     * speech-to-text response window. Used for [PauseUrgency.Critical] pauses
     * by default.
     */
    @Serializable
    data class Voice(
        val prompt: String,
        val expectedResponseSeconds: Int = 15,
        val voiceProfile: String? = null,
    ) : EscalationChannel

    /**
     * In-app card displayed inside the Ampere shell. The renderer resolves
     * [cardKind] to an in-app surface (e.g., a banner, a modal, an Arc-pinned
     * card).
     */
    @Serializable
    data class InAppCard(
        val cardKind: CardKind,
        val title: String,
        val body: String,
        val primaryActionLabel: String = "Approve",
        val secondaryActionLabel: String = "Reject",
    ) : EscalationChannel {

        @Serializable
        enum class CardKind {
            Banner,
            Modal,
            ArcPinned,
        }
    }

    /**
     * Lowest-priority fallback: a public URL the user opens in a browser.
     * Used when no native channel is available, or when the agent
     * intentionally wants to reach a non-Ampere recipient.
     */
    @Serializable
    data class PublicLink(
        val url: String,
        val displayLabel: String = "Open in browser",
    ) : EscalationChannel
}
