package link.socket.ampere.canon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.link.LinkId

/**
 * Canon entities cross the wire and land in traces. A drifted `@SerialName`
 * breaks `PlaybackRelay` replay of every trace already recorded, so the
 * discriminators are pinned here as literal strings — this test is meant to
 * fail loudly when someone renames one.
 */
class CanonSerializationTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val provenance = CanonProvenance(
        sourceHandle = SourceHandle(
            linkId = LinkId("link-1"),
            sourceSystem = "apple.mail",
            nativeId = "native-1",
            etag = "etag-1",
        ),
        observedAt = Instant.fromEpochMilliseconds(1_700_000_000_000),
        nativePayload = NativePayload(
            schema = NativeSchema("MailMessageEntity"),
            fields = JsonObject(mapOf("subject" to JsonPrimitive("hi"))),
        ),
    )

    private fun samples(): Map<String, CanonEntity> = mapOf(
        "canon.person" to CanonPerson(CanonId("p"), provenance, displayName = "Ada"),
        "canon.email_message" to CanonEmailMessage(CanonId("m"), provenance, subject = "hi", from = null),
        "canon.email_draft" to CanonEmailDraft(CanonId("d"), provenance, subject = "wip"),
        "canon.mailbox" to CanonMailbox(CanonId("b"), provenance, name = "Inbox"),
        "canon.photo" to CanonPhoto(CanonId("ph"), provenance),
        "canon.photo_album" to CanonPhotoAlbum(CanonId("al"), provenance, title = "Trip"),
        "canon.document" to CanonDocument(
            CanonId("doc"),
            provenance,
            title = "Spec",
            kind = DocumentKind.WORD_PROCESSOR,
        ),
        "canon.place" to CanonPlace(CanonId("pl"), provenance, name = "Home"),
        "canon.journal_entry" to CanonJournalEntry(CanonId("j"), provenance),
        "canon.book" to CanonBook(CanonId("bk"), provenance, title = "SICP"),
        "canon.web_bookmark" to CanonWebBookmark(CanonId("wb"), provenance, title = "Docs", url = "https://x"),
        "canon.browser_tab" to CanonBrowserTab(CanonId("tb"), provenance, title = "Docs", url = "https://x"),
        "canon.calendar_event" to CanonCalendarEvent(
            CanonId("ce"),
            provenance,
            title = "Standup",
            startsAt = Instant.fromEpochMilliseconds(1_700_000_100_000),
        ),
        "canon.reminder" to CanonReminder(CanonId("r"), provenance, title = "Pay rent"),
        "canon.alarm" to CanonAlarm(CanonId("a"), provenance),
        "canon.media_item" to CanonMediaItem(CanonId("mi"), provenance, title = "Track"),
        "canon.health_sample" to CanonHealthSample(
            CanonId("hs"),
            provenance,
            quantityType = "stepCount",
            value = 1200.0,
            unit = "count",
            recordedAt = Instant.fromEpochMilliseconds(1_700_000_200_000),
        ),
        "canon.home_accessory" to CanonHomeAccessory(CanonId("ha"), provenance, name = "Lamp"),
        "canon.transaction" to CanonTransaction(
            CanonId("tx"),
            provenance,
            amountMinorUnits = 1250,
            currencyCode = "USD",
            category = "groceries",
            status = CanonTransactionStatus.PENDING,
        ),
        "canon.pass" to CanonPass(CanonId("pa"), provenance, description = "Boarding"),
        "canon.weather_forecast" to CanonWeatherForecast(
            CanonId("wf"),
            provenance,
            place = null,
            validAt = Instant.fromEpochMilliseconds(1_700_000_300_000),
            series = listOf(
                CanonWeatherPoint(
                    validAt = Instant.fromEpochMilliseconds(1_700_003_900_000),
                    temperatureCelsius = 18.5,
                    conditionSummary = "Light rain",
                ),
            ),
        ),
        "canon.bluetooth_peripheral" to CanonBluetoothPeripheral(CanonId("bp"), provenance),
        "canon.motion_sample" to CanonMotionSample(
            CanonId("ms"),
            provenance,
            activity = "walking",
            recordedAt = Instant.fromEpochMilliseconds(1_700_000_400_000),
        ),
        "canon.message" to CanonMessage(CanonId("msg"), provenance, bodyText = CanonProse.bounded("yo")),
        "canon.note" to CanonNote(CanonId("n"), provenance),
        "canon.ride" to CanonRide(
            CanonId("rd"),
            provenance,
            status = CanonServiceStatus.REQUESTED,
            providerStatus = "requested",
        ),
        "canon.order" to CanonOrder(
            CanonId("or"),
            provenance,
            status = CanonServiceStatus.IN_PROGRESS,
            providerStatus = "placed",
        ),
        "canon.delivery" to CanonDelivery(
            CanonId("dl"),
            provenance,
            status = CanonServiceStatus.IN_PROGRESS,
            providerStatus = "in_transit",
        ),
        "canon.third_party_playlist" to CanonThirdPartyPlaylist(CanonId("pp"), provenance, title = "Focus"),
        "canon.work_item" to CanonWorkItem(
            CanonId("wi"),
            provenance,
            title = "Ship the canon wave",
            status = CanonWorkStatus.IN_PROGRESS,
            providerStatus = "In Review",
        ),
        "canon.project" to CanonProject(
            CanonId("pj"),
            provenance,
            name = "Chassis & Canon",
            status = CanonWorkStatus.BACKLOG,
            providerStatus = "Backlog",
        ),
        "canon.milestone" to CanonMilestone(CanonId("ml"), provenance, name = "Beta"),
        "canon.table" to CanonTable(
            CanonId("tbl"),
            provenance,
            title = "Q3 forecast",
            columnNames = listOf("region", "revenue"),
            rowCount = 412,
            preview = CanonTablePreview.bounded(listOf(listOf("EMEA", "120"))),
        ),
    )

    @Test
    fun `every canon entity round-trips through the sealed serializer`() {
        samples().forEach { (discriminator, entity) ->
            val encoded = json.encodeToString(CanonEntity.serializer(), entity)
            val decoded = json.decodeFromString(CanonEntity.serializer(), encoded)

            assertEquals(entity, decoded, "round-trip changed $discriminator")
        }
    }

    @Test
    fun `every canon entity writes its pinned discriminator`() {
        samples().forEach { (discriminator, entity) ->
            val encoded = json.encodeToString(CanonEntity.serializer(), entity)

            assertTrue(
                encoded.contains("\"type\":\"$discriminator\""),
                "expected discriminator $discriminator, got $encoded",
            )
        }
    }

    @Test
    fun `the sample set covers every canon type`() {
        val covered = samples().values.map { it.canonType }.toSet()

        assertEquals(
            CanonType.entries.toSet(),
            covered,
            "a canon type has no serialization sample; drift in it would go unnoticed",
        )
    }

    @Test
    fun `provenance survives serialization intact`() {
        val entity = CanonEmailMessage(CanonId("m"), provenance, subject = "hi", from = null)

        val decoded = json.decodeFromString(
            CanonEntity.serializer(),
            json.encodeToString(CanonEntity.serializer(), entity),
        )

        assertEquals(provenance.sourceHandle, decoded.provenance.sourceHandle)
        assertEquals(provenance.nativePayload, decoded.provenance.nativePayload)
        assertEquals(provenance.observedAt, decoded.provenance.observedAt)
    }

    @Test
    fun `canon type wire names are stable`() {
        // Spot-check the members most likely to be renamed in a refactor.
        assertEquals("email_message", CanonType.EMAIL_MESSAGE.wireName)
        assertEquals("calendar_event", CanonType.CALENDAR_EVENT.wireName)
        assertEquals("third_party_playlist", CanonType.THIRD_PARTY_PLAYLIST.wireName)
    }

    @Test
    fun `canon type serializes to its wire name`() {
        val encoded = json.encodeToString(CanonType.serializer(), CanonType.JOURNAL_ENTRY)
        assertEquals("\"journal_entry\"", encoded)
    }
}
