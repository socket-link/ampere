package link.socket.ampere.canon

import kotlin.jvm.JvmInline
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import link.socket.ampere.link.LinkId

/** Ampere-scoped identity for a canon entity. Stable across projections. */
@JvmInline
@Serializable
value class CanonId(val value: String)

/**
 * The opaque handle back to wherever an entity came from.
 *
 * This is the canon's escape hatch and its audit trail in one type. It is
 * *opaque by design*: Ampere never parses [nativeId], because the moment it
 * does, a provider's identifier format becomes a contract Ampere has to honour.
 *
 * [linkId] ties canon provenance to Link provenance so the two cannot disagree.
 * A trace that says "this CalendarEvent came from Link `google-oauth-1`" and a
 * consent ledger that says the same are then reading the same fact, not two
 * facts that happen to line up.
 *
 * @property linkId The Link the entity travelled over.
 * @property sourceSystem Coarse origin label, e.g. `apple.mail`, `mcp:notion`.
 *   Used for grouping and display; never for dispatch.
 * @property nativeId The provider's own identifier, verbatim.
 * @property etag Optimistic-concurrency token when the provider offers one.
 *   Write-back can use it to detect that the native object moved under it.
 */
@Serializable
data class SourceHandle(
    val linkId: LinkId,
    val sourceSystem: String,
    val nativeId: String,
    val etag: String? = null,
)

/**
 * The lossless native object, carried alongside the lossy canon projection.
 *
 * Held as a [JsonObject] rather than a string so preserve-and-merge write-back
 * can be a structural field overlay rather than a per-adapter re-parse. That
 * choice is what lets the merge live in the SPI instead of in every adapter.
 *
 * @property schema The native shape's identifier, e.g. `MailMessageEntity`.
 *   Checked against the adapter's declared schema before any write.
 * @property fields The native object's fields, verbatim.
 */
@Serializable
data class NativePayload(
    val schema: NativeSchema,
    val fields: JsonObject,
)

/**
 * Everything a canon entity knows about its own origin.
 *
 * Every canon entity carries one. An entity with no provenance is not a canon
 * entity — it is a guess.
 *
 * @property nativePayload Null when the adapter could not or would not carry
 *   the native object (large binaries, provider policy). A null payload does
 *   not disable write-back; it forces the adapter to re-fetch before merging.
 */
@Serializable
data class CanonProvenance(
    val sourceHandle: SourceHandle,
    val observedAt: Instant,
    val nativePayload: NativePayload? = null,
)
