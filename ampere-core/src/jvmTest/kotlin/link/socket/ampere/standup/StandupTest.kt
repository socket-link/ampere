package link.socket.ampere.standup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MeetingEvent
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.outcome.MeetingOutcome
import link.socket.ampere.agents.domain.status.MeetingStatus
import link.socket.ampere.agents.events.meetings.MeetingRepository
import link.socket.ampere.agents.events.meetings.MeetingType
import link.socket.ampere.canon.CanonId
import link.socket.ampere.canon.CanonWorkStatus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.room.Author
import link.socket.ampere.room.ReviewGate
import link.socket.ampere.room.RoomCard
import link.socket.ampere.room.RoomId
import link.socket.ampere.room.RoomTestRig
import link.socket.ampere.room.ThreadSubject
import link.socket.ampere.room.VentGraph
import link.socket.ampere.room.roomThreadId
import link.socket.ampere.roster.BlueprintRoster
import link.socket.ampere.roster.calibration.Calibration
import link.socket.ampere.roster.calibration.EstimateCalibrationSource
import link.socket.ampere.roster.calibration.EstimateCategory
import link.socket.ampere.roster.calibration.NoCalibration
import link.socket.ampere.roster.calibration.WorkEstimate

/**
 * AMPR-379 task 4 validation: given six lifecycle events since the last standup,
 * the outcome contains a revision proposal whose finish projection uses the
 * calibration source, one status post, and zero escalations — against a fake model.
 */
class StandupTest {

    private val since = Instant.parse("2026-09-21T09:00:00Z")

    private val baseline = listOf(
        WorkEstimate(CanonId("pick-duct"), EstimateCategory.RESEARCH, 1.hours),
        WorkEstimate(CanonId("pick-fan"), EstimateCategory.RESEARCH, 1.hours),
        WorkEstimate(CanonId("order-parts"), EstimateCategory.ADMIN, 30.minutes),
        WorkEstimate(CanonId("cut-duct"), EstimateCategory.PHYSICAL_WORK, 2.hours),
        WorkEstimate(CanonId("fit-grille"), EstimateCategory.ASSEMBLY, 1.hours),
    )

    private val timesOneAndAHalf = object : EstimateCalibrationSource {
        override suspend fun multiplier(category: EstimateCategory) = Calibration(multiplier = 1.5, samples = 7)
    }

    private fun RoomTestRig.standup(
        narrator: StandupNarrator = TemplatedNarrator,
        repository: MeetingRepository? = null,
    ) = Standup(
        room = room,
        reviewGate = ReviewGate(room, BlueprintRoster, door.api, clock, ids),
        eventApi = door.api,
        meetingRepository = repository,
        narrator = narrator,
        clock = clock,
        idGenerator = ids,
    )

    private suspend fun RoomTestRig.publishSixLifecycleEvents() {
        val source = EventSource.Agent("lifecycle")
        listOf("pick-duct", "pick-fan", "order-parts").forEachIndexed { index, taskId ->
            val startedAt = since + (index * 2 + 1).hours
            door.api.publish(
                TaskEvent.TaskStarted(
                    eventId = ids(),
                    taskId = taskId,
                    eventSource = source,
                    timestamp = startedAt,
                    assignedTo = "human",
                ),
            ).getOrThrow()
            door.api.publish(
                TaskEvent.TaskCompleted(
                    eventId = ids(),
                    taskId = taskId,
                    eventSource = source,
                    timestamp = startedAt + 1.hours,
                    summary = "done",
                ),
            ).getOrThrow()
        }
    }

    @Test
    fun `six lifecycle events yield a calibrated revision and a status post and no escalations`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val graph = VentGraph.graph()
            rig.room.open(graph).getOrThrow()
            rig.publishSixLifecycleEvents()
            val prompts = mutableListOf<String>()
            val narrator =
                LlmNarrator(
                    provider = { prompt ->
                        prompts += prompt
                        "Three parts picked and ordered; cutting and fitting remain."
                    },
                )
            val meetings = MeetingRepository(DEFAULT_JSON, rig.scope, rig.door.database)

            val outcome = rig.standup(narrator, meetings).run(
                graph = graph,
                since = since,
                calibration = timesOneAndAHalf,
                baseline = baseline,
            ).getOrThrow()

            // Remaining: cut-duct (2h) and fit-grille (1h), both ×1.5 → 4h30 from now.
            assertEquals(listOf(CanonId("cut-duct"), CanonId("fit-grille")), outcome.revisionProposal.remaining)
            assertEquals(rig.clock.now() + 4.hours + 30.minutes, outcome.revisionProposal.projectedFinish)
            assertEquals(4.hours + 30.minutes, outcome.revisionProposal.remainingWork)
            assertTrue(
                outcome.revisionProposal.rationale.any {
                    it.contains("×1.5 from 7 session(s)")
                },
                outcome.revisionProposal.rationale.toString(),
            )
            assertTrue(outcome.revisionReleased)
            assertTrue(outcome.escalations.isEmpty())
            assertEquals("Three parts picked and ordered; cutting and fitting remain.", outcome.narrative)
            assertEquals(1, prompts.size, "the narrative is the one metered step")
            assertTrue(prompts.single().startsWith("System: You are the Coordinator on a Blueprint."))

            val general = roomThreadId(RoomTestRigIds.roomId, ThreadSubject.General)
            val statusPosts = rig.room.history(RoomTestRigIds.roomId).getOrThrow().filter { it.card is RoomCard.Status }
            assertEquals(1, statusPosts.size)
            assertEquals(general, statusPosts.single().threadId)
            assertEquals(Author.Role(BlueprintRoster.coordinator.id), statusPosts.single().author)
            assertEquals(outcome.statusPost, statusPosts.single().messageId)
            val status = assertIs<RoomCard.Status>(statusPosts.single().card)
            assertEquals(3, status.completed)
            assertEquals(2, status.remaining)
            assertEquals(0, status.blocked)

            val revisionCards = rig.room.history(RoomTestRigIds.roomId).getOrThrow()
                .filter { it.card is RoomCard.PlanRevision }
            assertEquals(1, revisionCards.size, "the inspector released the revision into the room")

            val started = rig.events(MeetingEvent.MeetingStarted.EVENT_TYPE)
                .filterIsInstance<MeetingEvent.MeetingStarted>()
                .single()
            val completed = rig.events(MeetingEvent.MeetingCompleted.EVENT_TYPE)
                .filterIsInstance<MeetingEvent.MeetingCompleted>()
                .single()
            assertEquals(outcome.meetingId, started.meetingId)
            assertEquals(general, started.threadId)
            assertEquals(outcome.meetingId, completed.meetingId)
            assertEquals(1, completed.outcomes.size)
            assertIs<MeetingOutcome.DecisionMade>(completed.outcomes.single())

            val meeting = assertNotNull(meetings.getMeeting(outcome.meetingId).getOrThrow())
            assertIs<MeetingStatus.Completed>(meeting.status)
            assertEquals(
                MeetingType.Standup(teamId = "room:vent-42", sprintId = "since-${since.toEpochMilliseconds()}"),
                meeting.type,
            )
        }
    }

    @Test
    fun `without calibration the finish projects earlier`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val graph = VentGraph.graph()
            rig.room.open(graph).getOrThrow()
            rig.publishSixLifecycleEvents()

            val outcome = rig.standup().run(graph, since, NoCalibration, baseline).getOrThrow()

            assertEquals(rig.clock.now() + 3.hours, outcome.revisionProposal.projectedFinish)
            assertTrue(outcome.revisionProposal.rationale.any { it.contains("not earned: 0 samples") })
            assertEquals(TemplatedNarrator.render(briefOf(outcome, rig)), outcome.narrative)
        }
    }

    @Test
    fun `a graph the sequence probe convicts has its revision withheld and escalated`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val graph = VentGraph.graph(
                items = VentGraph.defaultItems() + VentGraph.item("mount-grille", CanonWorkStatus.TODO, "ghost"),
            )
            rig.room.open(graph).getOrThrow()

            val outcome = rig.standup().run(graph, since, timesOneAndAHalf, baseline).getOrThrow()

            assertTrue(!outcome.revisionReleased)
            assertEquals(1, outcome.escalations.size)
            assertTrue(outcome.escalations.single().reason.contains("withheld"))
            assertTrue(rig.room.history(RoomTestRigIds.roomId).getOrThrow().none { it.card is RoomCard.PlanRevision })
            val completed = rig.events(MeetingEvent.MeetingCompleted.EVENT_TYPE)
                .filterIsInstance<MeetingEvent.MeetingCompleted>()
                .single()
            assertEquals(2, completed.outcomes.size)
            assertIs<MeetingOutcome.BlockerRaised>(completed.outcomes[1])
        }
    }

    @Test
    fun `a standup for a room that was never opened fails typed`() = runBlocking<Unit> {
        RoomTestRig().use { rig ->
            val result = rig.standup().run(VentGraph.graph(), since, timesOneAndAHalf, baseline)

            val failure = assertIs<StandupException>(result.exceptionOrNull()).failure
            assertEquals(StandupFailure.RoomNotOpen(RoomTestRigIds.roomId), failure)
        }
    }

    private fun briefOf(outcome: StandupOutcome, rig: RoomTestRig) = StandupBrief(
        roomId = RoomTestRigIds.roomId,
        projectName = VentGraph.project.name,
        since = since,
        now = rig.clock.now(),
        eventsSince = 6,
        completed = 3,
        started = 3,
        remaining = 2,
        blocked = emptyList(),
        openVerdicts = emptyList(),
        proposal = outcome.revisionProposal,
        revisionReleased = true,
    )
}

private object RoomTestRigIds {
    val roomId = RoomId.forProject(VentGraph.project.canonId)
}
