package link.socket.ampere.agents.core.types

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.AgentConfiguration
import link.socket.ampere.agents.core.memory.Knowledge
import link.socket.ampere.agents.core.memory.KnowledgeWithScore
import link.socket.ampere.agents.core.memory.KnowledgeEntry
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.reasoning.Idea
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.domain.ai.configuration.AIConfiguration

class ProductManagerAgentTest {

    private fun createAgent(): ProductManagerAgent {
        return ProductManagerAgent(
            id = "test-pm-agent",
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                aiConfiguration = AIConfiguration.default()
            ),
            memoryService = null // Tests don't require actual memory service
        )
    }

    /**
     * Test 1: Verify plan incorporates test-first knowledge when high success rate exists
     */
    @Test
    fun `determinePlanForTask incorporates test-first learnings from past knowledge`() = runBlocking {
        val agent = createAgent()

        // Create past knowledge showing test-first approaches had high success
        val testFirstKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k1",
                    agentId = "test-pm-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Used test-first approach with 3 tasks",
                    learnings = "Test-first approach prevented issues in authentication flow",
                    timestamp = Clock.System.now(),
                    tags = listOf("success", "test"),
                    taskType = "code_change",
                    sourceId = "outcome-1"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-1",
                    approach = "Used test-first approach with 3 tasks",
                    learnings = "Test-first approach prevented issues in authentication flow",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.9 // High relevance
            ),
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k2",
                    agentId = "test-pm-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Started with test specifications",
                    learnings = "Testing first caught edge cases early in database migration",
                    timestamp = Clock.System.now(),
                    tags = listOf("success", "test"),
                    taskType = "code_change",
                    sourceId = "outcome-2"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-2",
                    approach = "Started with test specifications",
                    learnings = "Testing first caught edge cases early in database migration",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.85
            )
        )

        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Implement user profile feature"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = testFirstKnowledge)

        // Verify plan includes test-first approach
        assertTrue(plan.tasks.isNotEmpty(), "Plan should include tasks")
        val testTask = plan.tasks.firstOrNull { t ->
            when (t) {
                is Task.CodeChange -> t.description.contains("test", ignoreCase = true)
                else -> false
            }
        }
        assertTrue(testTask != null, "Plan should include test specification task")

        // Verify reasoning references past knowledge
        val testTaskDesc = (testTask as Task.CodeChange).description
        assertTrue(
            testTaskDesc.contains("success rate", ignoreCase = true) ||
                testTaskDesc.contains("Past knowledge", ignoreCase = true),
            "Task description should reference past knowledge insights"
        )
    }

    /**
     * Test 2: Verify plan includes validation for known failure patterns
     */
    @Test
    fun `determinePlanForTask adds validation for commonly failed patterns`() = runBlocking {
        val agent = createAgent()

        // Create past knowledge with failure patterns
        val failureKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k3",
                    agentId = "test-pm-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Decomposed into 5 tasks without validation",
                    learnings = "Failure: foreign key constraint violations were not caught early",
                    timestamp = Clock.System.now(),
                    tags = listOf("failure"),
                    taskType = "code_change",
                    sourceId = "outcome-3"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-3",
                    approach = "Decomposed into 5 tasks without validation",
                    learnings = "Failure: foreign key constraint violations were not caught early",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.75
            )
        )

        val task = Task.CodeChange(
            id = "task-2",
            status = TaskStatus.Pending,
            description = "Implement database schema changes"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = failureKnowledge)

        // Verify plan includes validation steps
        assertTrue(plan.tasks.isNotEmpty(), "Plan should include tasks")
        val validationTask = plan.tasks.firstOrNull { t ->
            when (t) {
                is Task.CodeChange -> t.description.contains("Validate", ignoreCase = true)
                else -> false
            }
        }
        assertTrue(validationTask != null, "Plan should include validation task for known failure")
    }

    /**
     * Test 3: Verify only high-relevance knowledge influences planning
     */
    @Test
    fun `determinePlanForTask filters out low-relevance knowledge`() = runBlocking {
        val agent = createAgent()

        // Mix of high and low relevance knowledge
        val mixedKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k4",
                    agentId = "test-pm-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Test-first approach",
                    learnings = "High relevance learning",
                    timestamp = Clock.System.now(),
                    tags = listOf("test"),
                    taskType = "code_change",
                    sourceId = "outcome-4"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-4",
                    approach = "Test-first approach",
                    learnings = "High relevance learning",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.9 // High relevance
            ),
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k5",
                    agentId = "test-pm-agent",
                    knowledgeType = "FromOutcome",
                    approach = "Some other approach",
                    learnings = "Low relevance learning",
                    timestamp = Clock.System.now(),
                    tags = emptyList(),
                    taskType = "code_change",
                    sourceId = "outcome-5"
                ),
                knowledge = Knowledge.FromOutcome(
                    outcomeId = "outcome-5",
                    approach = "Some other approach",
                    learnings = "Low relevance learning",
                    timestamp = Clock.System.now()
                ),
                relevanceScore = 0.3 // Low relevance - should be filtered out
            )
        )

        val task = Task.CodeChange(
            id = "task-3",
            status = TaskStatus.Pending,
            description = "Implement new feature"
        )

        val plan = agent.determinePlanForTask(task, relevantKnowledge = mixedKnowledge)

        // Plan should only be influenced by high-relevance knowledge
        // Low relevance knowledge should be filtered out (relevanceScore > 0.5 threshold)
        assertTrue(plan.tasks.isNotEmpty(), "Plan should include tasks")
    }

    /**
     * Test 4: Verify extractKnowledgeFromOutcome captures success patterns
     */
    @Test
    fun `extractKnowledgeFromOutcome captures success learnings correctly`() {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-4",
            status = TaskStatus.Pending,
            description = "Implement authentication system"
        )

        val plan = Plan.ForTask(
            task = task,
            tasks = listOf(
                Task.CodeChange(
                    id = "subtask-1",
                    status = TaskStatus.Pending,
                    description = "Define test specifications"
                ),
                Task.CodeChange(
                    id = "subtask-2",
                    status = TaskStatus.Pending,
                    description = "Implement authentication logic"
                )
            ),
            estimatedComplexity = 5
        )

        val outcome = object : Outcome.Success {
            override val id = "outcome-success-1"
        }

        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

        // Verify knowledge captures the approach
        assertTrue(knowledge.approach.contains("2 tasks"), "Approach should mention task count")
        assertTrue(
            knowledge.approach.contains("test", ignoreCase = true),
            "Approach should recognize test-first"
        )

        // Verify learnings capture success
        assertTrue(knowledge.learnings.contains("Success"), "Learnings should mention success")
        assertTrue(
            knowledge.learnings.contains("worked well") || knowledge.learnings.contains("prevented"),
            "Learnings should capture positive outcomes"
        )
    }

    /**
     * Test 5: Verify extractKnowledgeFromOutcome captures failure patterns
     */
    @Test
    fun `extractKnowledgeFromOutcome captures failure learnings correctly`() {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-5",
            status = TaskStatus.Pending,
            description = "Implement payment processing"
        )

        val plan = Plan.ForTask(
            task = task,
            tasks = listOf(
                Task.CodeChange(
                    id = "subtask-1",
                    status = TaskStatus.Pending,
                    description = "Implement payment logic"
                )
            ),
            estimatedComplexity = 3
        )

        val outcome = object : Outcome.Failure {
            override val id = "outcome-failure-1"
        }

        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

        // Verify learnings capture failure insights
        assertTrue(knowledge.learnings.contains("Failure"), "Learnings should mention failure")
        assertTrue(
            knowledge.learnings.contains("Recommend") || knowledge.learnings.contains("adjusting"),
            "Learnings should provide recommendations"
        )
    }

    /**
     * Test 6: Verify agent handles empty knowledge gracefully
     */
    @Test
    fun `determinePlanForTask handles empty knowledge with default strategy`() = runBlocking {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-6",
            status = TaskStatus.Pending,
            description = "Implement new feature"
        )

        // No knowledge provided - agent should use default strategy
        val plan = agent.determinePlanForTask(task, relevantKnowledge = emptyList())

        assertTrue(plan.tasks.isNotEmpty(), "Plan should include tasks even without knowledge")
        assertEquals(5, plan.estimatedComplexity, "Should use default complexity")
    }

    /**
     * Test 7: Verify extracted knowledge is type-safe
     */
    @Test
    fun `extractKnowledgeFromOutcome returns Knowledge_FromOutcome type`() {
        val agent = createAgent()

        val task = Task.CodeChange(
            id = "task-7",
            status = TaskStatus.Pending,
            description = "Test task"
        )

        val plan = Plan.ForTask(task = task)
        val outcome = object : Outcome.Success {
            override val id = "outcome-7"
        }

        val knowledge = agent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge is Knowledge.FromOutcome, "Should return Knowledge.FromOutcome")
        assertEquals(outcome.id, (knowledge as Knowledge.FromOutcome).outcomeId)
    }
}
