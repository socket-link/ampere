package link.socket.ampere.domain.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.ArcRunEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.outcome.TaskOutcome
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.data.DEFAULT_JSON

/** What a manifest keeps, and loses, on its way into the event store (AMPR-359). */
class CompletionRecordTest {

    private val leafA = GoalNode(id = "a", description = "Write the endpoint")
    private val leafB = GoalNode(id = "b", description = "Write the test")
    private val root = GoalNode(id = "root", description = "Ship it", children = listOf(leafA, leafB))

    private fun manifest(
        endedBy: TerminationReason = TerminationReason.CANCELLED,
        failure: String? = null,
        producedOutcomes: Map<String, List<Outcome>> = emptyMap(),
        completedGoals: List<GoalNode> = listOf(root),
        unmetGoals: List<GoalNode>? = listOf(leafA, leafB),
    ) = CompletionManifest(
        runId = "run-record",
        endedBy = endedBy,
        failure = failure,
        phasesStarted = listOf(ArcPhase.CHARGE, ArcPhase.FLOW),
        phasesCompleted = listOf(ArcPhase.CHARGE),
        phasesNotRun = listOf(ArcPhase.PULSE),
        reachedTick = 4,
        producedOutcomes = producedOutcomes,
        completedGoals = completedGoals,
        unmetGoals = unmetGoals,
    )

    @Test
    fun `a record keeps how the run ended and reads as its manifest does`() {
        val manifest = manifest(endedBy = TerminationReason.ERROR, failure = "IllegalStateException: tool exploded")
        val record = manifest.toRecord()

        assertEquals("run-record", record.runId)
        assertEquals(TerminationReason.ERROR, record.endedBy)
        assertEquals("IllegalStateException: tool exploded", record.failure, "A short failure is kept whole")
        assertEquals(manifest.phasesStarted, record.phasesStarted)
        assertEquals(manifest.phasesCompleted, record.phasesCompleted)
        assertEquals(manifest.phasesNotRun, record.phasesNotRun)
        assertEquals(ArcPhase.FLOW, record.endedDuring)
        assertEquals(4, record.reachedTick)
        assertEquals(manifest.summary(), record.summary())
        assertEquals("failed during FLOW at tick 4; 1/3 goals met; not run: PULSE", record.summary())
    }

    @Test
    fun `goals keep their id and description without their subtree`() {
        val record = manifest().toRecord()

        assertEquals(listOf(CompletionRecord.Goal(id = "root", description = "Ship it")), record.completedGoals)
        assertEquals(
            listOf(
                CompletionRecord.Goal(id = "a", description = "Write the endpoint"),
                CompletionRecord.Goal(id = "b", description = "Write the test"),
            ),
            record.unmetGoals,
        )
        assertTrue(record.intendedGoalsKnown)
    }

    @Test
    fun `unknown intended goals stay unknown rather than becoming none`() {
        val record = manifest(completedGoals = emptyList(), unmetGoals = null).toRecord()

        assertNull(record.unmetGoals, "An empty list would claim nothing was left undone")
        assertFalse(record.intendedGoalsKnown)
        assertTrue(record.summary().contains("intended goals unknown"))
    }

    @Test
    fun `outcomes are tallied per agent and only the latest ids are kept`() {
        val successes = (1..12).map { TaskOutcome.Success.Full(id = "ok-$it", task = Task.Blank, value = "done") }
        val failure = TaskOutcome.Failure(id = "bad-1", task = Task.Blank, errorMessage = "x".repeat(10_000))

        val record = manifest(
            producedOutcomes = mapOf(
                "planner" to listOf(Outcome.Blank, failure),
                "coder" to successes,
            ),
        ).toRecord()

        assertEquals(listOf("planner", "coder"), record.producedOutcomes.map { it.agentId })

        val planner = record.producedOutcomes[0]
        assertEquals(2, planner.total, "A blank outcome still counts as produced")
        assertEquals(0, planner.succeeded)
        assertEquals(1, planner.failed)
        assertEquals(listOf("bad-1"), planner.recentIds, "A blank outcome has no id to keep")

        val coder = record.producedOutcomes[1]
        assertEquals(12, coder.total)
        assertEquals(12, coder.succeeded)
        assertEquals(0, coder.failed)
        assertEquals(
            (12 - CompletionRecord.MAX_RECENT_OUTCOME_IDS + 1..12).map { "ok-$it" },
            coder.recentIds,
            "The last outcomes are the ones nearest where the run stopped",
        )
    }

    @Test
    fun `free text is clipped and the cut is visible`() {
        val record = manifest(
            endedBy = TerminationReason.ERROR,
            failure = "IllegalStateException: " + "m".repeat(5_000),
            completedGoals = emptyList(),
            unmetGoals = listOf(GoalNode(id = "long", description = "g".repeat(500))),
        ).toRecord()

        val failure = assertNotNull(record.failure)
        assertEquals(CompletionRecord.MAX_FAILURE_CHARS, failure.length)
        assertTrue(failure.startsWith("IllegalStateException: "))
        assertTrue(failure.endsWith("…"))

        val goal = assertNotNull(record.unmetGoals).single()
        assertEquals("long", goal.id)
        assertEquals(CompletionRecord.MAX_GOAL_DESCRIPTION_CHARS, goal.description.length)
        assertTrue(goal.description.endsWith("…"))
    }

    @Test
    fun `the manifest event survives the event serializer and ranks a failure above a cancel`() {
        val source = EventSource.Agent(CompletionManifestSink.DEFAULT_AGENT_ID)
        val at = Instant.fromEpochMilliseconds(5_000)

        val failed = manifest(endedBy = TerminationReason.ERROR, failure = "IllegalStateException: boom")
            .toEvent(eventSource = source, timestamp = at)
        val cancelled = manifest().toEvent(eventSource = source, timestamp = at)

        assertEquals("run-record", failed.runId)
        assertEquals(ArcRunEvent.CompletionManifestRecorded.EVENT_TYPE, failed.eventType)
        assertEquals(Urgency.HIGH, failed.urgency)
        assertEquals(Urgency.MEDIUM, cancelled.urgency)

        val encoded = DEFAULT_JSON.encodeToString(Event.serializer(), failed)
        assertEquals(failed, DEFAULT_JSON.decodeFromString(Event.serializer(), encoded))
    }
}
