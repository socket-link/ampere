package link.socket.ampere.pause

import kotlin.reflect.KClass

/**
 * Platform query for which [EscalationChannel] variants are currently
 * available on this device.
 *
 * The W1.5 channel-selector consults [available] before walking
 * [AgentPause.suggestedChannels] and skips channels whose variant is not in
 * the returned set. Per-platform implementations inspect runtime state
 * (notification permissions, voice-input availability, foreground/background)
 * to populate the result.
 *
 * This file ships intentionally empty stubs in W0.3 — the actual platform
 * logic lands in W1.5. The contract exists now so W1.5 and W2.2 can build
 * against a stable API.
 */
expect class ChannelAvailability() {

    /**
     * Returns the [EscalationChannel] subclasses that are currently usable.
     * An empty result means no native channel is available — callers should
     * fall through to [EscalationChannel.PublicLink] if the originating
     * [AgentPause.fallbackUrl] is set, or treat the pause as unrouteable.
     */
    fun available(): List<KClass<out EscalationChannel>>
}
