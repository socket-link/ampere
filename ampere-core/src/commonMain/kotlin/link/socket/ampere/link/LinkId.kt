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
 */
@JvmInline
@Serializable
value class LinkId(val value: String)
