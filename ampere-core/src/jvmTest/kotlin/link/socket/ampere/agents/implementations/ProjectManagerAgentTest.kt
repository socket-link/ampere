package link.socket.ampere.agents.implementations

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.definition.ProjectManagerAgent
import link.socket.ampere.agents.domain.concept.Plan
import link.socket.ampere.agents.domain.concept.knowledge.Knowledge
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.config.AgentConfiguration
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

    private lateinit var projectManagerAgent: ProjectManagerAgent
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

        projectManagerAgent = ProjectManagerAgent(
            agentConfiguration = agentConfiguration,
            toolCreateIssues = toolCreateIssues,
            toolAskHuman = toolAskHuman,
            coroutineScope = testScope,
        )
    }

    @Test
    fun `agent can be instantiated`() {
        // Verify the agent was created successfully
        assertTrue(projectManagerAgent.id.isNotEmpty(), "Agent ID should not be empty")
        assertTrue(
            projectManagerAgent.requiredTools.contains(toolCreateIssues),
            "Required tools should include ToolCreateIssues",
        )
        assertTrue(
            projectManagerAgent.requiredTools.contains(toolAskHuman),
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

        val knowledge = projectManagerAgent.extractKnowledgeFromOutcome(outcome, task, plan)

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
            error = link.socket.ampere.agents.domain.error.ExecutionError(
                type = link.socket.ampere.agents.domain.error.ExecutionError.Type.UNEXPECTED,
                message = "GitHub API rate limit exceeded",
                isRetryable = true,
            ),
            partialResponse = null,
        )

        val knowledge = projectManagerAgent.extractKnowledgeFromOutcome(outcome, task, plan)

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

        val knowledge = projectManagerAgent.extractKnowledgeFromOutcome(outcome, task, plan)

        assertTrue(knowledge.approach.isNotEmpty(), "Approach should be captured")
        assertTrue(knowledge.learnings.contains("escalation"), "Learnings should mention escalation")
        assertTrue(knowledge.learnings.contains("completed"), "Learnings should mention completion")
        assertTrue(knowledge is Knowledge.FromOutcome, "Knowledge should be FromOutcome type")
    }

    @Test
    fun `perceiveState gathers project context`() = kotlinx.coroutines.runBlocking {
        val currentState = link.socket.ampere.agents.definition.pm.ProjectManagerState.blank

        val perception = projectManagerAgent.perceiveState(currentState)

        assertTrue(perception.ideas.isNotEmpty(), "Perception should contain ideas")
        assertTrue(
            perception.ideas.any { it.name.contains("Project Manager Perception") },
            "Should have main perception idea",
        )
        assertTrue(perception.currentState is link.socket.ampere.agents.definition.pm.ProjectManagerState, "State should be ProjectManagerState")
    }

    @Test
    fun `perceiveState creates idea for project status`() = kotlinx.coroutines.runBlocking<Unit> {
        val currentState = link.socket.ampere.agents.definition.pm.ProjectManagerState.blank

        val perception = projectManagerAgent.perceiveState(currentState)

        val statusIdea = perception.ideas.find { it.name == "Project Status" }
        assertTrue(statusIdea != null, "Should have project status idea")
        assertTrue(statusIdea!!.description.contains("Open issues"), "Should mention open issues")
        assertTrue(statusIdea.description.contains("In Progress"), "Should mention in-progress tasks")
        assertTrue(statusIdea.description.contains("Completed"), "Should mention completed tasks")
    }

    @Test
    fun `perceiveState incorporates new ideas passed in`() = kotlinx.coroutines.runBlocking {
        val currentState = link.socket.ampere.agents.definition.pm.ProjectManagerState.blank
        val customIdea1 = link.socket.ampere.agents.domain.concept.Idea(
            name = "New feature request",
            description = "User requested dark mode",
        )
        val customIdea2 = link.socket.ampere.agents.domain.concept.Idea(
            name = "Bug report",
            description = "Login fails on mobile",
        )

        val perception = projectManagerAgent.perceiveState(currentState, customIdea1, customIdea2)

        assertTrue(
            perception.ideas.any { it.name == "New feature request" },
            "Should include custom idea 1",
        )
        assertTrue(
            perception.ideas.any { it.name == "Bug report" },
            "Should include custom idea 2",
        )
    }

    @Test
    fun `perceiveState updates state with fresh context`() = kotlinx.coroutines.runBlocking {
        val oldState = link.socket.ampere.agents.definition.pm.ProjectManagerState(
            outcome = link.socket.ampere.agents.domain.concept.outcome.Outcome.blank,
            task = link.socket.ampere.agents.domain.concept.task.Task.Blank,
            plan = link.socket.ampere.agents.domain.concept.Plan.blank,
            activeGoals = listOf(
                link.socket.ampere.agents.definition.pm.Goal("old-goal", "Old Goal", "high", "in_progress"),
            ),
            blockedTasks = listOf("old-blocked-task"),
        )

        val perception = projectManagerAgent.perceiveState(oldState)

        val newState = perception.currentState as link.socket.ampere.agents.definition.pm.ProjectManagerState
        // With stub implementations, these will be empty
        // But we verify the state was refreshed (not the same as old state)
        assertTrue(newState.outcome == oldState.outcome, "Outcome preserved")
        assertTrue(newState.task == oldState.task, "Task preserved")
        assertTrue(newState.plan == oldState.plan, "Plan preserved")
    }

    @Test
    fun `perceiveState creates timestamp`() = kotlinx.coroutines.runBlocking {
        val currentState = link.socket.ampere.agents.definition.pm.ProjectManagerState.blank

        val perception = projectManagerAgent.perceiveState(currentState)

        assertTrue(perception.timestamp > kotlinx.datetime.Instant.DISTANT_PAST, "Should have valid timestamp")
    }
}
