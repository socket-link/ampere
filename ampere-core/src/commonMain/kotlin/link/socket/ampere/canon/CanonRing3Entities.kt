package link.socket.ampere.canon

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ring 3 — Service. Typed declarations with minimal properties.
 *
 * These arrive over a non-OS Link: `Mcp`, `OAuthRest`, `FolderMount`, or `Cli`.
 * They have no platform binding at all, which makes the native payload the only
 * lossless record — Ring 3 adapters that write should be especially careful to
 * merge rather than replace.
 *
 * `Message` and `Note` were Ring 1 candidates. Neither has an Apple
 * assistant-schema domain, and on iOS neither has a public read API, so their
 * realistic sources are service Links (Slack, Twilio, Notion) and folder mounts
 * (an Obsidian vault). Housing `Note` here is why Ring 3's definition widened
 * from "Mcp/OAuthRest only" to "any non-OS Link".
 */

@Serializable
@SerialName("canon.message")
data class CanonMessage(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val bodyText: String,
    val sender: CanonPerson? = null,
    val conversationId: String? = null,
    val sentAt: Instant? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.MESSAGE
}

@Serializable
@SerialName("canon.note")
data class CanonNote(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String? = null,
    val bodyText: String? = null,
    val tags: List<String> = emptyList(),
    val modifiedAt: Instant? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.NOTE
}

@Serializable
@SerialName("canon.ride")
data class CanonRide(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val status: CanonServiceStatus,
    val pickup: CanonPlace? = null,
    val dropoff: CanonPlace? = null,
    val requestedAt: Instant? = null,
    val providerStatus: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.RIDE
}

@Serializable
@SerialName("canon.order")
data class CanonOrder(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val status: CanonServiceStatus,
    val merchantName: String? = null,
    val placedAt: Instant? = null,
    val providerStatus: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.ORDER
}

@Serializable
@SerialName("canon.delivery")
data class CanonDelivery(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val status: CanonServiceStatus,
    val carrier: String? = null,
    val expectedAt: Instant? = null,
    val destination: CanonPlace? = null,
    val providerStatus: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.DELIVERY
}

/**
 * A coarse lifecycle shared by [CanonRide], [CanonOrder], and [CanonDelivery].
 *
 * Ring 3 types arrive from independent services with independent status
 * vocabularies (AMPR-252) — an untyped `status: String` meant an Arc written
 * against one provider's strings silently broke against another's. This is
 * the cross-provider lifecycle only; a provider's exact wording is preserved
 * verbatim in `providerStatus` for callers that need it.
 */
@Serializable
enum class CanonServiceStatus {
    @SerialName("requested")
    REQUESTED,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,

    @SerialName("cancelled")
    CANCELLED,
}

@Serializable
@SerialName("canon.third_party_playlist")
data class CanonThirdPartyPlaylist(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val trackCount: Int? = null,
    val ownerDisplayName: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.THIRD_PARTY_PLAYLIST
}
