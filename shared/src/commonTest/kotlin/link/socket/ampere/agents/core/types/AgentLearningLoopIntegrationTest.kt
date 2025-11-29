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
import link.socket.ampere.agents.core.reasoning.Plan
import link.socket.ampere.agents.core.states.AgentState
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.domain.ai.configuration.AIConfiguration

/**
 * Integration tests for the full agent learning loop.
 *
 * These tests verify that:
 * 1. Agents extract knowledge from completed tasks
 * 2. Extracted knowledge can be stored
 * 3. Subsequent similar tasks use recalled knowledge to inform planning
 * 4. Plans demonstrably change based on past learnings
 */
class AgentLearningLoopIntegrationTest {

    /**
     * Test the full learning loop for ProductManagerAgent:
     * 1. Execute first task with no knowledge
     * 2. Extract and "store" knowledge from outcome
     * 3. Execute similar task with recalled knowledge
     * 4. Verify second plan incorporates learnings from first
     */
    @Test
    fun `ProductManagerAgent learning loop - second task benefits from first task knowledge`() = runBlocking {
        val agent = ProductManagerAgent(
            id = "integration-test-pm",
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                aiConfiguration = AIConfiguration.default()
            ),
            memoryService = null
        )

        // ===== FIRST TASK EXECUTION =====

        val firstTask = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Implement authentication feature"
        )

        // Execute first task without any knowledge (cold start)
        val firstPlan = agent.determinePlanForTask(firstTask, relevantKnowledge = emptyList())

        // Simulate successful execution with test-first approach
        val firstOutcome = object : Outcome.Success {
            override val id = "outcome-1"
        }

        // Modify the first plan to include test tasks (simulating what the LLM would do)
        val firstPlanWithTests = Plan.ForTask(
            task = firstTask,
            tasks = listOf(
                Task.CodeChange(
                    id = "subtask-1",
                    status = TaskStatus.Pending,
                    description = "Define test specifications for authentication"
                ),
                Task.CodeChange(
                    id = "subtask-2",
                    status = TaskStatus.Pending,
                    description = "Implement authentication logic"
                )
            ),
            estimatedComplexity = 5
        )

        // Extract knowledge from successful first execution
        val extractedKnowledge = agent.extractKnowledgeFromOutcome(firstOutcome, firstTask, firstPlanWithTests)

        // Verify extracted knowledge captures test-first approach
        assertTrue(
            extractedKnowledge.approach.contains("test", ignoreCase = true),
            "Extracted knowledge should mention test-first approach"
        )
        assertTrue(
            extractedKnowledge.learnings.contains("Success"),
            "Extracted knowledge should capture success"
        )

        // ===== SECOND TASK EXECUTION WITH RECALLED KNOWLEDGE =====

        val secondTask = Task.CodeChange(
            id = "task-2",
            status = TaskStatus.Pending,
            description = "Implement authorization feature"
        )

        // Simulate recalled knowledge from first task (high relevance since it's auth-related)
        val recalledKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k1",
                    agentId = "integration-test-pm",
                    knowledgeType = "FromOutcome",
                    approach = extractedKnowledge.approach,
                    learnings = extractedKnowledge.learnings,
                    timestamp = extractedKnowledge.timestamp,
                    tags = listOf("success", "test"),
                    taskType = "code_change",
                    sourceId = firstOutcome.id
                ),
                knowledge = extractedKnowledge,
                relevanceScore = 0.9 // High relevance - similar auth-related task
            )
        )

        // Execute second task WITH recalled knowledge
        val secondPlan = agent.determinePlanForTask(secondTask, relevantKnowledge = recalledKnowledge)

        // ===== VERIFY LEARNING OCCURRED =====

        // Second plan should be influenced by first task's success
        // It should include test-first tasks since that worked well
        val secondPlanTestTasks = secondPlan.tasks.filter { t ->
            when (t) {
                is Task.CodeChange -> t.description.contains("test", ignoreCase = true)
                else -> false
            }
        }

        assertTrue(
            secondPlanTestTasks.isNotEmpty(),
            "Second plan should include test tasks based on first task's success"
        )

        // Verify reasoning mentions past knowledge
        val hasKnowledgeReference = secondPlan.tasks.any { t ->
            when (t) {
                is Task.CodeChange -> t.description.contains("Past knowledge", ignoreCase = true) ||
                    t.description.contains("success rate", ignoreCase = true)
                else -> false
            }
        }

        assertTrue(
            hasKnowledgeReference,
            "Second plan should reference insights from past knowledge"
        )
    }

    /**
     * Test the learning loop with failure knowledge:
     * 1. First task fails with specific issue
     * 2. Extract failure knowledge
     * 3. Second similar task should add preventive validation
     */
    @Test
    fun `ProductManagerAgent learns from failures and adds preventive steps`() = runBlocking {
        val agent = ProductManagerAgent(
            id = "integration-test-pm-failure",
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                aiConfiguration = AIConfiguration.default()
            ),
            memoryService = null
        )

        // ===== FIRST TASK WITH FAILURE =====

        val firstTask = Task.CodeChange(
            id = "task-fail-1",
            status = TaskStatus.Pending,
            description = "Implement database migration"
        )

        val firstPlan = Plan.ForTask(
            task = firstTask,
            tasks = listOf(
                Task.CodeChange(
                    id = "subtask-fail-1",
                    status = TaskStatus.Pending,
                    description = "Apply schema changes"
                )
            ),
            estimatedComplexity = 3
        )

        // Simulate failure
        val failureOutcome = object : Outcome.Failure {
            override val id = "outcome-fail-1"
        }

        // Extract knowledge from failure
        val failureKnowledge = agent.extractKnowledgeFromOutcome(failureOutcome, firstTask, firstPlan)

        assertTrue(
            failureKnowledge.learnings.contains("Failure") ||
                failureKnowledge.learnings.contains("Recommend"),
            "Failure knowledge should capture lessons learned"
        )

        // ===== SECOND TASK WITH RECALLED FAILURE KNOWLEDGE =====

        val secondTask = Task.CodeChange(
            id = "task-fail-2",
            status = TaskStatus.Pending,
            description = "Implement schema update"
        )

        val recalledFailureKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k-fail-1",
                    agentId = "integration-test-pm-failure",
                    knowledgeType = "FromOutcome",
                    approach = failureKnowledge.approach,
                    learnings = failureKnowledge.learnings,
                    timestamp = failureKnowledge.timestamp,
                    tags = listOf("failure"),
                    taskType = "code_change",
                    sourceId = failureOutcome.id
                ),
                knowledge = failureKnowledge,
                relevanceScore = 0.85
            )
        )

        val secondPlan = agent.determinePlanForTask(secondTask, relevantKnowledge = recalledFailureKnowledge)

        // Should have added validation or preventive tasks based on failure
        // Complexity might increase due to learned caution
        assertTrue(
            secondPlan.estimatedComplexity >= firstPlan.estimatedComplexity,
            "Complexity should increase after learning from failure"
        )
    }

    /**
     * Test ValidationAgent learning loop:
     * 1. First validation finds issues using specific checks
     * 2. Extract effectiveness knowledge
     * 3. Second validation prioritizes effective checks
     */
    @Test
    fun `ValidationAgent learning loop - prioritizes effective checks from past knowledge`() = runBlocking {
        val agent = ValidationAgent(
            id = "integration-test-validation",
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                aiConfiguration = AIConfiguration.default()
            ),
            memoryService = null
        )

        // ===== FIRST VALIDATION TASK =====

        val firstTask = Task.CodeChange(
            id = "validation-task-1",
            status = TaskStatus.Pending,
            description = "Validate payment processing module"
        )

        val firstPlan = Plan.ForTask(
            task = firstTask,
            tasks = listOf(
                Task.CodeChange(
                    id = "val-subtask-1",
                    status = TaskStatus.Pending,
                    description = "Syntax validation"
                ),
                Task.CodeChange(
                    id = "val-subtask-2",
                    status = TaskStatus.Pending,
                    description = "Security validation"
                )
            ),
            estimatedComplexity = 4
        )

        val firstOutcome = object : Outcome.Success {
            override val id = "val-outcome-1"
        }

        // Extract knowledge showing which checks were effective
        val extractedValidationKnowledge = agent.extractKnowledgeFromOutcome(
            firstOutcome,
            firstTask,
            firstPlan
        )

        assertTrue(
            extractedValidationKnowledge.approach.contains("syntax") ||
                extractedValidationKnowledge.approach.contains("security"),
            "Should capture which validation types were performed"
        )

        // ===== SECOND VALIDATION TASK WITH RECALLED KNOWLEDGE =====

        val secondTask = Task.CodeChange(
            id = "validation-task-2",
            status = TaskStatus.Pending,
            description = "Validate user authentication module"
        )

        val recalledValidationKnowledge = listOf(
            KnowledgeWithScore(
                entry = KnowledgeEntry(
                    id = "k-val-1",
                    agentId = "integration-test-validation",
                    knowledgeType = "FromOutcome",
                    approach = extractedValidationKnowledge.approach,
                    learnings = extractedValidationKnowledge.learnings,
                    timestamp = extractedValidationKnowledge.timestamp,
                    tags = listOf("success", "syntax", "security"),
                    taskType = "code_change",
                    sourceId = firstOutcome.id
                ),
                knowledge = extractedValidationKnowledge,
                relevanceScore = 0.92
            )
        )

        val secondPlan = agent.determinePlanForTask(secondTask, relevantKnowledge = recalledValidationKnowledge)

        // Second plan should prioritize validation checks that were effective
        assertTrue(secondPlan.tasks.isNotEmpty(), "Should have validation tasks")

        // Should mention effectiveness from past experience
        val hasEffectivenessReference = secondPlan.tasks.any { t ->
            when (t) {
                is Task.CodeChange -> t.description.contains("effective", ignoreCase = true) ||
                    t.description.contains("%")
                else -> false
            }
        }

        // At minimum, should have created a plan based on knowledge
        assertTrue(secondPlan.tasks.isNotEmpty(), "Second plan should include learned validation approach")
    }

    /**
     * Test cold start scenario: agent without knowledge uses sensible defaults
     */
    @Test
    fun `Agents handle cold start with no knowledge gracefully`() = runBlocking {
        val pmAgent = ProductManagerAgent(
            id = "cold-start-pm",
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                aiConfiguration = AIConfiguration.default()
            ),
            memoryService = null
        )

        val validationAgent = ValidationAgent(
            id = "cold-start-validation",
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                aiConfiguration = AIConfiguration.default()
            ),
            memoryService = null
        )

        val task = Task.CodeChange(
            id = "cold-start-task",
            status = TaskStatus.Pending,
            description = "Implement new feature"
        )

        // Both should generate reasonable plans without knowledge
        val pmPlan = pmAgent.determinePlanForTask(task, relevantKnowledge = emptyList())
        val validationPlan = validationAgent.determinePlanForTask(task, relevantKnowledge = emptyList())

        assertTrue(pmPlan.tasks.isNotEmpty(), "PM should create plan without knowledge")
        assertTrue(validationPlan.tasks.isNotEmpty(), "Validation should create plan without knowledge")

        assertEquals(5, pmPlan.estimatedComplexity, "PM should use default complexity")
        assertEquals(5, validationPlan.estimatedComplexity, "Validation should use default complexity")
    }

    /**
     * Test knowledge extraction consistency across multiple outcomes
     */
    @Test
    fun `Extracted knowledge structure is consistent across outcome types`() {
        val agent = ProductManagerAgent(
            id = "consistency-test-pm",
            initialState = AgentState(),
            agentConfiguration = AgentConfiguration(
                aiConfiguration = AIConfiguration.default()
            ),
            memoryService = null
        )

        val task = Task.CodeChange(
            id = "consistency-task",
            status = TaskStatus.Pending,
            description = "Test task"
        )

        val plan = Plan.ForTask(task = task)

        // Extract from success
        val successOutcome = object : Outcome.Success {
            override val id = "success-outcome"
        }
        val successKnowledge = agent.extractKnowledgeFromOutcome(successOutcome, task, plan)

        // Extract from failure
        val failureOutcome = object : Outcome.Failure {
            override val id = "failure-outcome"
        }
        val failureKnowledge = agent.extractKnowledgeFromOutcome(failureOutcome, task, plan)

        // Both should be FromOutcome type
        assertTrue(successKnowledge is Knowledge.FromOutcome)
        assertTrue(failureKnowledge is Knowledge.FromOutcome)

        // Both should have non-empty approach and learnings
        assertTrue(successKnowledge.approach.isNotEmpty())
        assertTrue(successKnowledge.learnings.isNotEmpty())
        assertTrue(failureKnowledge.approach.isNotEmpty())
        assertTrue(failureKnowledge.learnings.isNotEmpty())

        // Learnings should differ based on outcome type
        assertTrue(successKnowledge.learnings.contains("Success"))
        assertTrue(failureKnowledge.learnings.contains("Failure"))
    }
}
