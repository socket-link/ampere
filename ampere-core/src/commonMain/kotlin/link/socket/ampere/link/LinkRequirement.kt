package link.socket.ampere.link

import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonType

/**
 * What a Plug declares it needs, before any concrete Link exists.
 *
 * A requirement names a *kind* of wire, not an instance. Resolution to a
 * concrete [Link] happens at Arc execution time, which is what keeps the Arc
 * manifest lean: an Arc references Plugs, never Link bindings.
 *
 * @property name A symbolic handle the Plug uses to refer to this wire, e.g.
 *   `"calendar"`. Mirrors [link.socket.ampere.plug.McpServerDependency.name].
 *   Required because a Plug can need two Links of the same [transport].
 * @property minimumScope The canon types that must be permitted for the Plug to
 *   function. A Link may allow more; it may not allow less.
 * @property role Which side the Plug acts as. Checked against the transport's
 *   per-platform capability, so a consumer-role requirement fails on iOS for a
 *   transport that has no iOS consumer path.
 * @property optional A requirement the Plug can run without. It never blocks
 *   resolution: with no candidate it reports [LinkResolution.Skipped], and a
 *   candidate that fails a check is still announced on the bus but does not
 *   fail the Plug.
 */
@Serializable
data class LinkRequirement(
    val name: String,
    val transport: Transport,
    val direction: LinkDirection,
    val minimumScope: Set<CanonType> = emptySet(),
    val role: TransportRole = TransportRole.CONSUMER,
    val optional: Boolean = false,
)

/**
 * The outcome of matching one [LinkRequirement] against the Links available to
 * a Plug.
 */
sealed interface LinkResolution {

    val requirement: LinkRequirement

    data class Resolved(
        override val requirement: LinkRequirement,
        val link: Link,
    ) : LinkResolution

    data class Failed(
        override val requirement: LinkRequirement,
        val failure: LinkResolutionFailure,
    ) : LinkResolution

    /** An [LinkRequirement.optional] requirement with no matching Link. */
    data class Skipped(
        override val requirement: LinkRequirement,
    ) : LinkResolution
}

/** Every resolved Link for a Plug, keyed by [LinkRequirement.name]. */
data class ResolvedLinks(
    val plugId: String,
    val byRequirement: Map<String, Link>,
) {
    operator fun get(requirementName: String): Link? = byRequirement[requirementName]
}
