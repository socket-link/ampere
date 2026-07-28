package link.socket.ampere.link

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * One Plug's standing on one Link.
 *
 * Grants are per-(Plug, Link), not per-Plug, because Links are shared. Granting
 * Gmail access to a Google Link says nothing about Calendar's access to the
 * same Link, and revoking one must not revoke the other.
 *
 * The consent ledger itself is Socket-side and out of scope; this is the
 * Ampere-side shape that ledger has to be able to express.
 */
@Serializable
data class LinkGrant(
    val plugId: String,
    val linkId: LinkId,
    val grantedAt: Instant,
    val revokedAt: Instant? = null,
) {
    val isRevoked: Boolean get() = revokedAt != null
}

/**
 * Every grant a single Plug holds.
 *
 * **Revoked beats granted**, matching the invariant
 * [link.socket.ampere.plug.permission.PlugPermissionGate] already enforces for
 * permissions. A Link that appears in both sets is revoked.
 */
@Serializable
data class LinkGrants(
    val plugId: String,
    val grants: List<LinkGrant> = emptyList(),
) {
    private val byLink: Map<LinkId, LinkGrant> = grants.associateBy { it.linkId }

    fun grantFor(linkId: LinkId): LinkGrant? = byLink[linkId]

    fun isGranted(linkId: LinkId): Boolean = byLink[linkId]?.isRevoked == false

    fun isRevoked(linkId: LinkId): Boolean = byLink[linkId]?.isRevoked == true

    companion object {
        fun empty(plugId: String): LinkGrants = LinkGrants(plugId)
    }
}
