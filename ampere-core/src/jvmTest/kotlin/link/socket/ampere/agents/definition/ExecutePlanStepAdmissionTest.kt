package link.socket.ampere.agents.definition

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.event.PermissionDeniedEvent
import link.socket.ampere.agents.domain.event.PermissionDeniedReason
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.ParameterStrategy
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.llm.LlmProvider
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.UserGrants

/**
 * AMPR-440 (F16): every plan step is admitted by the Breaker before its tool runs.
 *
 * Drives `SparkBasedAgent.executePlanStep` — the one step function both the Arc path and a
 * roster-hosted run go through — with a Plug-backed tool. The tool ships a
 * [ParameterStrategy], so a step that reaches the engine costs one model call; a step the
 * Breaker trips must cost none, publish no tool bracket, and leave exactly one
 * `PermissionDeniedEvent` on the record, shaped as the engine has always shaped it.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual
 * clock walks straight past it.
 */
class ExecutePlanStepAdmissionTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    /** Every prompt that reached the (fake) model, in order. */
    private val prompts = CopyOnWriteArrayList<String>()

    /** Every request the Plug-backed tool's own function was dispatched with. */
    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `a step missing its grant is tripped before the engine and the denial is published once`() =
        runBlocking<Unit> {
            val agent = agent(grants = null)

            val result = agent.executePlanStep(STEP, parentTask = STEP, priorResults = emptyList())

            val failure = assertIs<StepResult.Failure>(result)
            assertTrue(failure.isCritical, "a tripped step stops the plan")
            assertTrue(failure.error.contains("MISSING_GRANT"), "the reason is on the step; got '${failure.error}'")
            assertTrue(failure.error.contains(PERMISSION.toString()), "naming the permission; got '${failure.error}'")

            assertTrue(dispatched.isEmpty(), "the tool's own function never ran")
            assertTrue(prompts.isEmpty(), "no parameter-strategy call was made; got $prompts")
            assertTrue(
                toolStarts().isEmpty(),
                "no ToolExecutionStarted: the engine was never entered",
            )

            val denied = permissionDenials().single()
            assertEquals(MANIFEST.id.value, denied.plugId)
            assertEquals(plugTool.id, denied.toolId)
            assertEquals(plugTool.name, denied.toolName)
            assertEquals(PERMISSION, denied.permission)
            assertEquals(PermissionDeniedReason.MISSING_GRANT, denied.reason)
            assertEquals(Urgency.HIGH, denied.urgency)
            assertEquals(AGENT_ID, denied.eventSource.getIdentifier())
        }

    /**
     * The Breaker's denial and the engine's are one construction: the same tool refused
     * through the engine directly — the path that never goes through `executePlanStep` —
     * leaves an event equal to the Breaker's in every field but its id and its time.
     */
    @Test
    fun `the Breaker's denial carries the fields the engine's own denial carries`() = runBlocking<Unit> {
        val agent = agent(grants = { UserGrants.revoked(PERMISSION) })

        agent.executePlanStep(STEP, parentTask = STEP, priorResults = emptyList())
        val viaBreaker = permissionDenials().single()

        val engineOutcome = agent.runLLMToExecuteTool(plugTool, directRequest())
        assertIs<ExecutionOutcome.NoChanges.Failure>(engineOutcome)
        val viaEngine = permissionDenials().single { it.eventId != viaBreaker.eventId }

        assertEquals(PermissionDeniedReason.REVOKED_GRANT, viaBreaker.reason)
        assertEquals(
            viaEngine.copy(eventId = viaBreaker.eventId, timestamp = viaBreaker.timestamp),
            viaBreaker,
        )
    }

    @Test
    fun `a step whose grant is present runs as it did before`() = runBlocking<Unit> {
        val agent = agent(grants = { UserGrants.granted(PERMISSION) })

        val result = agent.executePlanStep(STEP, parentTask = STEP, priorResults = emptyList())

        val success = assertIs<StepResult.Success>(result, "got $result")
        assertTrue(success.details.orEmpty().contains(TOOL_RESULT), "got '${success.details}'")
        assertEquals(1, dispatched.size, "the tool's own function ran once")
        assertEquals(1, prompts.size, "and its parameter strategy made its one model call")
        assertEquals(1, toolStarts().size, "inside the engine's bracket")
        assertTrue(permissionDenials().isEmpty(), "nothing was denied")
    }

    @Test
    fun `a grant provider that throws fails the step closed`() = runBlocking<Unit> {
        val agent = agent(grants = { error("grant store unavailable") })

        val result = agent.executePlanStep(STEP, parentTask = STEP, priorResults = emptyList())

        val failure = assertIs<StepResult.Failure>(result)
        assertTrue(failure.isCritical)
        assertTrue(failure.error.contains("not admitted"), "got '${failure.error}'")
        assertTrue(failure.error.contains("grant store unavailable"), "got '${failure.error}'")
        assertTrue(dispatched.isEmpty(), "an undecided admission is not a pass")
        assertTrue(prompts.isEmpty())
        assertTrue(permissionDenials().isEmpty(), "nothing was decided, so nothing was denied")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun agent(grants: (suspend (PlugManifest) -> UserGrants)?): SparkBasedAgent<CodeState> {
        val provider: LlmProvider = { prompt ->
            prompts += prompt
            "{}"
        }
        return SparkBasedAgent(
            agentId = AGENT_ID,
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _additionalTools = setOf(plugTool),
            _eventApi = door.api,
            _aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_5,
            ),
            _llmProvider = provider,
            _observabilityScope = scope,
            _executor = FunctionExecutor.create(),
            _userGrantProvider = grants,
        )
    }

    /** The tool [MANIFEST] guards. Records what it was dispatched with into [dispatched]. */
    private val plugTool: Tool<*> = FunctionTool<ExecutionContext.NoChanges>(
        id = "notify",
        name = "Notify",
        description = "sends a notification through a Plug",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        plugManifest = MANIFEST,
        parameterStrategy = PASSTHROUGH_STRATEGY,
        executionFunction = { request ->
            dispatched += request
            val now = Clock.System.now()
            ExecutionOutcome.NoChanges.Success(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = TOOL_RESULT,
            )
        },
    )

    private suspend fun permissionDenials(): List<PermissionDeniedEvent> =
        door.repository.getAllEvents().getOrThrow().filterIsInstance<PermissionDeniedEvent>()

    private suspend fun toolStarts(): List<ToolEvent.ToolExecutionStarted> =
        door.repository.getAllEvents().getOrThrow().filterIsInstance<ToolEvent.ToolExecutionStarted>()

    /** A request for the engine directly, the way a caller outside a plan dispatches a tool. */
    private fun directRequest(): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        val ticket = Ticket(
            id = "ticket-1",
            title = "Notify the owner",
            description = "Notify the owner",
            type = TicketType.TASK,
            priority = TicketPriority.MEDIUM,
            status = TicketStatus.InProgress,
            assignedAgentId = AGENT_ID,
            createdByAgentId = AGENT_ID,
            createdAt = now,
            updatedAt = now,
            dueDate = null,
        )
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = AGENT_ID,
                executorId = "test-executor",
                ticket = ticket,
                task = STEP,
                instructions = STEP.description,
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
        )
    }

    private companion object {
        const val AGENT_ID = "admission-test-agent"
        const val TOOL_RESULT = "notified the owner"

        val PERMISSION: PlugPermission = PlugPermission.NativeAction("send-notification")
        val MANIFEST = PlugManifest(
            id = PlugId("example-notifier"),
            name = "Example Notifier",
            version = "1.0.0",
            requiredPermissions = listOf(PERMISSION),
        )

        val STEP = Task.CodeChange(
            id = "step-1",
            status = TaskStatus.Pending,
            description = "Notify the owner",
            toolId = "notify",
        )

        /** Leaves the request as it was: the strategy is here to cost a model call, not to shape one. */
        val PASSTHROUGH_STRATEGY = object : ParameterStrategy {
            override fun buildPrompt(tool: Tool<*>, request: ExecutionRequest<*>, intent: String): String =
                "Generate parameters for ${tool.id}: $intent"

            override fun parseAndEnrichRequest(
                jsonResponse: String,
                originalRequest: ExecutionRequest<*>,
            ): ExecutionRequest<*> = originalRequest
        }
    }
}
