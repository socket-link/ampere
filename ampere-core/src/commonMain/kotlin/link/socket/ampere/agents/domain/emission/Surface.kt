package link.socket.ampere.agents.domain.emission

import kotlinx.serialization.Serializable

/**
 * A delivery surface for [Emission]s.
 *
 * `SurfacePolicy` consults the surfaces declared on an Emission (via priority
 * ordering) and picks the first reachable one. [Console] is the floor surface:
 * when no richer surface is reachable, the Emission is printed to stdout and
 * the reply is read from stdin. This preserves the historical behaviour of
 * `ToolAskHuman` on JVM/CLI targets.
 *
 * Channel selection is policy (owned by `SurfacePolicy`), not payload. Callers
 * declare *intent* (surface priority list); the policy decides what actually fires.
 */
@Serializable
sealed interface Surface {

    /** The agent's foreground UI host — native app, web, or IDE extension. */
    @Serializable
    object Foreground : Surface

    /**
     * Stdout/stdin fallback used when no richer surface is reachable.
     *
     * This is the floor surface that preserves `ToolAskHuman`'s historical
     * console behaviour. `DefaultSurfacePolicy` falls back to this when the
     * surface priority list is exhausted without a successful delivery.
     */
    @Serializable
    object Console : Surface

    /** Native push notification channel. */
    @Serializable
    object Push : Surface

    /** Voice prompt channel. */
    @Serializable
    object Voice : Surface
}
