package link.socket.ampere.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import link.socket.ampere.agents.domain.event.MessageEvent
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.agents.domain.status.EventStatus
import link.socket.ampere.agents.events.messages.MessageChannel
import link.socket.ampere.agents.service.MessageActionService
import link.socket.ampere.canon.CanonId
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.roster.BlueprintRoster
import link.socket.ampere.roster.RoleId

/**
 * AMPR-379 task 2 validation, the Room half: opening a Room for the vent project
 * yields its milestone threads, threads are idempotent per subject, and posts by
 * role and by human land in the thread primitive's own tables and events.
 *
 * `runBlocking`, not `runTest`: the repository hops to a real IO dispatcher and the
 * transcript Flow waits on a real bus (see `feedback_door_tests_use_runblocking`).
 */
class DefaultRoomServiceTest {

    private val scout = Author.Role(BlueprintRoster.scout.id)

    @Test
    fun `opening the room for the vent project yields four milestone threads and a general one`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()

            assertEquals(RoomId.forProject(CanonId("vent-42")), roomId)
            val threads = rig.room.threads(roomId).getOrThrow()
            assertEquals(5, threads.size)
            assertEquals(ThreadSubject.General, threads.first().subject)
            // Opened under a pinned clock, so ties are ordered by id; compare as a set.
            assertEquals(
                VentGraph.milestoneIds.map { ThreadSubject.Milestone(CanonId(it)) }.toSet(),
                threads.drop(1).map { it.subject }.toSet(),
            )
            assertTrue(threads.all { it.status == EventStatus.Open })
            assertEquals(1, rig.events(RoomEvent.RoomOpened.EVENT_TYPE).size)
            assertEquals(5, rig.events(RoomEvent.ThreadOpened.EVENT_TYPE).size)
            assertEquals(5, rig.events(MessageEvent.ThreadCreated.EVENT_TYPE).size)
        }
    }

    @Test
    fun `opening the room twice names the same room and publishes nothing new`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val first = rig.room.open(VentGraph.graph()).getOrThrow()
            val second = rig.room.open(VentGraph.graph()).getOrThrow()

            assertEquals(first, second)
            assertEquals(5, rig.room.threads(first).getOrThrow().size)
            assertEquals(1, rig.events(RoomEvent.RoomOpened.EVENT_TYPE).size)
            assertEquals(5, rig.events(RoomEvent.ThreadOpened.EVENT_TYPE).size)
        }
    }

    @Test
    fun `a thread is idempotent per subject and keeps its first assignment`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val subject = ThreadSubject.Verdict("duct", VerdictKind.VIOLATED)

            val first = rig.room.thread(roomId, subject, assignedTo = scout.id, title = "Violated: duct").getOrThrow()
            val second = rig.room.thread(roomId, subject, assignedTo = RoleId("planner")).getOrThrow()

            assertEquals(first, second)
            assertEquals(roomThreadId(roomId, subject), first)
            val thread = rig.room.threads(roomId).getOrThrow().single { it.threadId == first }
            assertEquals(scout.id, thread.assignedTo)
            assertEquals(6, rig.room.threads(roomId).getOrThrow().size)
        }
    }

    @Test
    fun `a role post is persisted with its card and leaves through both events`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val subject = ThreadSubject.Verdict("duct", VerdictKind.VIOLATED)
            val threadId = rig.room.thread(roomId, subject, assignedTo = scout.id).getOrThrow()
            val card = RoomCard.Verdict(ProbeId("blueprint.part-fit"), "duct", ProbeVerdict.Violated("duct undersized"))

            val inspector = Author.Role(BlueprintRoster.inspector.id)
            val messageId = rig.room.post(threadId, inspector, "duct undersized", card).getOrThrow()

            val history = rig.room.history(roomId).getOrThrow()
            val posted = history.single { it.messageId == messageId }
            assertEquals(inspector, posted.author)
            assertEquals(card, posted.card)
            assertEquals(subject, posted.subject)
            assertEquals(threadId, posted.threadId)

            val roomPosted = rig.events(RoomEvent.Posted.EVENT_TYPE).filterIsInstance<RoomEvent.Posted>().single()
            assertEquals(card, roomPosted.card)
            assertEquals("duct undersized", roomPosted.body)
            // The opening line of every thread plus this post: all through MessagePosted too.
            assertEquals(7, rig.events(MessageEvent.MessagePosted.EVENT_TYPE).size)
        }
    }

    @Test
    fun `a role cannot post into a resolved thread but the human can and that reopens it`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val fan = ThreadSubject.Verdict("fan", VerdictKind.VIOLATED)
            val threadId = rig.room.thread(roomId, fan, scout.id).getOrThrow()
            rig.room.resolve(threadId, Author.Role(BlueprintRoster.inspector.id), "fan fits").getOrThrow()
            rig.room.resolve(threadId, Author.Role(BlueprintRoster.inspector.id), "again").getOrThrow()

            val refused = rig.room.post(threadId, scout, "late finding")
            val humanPost = rig.room.post(threadId, Author.Human, "Are we sure about the fan?")

            val failure = assertIs<RoomException>(refused.exceptionOrNull()).failure
            assertEquals(RoomFailure.ThreadResolved(threadId), failure)
            assertTrue(humanPost.isSuccess)
            val thread = rig.room.threads(roomId).getOrThrow().single { it.threadId == threadId }
            assertEquals(EventStatus.Open, thread.status)
            val changes = rig.events(MessageEvent.ThreadStatusChanged.EVENT_TYPE)
                .filterIsInstance<MessageEvent.ThreadStatusChanged>()
                .map { it.oldStatus to it.newStatus }
                .toSet()
            assertEquals(
                setOf(EventStatus.Open to EventStatus.Resolved, EventStatus.Resolved to EventStatus.Open),
                changes,
            )
            assertEquals(1, rig.events(RoomEvent.ThreadResolved.EVENT_TYPE).size, "resolve is idempotent")
        }
    }

    @Test
    fun `posting outside the room and into a missing thread is refused`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            rig.room.open(VentGraph.graph()).getOrThrow()
            val engineering = MessageActionService(rig.repository, rig.door.api)
                .createThread("not a room", listOf("scout"), MessageChannel.Public.Engineering)
                .getOrThrow()

            val outside = rig.room.post(engineering.id, scout, "hello")
            val missing = rig.room.post("room:vent-42/verdict:violated:nothing", scout, "hello")

            assertEquals(
                RoomFailure.NotARoomThread(engineering.id),
                assertIs<RoomException>(outside.exceptionOrNull()).failure,
            )
            assertIs<RoomFailure.ThreadNotFound>(assertIs<RoomException>(missing.exceptionOrNull()).failure)
        }
    }

    @Test
    fun `history is chronological across threads and the transcript streams live posts`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val general = roomThreadId(roomId, ThreadSubject.General)
            val milestone = roomThreadId(roomId, ThreadSubject.Milestone(CanonId("ms-source")))

            val live = rig.scope.async { rig.room.transcript(roomId).first { it.author == Author.Human } }
            delay(200) // let the collector register its bus subscription before anything is posted
            rig.clock.current = rig.clock.current + 1.seconds
            rig.room.post(general, Author.Role(BlueprintRoster.coordinator.id), "status").getOrThrow()
            rig.clock.current = rig.clock.current + 1.seconds
            rig.room.post(milestone, Author.Human, "Ordered the fan myself").getOrThrow()

            val streamed = withTimeout(10.seconds) { live.await() }
            assertEquals("Ordered the fan myself", streamed.body)
            assertEquals(ThreadSubject.Milestone(CanonId("ms-source")), streamed.subject)

            val history = rig.room.history(roomId).getOrThrow()
            assertEquals(7, history.size)
            assertEquals(listOf("status", "Ordered the fan myself"), history.takeLast(2).map { it.body })
            assertTrue(history.zipWithNext().all { (a, b) -> a.postedAt <= b.postedAt })
            assertNotNull(history.first().subject)
        }
    }

    @Test
    fun `the transcript renders threads with their status and assignment`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val subject = ThreadSubject.Verdict("grille", VerdictKind.UNDETERMINED)
            val threadId = rig.room.thread(roomId, subject, scout.id, "Undetermined: grille").getOrThrow()
            rig.room.post(
                threadId,
                Author.Role(BlueprintRoster.coordinator.id),
                "Asking the human: grille publishes no CFM",
            ).getOrThrow()

            val rendered = RoomTranscript.render(
                rig.room.threads(roomId).getOrThrow(),
                rig.room.history(roomId).getOrThrow(),
            )

            assertTrue(rendered.startsWith("# Room room:vent-42"), rendered)
            assertTrue(rendered.contains("## [general] — open"), rendered)
            assertTrue(rendered.contains("## [milestone] ms-measure — open"), rendered)
            assertTrue(rendered.contains("## [verdict] grille (undetermined) — open · assigned to scout"), rendered)
            assertTrue(rendered.contains("  coordinator: Asking the human: grille publishes no CFM"), rendered)
        }
    }
}
