package link.socket.ampere.agents.execution

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
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

/**
 * AMPR-408: the earlier steps' results reach the parameter prompt, and survive the
 * request rebuild on the way to the tool.
 *
 * The prompt half is the bug: `ToolExecutionEngine` builds its parameter prompt from
 * the tool, the request and `request.context.instructions`, so whatever a strategy
 * cannot read off the request cannot reach the model that generates the parameters.
 * The rebuild half is the AMPR-351 / AMPR-414 problem one field over — a strategy
 * constructs a fresh request for its generated parameters, so the dispatch funnel
 * re-applies what the agent stamped.
 */
class ToolExecutionEnginePriorResultsTest {

    private val searchResult = StepOutcome.Success(
        id = "step-1",
        stepDescription = "search the notes",
        startTimestamp = Instant.fromEpochSeconds(0),
        endTimestamp = Instant.fromEpochSeconds(0),
        details = "found: the launch slipped to Thursday",
    )

    @Test
    fun `the parameter prompt renders what the earlier steps produced`() = runTest {
        val prompts = mutableListOf<String>()
        val engine = engineWith(prompts, renderingStrategy)

        engine.execute(recordingTool(mutableListOf()), request(priorResults = listOf(searchResult)))

        val prompt = prompts.single()
        assertContains(prompt, "search the notes")
        assertContains(prompt, "the launch slipped to Thursday")
    }

    @Test
    fun `a request with no prior results asks the question it always asked`() = runTest {
        val prompts = mutableListOf<String>()
        val engine = engineWith(prompts, renderingStrategy)

        engine.execute(recordingTool(mutableListOf()), request(priorResults = emptyList()))

        val prompt = prompts.single()
        assertFalse(
            prompt.contains("Results of the steps already executed"),
            "an empty chain adds nothing, so a plan's first step is unaffected",
        )
    }

    @Test
    fun `the results reach the tool through a strategy that rebuilds the request`() = runTest {
        val dispatched = mutableListOf<List<StepOutcome>>()
        val engine = engineWith(mutableListOf(), renderingStrategy)

        engine.execute(recordingTool(dispatched), request(priorResults = listOf(searchResult)))

        assertEquals(listOf(searchResult), dispatched.single())
    }

    /**
     * Mirrors the real strategies on both counts: it renders the prior results into
     * its prompt, and it returns a brand-new request carrying only what it chose to
     * copy.
     */
    private val renderingStrategy = object : ParameterStrategy {
        override fun buildPrompt(
            tool: Tool<*>,
            request: ExecutionRequest<*>,
            intent: String,
        ): String = listOf(
            "Generate parameters for ${tool.id}: $intent",
            priorResultsSection(request.priorResults),
        ).filter { it.isNotBlank() }.joinToString("\n\n")

        override fun parseAndEnrichRequest(
            jsonResponse: String,
            originalRequest: ExecutionRequest<*>,
        ): ExecutionRequest<*> = ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = originalRequest.context.executorId,
                ticket = originalRequest.context.ticket,
                task = originalRequest.context.task,
                instructions = "enriched: ${originalRequest.context.instructions}",
            ),
            constraints = originalRequest.constraints,
        )
    }

    private fun recordingTool(seen: MutableList<List<StepOutcome>>) =
        FunctionTool<ExecutionContext>(
            id = TOOL_ID,
            name = "Record Prior Results",
            description = "Records the prior results of the request it was dispatched with",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { executionRequest ->
                seen += executionRequest.priorResults
                val now = Clock.System.now()
                ExecutionOutcome.NoChanges.Success(
                    executorId = executionRequest.context.executorId,
                    ticketId = executionRequest.context.ticket.id,
                    taskId = executionRequest.context.task.id,
                    executionStartTimestamp = now,
                    executionEndTimestamp = now,
                    message = "recorded",
                )
            },
        )

    private fun engineWith(
        prompts: MutableList<String>,
        strategy: ParameterStrategy,
    ): ToolExecutionEngine {
        val executor = FunctionExecutor.create()
        val llmService = AgentLLMService(
            AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_5,
                ),
                llmProvider = { prompt ->
                    prompts += prompt
                    "{}"
                },
            ),
        )
        return ToolExecutionEngine(
            llmService = llmService,
            executor = executor,
            executorId = executor.id,
        ).also { engine -> engine.registerStrategy(TOOL_ID, strategy) }
    }

    private fun request(
        priorResults: List<StepOutcome>,
    ): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = "agent-1",
                ticket = Ticket(
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
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Summarise what the search found",
                ),
                instructions = "Summarise what the search found",
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
            priorResults = priorResults,
        )
    }

    private companion object {
        const val TOOL_ID = "record-prior-results"
    }
}
