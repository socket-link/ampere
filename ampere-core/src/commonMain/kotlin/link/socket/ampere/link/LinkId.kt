package link.socket.ampere.link

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/**
 * Identifier for a [Link] — the user-bound authorization scope a capability's
 * credentials and traffic hang off.
 *
 * One Link is shared across Plugs: a single Google OAuth Link serves both the
 * Calendar and Gmail Plugs. The user authenticates once; each Plug records its
 * own grant against the same [LinkId], and revoking the Link cascades to every
 * grant that references it.
 *
 * An id comes in one of two forms, and [LinkResolutionGate] tells them apart:
 *
 * - **Minted for a requirement** — `<transport>:<requirement name>`, from
 *   [forRequirement]. The Link answers only a requirement of that name, which
 *   is how two Plugs that both require a `NATIVE_FRAMEWORK` wire stay on their
 *   own Links. Two Plugs share one by declaring the same requirement name.
 * - **Opaque** — anything else, an OAuth Link named for its account, say. The
 *   Link answers any requirement of its transport, which is how one Link serves
 *   differently named requirements.
 */
@JvmInline
@Serializable
value class LinkId(val value: String) {

    companion object {

        /**
         * The id of the Link minted for [requirement]: its transport, lowercased,
         * then a colon, then its name. A `calendar` requirement over
         * [Transport.NATIVE_FRAMEWORK] mints `native_framework:calendar`.
         *
         * [Link.requirementName] reads the name back out, so this is the one
         * place the form is written down.
         */
        fun forRequirement(requirement: LinkRequirement): LinkId =
            LinkId(prefixOf(requirement.transport) + requirement.name)

        internal fun prefixOf(transport: Transport): String = "${transport.name.lowercase()}:"
    }
}
