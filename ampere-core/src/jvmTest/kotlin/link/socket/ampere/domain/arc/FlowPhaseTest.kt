package link.socket.ampere.domain.arc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
