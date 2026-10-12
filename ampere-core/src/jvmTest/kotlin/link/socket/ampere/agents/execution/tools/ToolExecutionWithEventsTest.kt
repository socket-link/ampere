package link.socket.ampere.agents.execution.tools

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.ToolExecutionEngine
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic

/**
 * End-to-end validation of the tool-event pair at the engine seam.
 *
 * [ToolExecutionEngine] is the only producer of `ToolExecutionStarted` and
 * `ToolExecutionCompleted` (AMPR-389, AMPR-420), so what a dispatch leaves in the event store
 * is what these tests read back:
 * - Events are emitted for tool invocation and completion
 * - Errors are captured and surfaced on the Completed event
 * - A tool with nothing to report completes its pair as a non-failure
 * - Each dispatch's pair is joined on its own invocation id
 * - An engine with no event API publishes nothing
 *
 * What the engine returns for each shape of tool result is `ToolExecutionEngineDispatchTest`
 * (commonTest). The same pair read back through the trace projection is
 * `ExecutePublishesItsStepsTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutionWithEventsTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val eventSerialBusFactory = EventSerialBusFactory(scope)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventRepository: EventRepository
    private lateinit var agentEventApi: AgentEventApi

    @BeforeTest
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)

        database = Database(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        val eventBus = eventSerialBusFactory.create()

        agentEventApi = AgentEventApi(
            agentId = "test-agent",
            eventRepository = eventRepository,
            eventSerialBus = eventBus,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private fun createTestTicket(): Ticket = Ticket(
        id = generateUUID(),
        title = "Test ticket",
        description = "Test ticket description",
        type = TicketType.TASK,
        priority = TicketPriority.MEDIUM,
        status = TicketStatus.Ready,
        assignedAgentId = null,
        createdByAgentId = "test-agent",
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun createTestTask(): Task.CodeChange = Task.CodeChange(
        id = generateUUID(),
        status = TaskStatus.Pending,
        description = "Test task",
    )

    private fun createTestRequest(runId: String? = null): ExecutionRequest<ExecutionContext.NoChanges> =
        ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = "test-agent",
                executorId = "test-executor",
                ticket = createTestTicket(),
                task = createTestTask(),
                instructions = "Test instructions",
            ),
            constraints = ExecutionConstraints(
                timeoutMinutes = 30,
                maxFilesChanged = 100,
                requireTests = false,
                requireLinting = false,
                allowBreakingChanges = false,
            ),
            runId = runId,
        )

    private fun createEngine(eventApi: AgentEventApi?): ToolExecutionEngine {
        val executor = FunctionExecutor.create()
        return ToolExecutionEngine(
            llmService = AgentLLMService(
                AgentConfiguration(
                    agentDefinition = WriteCodeAgent,
                    aiConfiguration = AIConfiguration_Default(
                        provider = AIProvider_Anthropic,
                        model = AIModel_Claude.Sonnet_5,
                    ),
                    llmProvider = { "{}" },
                ),
            ),
            executor = executor,
            actorId = "agent-1",
            eventApi = eventApi,
        )
    }

    private fun createSucceedingTool(id: String, name: String) = FunctionTool<ExecutionContext.NoChanges>(
        id = id,
        name = name,
        description = "A test tool",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            ExecutionOutcome.NoChanges.Success(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = Clock.System.now(),
                executionEndTimestamp = Clock.System.now(),
                message = "Success",
            )
        },
    )

    private fun createFailingTool(id: String, name: String, message: String) = FunctionTool<ExecutionContext.NoChanges>(
        id = id,
        name = name,
        description = "A tool that fails",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            ExecutionOutcome.NoChanges.Failure(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = Clock.System.now(),
                executionEndTimestamp = Clock.System.now(),
                message = message,
            )
        },
    )

    private fun createBlankTool(id: String, name: String) = FunctionTool<ExecutionContext.NoChanges>(
        id = id,
        name = name,
        description = "A tool with nothing to report",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { Outcome.blank },
    )

    /**
     * Test: the engine emits Started and Completed events
     *
     * Validates that when a tool is dispatched through an engine holding an
     * AgentEventApi, the correct events are emitted.
     */
    @Test
    fun testEngineEmitsEvents() = runTest {
        val tool = createSucceedingTool(id = "test-tool", name = "Test Tool")

        // Dispatch the tool through an engine WITH event API
        val outcome = createEngine(agentEventApi).execute(tool, createTestRequest(runId = "test-run"))

        // Verify the outcome
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)

        // Retrieve events from repository
        val events = eventRepository.getAllEvents().getOrNull()
        assertNotNull(events)

        // Find ToolEvent.ToolExecutionStarted
        val startedEvents = events.filterIsInstance<ToolEvent.ToolExecutionStarted>()
        assertEquals(1, startedEvents.size, "Should have exactly one Started event")

        val startedEvent = startedEvents.first()
        assertEquals("test-tool", startedEvent.toolId)
        assertEquals("Test Tool", startedEvent.toolName)
        assertEquals("test-run", startedEvent.runId)

        // Find ToolEvent.ToolExecutionCompleted
        val completedEvents = events.filterIsInstance<ToolEvent.ToolExecutionCompleted>()
        assertEquals(1, completedEvents.size, "Should have exactly one Completed event")

        val completedEvent = completedEvents.first()
        assertEquals("test-tool", completedEvent.toolId)
        assertEquals("Test Tool", completedEvent.toolName)
        assertEquals(true, completedEvent.success)
        assertEquals(null, completedEvent.errorMessage)
        assertTrue(completedEvent.durationMs >= 0, "Duration should be non-negative")
        assertEquals("test-run", completedEvent.runId)

        // Verify invocation IDs match
        assertEquals(startedEvent.invocationId, completedEvent.invocationId)
    }

    /**
     * Test: the engine emits Completed event with failure info on error
     *
     * Validates that when a tool fails, the Completed event contains
     * appropriate error information.
     */
    @Test
    fun testEngineEmitsFailureEvent() = runTest {
        val tool = createFailingTool(id = "failing-tool", name = "Failing Tool", message = "Intentional failure")

        val outcome = createEngine(agentEventApi).execute(tool, createTestRequest())

        // Verify the outcome is a failure
        assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)

        // Retrieve events from repository
        val events = eventRepository.getAllEvents().getOrNull()
        assertNotNull(events)

        // Find ToolEvent.ToolExecutionCompleted
        val completedEvents = events.filterIsInstance<ToolEvent.ToolExecutionCompleted>()
        assertEquals(1, completedEvents.size)

        val completedEvent = completedEvents.first()
        assertEquals("failing-tool", completedEvent.toolId)
        assertEquals(false, completedEvent.success)
        assertEquals("Intentional failure", completedEvent.errorMessage)
    }

    /**
     * Test: a blank outcome completes the pair as a non-failure
     *
     * Validates that a tool that ran and had nothing to report still leaves a Started and a
     * Completed event, and that the Completed one does not read as a failure: no error
     * message to go looking for, and not the medium urgency a failure gets.
     */
    @Test
    fun testEngineCompletesThePairForABlankOutcome() = runTest {
        val tool = createBlankTool(id = "blank-tool", name = "Blank Tool")

        val outcome = createEngine(agentEventApi).execute(tool, createTestRequest())

        assertIs<ExecutionOutcome.Blank>(outcome)

        val events = eventRepository.getAllEvents().getOrNull()
        assertNotNull(events)

        val startedEvents = events.filterIsInstance<ToolEvent.ToolExecutionStarted>()
        assertEquals(1, startedEvents.size, "A tool that ran has a Started event whatever it returned")

        val completedEvents = events.filterIsInstance<ToolEvent.ToolExecutionCompleted>()
        assertEquals(1, completedEvents.size, "and a Completed one, so the call is not left in flight")

        val completedEvent = completedEvents.first()
        assertEquals(startedEvents.first().invocationId, completedEvent.invocationId)
        assertEquals(true, completedEvent.success)
        assertEquals(null, completedEvent.errorMessage)
        assertEquals(Urgency.LOW, completedEvent.urgency)
    }

    /**
     * Test: each dispatch's pair is joined on its own invocation id
     *
     * Validates that two dispatches through one engine leave two pairs, each Completed event
     * matching the Started event of the same tool and no other. `ArcTraceProjection` joins on
     * the invocation id, so a pair that crossed would show one tool's duration and verdict
     * against another's call.
     */
    @Test
    fun testEngineJoinsEachDispatchOnItsOwnInvocationId() = runTest {
        val engine = createEngine(agentEventApi)

        engine.execute(createSucceedingTool(id = "tool-a", name = "Tool A"), createTestRequest())
        engine.execute(createFailingTool(id = "tool-b", name = "Tool B", message = "boom"), createTestRequest())

        val events = eventRepository.getAllEvents().getOrNull()
        assertNotNull(events)

        val startedEvents = events.filterIsInstance<ToolEvent.ToolExecutionStarted>()
        val completedEvents = events.filterIsInstance<ToolEvent.ToolExecutionCompleted>()
        assertEquals(2, startedEvents.size)
        assertEquals(2, completedEvents.size)
        assertEquals(
            2,
            startedEvents.map { it.invocationId }.toSet().size,
            "Each dispatch should mint its own invocation id",
        )

        startedEvents.forEach { started ->
            val completed = assertNotNull(
                completedEvents.firstOrNull { it.invocationId == started.invocationId },
                "Started event for ${started.toolId} has no Completed event on its invocation id",
            )
            assertEquals(started.toolId, completed.toolId, "A pair should never join two tools")
        }

        assertEquals(true, completedEvents.first { it.toolId == "tool-a" }.success)
        assertEquals(false, completedEvents.first { it.toolId == "tool-b" }.success)
    }

    /**
     * Test: the engine works without event API (backward compatibility)
     *
     * Validates that the engine still functions correctly when no
     * AgentEventApi is provided (events are simply not emitted).
     */
    @Test
    fun testEngineWithoutEventApi() = runTest {
        val tool = createSucceedingTool(id = "test-tool", name = "Test Tool")

        // Dispatch the tool through an engine WITHOUT event API - should work without errors
        val outcome = createEngine(eventApi = null).execute(tool, createTestRequest())

        // Verify the outcome
        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)

        // No events should be emitted (event repository should be empty or only have setup events).
        // The engine holds no reference to this repository, so this half is a tripwire for a
        // default event API appearing later rather than a check on what the engine does today.
        val events = eventRepository.getAllEvents().getOrNull()
        assertNotNull(events)

        val toolEvents = events.filterIsInstance<ToolEvent>().filter {
            it is ToolEvent.ToolExecutionStarted || it is ToolEvent.ToolExecutionCompleted
        }
        assertEquals(0, toolEvents.size, "Should have no tool execution events when no API provided")
    }
}
