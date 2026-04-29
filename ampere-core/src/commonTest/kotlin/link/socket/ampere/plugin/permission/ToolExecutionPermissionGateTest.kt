package link.socket.ampere.plugin.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.ToolExecutionEngine
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.plugin.PluginManifest

class ToolExecutionPermissionGateTest {

    @Test
    fun `blocked plugin tool does not call LLM provider`() = runTest {
        var llmCalls = 0
        val executor = FunctionExecutor.create()
        val permission = PluginPermission.NetworkDomain("api.example.com")
        val manifest = PluginManifest(
            id = "example-plugin",
            name = "Example Plugin",
            version = "1.0.0",
            requiredPermissions = listOf(permission),
        )
        val tool = FunctionTool<ExecutionContext>(
            id = "fetch-example",
            name = "Fetch Example",
            description = "Fetches example data",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            pluginManifest = manifest,
            executionFunction = {
                error("Blocked tool should not execute")
            },
        )
        val llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_4,
                ),
                llmProvider = {
                    llmCalls += 1
                    "{}"
                },
            ),
        )
        val engine = ToolExecutionEngine(
            llmService = llmService,
            executor = executor,
            executorId = executor.id,
        ).also { engine ->
            engine.registerStrategy(tool.id, passthroughStrategy)
        }

        val outcome = engine.execute(tool, createRequest(executor.id))

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertTrue(failure.message.contains("Permission denied"))
        assertEquals(0, llmCalls)
    }

    private val passthroughStrategy = object : ParameterStrategy {
        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String = "Generate parameters for ${tool.id}: $intent"

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> = originalRequest
    }

    private fun createRequest(executorId: String): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        val ticket = Ticket(
            id = "ticket-1",
            title = "Test ticket",
            description = "Test ticket description",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = "agent-1",
            createdByAgentId = "agent-1",
            createdAt = now,
            updatedAt = now,
            dueDate = null,
        )
        val task = Task.CodeChange(
            id = "task-1",
            status = TaskStatus.Pending,
            description = "Fetch data",
        )

        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = executorId,
                ticket = ticket,
                task = task,
                instructions = "Fetch example data",
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )
    }
}
