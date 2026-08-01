package link.socket.ampere.bindings.apple

import link.socket.ampere.canon.CanonType

/**
 * Every [CanonType]'s projection onto Apple's assistant-schema vocabulary,
 * settled by the SDK pass recorded in `docs/ampr-222-domain-type-canon-v1.md`.
 *
 * CALENDAR_EVENT, REMINDER, ALARM and MEDIA_ITEM were Ring 1 candidates. They
 * demoted to Ring 2 because the shipped assistant-schema catalog has no
 * calendar, reminders, clock or music/video domain — `Calendar.RecurrenceRule`
 * and `Date` bind *fields*, never the entity. MESSAGE and NOTE were also Ring 1
 * candidates; neither has an assistant-schema domain, and neither has a public
 * iOS read API — their real sources are service Links (Slack, Twilio, Notion)
 * and folder mounts (Obsidian), which is why they demoted to Ring 3.
 *
 * A static factory keyed by [CanonType] rather than a field on the enum itself
 * — this is what lets `ampere-core` stay platform-neutral while this module
 * still lets an Arc ask "how does this canon type reach Apple?"
 */
object AppleCanonBindingRegistry {

    /** Verified: no Apple assistant-schema domain exists in the shipped SDK for these. */
    private val bindings: Map<CanonType, AppleCanonBinding> = mapOf(
        // ---------------------------------------------------------------
        // Ring 1 — Interchange
        // ---------------------------------------------------------------

        CanonType.PERSON to AppleCanonBinding(
            schema = AppleSchemaBinding.SystemValueType("IntentPerson"),
            lossyFields = listOf("relationships", "alternateHandles", "organization", "jobTitle"),
        ),

        CanonType.EMAIL_MESSAGE to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("mail", "message", "MailMessageEntity"),
            lossyFields = listOf(
                "mimeStructure",
                "rawHeaders",
                "threadIdentifiers",
                "providerLabels",
                "attachmentBytes",
            ),
        ),

        CanonType.EMAIL_DRAFT to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("mail", "draft", "MailDraftEntity"),
            lossyFields = listOf("mimeStructure", "rawHeaders", "sendState", "attachmentBytes"),
        ),

        CanonType.MAILBOX to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("mail", "mailbox", "MailboxEntity"),
            lossyFields = listOf("providerFolderSemantics", "syncState"),
        ),

        CanonType.PHOTO to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("photos", "asset", "PhotoEntity"),
            lossyFields = listOf(
                "editStack",
                "adjustmentData",
                "livePhotoPair",
                "burstIdentity",
                "originalRendition",
            ),
        ),

        CanonType.PHOTO_ALBUM to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("photos", "album", "PhotoAlbumEntity"),
            lossyFields = listOf("smartAlbumPredicate", "sharedParticipants"),
        ),

        // One canon type fans out to five Apple document domains —
        // `wordProcessor`, `reader`, `spreadsheet`, `presentation`,
        // `whiteboard` — plus `files.file`. The fan-out *is* the lossy axis:
        // a projection that drops `CanonDocument.kind` cannot round-trip to
        // the right Apple schema.
        CanonType.DOCUMENT to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("files", "file", "FileEntity"),
            lossyFields = listOf("documentKindSpecifics", "pageGeometry", "templateIdentity", "revisionHistory"),
        ),

        CanonType.PLACE to AppleCanonBinding(
            schema = AppleSchemaBinding.SystemValueType("CLPlacemark"),
            lossyFields = listOf("venueIdentity", "openingHours", "category", "rating", "providerPlaceId"),
        ),

        CanonType.JOURNAL_ENTRY to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("journal", "entry", "JournalEntity"),
            lossyFields = listOf("attachedMedia", "suggestionMetadata"),
        ),

        CanonType.BOOK to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("books", "book", "BookEntity"),
            lossyFields = listOf("readingPosition", "annotations", "assetIdentity"),
        ),

        CanonType.WEB_BOOKMARK to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("browser", "bookmark", "BookmarkEntity"),
            lossyFields = listOf("folderHierarchy", "syncState", "favicon"),
        ),

        CanonType.BROWSER_TAB to AppleCanonBinding(
            schema = AppleSchemaBinding.EntitySchema("browser", "tab", "TabEntity"),
            lossyFields = listOf("backForwardHistory", "windowMembership", "scrollState"),
        ),

        // ---------------------------------------------------------------
        // Ring 2 — Platform
        // ---------------------------------------------------------------

        CanonType.CALENDAR_EVENT to AppleCanonBinding(
            schema = null,
            lossyFields = listOf("eventKitAttendeeStatus", "alarms", "structuredLocation"),
        ),

        CanonType.REMINDER to AppleCanonBinding(
            schema = null,
            lossyFields = listOf("eventKitAlarms", "recurrenceRules", "priority", "subtasks"),
        ),

        CanonType.ALARM to AppleCanonBinding(
            schema = null,
            lossyFields = listOf("alarmKitPresentation", "snoozeConfiguration"),
        ),

        CanonType.MEDIA_ITEM to AppleCanonBinding(
            schema = null,
            lossyFields = listOf(
                "playbackState",
                "libraryIdentity",
                "drmAssetHandle",
                "albumTitle",
                "artworkUrl",
            ),
        ),

        CanonType.HEALTH_SAMPLE to AppleCanonBinding(
            schema = null,
            lossyFields = listOf("healthKitMetadata", "deviceProvenance"),
        ),

        CanonType.HOME_ACCESSORY to AppleCanonBinding(
            schema = null,
            lossyFields = listOf("serviceGraph", "roomAssignment"),
        ),

        CanonType.TRANSACTION to AppleCanonBinding(
            schema = AppleSchemaBinding.SystemValueType("IntentCurrencyAmount"),
            lossyFields = listOf("merchantIdentity", "financeKitAccount", "paymentMethod"),
        ),

        CanonType.PASS to AppleCanonBinding(
            schema = null,
            lossyFields = listOf("passType", "structuredFields", "barcodePayload", "relevantDate"),
        ),

        CanonType.WEATHER_FORECAST to AppleCanonBinding(
            schema = AppleSchemaBinding.SystemValueType("Measurement"),
            // AMPR-252: series is now modelled via CanonWeatherForecast.series;
            // per-point richness beyond temperature/condition is still lossy.
            lossyFields = listOf(
                "weatherKitAttribution",
                "precipitationChance",
                "windSpeed",
                "uvIndex",
                "humidity",
            ),
        ),

        CanonType.BLUETOOTH_PERIPHERAL to AppleCanonBinding(
            schema = null,
            lossyFields = listOf("gattServices", "advertisementData"),
        ),

        CanonType.MOTION_SAMPLE to AppleCanonBinding(
            schema = null,
            lossyFields = listOf("coreMotionRawVectors"),
        ),

        // ---------------------------------------------------------------
        // Ring 3 — Service (unbound: arrives only over a service Link)
        // ---------------------------------------------------------------

        CanonType.MESSAGE to AppleCanonBinding.UNBOUND,
        CanonType.NOTE to AppleCanonBinding.UNBOUND,
        CanonType.RIDE to AppleCanonBinding.UNBOUND,
        CanonType.ORDER to AppleCanonBinding.UNBOUND,
        CanonType.DELIVERY to AppleCanonBinding.UNBOUND,
        CanonType.THIRD_PARTY_PLAYLIST to AppleCanonBinding.UNBOUND,

        // The knowledge-work wave (AMPR-262). Apple ships no project-management
        // or tabular-data assistant schema, so these are unbound by definition
        // rather than by SDK-pass verdict.
        CanonType.WORK_ITEM to AppleCanonBinding.UNBOUND,
        CanonType.PROJECT to AppleCanonBinding.UNBOUND,
        CanonType.MILESTONE to AppleCanonBinding.UNBOUND,
        CanonType.TABLE to AppleCanonBinding.UNBOUND,
    )

    /** The Apple binding for [type]. Falls back to [AppleCanonBinding.UNBOUND] for any type not listed. */
    fun bindingFor(type: CanonType): AppleCanonBinding = bindings[type] ?: AppleCanonBinding.UNBOUND
}
