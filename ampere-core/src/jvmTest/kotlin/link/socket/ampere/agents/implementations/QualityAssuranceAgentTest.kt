package link.socket.ampere.agents.implementations

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.memory.KnowledgeWithScore
import link.socket.ampere.agents.definition.QualityAssuranceAgent
import link.socket.ampere.stubKnowledgeEntry
import link.socket.ampere.stubQualityAssuranceAgent
import link.socket.ampere.stubSuccessOutcome

class QualityAssuranceAgentTest {

    private lateinit var qualityAssuranceAgent: QualityAssuranceAgent

    @BeforeTest
    fun setUp() {
        qualityAssuranceAgent = stubQualityAssuranceAgent()
    }

    @Test
    fun `determinePlanForTask prioritizes effective checks`() = runBlocking {
        val validationKnowledge = listOf(
            KnowledgeWithScore(
                entry = stubKnowledgeEntry(
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

        val plan = qualityAssuranceAgent.determinePlanForTask(task, relevantKnowledge = validationKnowledge)

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include validation tasks")
    }

    @Test
    fun `extractKnowledgeFromOutcome captures validation success`() {
        val task = Task.CodeChange(
            id = "task-4",
            status = TaskStatus.Pending,
            description = "Validate payment processing"
        )

        val plan = Plan.ForTask(task = task)
        val outcome = stubSuccessOutcome()

        val knowledge = qualityAssuranceAgent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge.approach.isNotEmpty(), "Approach should be captured")
        assertTrue(knowledge.learnings.contains("Success"), "Learnings should mention success")
        assertTrue(knowledge is Knowledge.FromOutcome)
    }

    @Test
    fun `determinePlanForTask uses default strategy without knowledge`() = runBlocking {
        val task = Task.CodeChange(
            id = "task-6",
            status = TaskStatus.Pending,
            description = "Validate new feature"
        )

        val plan = qualityAssuranceAgent.determinePlanForTask(task, relevantKnowledge = emptyList())

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include default validation tasks")
    }
}
