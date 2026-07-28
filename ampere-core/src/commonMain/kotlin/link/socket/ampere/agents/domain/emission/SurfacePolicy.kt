package link.socket.ampere.agents.domain.emission

import link.socket.ampere.agents.domain.Urgency

/**
 * Reports whether a given [Surface] can currently accept delivery.
 *
 * AMPERE has no universal way to detect "is the host app foregrounded" or
 * "is push transport registered and permitted" from commonMain — those are
 * platform concerns. Hosts that can answer those questions should supply
 * their own [SurfaceAvailability]. [Surface.Console] is deliberately not
 * gated here: it is the floor surface [DefaultSurfacePolicy] falls back to
 * unconditionally, so a [SurfaceAvailability] declaring it unreachable does
 * not remove it as the ultimate fallback.
 */
fun interface SurfaceAvailability {

    fun isReachable(surface: Surface): Boolean

    companion object {
        /** Treats [Surface.Console] as reachable and every other surface as unreachable. */
        val ConsoleOnly: SurfaceAvailability = SurfaceAvailability { it is Surface.Console }
    }
}

/**
 * Result of resolving an [Emission] to a delivery [Surface].
 *
 * [fallbackUrl] is carried through from [Emission.fallbackUrl] only when
 * [surface] is [Surface.Push], mirroring
 * [link.socket.ampere.pause.AgentPause.fallbackUrl]: a renderer without a
 * live push transport can fall through to a browser-openable link.
 */
data class SurfaceResolution(
    val surface: Surface,
    val fallbackUrl: String? = null,
)

/**
 * Chooses a delivery [Surface] for an [Emission].
 *
 * Consults, in order: the [urgency] of the request (used to derive a
 * default priority when [Emission.surfaces] is empty), the ordered surface
 * priority declared on the Emission, and transport availability (via a
 * [SurfaceAvailability]) to pick the first reachable surface.
 */
interface SurfacePolicy {
    fun resolve(emission: Emission, urgency: Urgency = Urgency.HIGH): SurfaceResolution
}

/**
 * Default [SurfacePolicy]: walks the declared (or urgency-derived) surface
 * priority in order and picks the first surface [availability] reports as
 * reachable. Falls back to [Surface.Console] — the floor surface — when the
 * list is empty or nothing on it is reachable.
 */
class DefaultSurfacePolicy(
    private val availability: SurfaceAvailability = SurfaceAvailability.ConsoleOnly,
) : SurfacePolicy {

    override fun resolve(emission: Emission, urgency: Urgency): SurfaceResolution {
        val priority = emission.surfaces.ifEmpty { defaultPriorityFor(urgency) }
        val chosen = priority.firstOrNull(availability::isReachable) ?: Surface.Console
        val fallbackUrl = emission.fallbackUrl.takeIf { chosen is Surface.Push }
        return SurfaceResolution(surface = chosen, fallbackUrl = fallbackUrl)
    }

    private fun defaultPriorityFor(urgency: Urgency): List<Surface> = when (urgency) {
        Urgency.HIGH -> listOf(Surface.Push, Surface.Foreground, Surface.Console)
        Urgency.MEDIUM -> listOf(Surface.Foreground, Surface.Console)
        Urgency.LOW -> listOf(Surface.Console)
    }
}
