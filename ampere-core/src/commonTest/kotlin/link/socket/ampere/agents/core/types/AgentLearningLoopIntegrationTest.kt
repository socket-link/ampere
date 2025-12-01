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

/**
 * Integration tests for the full agent learning loop.
 */
class AgentLearningLoopIntegrationTest {

    @Test
    fun `ProductManagerAgent learning loop - second task benefits from first`() = runBlocking {
        val agent = ProductManagerAgent(
            id = "integration-test-pm",
            initialState = AgentState(),
            agentConfiguration = testAgentConfiguration(),
            memoryService = null
        )

        // First task execution
        val firstTask = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Implement authentication feature"
        )

        val firstPlan = agent.determinePlanForTask(firstTask, relevantKnowledge = emptyList())
        val firstOutcome = testSuccessOutcome(id = "outcome-1")

        // Extract knowledge from first execution
        val extractedKnowledge = agent.extractKnowledgeFromOutcome(
            firstOutcome,
            firstTask,
            Plan.ForTask(
                task = firstTask,
                tasks = listOf(
                    Task.CodeChange(
                        id = "subtask-1",
                        status = TaskStatus.Pending,
                        description = "Define test specifications"
                    )
                )
            )
        )

        assertTrue(extractedKnowledge.approach.isNotEmpty())
        assertTrue(extractedKnowledge.learnings.contains("Success"))

        // Second task execution with recalled knowledge
        val secondTask = Task.CodeChange(
            id = "task-2",
            status = TaskStatus.Pending,
            description = "Implement authorization feature"
        )

        val recalledKnowledge = listOf(
            KnowledgeWithScore(
                entry = testKnowledgeEntry(
                    id = "k1",
                    approach = extractedKnowledge.approach,
                    learnings = extractedKnowledge.learnings,
                    outcomeId = firstOutcome.id,
                    tags = listOf("success"),
                    taskType = "code_change"
                ),
                knowledge = extractedKnowledge,
                relevanceScore = 0.9
            )
        )

        val secondPlan = agent.determinePlanForTask(secondTask, relevantKnowledge = recalledKnowledge)

        assertTrue(secondPlan.tasks.isNotEmpty())
    }

    @Test
    fun `Agents handle cold start with no knowledge`() = runBlocking {
        val pmAgent = ProductManagerAgent(
            id = "cold-start-pm",
            initialState = AgentState(),
            agentConfiguration = testAgentConfiguration(),
            memoryService = null
        )

        val validationAgent = ValidationAgent(
            id = "cold-start-validation",
            initialState = AgentState(),
            agentConfiguration = testAgentConfiguration(),
            memoryService = null
        )

        val task = Task.CodeChange(
            id = "cold-start-task",
            status = TaskStatus.Pending,
            description = "Implement new feature"
        )

        val pmPlan = pmAgent.determinePlanForTask(task, relevantKnowledge = emptyList())
        val validationPlan = validationAgent.determinePlanForTask(task, relevantKnowledge = emptyList())

        assertTrue(pmPlan.tasks.isNotEmpty(), "PM should create plan without knowledge")
        assertTrue(validationPlan.tasks.isNotEmpty(), "Validation should create plan without knowledge")
    }

    @Test
    fun `Extracted knowledge structure is consistent`() {
        val agent = ProductManagerAgent(
            id = "consistency-test-pm",
            initialState = AgentState(),
            agentConfiguration = testAgentConfiguration(),
            memoryService = null
        )

        val task = Task.CodeChange(
            id = "consistency-task",
            status = TaskStatus.Pending,
            description = "Test task"
        )

        val plan = Plan.ForTask(task = task)

        // Extract from success
        val successOutcome = testSuccessOutcome(id = "success-outcome")
        val successKnowledge = agent.extractKnowledgeFromOutcome(successOutcome, task, plan)

        // Extract from failure
        val failureOutcome = testFailureOutcome(id = "failure-outcome")
        val failureKnowledge = agent.extractKnowledgeFromOutcome(failureOutcome, task, plan)

        assertTrue(successKnowledge is Knowledge.FromOutcome)
        assertTrue(failureKnowledge is Knowledge.FromOutcome)
        assertTrue(successKnowledge.approach.isNotEmpty())
        assertTrue(failureKnowledge.approach.isNotEmpty())
        assertTrue(successKnowledge.learnings.contains("Success"))
        assertTrue(failureKnowledge.learnings.contains("Failure"))
    }
}
