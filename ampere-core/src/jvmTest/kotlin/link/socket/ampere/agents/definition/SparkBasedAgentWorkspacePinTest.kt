package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.expectation.Expectations
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.domain.arc.ArcAgentConfig
import link.socket.ampere.domain.arc.ArcAgentSpawner
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.domain.arc.ProjectContext
import link.socket.ampere.domain.arc.ProjectContextSource
import okio.Path.Companion.toPath

/**
 * AMPR-300: the workspace an agent is created with is the one every plan-step
 * request it dispatches carries, and the production factories cannot build an
 * agent without one.
 */
class SparkBasedAgentWorkspacePinTest {

    private val phaseSparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }
    private val pinned = ExecutionWorkspace(baseDirectory = "/srv/worktrees/AMPR-300")

    @Test
    fun `a plan-step request carries the agent's pinned workspace`() {
        val seen = CopyOnWriteArrayList<ExecutionRequest<*>>()
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "pinned-agent",
            tools = setOf(recordingTool(seen)),
            reasoningOverride = reasoningNominating("write_code_file", seen),
            workspace = pinned,
        )

        agent.runLLMToExecuteTask(parentTask())

        assertEquals(pinned, seen.single().workspace)
        assertEquals(pinned, agent.workspace)
    }

    @Test
    fun `an agent built without a workspace dispatches an unpinned request rather than inventing one`() {
        val seen = CopyOnWriteArrayList<ExecutionRequest<*>>()
        val agent = SparkBasedAgent.Code(
            sparkRegistry = phaseSparkLibrary,
            agentId = "unpinned-agent",
            tools = setOf(recordingTool(seen)),
            reasoningOverride = reasoningNominating("write_code_file", seen),
        )

        agent.runLLMToExecuteTask(parentTask())

        assertNull(seen.single().workspace)
        assertNull(agent.workspace)
    }

    @Test
    fun `SparkAgentFactory pins every agent it creates to its workspace`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val agent = SparkAgentFactory(scope = scope, workspace = pinned)
                .createAgent(id = "factory-agent", affinity = CognitiveAffinity.ANALYTICAL)

            assertEquals(pinned, agent.workspace)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `Arc agents are pinned to the factory's workspace regardless of role`() {
        val projectRoot = createTempDirectory("ampr300-arc").toString().toPath()
        val projectContext = ProjectContext(
            projectId = "demo",
            description = "Demo project",
            repositoryRoot = projectRoot,
            architecture = "Layered",
            conventions = "Use Kotlin",
            techStack = listOf("Kotlin"),
            sources = listOf(ProjectContextSource(projectRoot / "README.md", "Demo")),
        )
        val arc = ArcConfig(
            name = "demo-arc",
            agents = listOf(ArcAgentConfig(role = "pm"), ArcAgentConfig(role = "code")),
        )
        val scope = CoroutineScope(SupervisorJob())
        try {
            val agents = ArcAgentSpawner(SparkAgentFactory(scope = scope, workspace = pinned))
                .spawn(arc, projectContext)

            assertEquals(2, agents.size)
            agents.forEach { assertEquals(pinned, it.workspace) }
        } finally {
            scope.cancel()
        }
    }

    private fun parentTask(): Task.CodeChange =
        Task.CodeChange(id = "parent-task", status = TaskStatus.Pending, description = "do the work")

    private fun recordingTool(seen: MutableList<ExecutionRequest<*>>): FunctionTool<ExecutionContext.NoChanges> =
        FunctionTool(
            id = "write_code_file",
            name = "Recording write_code_file",
            description = "records the request it was dispatched with",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                seen += request
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "recorded",
                )
            },
        )

    /** Mock reasoning that plans one step nominating [toolId] and records the request handed to it. */
    private fun reasoningNominating(toolId: String, seen: MutableList<ExecutionRequest<*>>): AgentReasoning {
        val plan = Plan.ForTask(
            task = parentTask(),
            tasks = listOf(
                Task.CodeChange(
                    id = "step-1-parent-task",
                    status = TaskStatus.Pending,
                    description = "write the file",
                    toolId = toolId,
                ),
            ),
            estimatedComplexity = 1,
            expectations = Expectations.blank,
        )
        return AgentReasoning.createForTesting(executorId = "pin-test") {
            onPlanning { _, _ -> plan }
            onToolExecution { _, request ->
                seen += request
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "recorded",
                )
            }
        }
    }
}
