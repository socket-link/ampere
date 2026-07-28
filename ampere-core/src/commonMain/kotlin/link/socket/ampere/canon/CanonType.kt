package link.socket.ampere.canon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed canon of domain types Ampere understands — v1.
 *
 * The canon is an **intermediate representation**, not a convenience DTO set.
 * Apps are frontends and backends; Arc logic compiles against the IR, and one
 * Arc runs on iOS and Android because it targets the IR rather than either
 * platform's vocabulary. Where Apple built a canonical registry the IR partly
 * duplicates it and integration is a mapping table; where Android built none,
 * the IR *is* the missing canon. An agent guesses two tasks are the same kind
 * of thing; Ampere knows it, with provenance.
 *
 * **The set is closed for v1.** Extensibility is a post-launch decision. The
 * escape hatch is not a new canon member — it is
 * [CanonProvenance.nativePayload], which carries the lossless native object
 * alongside the projection.
 *
 * Each member declares its [ring] and its [binding]. Both were settled by the
 * SDK pass recorded in `.context/issue-586-domain-type-canon-v1.md`; the
 * `wireName` is a stable serialization contract — renaming one breaks
 * `PlaybackRelay` replay of any trace that already carries it.
 */
@Serializable
enum class CanonType(
    val wireName: String,
    val ring: CanonRing,
    val binding: CanonBinding,
) {

    // ---------------------------------------------------------------------
    // Ring 1 — Interchange
    // ---------------------------------------------------------------------

    @SerialName("person")
    PERSON(
        wireName = "person",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.SystemValueType("IntentPerson"),
            lossyFields = listOf("relationships", "alternateHandles", "organization", "jobTitle"),
        ),
    ),

    @SerialName("email_message")
    EMAIL_MESSAGE(
        wireName = "email_message",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("mail", "message", "MailMessageEntity"),
            lossyFields = listOf(
                "mimeStructure",
                "rawHeaders",
                "threadIdentifiers",
                "providerLabels",
                "attachmentBytes",
            ),
        ),
    ),

    @SerialName("email_draft")
    EMAIL_DRAFT(
        wireName = "email_draft",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("mail", "draft", "MailDraftEntity"),
            lossyFields = listOf("mimeStructure", "rawHeaders", "sendState", "attachmentBytes"),
        ),
    ),

    @SerialName("mailbox")
    MAILBOX(
        wireName = "mailbox",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("mail", "mailbox", "MailboxEntity"),
            lossyFields = listOf("providerFolderSemantics", "syncState"),
        ),
    ),

    @SerialName("photo")
    PHOTO(
        wireName = "photo",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("photos", "asset", "PhotoEntity"),
            lossyFields = listOf("editStack", "adjustmentData", "livePhotoPair", "burstIdentity", "originalRendition"),
        ),
    ),

    @SerialName("photo_album")
    PHOTO_ALBUM(
        wireName = "photo_album",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("photos", "album", "PhotoAlbumEntity"),
            lossyFields = listOf("smartAlbumPredicate", "sharedParticipants"),
        ),
    ),

    /**
     * One canon type fans out to five Apple document domains — `wordProcessor`,
     * `reader`, `spreadsheet`, `presentation`, `whiteboard` — plus `files.file`.
     * The fan-out *is* the lossy axis: a projection that drops
     * `CanonDocument.kind` cannot round-trip to the right Apple schema.
     */
    @SerialName("document")
    DOCUMENT(
        wireName = "document",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("files", "file", "FileEntity"),
            lossyFields = listOf("documentKindSpecifics", "pageGeometry", "templateIdentity", "revisionHistory"),
        ),
    ),

    @SerialName("place")
    PLACE(
        wireName = "place",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.SystemValueType("CLPlacemark"),
            lossyFields = listOf("venueIdentity", "openingHours", "category", "rating", "providerPlaceId"),
        ),
    ),

    @SerialName("journal_entry")
    JOURNAL_ENTRY(
        wireName = "journal_entry",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("journal", "entry", "JournalEntity"),
            lossyFields = listOf("attachedMedia", "suggestionMetadata"),
        ),
    ),

    @SerialName("book")
    BOOK(
        wireName = "book",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("books", "book", "BookEntity"),
            lossyFields = listOf("readingPosition", "annotations", "assetIdentity"),
        ),
    ),

    @SerialName("web_bookmark")
    WEB_BOOKMARK(
        wireName = "web_bookmark",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("browser", "bookmark", "BookmarkEntity"),
            lossyFields = listOf("folderHierarchy", "syncState", "favicon"),
        ),
    ),

    @SerialName("browser_tab")
    BROWSER_TAB(
        wireName = "browser_tab",
        ring = CanonRing.INTERCHANGE,
        binding = CanonBinding(
            apple = AppleSchemaBinding.EntitySchema("browser", "tab", "TabEntity"),
            lossyFields = listOf("backForwardHistory", "windowMembership", "scrollState"),
        ),
    ),

    // ---------------------------------------------------------------------
    // Ring 2 — Platform
    //
    // CALENDAR_EVENT, REMINDER, ALARM and MEDIA_ITEM were Ring 1 candidates.
    // They demoted because the shipped assistant-schema catalog has no
    // calendar, reminders, clock or music/video domain — `Calendar.Recurrence-
    // Rule` and `Date` bind *fields*, never the entity.
    // ---------------------------------------------------------------------

    @SerialName("calendar_event")
    CALENDAR_EVENT(
        wireName = "calendar_event",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(
            apple = null,
            lossyFields = listOf("eventKitAttendeeStatus", "alarms", "structuredLocation"),
        ),
    ),

    @SerialName("reminder")
    REMINDER(
        wireName = "reminder",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(apple = null, lossyFields = listOf("eventKitAlarms", "recurrenceRules")),
    ),

    @SerialName("alarm")
    ALARM(
        wireName = "alarm",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(
            apple = null,
            lossyFields = listOf("alarmKitPresentation", "snoozeConfiguration"),
        ),
    ),

    @SerialName("media_item")
    MEDIA_ITEM(
        wireName = "media_item",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(
            apple = null,
            lossyFields = listOf("playbackState", "libraryIdentity", "drmAssetHandle"),
        ),
    ),

    @SerialName("health_sample")
    HEALTH_SAMPLE(
        wireName = "health_sample",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(apple = null, lossyFields = listOf("healthKitMetadata", "deviceProvenance")),
    ),

    @SerialName("home_accessory")
    HOME_ACCESSORY(
        wireName = "home_accessory",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(apple = null, lossyFields = listOf("serviceGraph", "roomAssignment")),
    ),

    @SerialName("transaction")
    TRANSACTION(
        wireName = "transaction",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(
            apple = AppleSchemaBinding.SystemValueType("IntentCurrencyAmount"),
            lossyFields = listOf("merchantIdentity", "financeKitAccount", "paymentMethod"),
        ),
    ),

    @SerialName("pass")
    PASS(
        wireName = "pass",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(apple = null, lossyFields = listOf("passKitFields", "barcodePayload")),
    ),

    @SerialName("weather_forecast")
    WEATHER_FORECAST(
        wireName = "weather_forecast",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(
            apple = AppleSchemaBinding.SystemValueType("Measurement"),
            lossyFields = listOf("weatherKitAttribution", "hourlyBreakdown"),
        ),
    ),

    @SerialName("bluetooth_peripheral")
    BLUETOOTH_PERIPHERAL(
        wireName = "bluetooth_peripheral",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(apple = null, lossyFields = listOf("gattServices", "advertisementData")),
    ),

    @SerialName("motion_sample")
    MOTION_SAMPLE(
        wireName = "motion_sample",
        ring = CanonRing.PLATFORM,
        binding = CanonBinding(apple = null, lossyFields = listOf("coreMotionRawVectors")),
    ),

    // ---------------------------------------------------------------------
    // Ring 3 — Service
    //
    // MESSAGE and NOTE were Ring 1 candidates. Neither has an assistant-schema
    // domain, and neither has a public iOS read API; their real sources are
    // service Links (Slack, Twilio, Notion) and folder mounts (Obsidian).
    // ---------------------------------------------------------------------

    @SerialName("message")
    MESSAGE(
        wireName = "message",
        ring = CanonRing.SERVICE,
        binding = CanonBinding.UNBOUND,
    ),

    @SerialName("note")
    NOTE(
        wireName = "note",
        ring = CanonRing.SERVICE,
        binding = CanonBinding.UNBOUND,
    ),

    @SerialName("ride")
    RIDE(
        wireName = "ride",
        ring = CanonRing.SERVICE,
        binding = CanonBinding.UNBOUND,
    ),

    @SerialName("order")
    ORDER(
        wireName = "order",
        ring = CanonRing.SERVICE,
        binding = CanonBinding.UNBOUND,
    ),

    @SerialName("delivery")
    DELIVERY(
        wireName = "delivery",
        ring = CanonRing.SERVICE,
        binding = CanonBinding.UNBOUND,
    ),

    @SerialName("third_party_playlist")
    THIRD_PARTY_PLAYLIST(
        wireName = "third_party_playlist",
        ring = CanonRing.SERVICE,
        binding = CanonBinding.UNBOUND,
    ),
    ;

    companion object {

        /** Lookup by the stable [wireName]. Returns null rather than throwing. */
        fun fromWireName(wireName: String): CanonType? = byWireName[wireName]

        fun inRing(ring: CanonRing): Set<CanonType> = entries.filter { it.ring == ring }.toSet()

        private val byWireName: Map<String, CanonType> by lazy {
            entries.associateBy { it.wireName }
        }
    }
}
