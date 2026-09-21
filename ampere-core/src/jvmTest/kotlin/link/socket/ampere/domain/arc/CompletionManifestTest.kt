package link.socket.ampere.domain.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class CompletionManifestTest {

    private val leafA = GoalNode(id = "a", description = "Write the endpoint")
    private val leafB = GoalNode(id = "b", description = "Write the test")
    private val root = GoalNode(id = "root", description = "Ship it", children = listOf(leafA, leafB))

    private val charge = ChargeResult(
        projectContext = ProjectContext(
            projectId = "p",
            description = "",
            repositoryRoot = "/tmp".toPath(),
            architecture = "",
            conventions = "",
            techStack = emptyList(),
            sources = emptyList(),
        ),
        goalTree = GoalTree(root),
        agents = emptyList(),
    )

    @Test
    fun `cancel during Charge leaves intended goals unknown rather than empty`() {
        val manifest = CompletionManifest.fromIncompleteRun(
            runId = "run-1",
            endedBy = TerminationReason.CANCELLED,
            cause = null,
            reachedPhase = ArcPhase.CHARGE,
            chargeResult = null,
            flowResult = null,
            flowCompleted = false,
        )

        assertEquals(listOf(ArcPhase.CHARGE), manifest.phasesStarted)
        assertEquals(emptyList(), manifest.phasesCompleted)
        assertEquals(listOf(ArcPhase.FLOW, ArcPhase.PULSE), manifest.phasesNotRun)
        assertEquals(ArcPhase.CHARGE, manifest.endedDuring)
        assertNull(manifest.reachedTick, "Flow never started, so no tick was reached")
        assertNull(manifest.unmetGoals, "No goal tree was built; an empty list would claim nothing is missing")
        assertFalse(manifest.intendedGoalsKnown)
        assertNull(manifest.failure, "A cancelled run has no failure to report")
    }

    @Test
    fun `cancel mid Flow names the goals that did not happen`() {
        val snapshot = FlowResult(
            completedGoals = listOf(root),
            finalTick = 3,
            agentOutcomes = emptyMap(),
            terminationReason = TerminationReason.CANCELLED,
        )

        val manifest = CompletionManifest.fromIncompleteRun(
            runId = "run-2",
            endedBy = TerminationReason.CANCELLED,
            cause = null,
            reachedPhase = ArcPhase.FLOW,
            chargeResult = charge,
            flowResult = snapshot,
            flowCompleted = false,
        )

        assertEquals(listOf(ArcPhase.CHARGE, ArcPhase.FLOW), manifest.phasesStarted)
        assertEquals(listOf(ArcPhase.CHARGE), manifest.phasesCompleted)
        assertEquals(listOf(ArcPhase.PULSE), manifest.phasesNotRun)
        assertEquals(ArcPhase.FLOW, manifest.endedDuring)
        assertEquals(3, manifest.reachedTick)
        assertEquals(listOf(root), manifest.completedGoals)
        assertEquals(listOf(leafA, leafB), manifest.unmetGoals)
        assertEquals(
            "cancelled during FLOW at tick 3; 1/3 goals met; not run: PULSE",
            manifest.summary(),
        )
    }

    @Test
    fun `cancel between a finished Flow and Pulse is attributed to Pulse`() {
        val finished = FlowResult(
            completedGoals = listOf(root, leafA, leafB),
            finalTick = 5,
            agentOutcomes = emptyMap(),
            terminationReason = TerminationReason.GOAL_COMPLETE,
        )

        val manifest = CompletionManifest.fromIncompleteRun(
            runId = "run-3",
            endedBy = TerminationReason.CANCELLED,
            cause = null,
            reachedPhase = ArcPhase.FLOW,
            chargeResult = charge,
            flowResult = finished,
            flowCompleted = true,
        )

        assertEquals(listOf(ArcPhase.CHARGE, ArcPhase.FLOW), manifest.phasesCompleted)
        assertEquals(ArcPhase.PULSE, manifest.endedDuring)
        assertTrue(ArcPhase.PULSE in manifest.phasesNotRun, "Pulse never ran, so no Knowledge was captured")
        assertEquals(emptyList(), manifest.unmetGoals)
    }

    @Test
    fun `failure mid Flow records the cause and is summarised as failed`() {
        val snapshot = FlowResult(
            completedGoals = emptyList(),
            finalTick = 2,
            agentOutcomes = emptyMap(),
            terminationReason = TerminationReason.ERROR,
        )

        val manifest = CompletionManifest.fromIncompleteRun(
            runId = "run-4",
            endedBy = TerminationReason.ERROR,
            cause = IllegalStateException("tool exploded"),
            reachedPhase = ArcPhase.FLOW,
            chargeResult = charge,
            flowResult = snapshot,
            flowCompleted = false,
        )

        assertEquals(TerminationReason.ERROR, manifest.endedBy)
        assertEquals("IllegalStateException: tool exploded", manifest.failure)
        assertEquals(ArcPhase.FLOW, manifest.endedDuring)
        assertEquals(listOf(root, leafA, leafB), manifest.unmetGoals)
        assertEquals(
            "failed during FLOW at tick 2; 0/3 goals met; not run: PULSE",
            manifest.summary(),
        )
    }

    @Test
    fun `a manifest only records unfinished endings`() {
        assertFailsWith<IllegalArgumentException> {
            CompletionManifest.fromIncompleteRun(
                runId = "run-5",
                endedBy = TerminationReason.GOAL_COMPLETE,
                cause = null,
                reachedPhase = ArcPhase.PULSE,
                chargeResult = charge,
                flowResult = null,
                flowCompleted = true,
            )
        }
    }
}
