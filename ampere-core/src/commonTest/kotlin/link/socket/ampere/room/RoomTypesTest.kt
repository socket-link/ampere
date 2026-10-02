package link.socket.ampere.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.events.messages.MessageSender
import link.socket.ampere.canon.CanonId
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.probe.safety.HazardCategory
import link.socket.ampere.probe.safety.MitigationHint
import link.socket.ampere.roster.RoleId

/** AMPR-379 task 2: the Room's value types — identity rules and pinned wire names. */
class RoomTypesTest {

    private val json = Json {
        encodeDefaults = true
        classDiscriminator = "type"
        ignoreUnknownKeys = true
    }

    @Test
    fun `a room id is derived from the project and carries the channel prefix`() {
        val roomId = RoomId.forProject(CanonId("vent-42"))

        assertEquals("room:vent-42", roomId.value)
        assertEquals(CanonId("vent-42"), roomId.projectId)
        assertEquals(MessageChannel.Room("room:vent-42"), roomId.channel())
        assertEquals(roomId, RoomId.of(roomId.channel()))
        assertNull(RoomId.of(MessageChannel.Public.Engineering))
    }

    @Test
    fun `a room channel survives the channel id round-trip`() {
        val channel = RoomId.forProject(CanonId("vent-42")).channel()

        val restored = MessageChannel.fromMessageChannelId(channel.getIdentifier())

        assertEquals(channel, restored)
        assertIs<MessageChannel.Direct>(MessageChannel.fromMessageChannelId("scout@me"))
        assertEquals(MessageChannel.Public.Design, MessageChannel.fromMessageChannelId("#design"))
    }

    @Test
    fun `the room channel has a pinned discriminator`() {
        val encoded = json.encodeToString(MessageChannel.serializer(), MessageChannel.Room("room:vent-42"))

        assertTrue(encoded.contains("MessageChannel.Room"), encoded)
        assertEquals(MessageChannel.Room("room:vent-42"), json.decodeFromString(MessageChannel.serializer(), encoded))
    }

    @Test
    fun `thread ids are deterministic per subject`() {
        val roomId = RoomId.forProject(CanonId("vent-42"))

        assertEquals("room:vent-42/general", roomThreadId(roomId, ThreadSubject.General))
        assertEquals("room:vent-42/milestone:ms-1", roomThreadId(roomId, ThreadSubject.Milestone(CanonId("ms-1"))))
        assertEquals(
            "room:vent-42/verdict:violated:duct",
            roomThreadId(roomId, ThreadSubject.Verdict("duct", VerdictKind.VIOLATED)),
        )
        assertEquals(
            roomThreadId(roomId, ThreadSubject.Verdict("duct", VerdictKind.UNDETERMINED)),
            roomThreadId(roomId, ThreadSubject.Verdict("duct", VerdictKind.UNDETERMINED)),
        )
    }

    @Test
    fun `a hazard thread is keyed by the subject and the category`() {
        val roomId = RoomId.forProject(CanonId("vent-42"))

        assertEquals(
            "room:vent-42/hazard:electrical:mount-fan",
            roomThreadId(roomId, ThreadSubject.Hazard("mount-fan", HazardCategory.ELECTRICAL)),
        )
        assertTrue(
            roomThreadId(roomId, ThreadSubject.Hazard("mount-fan", HazardCategory.FUMES_OR_CHEMICALS)) !=
                roomThreadId(roomId, ThreadSubject.Hazard("mount-fan", HazardCategory.ELECTRICAL)),
            "two hazards on one task are two conversations",
        )
    }

    @Test
    fun `subjects round-trip with pinned wire names`() {
        val subjects: List<ThreadSubject> = listOf(
            ThreadSubject.General,
            ThreadSubject.Milestone(CanonId("ms-1")),
            ThreadSubject.Verdict("grille", VerdictKind.UNDETERMINED),
            ThreadSubject.Hazard("mount-fan", HazardCategory.ELECTRICAL),
        )

        subjects.forEach { subject ->
            val encoded = json.encodeToString(ThreadSubject.serializer(), subject)
            assertEquals(subject, json.decodeFromString(ThreadSubject.serializer(), encoded))
        }
        assertTrue(
            json.encodeToString(ThreadSubject.serializer(), ThreadSubject.General).contains("ThreadSubject.General"),
        )
        assertTrue(
            json.encodeToString(ThreadSubject.serializer(), subjects[2]).contains("ThreadSubject.Verdict"),
        )
        assertTrue(
            json.encodeToString(ThreadSubject.serializer(), subjects[3]).contains("ThreadSubject.Hazard"),
        )
    }

    @Test
    fun `an author maps onto the thread sender and back`() {
        val scout = Author.Role(RoleId("scout"))

        assertEquals(MessageSender.Agent("scout"), scout.toSender())
        assertEquals(MessageSender.Human, Author.Human.toSender())
        assertEquals(scout, Author.fromSender(MessageSender.Agent("scout")))
        assertEquals(Author.Human, Author.fromSender(MessageSender.Human))
        assertEquals("scout", scout.label)
        assertEquals("human", Author.Human.label)
    }

    @Test
    fun `cards round-trip and the undetermined verdict keeps its cause`() {
        val cards: List<RoomCard> = listOf(
            RoomCard.PlanRevision(
                revision = 2,
                remainingItems = 3,
                remaining = 6.hours,
                projectedFinish = Instant.fromEpochMilliseconds(1_700_000_000_000),
                changes = listOf("calibration ×1.5 from 7 session(s)"),
            ),
            RoomCard.Verdict(
                probeId = ProbeId("blueprint.part-fit"),
                subjectId = "grille",
                verdict = ProbeVerdict.Undetermined("grille publishes no CFM", UndeterminedCause.EVIDENCE_ABSENT),
            ),
            RoomCard.Status(headline = "Standup", completed = 2, remaining = 5, blocked = 0, projectedFinish = null),
            RoomCard.Hazard(
                category = HazardCategory.FUMES_OR_CHEMICALS,
                subjectId = "mount-fan",
                mitigationHint = MitigationHint.CONFIRM_VENTILATION,
                evidence = "manifest line line-sealant has kind RESIN",
                mitigationTaskId = CanonId("mount-fan/mitigation:confirm_ventilation"),
            ),
        )

        cards.forEach { card ->
            val encoded = json.encodeToString(RoomCard.serializer(), card)
            assertEquals(card, json.decodeFromString(RoomCard.serializer(), encoded))
        }
        val verdict = json.decodeFromString(
            RoomCard.serializer(),
            json.encodeToString(RoomCard.serializer(), cards[1]),
        )
        assertIs<RoomCard.Verdict>(verdict)
        assertIs<ProbeVerdict.Undetermined>(verdict.verdict)
    }
}
