package link.socket.ampere.canon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed canon of domain types Ampere understands — v1.
 *
 * The canon is an **intermediate representation**, not a convenience DTO set.
 * Apps are frontends and backends; Arc logic compiles against the IR, and one
 * Arc runs on iOS and Android because it targets the IR rather than either
 * platform's vocabulary. `ampere-core` carries zero platform-SDK references —
 * how a canon type reaches a given platform's own vocabulary is a *binding*,
 * declared in an edge module (`ampere-bindings-apple`, `ampere-bindings-android`)
 * that depends on this module, never the reverse. See
 * [link.socket.ampere.bindings.apple.AppleCanonBindingRegistry] and
 * [link.socket.ampere.bindings.android.AndroidCanonBindingRegistry].
 *
 * **The set is closed for v1.** Extensibility is a post-launch decision. The
 * escape hatch is not a new canon member — it is
 * [CanonProvenance.nativePayload], which carries the lossless native object
 * alongside the projection.
 *
 * Each member declares its [ring]. Ring membership was settled by the SDK pass
 * recorded in `docs/ampr-222-domain-type-canon-v1.md`; the `wireName` is a
 * stable serialization contract — renaming one breaks `PlaybackRelay` replay of
 * any trace that already carries it.
 */
@Serializable
enum class CanonType(
    val wireName: String,
    val ring: CanonRing,
) {

    // ---------------------------------------------------------------------
    // Ring 1 — Interchange
    // ---------------------------------------------------------------------

    @SerialName("person")
    PERSON(wireName = "person", ring = CanonRing.INTERCHANGE),

    @SerialName("email_message")
    EMAIL_MESSAGE(wireName = "email_message", ring = CanonRing.INTERCHANGE),

    @SerialName("email_draft")
    EMAIL_DRAFT(wireName = "email_draft", ring = CanonRing.INTERCHANGE),

    @SerialName("mailbox")
    MAILBOX(wireName = "mailbox", ring = CanonRing.INTERCHANGE),

    @SerialName("photo")
    PHOTO(wireName = "photo", ring = CanonRing.INTERCHANGE),

    @SerialName("photo_album")
    PHOTO_ALBUM(wireName = "photo_album", ring = CanonRing.INTERCHANGE),

    @SerialName("document")
    DOCUMENT(wireName = "document", ring = CanonRing.INTERCHANGE),

    @SerialName("place")
    PLACE(wireName = "place", ring = CanonRing.INTERCHANGE),

    @SerialName("journal_entry")
    JOURNAL_ENTRY(wireName = "journal_entry", ring = CanonRing.INTERCHANGE),

    @SerialName("book")
    BOOK(wireName = "book", ring = CanonRing.INTERCHANGE),

    @SerialName("web_bookmark")
    WEB_BOOKMARK(wireName = "web_bookmark", ring = CanonRing.INTERCHANGE),

    @SerialName("browser_tab")
    BROWSER_TAB(wireName = "browser_tab", ring = CanonRing.INTERCHANGE),

    // ---------------------------------------------------------------------
    // Ring 2 — Platform
    //
    // CALENDAR_EVENT, REMINDER, ALARM and MEDIA_ITEM were Ring 1 candidates
    // in the original SDK pass; see the edge-module binding registries for
    // why they demoted.
    // ---------------------------------------------------------------------

    @SerialName("calendar_event")
    CALENDAR_EVENT(wireName = "calendar_event", ring = CanonRing.PLATFORM),

    @SerialName("reminder")
    REMINDER(wireName = "reminder", ring = CanonRing.PLATFORM),

    @SerialName("alarm")
    ALARM(wireName = "alarm", ring = CanonRing.PLATFORM),

    @SerialName("media_item")
    MEDIA_ITEM(wireName = "media_item", ring = CanonRing.PLATFORM),

    @SerialName("health_sample")
    HEALTH_SAMPLE(wireName = "health_sample", ring = CanonRing.PLATFORM),

    @SerialName("home_accessory")
    HOME_ACCESSORY(wireName = "home_accessory", ring = CanonRing.PLATFORM),

    @SerialName("transaction")
    TRANSACTION(wireName = "transaction", ring = CanonRing.PLATFORM),

    @SerialName("pass")
    PASS(wireName = "pass", ring = CanonRing.PLATFORM),

    @SerialName("weather_forecast")
    WEATHER_FORECAST(wireName = "weather_forecast", ring = CanonRing.PLATFORM),

    @SerialName("bluetooth_peripheral")
    BLUETOOTH_PERIPHERAL(wireName = "bluetooth_peripheral", ring = CanonRing.PLATFORM),

    @SerialName("motion_sample")
    MOTION_SAMPLE(wireName = "motion_sample", ring = CanonRing.PLATFORM),

    // ---------------------------------------------------------------------
    // Ring 3 — Service
    //
    // MESSAGE and NOTE were Ring 1 candidates in the original SDK pass; see
    // the edge-module binding registries for why they demoted.
    // ---------------------------------------------------------------------

    @SerialName("message")
    MESSAGE(wireName = "message", ring = CanonRing.SERVICE),

    @SerialName("note")
    NOTE(wireName = "note", ring = CanonRing.SERVICE),

    @SerialName("ride")
    RIDE(wireName = "ride", ring = CanonRing.SERVICE),

    @SerialName("order")
    ORDER(wireName = "order", ring = CanonRing.SERVICE),

    @SerialName("delivery")
    DELIVERY(wireName = "delivery", ring = CanonRing.SERVICE),

    @SerialName("third_party_playlist")
    THIRD_PARTY_PLAYLIST(wireName = "third_party_playlist", ring = CanonRing.SERVICE),

    // The knowledge-work wave (AMPR-262). Ring 3 by definition: no assistant
    // schema, no native framework — these reach Ampere only over `Mcp`,
    // `OAuthRest`, or `FolderMount`. Their provider intersections and the
    // fields each one drops are recorded on the entities themselves.

    @SerialName("work_item")
    WORK_ITEM(wireName = "work_item", ring = CanonRing.SERVICE),

    @SerialName("project")
    PROJECT(wireName = "project", ring = CanonRing.SERVICE),

    @SerialName("milestone")
    MILESTONE(wireName = "milestone", ring = CanonRing.SERVICE),

    @SerialName("table")
    TABLE(wireName = "table", ring = CanonRing.SERVICE),
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
