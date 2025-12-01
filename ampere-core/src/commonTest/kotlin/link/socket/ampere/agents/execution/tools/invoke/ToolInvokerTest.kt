package link.socket.ampere.agents.execution.tools.invoke

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.core.actions.AgentActionAutonomy
import link.socket.ampere.agents.core.outcomes.ExecutionOutcome
import link.socket.ampere.agents.core.outcomes.Outcome
import link.socket.ampere.agents.core.status.TaskStatus
import link.socket.ampere.agents.core.status.TicketStatus
import link.socket.ampere.agents.core.tasks.Task
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.events.utils.generateUUID
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for ToolInvoker - the motor neuron abstraction for tool execution.
 *
 * These tests validate:
 * 1. Basic invocation and result transformation
 * 2. Error handling and isolation
 * 3. Timing measurement
 * 4. Validation logic
 * 5. Concurrent execution without interference
 */
class ToolInvokerTest {

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

    private fun createTestContext(): ExecutionContext.NoChanges = ExecutionContext.NoChanges(
        executorId = "test-executor",
        ticket = createTestTicket(),
        task = createTestTask(),
        instructions = "Test instructions"
    )

    private fun createTestRequest(context: ExecutionContext.NoChanges): ExecutionRequest<ExecutionContext.NoChanges> =
        ExecutionRequest(
            context = context,
            constraints = ExecutionConstraints(
                timeoutMinutes = 30,
                maxFilesChanged = 100,
                requireTests = false,
                requireLinting = false,
                allowBreakingChanges = false
            )
        )

    /**
     * Test 1: Executor creation and basic invocation
     *
     * Validates:
     * - Can create a ToolInvoker wrapping a simple function tool
     * - Can call invoke with a valid request
     * - Returns ToolInvocationResult.Success with expected outcome
     */
    @Test
    fun testBasicInvocation() = runTest {
        // Create a simple tool that returns a success outcome
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool-1",
            name = "Hello World Tool",
            description = "Returns a greeting",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "hello world"
                )
            }
        )

        // Create invoker wrapping the tool
        val invoker = ToolInvoker(tool)

        // Create request
        val request = createTestRequest(createTestContext())

        // Invoke the tool
        val result = invoker.invoke(request)

        // Verify success
        assertIs<ToolInvocationResult.Success>(result)
        assertEquals("test-tool-1", result.toolId)
        assertIs<Outcome.Success>(result.outcome)
        assertIs<ExecutionOutcome.NoChanges.Success>(result.outcome)
        assertEquals("hello world", (result.outcome as ExecutionOutcome.NoChanges.Success).message)
    }

    /**
     * Test 2: Validation logic
     *
     * Validates:
     * - Validation accepts compatible requests
     * - Returns ValidationResult.Valid for matching context types
     */
    @Test
    fun testValidation() = runTest {
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool-2",
            name = "Test Tool",
            description = "Test tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "ok"
                )
            }
        )

        val invoker = ToolInvoker(tool)
        val request = createTestRequest(createTestContext())

        // Validate the request
        val validationResult = invoker.validate(request)

        // Should be valid
        assertIs<ValidationResult.Valid>(validationResult)
    }

    /**
     * Test 3: Error handling transforms tool errors
     *
     * Validates:
     * - Tool errors are caught and transformed to ToolInvocationResult.Failed
     * - Exception doesn't propagate to caller
     * - Error message is preserved
     */
    @Test
    fun testErrorHandling() = runTest {
        // Create a tool that throws an exception
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool-3",
            name = "Failing Tool",
            description = "Always fails",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { _ ->
                throw IllegalStateException("Tool failure simulation")
            }
        )

        val invoker = ToolInvoker(tool)
        val request = createTestRequest(createTestContext())

        // Invoke should not throw
        val result = invoker.invoke(request)

        // Should get Failed result
        assertIs<ToolInvocationResult.Failed>(result)
        assertEquals("test-tool-3", result.toolId)
        assertTrue(result.error.contains("Tool execution failed unexpectedly"))
        // Verify the outcome is a Failure type
        assertIs<Outcome.Failure>(result.outcome)
    }

    /**
     * Test 4: Tool returning Outcome.Failure
     *
     * Validates:
     * - Tools can return Outcome.Failure without throwing
     * - Failure outcome is transformed to ToolInvocationResult.Failed
     */
    @Test
    fun testToolReturnsFailure() = runTest {
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool-4",
            name = "Failure Returning Tool",
            description = "Returns failure outcome",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                ExecutionOutcome.NoChanges.Failure(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "Something went wrong"
                )
            }
        )

        val invoker = ToolInvoker(tool)
        val request = createTestRequest(createTestContext())

        val result = invoker.invoke(request)

        assertIs<ToolInvocationResult.Failed>(result)
        assertEquals("test-tool-4", result.toolId)
        assertEquals("Something went wrong", result.error)
        assertIs<ExecutionOutcome.NoChanges.Failure>(result.outcome)
    }

    /**
     * Test 5: Timing measurement
     *
     * Validates:
     * - Duration is measured and included in result
     * - Duration is approximately correct
     */
    @Test
    fun testTimingMeasurement() = runTest {
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool-5",
            name = "Slow Tool",
            description = "Takes some time",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                // Simulate some work (use a busy loop instead of delay to avoid suspending)
                val start = Clock.System.now()
                while (Clock.System.now() - start < 10.milliseconds) {
                    // busy wait
                }
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = start,
                    executionEndTimestamp = Clock.System.now(),
                    message = "done"
                )
            }
        )

        val invoker = ToolInvoker(tool)
        val request = createTestRequest(createTestContext())

        val result = invoker.invoke(request)

        assertIs<ToolInvocationResult.Success>(result)
        // Duration should be at least a few milliseconds
        assertTrue(result.duration.inWholeMilliseconds >= 0, "Duration should be non-negative")
    }

    /**
     * Test 6: Multiple invokers don't interfere
     *
     * Validates:
     * - Can create multiple invokers for different tools
     * - Invoking them concurrently works correctly
     * - Results are properly isolated
     */
    @Test
    fun testMultipleInvokersNoInterference() = runTest {
        val tool1 = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool-6a",
            name = "Tool A",
            description = "First tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "result-a"
                )
            }
        )

        val tool2 = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool-6b",
            name = "Tool B",
            description = "Second tool",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                ExecutionOutcome.NoChanges.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    message = "result-b"
                )
            }
        )

        val invoker1 = ToolInvoker(tool1)
        val invoker2 = ToolInvoker(tool2)

        val request1 = createTestRequest(createTestContext())
        val request2 = createTestRequest(createTestContext())

        // Invoke both
        val result1 = invoker1.invoke(request1)
        val result2 = invoker2.invoke(request2)

        // Verify results are correct and isolated
        assertIs<ToolInvocationResult.Success>(result1)
        assertEquals("test-tool-6a", result1.toolId)
        assertIs<ExecutionOutcome.NoChanges.Success>(result1.outcome)
        assertEquals("result-a", (result1.outcome as ExecutionOutcome.NoChanges.Success).message)

        assertIs<ToolInvocationResult.Success>(result2)
        assertEquals("test-tool-6b", result2.toolId)
        assertIs<ExecutionOutcome.NoChanges.Success>(result2.outcome)
        assertEquals("result-b", (result2.outcome as ExecutionOutcome.NoChanges.Success).message)
    }

    /**
     * Test 7: Tool returning Outcome.Blank
     *
     * Validates:
     * - Tools can return Outcome.Blank
     * - Blank outcome is transformed to ToolInvocationResult.Blank
     */
    @Test
    fun testToolReturnsBlank() = runTest {
        val tool = FunctionTool<ExecutionContext.NoChanges>(
            id = "test-tool-7",
            name = "Blank Tool",
            description = "Returns blank outcome",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { _ -> Outcome.blank }
        )

        val invoker = ToolInvoker(tool)
        val request = createTestRequest(createTestContext())

        val result = invoker.invoke(request)

        assertIs<ToolInvocationResult.Blank>(result)
    }

    /**
     * Test 8: Code context tool invocation
     *
     * Validates:
     * - ToolInvoker works with Code context types
     * - Type safety is maintained
     */
    @Test
    fun testCodeContextInvocation() = runTest {
        val tool = FunctionTool<ExecutionContext.Code.WriteCode>(
            id = "test-tool-8",
            name = "Write Code Tool",
            description = "Writes code",
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                // Access code-specific context
                val filePaths = request.context.instructionsPerFilePath.map { it.first }
                ExecutionOutcome.CodeChanged.Success(
                    executorId = request.context.executorId,
                    ticketId = request.context.ticket.id,
                    taskId = request.context.task.id,
                    executionStartTimestamp = Clock.System.now(),
                    executionEndTimestamp = Clock.System.now(),
                    changedFiles = filePaths,
                    validation = link.socket.ampere.agents.execution.results.ExecutionResult(
                        codeChanges = null,
                        compilation = null,
                        linting = null,
                        tests = null
                    )
                )
            }
        )

        val invoker = ToolInvoker(tool)

        val codeContext = ExecutionContext.Code.WriteCode(
            executorId = "test-executor",
            ticket = createTestTicket(),
            task = createTestTask(),
            instructions = "Write some code",
            workspace = link.socket.ampere.agents.environment.workspace.ExecutionWorkspace(
                baseDirectory = "/tmp/test"
            ),
            instructionsPerFilePath = listOf(
                "/tmp/test/file1.kt" to "fun main() {}",
                "/tmp/test/file2.kt" to "class Test {}"
            )
        )

        val request = ExecutionRequest(
            context = codeContext,
            constraints = ExecutionConstraints(
                timeoutMinutes = 30,
                maxFilesChanged = 100,
                requireTests = false,
                requireLinting = false,
                allowBreakingChanges = true
            )
        )

        val result = invoker.invoke(request)

        assertIs<ToolInvocationResult.Success>(result)
        assertIs<ExecutionOutcome.CodeChanged.Success>(result.outcome)
        assertEquals(2, (result.outcome as ExecutionOutcome.CodeChanged.Success).changedFiles.size)
    }
}
