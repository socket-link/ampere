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

class ValidationAgentTest {

    private fun createAgent(): ValidationAgent {
        return ValidationAgent(
            id = "test-validation-agent",
            initialState = AgentState(),
            agentConfiguration = testAgentConfiguration(),
            memoryService = null
        )
    }

    @Test
    fun `determinePlanForTask prioritizes effective checks`() = runBlocking {
        val agent = createAgent()

        val validationKnowledge = listOf(
            KnowledgeWithScore(
                entry = testKnowledgeEntry(
                    id = "k1",
                    approach = "Performed syntax validation with high effectiveness",
                    learnings = "Syntax validation caught compilation errors early",
                    outcomeId = "outcome-1",
                    tags = listOf("success", "syntax"),
                    taskType = "code_change"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-1",
                    approach = "Performed syntax validation with high effectiveness",
                    learnings = "Syntax validation caught compilation errors early",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.95
            )
        )

        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Validate user input handling"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = validationKnowledge)

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include validation tasks")
    }

    @Test
    fun `extractKnowledgeFromOutcome captures validation success`() {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-4",
            status = TaskStatus.Pending,
            description = "Validate payment processing"
        )

        val plan = Plan.ForTask(task = task)
        val outcome = TestSuccessOutcome(id = "outcome-success-1")

        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge.approach.isNotEmpty(), "Approach should be captured")
        assertTrue(knowledge.learnings.contains("Success"), "Learnings should mention success")
        assertTrue(knowledge is Knowledge.FromOutcome)
    }

    @Test
    fun `determinePlanForTask uses default strategy without knowledge`() = runBlocking {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-6",
            status = TaskStatus.Pending,
            description = "Validate new feature"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = emptyList())

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include default validation tasks")
    }
}
