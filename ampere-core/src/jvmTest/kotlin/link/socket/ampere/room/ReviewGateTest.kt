package link.socket.ampere.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.RoomEvent
import link.socket.ampere.probe.ProbeId
import link.socket.ampere.probe.ProbeReport
import link.socket.ampere.probe.UndeterminedCause
import link.socket.ampere.probe.Verdict as ProbeVerdict
import link.socket.ampere.roster.BlueprintRoster

/** The open-for-review path: the Inspector reviews Planner cards before they reach the Room. */
class ReviewGateTest {

    private val planner = Author.Role(BlueprintRoster.planner.id)
    private val inspector = Author.Role(BlueprintRoster.inspector.id)
    private val coordinator = Author.Role(BlueprintRoster.coordinator.id)
    private val revision = RoomCard.PlanRevision(
        revision = 2,
        remainingItems = 3,
        remaining = 6.hours,
        projectedFinish = null,
    )

    private fun RoomTestRig.gate() = ReviewGate(room, BlueprintRoster, door.api, clock, ids)

    @Test
    fun `a planner card is held until the inspector reviews it`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val general = roomThreadId(roomId, ThreadSubject.General)

            val submission = rig.gate().submit(roomId, general, planner, "Revision 2", revision).getOrThrow()

            val pending = assertIs<ReviewGate.Submission.Pending>(submission)
            assertEquals(inspector.id, pending.reviewer)
            assertTrue(rig.room.history(roomId).getOrThrow().none { it.card is RoomCard.PlanRevision })
            val requested = rig.events(RoomEvent.ReviewRequested.EVENT_TYPE)
                .filterIsInstance<RoomEvent.ReviewRequested>()
                .single()
            assertEquals(pending.reviewId, requested.reviewId)
            assertEquals(revision, requested.card)
        }
    }

    @Test
    fun `a holding probe releases the card as its author after the verdict card`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val general = roomThreadId(roomId, ThreadSubject.General)
            val gate = rig.gate()
            val pending = gate.submit(roomId, general, planner, "Revision 2", revision).getOrThrow()
                as ReviewGate.Submission.Pending

            val outcome = gate.review(
                pending.reviewId,
                inspector,
                ProbeReport(ProbeId("ampere.sequence"), "vent-42", ProbeVerdict.Holds()),
            ).getOrThrow()

            assertIs<ReviewGate.ReviewOutcome.Released>(outcome)
            val posts = rig.room.history(roomId).getOrThrow().filter { it.threadId == general }.drop(1)
            assertEquals(listOf(inspector, planner), posts.map { it.author })
            assertIs<RoomCard.Verdict>(posts[0].card)
            assertEquals(revision, posts[1].card)
            val completed = rig.events(RoomEvent.ReviewCompleted.EVENT_TYPE)
                .filterIsInstance<RoomEvent.ReviewCompleted>()
                .single()
            assertTrue(completed.released)
            assertEquals(posts[1].messageId, completed.messageId)
            assertTrue(gate.pending().isEmpty())
        }
    }

    @Test
    fun `a violated or undetermined probe withholds the card`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val general = roomThreadId(roomId, ThreadSubject.General)
            val gate = rig.gate()
            val first = gate.submit(roomId, general, planner, "Revision 2", revision).getOrThrow()
                as ReviewGate.Submission.Pending
            val second = gate.submit(roomId, general, planner, "Revision 3", revision.copy(revision = 3)).getOrThrow()
                as ReviewGate.Submission.Pending

            val violated = gate.review(
                first.reviewId,
                inspector,
                ProbeReport(ProbeId("ampere.sequence"), "vent-42", ProbeVerdict.Violated("cycle: a -> b -> a")),
            ).getOrThrow()
            val undetermined = gate.review(
                second.reviewId,
                inspector,
                ProbeReport(
                    ProbeId("ampere.freshness"),
                    "vent-42",
                    ProbeVerdict.Undetermined("stale", UndeterminedCause.STALE),
                ),
            ).getOrThrow()

            assertEquals("cycle: a -> b -> a", assertIs<ReviewGate.ReviewOutcome.Withheld>(violated).reason)
            assertIs<ReviewGate.ReviewOutcome.Withheld>(undetermined)
            val history = rig.room.history(roomId).getOrThrow()
            assertTrue(history.none { it.card is RoomCard.PlanRevision })
            assertEquals(2, history.count { it.card is RoomCard.Verdict })
            val completions = rig.events(RoomEvent.ReviewCompleted.EVENT_TYPE)
                .filterIsInstance<RoomEvent.ReviewCompleted>()
            assertTrue(
                completions.none {
                    it.released
                },
            )
        }
    }

    @Test
    fun `only the named reviewer may review and unknown reviews are refused`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val general = roomThreadId(roomId, ThreadSubject.General)
            val gate = rig.gate()
            val pending = gate.submit(roomId, general, planner, "Revision 2", revision).getOrThrow()
                as ReviewGate.Submission.Pending
            val report = ProbeReport(ProbeId("ampere.sequence"), "vent-42", ProbeVerdict.Holds())

            val wrongReviewer = gate.review(pending.reviewId, coordinator, report)
            val unknown = gate.review(ReviewId("nope"), inspector, report)

            assertEquals(
                RoomFailure.NotTheReviewer(pending.reviewId, expected = inspector.id, actual = coordinator.id),
                assertIs<RoomException>(wrongReviewer.exceptionOrNull()).failure,
            )
            assertEquals(
                RoomFailure.ReviewNotFound(ReviewId("nope")),
                assertIs<RoomException>(unknown.exceptionOrNull()).failure,
            )
            assertEquals(1, gate.pending().size)
        }
    }

    @Test
    fun `a card from an unreviewed role reaches the room directly`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val roomId = rig.room.open(VentGraph.graph()).getOrThrow()
            val general = roomThreadId(roomId, ThreadSubject.General)
            val status = RoomCard.Status("Standup", completed = 1, remaining = 2, blocked = 0, projectedFinish = null)

            val submission = rig.gate().submit(roomId, general, coordinator, "Status", status).getOrThrow()

            val released = assertIs<ReviewGate.Submission.Released>(submission)
            assertEquals(
                status,
                rig.room.history(roomId).getOrThrow().single { it.messageId == released.messageId }.card,
            )
            assertTrue(rig.events(RoomEvent.ReviewRequested.EVENT_TYPE).isEmpty())
        }
    }
}
