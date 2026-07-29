package link.socket.ampere.domain.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import link.socket.ampere.agents.definition.SparkAgentFactory
import okio.Path.Companion.toPath

class FlowPhaseTest {

    @Test
    fun `flow phase executes until max ticks`() = runTest {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "pm")),
            orchestration = OrchestrationConfig(
                type = OrchestrationType.SEQUENTIAL,
            ),
        )

        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Test goal",
            ),
        )

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = emptyList(), // Skip agent execution for now
            goalTree = goalTree,
            maxTicks = 3,
        )

        assertEquals(0, flow.getCurrentTick())

        // Flow should fail quickly with empty agents list
        try {
            flow.execute()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("at least one agent") == true)
        }
    }

    @Test
    fun `flow phase initializes with correct state`() {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
        )

        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Test goal",
            ),
        )

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = emptyList(),
            goalTree = goalTree,
            maxTicks = 100,
        )

        assertEquals(0, flow.getCurrentTick())
        assertEquals(false, flow.isComplete())
    }

    @Test
    fun `flow phase can be stopped manually`() {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
        )

        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Test goal",
            ),
        )

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = emptyList(),
            goalTree = goalTree,
            maxTicks = 100,
        )

        assertEquals(false, flow.isComplete())
        flow.stop()
        assertTrue(flow.isComplete())
        assertEquals(TerminationReason.MANUAL_STOP, flow.snapshot().terminationReason)
    }

    @Test
    fun `flow phase tick loop bails out when its coroutine is cancelled`() = runTest {
        val arcConfig = ArcConfig(
            name = "test-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
            orchestration = OrchestrationConfig(type = OrchestrationType.SEQUENTIAL),
        )

        val goalTree = GoalTree(root = GoalNode(id = "goal-1", description = "Test goal"))

        val agentScope = CoroutineScope(SupervisorJob())
        val agents = try {
            ArcAgentSpawner(SparkAgentFactory(scope = agentScope)).spawn(
                arcConfig,
                ProjectContext(
                    projectId = "demo",
                    description = "Demo",
                    repositoryRoot = "/tmp".toPath(),
                    architecture = "Layered",
                    conventions = "Kotlin",
                    techStack = listOf("Kotlin"),
                    sources = emptyList(),
                ),
            )
        } finally {
            agentScope.cancel()
        }

        val flow = FlowPhase(
            arcConfig = arcConfig,
            agents = agents,
            goalTree = goalTree,
            maxTicks = 100,
        )

        // Cancel from inside the coroutine that runs the loop: the very first `ensureActive()`
        // must throw rather than grinding through all 100 ticks.
        val outerJob = Job()
        val thrown = runCatching {
            withContext(outerJob) {
                outerJob.cancel()
                flow.execute()
            }
        }.exceptionOrNull()

        assertTrue(thrown is CancellationException, "Cancellation must propagate, not be swallowed")
        assertEquals(0, flow.getCurrentTick())
        assertEquals(TerminationReason.CANCELLED, flow.snapshot().terminationReason)
    }

    @Test
    fun `shared context tracks goal completion`() {
        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Root goal",
                children = listOf(
                    GoalNode(id = "goal-2", description = "Child goal 1"),
                    GoalNode(id = "goal-3", description = "Child goal 2"),
                ),
            ),
        )

        val context = SharedContext(
            goalTree = goalTree,
            currentGoal = goalTree.root,
        )

        assertEquals(false, context.isGoalTreeComplete())

        // Mark goals as complete
        context.markGoalComplete(goalTree.root)
        context.markGoalComplete(goalTree.root.children[0])
        context.markGoalComplete(goalTree.root.children[1])

        assertTrue(context.isGoalTreeComplete())
    }

    @Test
    fun `goal tree allNodes returns all nodes`() {
        val goalTree = GoalTree(
            root = GoalNode(
                id = "goal-1",
                description = "Root",
                children = listOf(
                    GoalNode(id = "goal-2", description = "Child 1"),
                    GoalNode(
                        id = "goal-3",
                        description = "Child 2",
                        children = listOf(
                            GoalNode(id = "goal-4", description = "Grandchild"),
                        ),
                    ),
                ),
            ),
        )

        val allNodes = goalTree.allNodes()
        assertEquals(4, allNodes.size)
        assertTrue(allNodes.any { it.id == "goal-1" })
        assertTrue(allNodes.any { it.id == "goal-2" })
        assertTrue(allNodes.any { it.id == "goal-3" })
        assertTrue(allNodes.any { it.id == "goal-4" })
    }
}
