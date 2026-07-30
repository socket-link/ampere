package link.socket.ampere.plug

import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonType
import link.socket.ampere.link.LinkRequirement
import link.socket.ampere.plug.permission.PlugPermission

/**
 * Manifest metadata for a plug: what it is, what it may do, and what it needs
 * to be wired to.
 *
 * The grammar this encodes is *a Plug connects through a Link and powers Arcs*.
 * A Plug never names a concrete Link — it declares [requiredLinks], a list of
 * *kinds* of wire, and resolution to a credentialed endpoint happens at Arc
 * execution time. That is what lets one authenticated Google Link serve both
 * the Calendar and Gmail Plugs, and what keeps Arc manifests lean.
 *
 * [emits] and [consumes] are the canon-level data contract: what this Plug can
 * produce for other Arc steps, and what it needs handed to it. They are what a
 * future planner reads to decide two Plugs can be chained.
 *
 * Every collection field defaults to empty so manifests written before each
 * schema addition continue to decode unchanged.
 */
@Serializable
data class PlugManifest(
    val id: PlugId,
    val name: String,
    val version: String,
    val description: String? = null,
    val requiredPermissions: List<PlugPermission> = emptyList(),
    val mcpServers: List<McpServerDependency> = emptyList(),
    val requiredLinks: List<LinkRequirement> = emptyList(),
    val emits: Set<CanonType> = emptySet(),
    val consumes: Set<CanonType> = emptySet(),
)
