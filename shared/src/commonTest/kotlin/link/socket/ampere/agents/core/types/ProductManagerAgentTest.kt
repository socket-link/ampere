package link.socket.ampere.agents.core.types

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeWithScore
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.tasks.Task

class ProductManagerAgentTest {

    private fun createAgent(): ProductManagerAgent {
        return ProductManagerAgent(
            id = "test-pm-agent",
            initialState = AgentState(),
            agentConfiguration = testAgentConfiguration(),
            memoryService = null
        )
    }

    @Test
    fun `determinePlanForTask creates plan with knowledge`() = runBlocking {
        val agent = createAgent()

        val testFirstKnowledge = listOf(
            KnowledgeWithScore(
                entry = testKnowledgeEntry(
                    id = "k1",
                    approach = "Used test-first approach with 3 tasks",
                    learnings = "Test-first approach prevented issues in authentication flow",
                    outcomeId = "outcome-1",
                    tags = listOf("success", "test"),
                    taskType = "code_change"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-1",
                    approach = "Used test-first approach with 3 tasks",
                    learnings = "Test-first approach prevented issues in authentication flow",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.9
            )
        )

        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Implement user profile feature"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = testFirstKnowledge)

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include tasks")
    }

    @Test
    fun `extractKnowledgeFromOutcome captures success learnings`() {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-4",
            status = TaskStatus.Pending,
            description = "Implement authentication system"
        )

        val plan = Plan.ForTask(task = task)
        val outcome = TestSuccessOutcome(id = "outcome-success-1")

        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge.approach.isNotEmpty(), "Approach should be captured")
        assertTrue(knowledge.learnings.contains("Success"), "Learnings should mention success")
        assertTrue(knowledge is Knowledge.FromOutcome)
    }

    @Test
    fun `determinePlanForTask handles empty knowledge`() = runBlocking {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-6",
            status = TaskStatus.Pending,
            description = "Implement new feature"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = emptyList())

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include tasks even without knowledge")
    }
}
