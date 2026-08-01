package link.socket.ampere.plug

import kotlinx.serialization.Serializable
import link.socket.ampere.canon.CanonType
import link.socket.ampere.canon.table.TableWriteCapability
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
 * [optionalConsumes] names canon types this Plug can use if an Arc-execution
 * planner hands them one, but does not require — e.g. a Plug that can use a
 * [link.socket.ampere.canon.CanonType.PHOTO] earlier in an Arc without forcing
 * every caller through whatever Plug would otherwise produce one. Unlike
 * [consumes], no input source is required for the Plug to run. The
 * Arc-execution planner that chains one Plug's canon output into another
 * Plug's optional input is out of scope here; this field only needs to exist
 * for that planner to read.
 *
 * [resolvesAssets] declares the third, optional chassis capability — a Plug
 * implementing [link.socket.ampere.plug.spi.AssetResolver] alongside (or
 * instead of) Perceive/Execute — so a consent surface can state that a Plug
 * resolves assets before any [link.socket.ampere.canon.CanonAssetRef.NativeHandle]
 * it produced is ever resolved.
 *
 * [isCanonExternal] is a positive declaration that this Plug has no canon-level
 * data contract at all — it neither emits nor consumes any [CanonType], by
 * design, not by omission. The closed v1 canon has no member for some Plugs'
 * data (e.g. a notification, a pasteboard payload, recognised text), so
 * [emits] and [consumes] being empty is the correct, permanent state rather
 * than a gap to fill in later. See [PlugManifestValidator] for how this flag
 * changes Link requirement validation.
 *
 * [tableWriteCapabilities] is the AMPR-263 verdict expressed as a manifest
 * declaration: which [TableWriteCapability] this Plug can honor losslessly
 * for `TABLE`, never more than it can actually guarantee. A Plug that cannot
 * honor preserve-and-merge for [TableWriteCapability.UPDATE_CELL] on its
 * provider simply omits it — the AMPR-263 non-negotiable's "degrade to
 * read-only" clause is this field being empty or partial, not a runtime
 * override. See [link.socket.ampere.canon.table.TableWriteSink] for the
 * corresponding execute-side guard, and [PlugManifestValidator] for how a
 * declaration here is cross-checked against [emits]/[consumes].
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
    val optionalConsumes: Set<CanonType> = emptySet(),
    val resolvesAssets: Boolean = false,
    val isCanonExternal: Boolean = false,
    val tableWriteCapabilities: Set<TableWriteCapability> = emptySet(),
)
