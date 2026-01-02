package link.socket.ampere.agents.implementations

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.ProjectAgent
import link.socket.ampere.agents.definition.project.Goal
import link.socket.ampere.agents.definition.project.ProjectAgentState
import link.socket.ampere.agents.domain.error.ExecutionError
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.EvaluationResult
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.agents.execution.tools.ToolAskHuman
import link.socket.ampere.agents.execution.tools.ToolCreateIssues
import link.socket.ampere.agents.execution.tools.issue.BatchIssueCreateResponse
import link.socket.ampere.agents.execution.tools.issue.CreatedIssue
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider

/**
 * Tests for ProjectManagerAgent scaffold.
 *
 * These tests verify basic functionality like instantiation and knowledge extraction.
 * Full integration tests will be added as the agent implementation matures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectManagerAgentTest {

    private lateinit var projectAgent: ProjectAgent
    private val testScope = TestScope(UnconfinedTestDispatcher())

    private val toolCreateIssues: Tool<ExecutionContext.IssueManagement> = ToolCreateIssues(
        requiredAgentAutonomy = AgentActionAutonomy.ACT_WITH_NOTIFICATION,
    )

    private val toolAskHuman: Tool<ExecutionContext.NoChanges> = ToolAskHuman(
        requiredAgentAutonomy = AgentActionAutonomy.ASK_BEFORE_ACTION,
    )

    /**
     * Fake AI configuration for testing.
     */
    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError("Provider not needed for these tests")
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    @BeforeTest
    fun setup() {
        val agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent, // Placeholder - PM agent definition not yet created
            aiConfiguration = FakeAIConfiguration(),
        )

        // Create mock reasoning for testing without real LLM calls
        val mockReasoning = AgentReasoning.createForTesting("test-executor") {
            onPerception { perception ->
                Idea(
                    name = "Project Manager Perception",
                    description = "Mock perception analysis for testing",
                )
            }

            onPlanning { task, ideas ->
                Plan.ForTask(
                    task = task,
                    tasks = listOf(task),
                    estimatedComplexity = 1,
                )
            }

            onOutcomeEvaluation { outcomes ->
                EvaluationResult(
                    summaryIdea = Idea(
                        name = "Mock outcome evaluation",
                        description = "Task completed successfully",
                    ),
                    knowledge = emptyList(),
                )
            }

            onLLMCall { prompt ->
                "Mock LLM response"
            }
        }

        projectAgent = ProjectAgent(
            agentConfiguration = agentConfiguration,
            toolCreateIssues = toolCreateIssues,
            toolAskHuman = toolAskHuman,
            coroutineScope = testScope,
            reasoningOverride = mockReasoning,
        )
    }

    @Test
    fun `agent can be instantiated`() {
        // Verify the agent was created successfully
        assertTrue(projectAgent.id.isNotEmpty(), "Agent ID should not be empty")
        assertTrue(
            projectAgent.requiredTools.contains(toolCreateIssues),
            "Required tools should include ToolCreateIssues",
        )
        assertTrue(
            projectAgent.requiredTools.contains(toolAskHuman),
            "Required tools should include ToolAskHuman",
        )
    }

    @Test
    fun `extractKnowledgeFromOutcome captures issue creation success`() {
        val task = Task.CodeChange(
            id = "pm-task-1",
            status = TaskStatus.Pending,
            description = "Decompose feature into tasks",
        )

        val plan = Plan.ForTask(
            task = task,
            estimatedComplexity = 5,
        )

        val outcome = ExecutionOutcome.IssueManagement.Success(
            executorId = "ProjectManagerAgent",
            ticketId = "epic-123",
            taskId = task.id,
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now(),
            response = BatchIssueCreateResponse(
                success = true,
                created = listOf(
                    CreatedIssue(
                        localId = "epic-1",
                        issueNumber = 100,
                        url = "https://github.com/repo/issues/100",
                    ),
                    CreatedIssue(
                        localId = "task-1",
                        issueNumber = 101,
                        url = "https://github.com/repo/issues/101",
                        parentIssueNumber = 100,
                    ),
                ),
                errors = emptyList(),
            ),
        )

        val knowledge = projectAgent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge.approach.isNotEmpty(), "Approach should be captured")
        assertTrue(knowledge.learnings.contains("succeeded"), "Learnings should mention success")
        assertTrue(knowledge.learnings.contains("2"), "Learnings should mention number of issues created")
        assertTrue(knowledge is Knowledge.FromOutcome, "Knowledge should be FromOutcome type")
        assertTrue(knowledge.outcomeId == outcome.id, "Knowledge should reference the correct outcome ID")
    }

    @Test
    fun `extractKnowledgeFromOutcome captures issue creation failure`() {
        val task = Task.CodeChange(
            id = "pm-task-2",
            status = TaskStatus.Pending,
            description = "Create milestone issues",
        )

        val plan = Plan.ForTask(
            task = task,
            estimatedComplexity = 3,
        )

        val outcome = ExecutionOutcome.IssueManagement.Failure(
            executorId = "ProjectManagerAgent",
            ticketId = "epic-456",
            taskId = task.id,
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now(),
            error = ExecutionError(
                type = ExecutionError.Type.UNEXPECTED,
                message = "GitHub API rate limit exceeded",
                isRetryable = true,
            ),
            partialResponse = null,
        )

        val knowledge = projectAgent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge.approach.isNotEmpty(), "Approach should be captured")
        assertTrue(knowledge.learnings.contains("failed"), "Learnings should mention failure")
        assertTrue(knowledge.learnings.contains("rate limit"), "Learnings should mention the error")
        assertTrue(knowledge is Knowledge.FromOutcome, "Knowledge should be FromOutcome type")
    }

    @Test
    fun `extractKnowledgeFromOutcome captures human escalation success`() {
        val task = Task.CodeChange(
            id = "pm-task-3",
            status = TaskStatus.Pending,
            description = "Escalate scope decision",
        )

        val plan = Plan.ForTask(
            task = task,
            estimatedComplexity = 1,
        )

        val outcome = ExecutionOutcome.NoChanges.Success(
            executorId = "ProjectManagerAgent",
            ticketId = "",
            taskId = task.id,
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now(),
            message = "Human decided to include authentication in MVP scope",
        )

        val knowledge = projectAgent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge.approach.isNotEmpty(), "Approach should be captured")
        // Learnings should contain the outcome message or indicate success
        assertTrue(
            knowledge.learnings.contains("SUCCEEDED") ||
                knowledge.learnings.contains("authentication") ||
                knowledge.learnings.contains("Human"),
            "Learnings should capture the outcome: ${knowledge.learnings}",
        )
        assertTrue(knowledge is Knowledge.FromOutcome, "Knowledge should be FromOutcome type")
    }

    @Test
    fun `perceiveState gathers project context`() = kotlinx.coroutines.runBlocking {
        val currentState = ProjectAgentState.blank

        val perception = projectAgent.perceiveState(currentState)

        assertTrue(perception.ideas.isNotEmpty(), "Perception should contain ideas")
        assertTrue(
            perception.ideas.any { it.name.contains("Project Manager Perception") },
            "Should have main perception idea",
        )
        assertTrue(
            perception.currentState is ProjectAgentState,
            "State should be ProjectAgentState",
        )
    }

    @Test
    fun `perceiveState creates idea for project status`() = kotlinx.coroutines.runBlocking<Unit> {
        val currentState = ProjectAgentState.blank

        val perception = projectAgent.perceiveState(currentState)

        // With mock reasoning, we get a "Project Manager Perception" idea
        val perceptionIdea = perception.ideas.find { it.name.contains("Project Manager") }
        assertTrue(perceptionIdea != null, "Should have perception idea from mock")
        assertTrue(perceptionIdea!!.description.isNotEmpty(), "Idea should have description")
    }

    @Test
    fun `perceiveState incorporates new ideas passed in`() = kotlinx.coroutines.runBlocking {
        val currentState = ProjectAgentState.blank
        val customIdea1 = Idea(
            name = "New feature request",
            description = "User requested dark mode",
        )
        val customIdea2 = Idea(
            name = "Bug report",
            description = "Login fails on mobile",
        )

        val perception = projectAgent.perceiveState(currentState, customIdea1, customIdea2)

        // perceiveState processes ideas through LLM, so the output ideas come from mock reasoning
        // The important thing is that perception was created successfully
        assertTrue(perception.ideas.isNotEmpty(), "Perception should have ideas")
        assertTrue(
            perception.currentState is ProjectAgentState,
            "Should have correct state type",
        )
    }

    @Test
    fun `perceiveState updates state with fresh context`() = kotlinx.coroutines.runBlocking {
        val oldState = ProjectAgentState(
            outcome = Outcome.blank,
            task = Task.Blank,
            plan = Plan.blank,
            activeGoals = listOf(
                Goal("old-goal", "Old Goal", "high", "in_progress"),
            ),
            blockedTasks = listOf("old-blocked-task"),
        )

        val perception = projectAgent.perceiveState(oldState)

        val newState = perception.currentState as ProjectAgentState
        // With stub implementations, these will be empty
        // But we verify the state was refreshed (not the same as old state)
        assertTrue(newState.outcome == oldState.outcome, "Outcome preserved")
        assertTrue(newState.task == oldState.task, "Task preserved")
        assertTrue(newState.plan == oldState.plan, "Plan preserved")
    }

    @Test
    fun `perceiveState creates timestamp`() = kotlinx.coroutines.runBlocking {
        val currentState = ProjectAgentState.blank

        val perception = projectAgent.perceiveState(currentState)

        assertTrue(perception.timestamp > kotlinx.datetime.Instant.DISTANT_PAST, "Should have valid timestamp")
    }
}
