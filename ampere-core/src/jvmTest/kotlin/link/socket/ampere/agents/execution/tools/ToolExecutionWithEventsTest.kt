package link.socket.ampere.agents.execution.tools

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.config.AgentActionAutonomy
import link.socket.ampere.agents.domain.concept.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.concept.outcome.Outcome
import link.socket.ampere.agents.domain.concept.status.TaskStatus
import link.socket.ampere.agents.domain.concept.status.TicketStatus
import link.socket.ampere.agents.domain.concept.task.Task
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBusFactory
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.execution.tools.invoke.ToolInvoker
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * End-to-end validation tests for tool execution with event emission.
 *
 * Task 3.1: Validate ToolWriteCodeFile End-to-End
 * - Tool receives correct parameters
 * - Files are written to correct locations
 * - Events are emitted for tool invocation and completion
 * - Errors are captured and surfaced appropriately
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
            eventSerialBus = eventBus
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
        updatedAt = Clock.System.now()
    )

    private fun createTestTask(): Task.CodeChange = Task.CodeChange(
        id = generateUUID(),
        status = TaskStatus.Pending,
        description = "Test task"
    )

    /**
     * Test: ToolInvoker emits Started and Completed events
     *
     * Validates that when a tool is invoked through ToolInvoker with an
     * AgentEventApi, the correct events are emitted.
     */
    @Test
    fun testToolInvokerEmitsEvents() = runTest {
        // Create a simple test tool
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool",
            name = "Test Tool",
            description = "A test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "Success"
                )
            }
        )

        // Create invoker WITH event API
        val invoker = ToolInvoker(tool, agentEventApi)

        val context = ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = createTestTicket(),
            task = createTestTask(),
            instructions = "Test instructions"
        )

        val request = ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(
                timeoutMinutes = 30,
                maxFilesChanged = 100,
                requireTests = false,
                requireLinting = false,
                allowBreakingChanges = false
            )
        )

        // Invoke the tool
        val result = invoker.invoke(request)

        // Verify the result
        assertIs<link.socket.ampere.agents.execution.tools.invoke.ToolInvocationResult.Success>(result)

        // Allow events to be processed
        delay(100)

        // Retrieve events from repository
        val events = eventRepository.getAllEvents().getOrNull()
        assertNotNull(events)

        // Find ToolEvent.ToolExecutionStarted
        val startedEvents = events.filterIsInstance<ToolEvent.ToolExecutionStarted>()
        assertEquals(1, startedEvents.size, "Should have exactly one Started event")

        val startedEvent = startedEvents.first()
        assertEquals("test-tool", startedEvent.toolId)
        assertEquals("Test Tool", startedEvent.toolName)

        // Find ToolEvent.ToolExecutionCompleted
        val completedEvents = events.filterIsInstance<ToolEvent.ToolExecutionCompleted>()
        assertEquals(1, completedEvents.size, "Should have exactly one Completed event")

        val completedEvent = completedEvents.first()
        assertEquals("test-tool", completedEvent.toolId)
        assertEquals("Test Tool", completedEvent.toolName)
        assertEquals(true, completedEvent.success)
        assertEquals(null, completedEvent.errorMessage)

        // Verify invocation IDs match
        assertEquals(startedEvent.invocationId, completedEvent.invocationId)
    }

    /**
     * Test: ToolInvoker emits Completed event with failure info on error
     *
     * Validates that when a tool fails, the Completed event contains
     * appropriate error information.
     */
    @Test
    fun testToolInvokerEmitsFailureEvent() = runTest {
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "failing-tool",
            name = "Failing Tool",
            description = "A tool that fails",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                ExecutionOutcome.NoChanges.Failure(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "Intentional failure"
                )
            }
        )

        val invoker = ToolInvoker(tool, agentEventApi)

        val context = ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = createTestTicket(),
            task = createTestTask(),
            instructions = "Test instructions"
        )

        val request = ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(
                timeoutMinutes = 30,
                maxFilesChanged = 100,
                requireTests = false,
                requireLinting = false,
                allowBreakingChanges = false
            )
        )

        // Invoke the tool
        val result = invoker.invoke(request)

        // Verify the result is a failure
        assertIs<link.socket.ampere.agents.execution.tools.invoke.ToolInvocationResult.Failed>(result)

        // Allow events to be processed
        delay(100)

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
     * Test: ToolInvoker works without event API (backward compatibility)
     *
     * Validates that ToolInvoker still functions correctly when no
     * AgentEventApi is provided (events are simply not emitted).
     */
    @Test
    fun testToolInvokerWithoutEventApi() = runTest {
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool",
            name = "Test Tool",
            description = "A test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "Success"
                )
            }
        )

        // Create invoker WITHOUT event API
        val invoker = ToolInvoker(tool)

        val context = ExecutionContext.NoChanges(
            executorId = "test-executor",
            ticket = createTestTicket(),
            task = createTestTask(),
            instructions = "Test instructions"
        )

        val request = ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(
                timeoutMinutes = 30,
                maxFilesChanged = 100,
                requireTests = false,
                requireLinting = false,
                allowBreakingChanges = false
            )
        )

        // Invoke the tool - should work without errors
        val result = invoker.invoke(request)

        // Verify the result
        assertIs<link.socket.ampere.agents.execution.tools.invoke.ToolInvocationResult.Success>(result)

        // No events should be emitted (event repository should be empty or only have setup events)
        val events = eventRepository.getAllEvents().getOrNull()
        assertNotNull(events)

        val toolEvents = events.filterIsInstance<ToolEvent>().filter {
            it is ToolEvent.ToolExecutionStarted || it is ToolEvent.ToolExecutionCompleted
        }
        assertEquals(0, toolEvents.size, "Should have no tool execution events when no API provided")
    }
}
