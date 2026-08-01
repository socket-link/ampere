package link.socket.ampere.canon

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ring 1 — Interchange. Modelled in full.
 *
 * Each type maps to an Apple assistant-schema entity or system value type
 * without contortion; see [CanonType] for the binding and for the fields each
 * projection drops.
 */

/**
 * A human, addressable by handle.
 *
 * Apple's `IntentPerson` is handle-plus-display-name shaped, so relationships,
 * organisation, and alternate handles live on the canon type but not in the
 * projection. A photos-scoped recognized person projects here too, with
 * [recognizedIn] set — an Arc asking "who is in this photo" and one asking "who
 * did I email" want the same type.
 */
@Serializable
@SerialName("canon.person")
data class CanonPerson(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val displayName: String,
    val handles: List<PersonHandle> = emptyList(),
    val organization: String? = null,
    val jobTitle: String? = null,
    val recognizedIn: RecognitionContext? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.PERSON
}

@Serializable
data class PersonHandle(
    val kind: PersonHandleKind,
    val value: String,
    val isPrimary: Boolean = false,
)

@Serializable
enum class PersonHandleKind {
    @SerialName("email")
    EMAIL,

    @SerialName("phone")
    PHONE,

    @SerialName("service")
    SERVICE,
}

@Serializable
enum class RecognitionContext {
    @SerialName("photos")
    PHOTOS,
}

/**
 * A delivered email. Its MIME structure and raw headers stay in the payload.
 *
 * @property mailboxId The mailbox this message is filed under, or null. Null
 *   conflates two facts a reader must not tell apart: *not filed under any
 *   mailbox the provider models* and *the provider did not say*. Do not read
 *   null as "definitely unfiled".
 *
 *   This is the cross-reference precedent every other nullable `CanonId`
 *   field in the canon follows — [link.socket.ampere.canon.CanonWorkItem.projectId],
 *   [link.socket.ampere.canon.CanonMilestone.projectId], and
 *   [link.socket.ampere.canon.CanonTable.documentId] — and it is a **caller
 *   contract, not a resolution mechanism** (AMPR-266): a [CanonId] is
 *   Ampere-scoped and resolvable only against [CanonMailbox] entities produced
 *   by the *same* Link, since the same id string from a different Link names a
 *   different mailbox. Nothing guarantees the referenced mailbox was ever
 *   perceived — resolving one is the caller's job, and it may find nothing.
 *   See the cross-reference invariant in `docs/concepts/domain-canon.md`.
 */
@Serializable
@SerialName("canon.email_message")
data class CanonEmailMessage(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val subject: String,
    val from: CanonPerson?,
    val to: List<CanonPerson> = emptyList(),
    val cc: List<CanonPerson> = emptyList(),
    val bodyText: String? = null,
    val sentAt: Instant? = null,
    val isRead: Boolean = false,
    val mailboxId: CanonId? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.EMAIL_MESSAGE
}

/** An unsent email. Distinct from [CanonEmailMessage] because Apple's registry is. */
@Serializable
@SerialName("canon.email_draft")
data class CanonEmailDraft(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val subject: String,
    val to: List<CanonPerson> = emptyList(),
    val cc: List<CanonPerson> = emptyList(),
    val bodyText: String? = null,
    val lastEditedAt: Instant? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.EMAIL_DRAFT
}

/**
 * A mail container.
 *
 * The lossy axis is real and worth naming: a Gmail label and an IMAP folder
 * both project here, and they are not the same thing. The provider's own
 * semantics survive only in the native payload.
 */
@Serializable
@SerialName("canon.mailbox")
data class CanonMailbox(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val name: String,
    val accountId: String? = null,
    val unreadCount: Int? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.MAILBOX
}

/** A photo or video asset. The edit stack never crosses the projection. */
@Serializable
@SerialName("canon.photo")
data class CanonPhoto(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val capturedAt: Instant? = null,
    val widthPixels: Int? = null,
    val heightPixels: Int? = null,
    val isFavorite: Boolean = false,
    val place: CanonPlace? = null,
    val recognizedPeople: List<CanonPerson> = emptyList(),
    val thumbnail: CanonAssetRef? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.PHOTO
}

@Serializable
@SerialName("canon.photo_album")
data class CanonPhotoAlbum(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val assetCount: Int? = null,
    val isShared: Boolean = false,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.PHOTO_ALBUM
}

/**
 * A document of any kind.
 *
 * One canon type covers Apple's five document domains. [kind] is the
 * discriminator that makes the fan-out survivable: drop it, and a projection
 * cannot find its way back to the right schema on write-back.
 *
 * @property plainText A bounded snippet, not the document's full text — see
 *   [CanonProse]. A single unbounded document body was the counterexample to
 *   the bulk rule the rest of canon holds to; this is the fix.
 */
@Serializable
@SerialName("canon.document")
data class CanonDocument(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val kind: DocumentKind,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAt: Instant? = null,
    val plainText: CanonProse? = null,
    val thumbnail: CanonAssetRef? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.DOCUMENT
}

/**
 * Which Apple document domain a [CanonDocument] projects to.
 *
 * [FILE] is the fallback for anything with no richer domain — it binds to
 * `files.file` alone.
 *
 * AMPR-257: [appleDomain] is a deliberate, narrow exception to "no vendor
 * references in `ampere-core`". It is a field on a canon *entity*
 * ([CanonDocument.kind]), not a [link.socket.ampere.bindings.apple.AppleCanonBinding]
 * declaration, and moving it would mean restructuring `DocumentKind` itself —
 * out of scope for this ticket, which changes bindings, not canon membership
 * or shape. It carries no platform-SDK dependency, only a vendor-named string.
 */
@Serializable
enum class DocumentKind(val appleDomain: String) {
    @SerialName("file")
    FILE("files"),

    @SerialName("word_processor")
    WORD_PROCESSOR("wordProcessor"),

    @SerialName("spreadsheet")
    SPREADSHEET("spreadsheet"),

    @SerialName("presentation")
    PRESENTATION("presentation"),

    @SerialName("reader")
    READER("reader"),

    @SerialName("whiteboard")
    WHITEBOARD("whiteboard"),
}

/**
 * A location.
 *
 * `CLPlacemark` is a postal address plus a coordinate, so venue identity,
 * hours, and category are canon-only. That is exactly the gap a Ring 3 places
 * service (Google Places, Foursquare) fills over an `OAuthRest` Link.
 */
@Serializable
@SerialName("canon.place")
data class CanonPlace(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val street: String? = null,
    val locality: String? = null,
    val administrativeArea: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.PLACE
}

/** @property bodyText A bounded snippet, not the entry's full text — see [CanonProse]. */
@Serializable
@SerialName("canon.journal_entry")
data class CanonJournalEntry(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String? = null,
    val bodyText: CanonProse? = null,
    val createdAt: Instant? = null,
    val place: CanonPlace? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.JOURNAL_ENTRY
}

@Serializable
@SerialName("canon.book")
data class CanonBook(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val authors: List<String> = emptyList(),
    val isAudiobook: Boolean = false,
    val progressFraction: Double? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.BOOK
}

@Serializable
@SerialName("canon.web_bookmark")
data class CanonWebBookmark(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val url: String,
    val createdAt: Instant? = null,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.WEB_BOOKMARK
}

@Serializable
@SerialName("canon.browser_tab")
data class CanonBrowserTab(
    override val canonId: CanonId,
    override val provenance: CanonProvenance,
    val title: String,
    val url: String,
    val isActive: Boolean = false,
) : CanonEntity {
    override val canonType: CanonType get() = CanonType.BROWSER_TAB
}
