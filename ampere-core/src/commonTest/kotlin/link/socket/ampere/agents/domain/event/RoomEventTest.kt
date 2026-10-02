package link.socket.ampere.agents.domain.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict
import link.socket.ampere.room.Author
import link.socket.ampere.room.ReviewId
import link.socket.ampere.room.RoomCard
import link.socket.ampere.room.ThreadSubject
import link.socket.ampere.room.VerdictKind
import link.socket.ampere.roster.RoleId

/**
 * Serialization samples for the [RoomEvent] family (AMPR-379): every variant
 * survives the polymorphic `Event` round-trip, and the discriminators and event
 * types a stored Room decodes through are pinned.
 */
class RoomEventTest {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    private val timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000)
    private val source = EventSource.Agent("coordinator")

    private fun samples(): List<RoomEvent> = listOf(
        RoomEvent.RoomOpened(
            eventId = "e1",
            timestamp = timestamp,
            eventSource = source,
            roomId = "room:vent-42",
            projectId = "vent-42",
            generalThreadId = "room:vent-42/general",
            milestones = 4,
        ),
        RoomEvent.ThreadOpened(
            eventId = "e2",
            timestamp = timestamp,
            eventSource = source,
            roomId = "room:vent-42",
            threadId = "room:vent-42/verdict:violated:duct",
            subject = ThreadSubject.Verdict("duct", VerdictKind.VIOLATED),
            openedBy = Author.Role(RoleId("coordinator")),
            assignedTo = RoleId("scout"),
        ),
        RoomEvent.Posted(
            eventId = "e3",
            timestamp = timestamp,
            eventSource = EventSource.Human,
            roomId = "room:vent-42",
            threadId = "room:vent-42/general",
            subject = ThreadSubject.General,
            messageId = "m1",
            author = Author.Human,
            body = "Can we use the 6in duct?",
        ),
        RoomEvent.Posted(
            eventId = "e4",
            timestamp = timestamp,
            eventSource = EventSource.Agent("inspector"),
            roomId = "room:vent-42",
            threadId = "room:vent-42/verdict:undetermined:grille",
            subject = ThreadSubject.Verdict("grille", VerdictKind.UNDETERMINED),
            messageId = "m2",
            author = Author.Role(RoleId("inspector")),
            body = "grille publishes no CFM",
            card = RoomCard.Verdict(
                ProbeId("blueprint.part-fit"),
                "grille",
                Verdict.Undetermined("grille publishes no CFM", UndeterminedCause.EVIDENCE_ABSENT),
            ),
        ),
        RoomEvent.ThreadResolved(
            eventId = "e5",
            timestamp = timestamp,
            eventSource = EventSource.Agent("inspector"),
            roomId = "room:vent-42",
            threadId = "room:vent-42/verdict:violated:duct",
            subject = ThreadSubject.Verdict("duct", VerdictKind.VIOLATED),
            resolvedBy = Author.Role(RoleId("inspector")),
            reason = "6in duct fits",
        ),
        RoomEvent.ReviewRequested(
            eventId = "e6",
            timestamp = timestamp,
            eventSource = EventSource.Agent("planner"),
            roomId = "room:vent-42",
            threadId = "room:vent-42/general",
            reviewId = ReviewId("r1"),
            author = RoleId("planner"),
            reviewer = RoleId("inspector"),
            card = RoomCard.PlanRevision(2, 3, 6.hours, null, listOf("no estimate for fit-grille")),
        ),
        RoomEvent.ReviewCompleted(
            eventId = "e7",
            timestamp = timestamp,
            eventSource = EventSource.Agent("inspector"),
            roomId = "room:vent-42",
            threadId = "room:vent-42/general",
            reviewId = ReviewId("r1"),
            reviewer = RoleId("inspector"),
            probeId = ProbeId("ampere.sequence"),
            verdict = Verdict.Holds(),
            released = true,
            messageId = "m3",
        ),
    )

    @Test
    fun `every room event survives the polymorphic Event round-trip`() {
        samples().forEach { original ->
            val encoded = json.encodeToString(Event.serializer(), original)
            val decoded = json.decodeFromString(Event.serializer(), encoded)

            assertEquals(original, decoded, "changed shape: $encoded")
            assertIs<RoomEvent>(decoded)
            assertEquals(original.roomId, decoded.roomId)
        }
    }

    @Test
    fun `discriminators and event types are pinned and registered`() {
        val expected = mapOf(
            "RoomEvent.RoomOpened" to RoomEvent.RoomOpened.EVENT_TYPE,
            "RoomEvent.ThreadOpened" to RoomEvent.ThreadOpened.EVENT_TYPE,
            "RoomEvent.Posted" to RoomEvent.Posted.EVENT_TYPE,
            "RoomEvent.ThreadResolved" to RoomEvent.ThreadResolved.EVENT_TYPE,
            "RoomEvent.ReviewRequested" to RoomEvent.ReviewRequested.EVENT_TYPE,
            "RoomEvent.ReviewCompleted" to RoomEvent.ReviewCompleted.EVENT_TYPE,
        )

        samples().distinctBy { it::class }.forEach { event ->
            val encoded = json.encodeToString(Event.serializer(), event)
            val discriminator = expected.keys.single { encoded.contains("\"type\":\"$it\"") }
            assertEquals(expected.getValue(discriminator), event.eventType)
            assertTrue(event.eventType in EventRegistry.allEventTypes, "${event.eventType} is not registered")
        }
        assertEquals(
            listOf(
                "RoomOpened",
                "RoomThreadOpened",
                "RoomPosted",
                "RoomThreadResolved",
                "RoomReviewRequested",
                "RoomReviewCompleted",
            ),
            expected.values.toList(),
        )
    }

    @Test
    fun `an undetermined card never reads as a pass in the summary`() {
        val summary = samples()[3].getSummary(formatUrgency = { "[${it.name}]" }, formatSource = { it.getIdentifier() })

        assertTrue(summary.contains("inspector posted in verdict:undetermined:grille"), summary)
        assertTrue(summary.contains("[Verdict]"), summary)
        assertTrue(!summary.contains("holds"), summary)
    }

    @Test
    fun `a withheld review says so in its summary`() {
        val withheld = (samples()[6] as RoomEvent.ReviewCompleted).copy(
            verdict = Verdict.Violated("dangling dependsOn: fit-grille -> mount-grille"),
            released = false,
            messageId = null,
        )

        val summary = withheld.getSummary(formatUrgency = { "" }, formatSource = { it.getIdentifier() })

        assertTrue(summary.contains("withheld"), summary)
        assertTrue(summary.contains("dangling dependsOn"), summary)
    }

    @Test
    fun `thread subject in a posted event is optional`() {
        val posted = (samples()[2] as RoomEvent.Posted).copy(subject = null)

        val decoded = json.decodeFromString(Event.serializer(), json.encodeToString(Event.serializer(), posted))

        assertEquals(posted, decoded)
    }
}
