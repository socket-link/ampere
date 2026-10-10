package link.socket.ampere.agents.domain.reasoning

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
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
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.trace.ArcRunTrace
import link.socket.ampere.trace.ArcTraceProjection
import link.socket.ampere.trace.PropelPhase

/**
 * AMPR-389: a run's trace shows each plan step and each tool execution as a started/completed
 * pair under the run id, and every model call Execute makes is filed under EXECUTE.
 *
 * Driven through the production wiring — `AgentReasoning.create` builds the door-aware
 * [PlanExecutor] and the [ToolExecutionEngine][link.socket.ampere.agents.execution.ToolExecutionEngine]
 * — so what the test asserts is what `SparkBasedAgent.executePlanStep` produces, not what a
 * hand-built executor could be made to produce.
 *
 * `runBlocking`, not `runTest`: the door's persist hops to an IO dispatcher, and a virtual
 * clock would walk past it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExecutePublishesItsStepsTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private lateinit var door: InMemoryEventDoor.Handle

    @BeforeTest
    fun setUp() {
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        door.close()
    }

    @Test
    fun `a step that ran a tool leaves both pairs and the parameter call under EXECUTE`() {
        val runId = "run-execute-1"
        val reasoning = reasoningWith(runId)
        val plan = planOf(step("step-1", "Write the greeting"))

        val result = runBlocking {
            reasoning.executePlan(plan, priorResults = emptyList()) { step, _ ->
                when (val outcome = reasoning.executeTool(greetingTool, requestFor(step))) {
                    is ExecutionOutcome.Success -> StepResult.success(
                        description = "ran ${greetingTool.id}",
                        details = outcome::class.simpleName,
                    )
                    else -> StepResult.failure(
                        description = "ran ${greetingTool.id}",
                        error = "unexpected $outcome",
                        isCritical = true,
                    )
                }
            }
        }
        assertTrue(result.isSuccess, "the step succeeded, so the plan did")

        val execute = assertNotNull(
            trace(runId).phases.singleOrNull { it.name == CognitivePhase.EXECUTE.name },
            "everything Execute did is filed under EXECUTE",
        )

        // The engine's pair, joined by the projection into a ToolCallTrace.
        val toolCall = assertNotNull(
            execute.toolCalls.singleOrNull(),
            "ArcRunTrace.toolCalls is populated for a run that executed a tool",
        )
        assertEquals(greetingTool.id, toolCall.toolId)
        assertEquals(greetingTool.name, toolCall.toolName)
        assertEquals(CognitivePhase.EXECUTE.name, toolCall.phaseName)
        assertEquals(true, toolCall.success)
        assertNull(toolCall.errorMessage)
        assertNotNull(toolCall.endedAt, "a completed call is not left pending")
        assertTrue(toolCall.durationMs!! >= 0)

        // Both halves are real rows carrying the run, not just a projection artifact.
        val started = execute.decode<ToolEvent.ToolExecutionStarted>()
        val completed = execute.decode<ToolEvent.ToolExecutionCompleted>()
        assertEquals(toolCall.invocationId, started.single().invocationId)
        assertEquals(toolCall.invocationId, completed.single().invocationId)
        assertEquals(runId, started.single().runId)
        assertEquals(runId, completed.single().runId)

        // The plan-step pair, with the plan id and the run id.
        val stepStarted = execute.decode<PlanEvent.PlanStepStarted>().single()
        val stepCompleted = execute.decode<PlanEvent.PlanStepCompleted>().single()
        assertEquals(plan.id, stepStarted.planId)
        assertEquals(plan.id, stepCompleted.planId)
        assertEquals("step-1", stepStarted.stepId)
        assertEquals("step-1", stepCompleted.stepId)
        assertEquals("Write the greeting", stepStarted.stepDescription)
        assertEquals(0, stepStarted.stepIndex)
        assertEquals(1, stepStarted.totalSteps)
        assertEquals(runId, stepStarted.runId)
        assertEquals(runId, stepCompleted.runId)
        assertTrue(stepCompleted.outcome is StepOutcome.Success)
        assertTrue(
            stepStarted.timestamp <= started.single().timestamp,
            "the step opens before the tool it dispatches",
        )

        // The parameter-strategy call is a model call Execute made, so it is filed as one.
        val invocation = assertNotNull(
            execute.modelInvocations.singleOrNull(),
            "the parameter-strategy call no longer lands in the UNKNOWN bucket",
        )
        assertEquals(CognitivePhase.EXECUTE.name, invocation.phaseName)
        assertEquals(AGENT_ID, invocation.agentId)
        assertEquals(1, generatedPrompts.size, "exactly one parameter call per dispatch")
    }

    @Test
    fun `a failing tool completes the pair with the failure message`() {
        val runId = "run-execute-2"
        val reasoning = reasoningWith(runId)

        runBlocking {
            val plan = planOf(step("step-1", "Fail on purpose"))
            reasoning.executePlan(plan, priorResults = emptyList()) { stepTask, _ ->
                reasoning.executeTool(failingTool, requestFor(stepTask))
                StepResult.failure(
                    description = "ran ${failingTool.id}",
                    error = "tool failed",
                    isCritical = true,
                )
            }
        }

        val execute = assertNotNull(trace(runId).phases.singleOrNull { it.name == CognitivePhase.EXECUTE.name })
        val toolCall = assertNotNull(execute.toolCalls.singleOrNull())
        assertEquals(false, toolCall.success)
        assertEquals(FAILURE_MESSAGE, toolCall.errorMessage)

        val stepCompleted = execute.decode<PlanEvent.PlanStepCompleted>().single()
        assertTrue(stepCompleted.outcome is StepOutcome.Failure)
    }

    @Test
    fun `a step skipped after a critical failure opens no pair`() {
        val runId = "run-execute-3"
        val reasoning = reasoningWith(runId)
        val plan = planOf(step("step-1", "Fail on purpose"), step("step-2", "Never reached"))

        val result = runBlocking {
            reasoning.executePlan(plan, priorResults = emptyList()) { stepTask, _ ->
                reasoning.executeTool(failingTool, requestFor(stepTask))
                StepResult.failure(
                    description = "ran ${failingTool.id}",
                    error = "tool failed",
                    isCritical = true,
                )
            }
        }

        assertEquals(
            listOf("step-1", "step-2"),
            result.stepOutcomes.map { it.id },
            "the skipped step is still reported in the result",
        )

        val execute = assertNotNull(trace(runId).phases.singleOrNull { it.name == CognitivePhase.EXECUTE.name })
        assertEquals(
            listOf("step-1"),
            execute.decode<PlanEvent.PlanStepStarted>().map { it.stepId },
            "a step that never started is not announced as started",
        )
        assertEquals(
            listOf("step-1"),
            execute.decode<PlanEvent.PlanStepCompleted>().map { it.stepId },
        )
    }

    @Test
    fun `a doorless reasoning unit stays silent`() {
        val reasoning = AgentReasoning.create(
            config = configuration(),
            executorId = AGENT_ID,
            eventApi = null,
            runId = "run-execute-4",
        ) {
            agentRole = "Test Agent"
            executor = FunctionExecutor.create()
            availableTools = { setOf(greetingTool) }
            execution { registerStrategy(greetingTool.id, passthroughStrategy) }
        }

        runBlocking {
            val plan = planOf(step("step-1", "Write the greeting"))
            reasoning.executePlan(plan, priorResults = emptyList()) { stepTask, _ ->
                reasoning.executeTool(greetingTool, requestFor(stepTask))
                StepResult.success(description = "ran")
            }
        }

        assertEquals(1, generatedPrompts.size, "the parameter call still happened")
        val stored = runBlocking { door.repository.getAllEvents() }.getOrThrow()
        assertTrue(stored.isEmpty(), "no door, no events — as before AMPR-389")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private val generatedPrompts = mutableListOf<String>()

    private fun trace(runId: String): ArcRunTrace =
        runBlocking { ArcTraceProjection(door.database).project(runId = runId, arcId = "test-arc") }
            .getOrThrow()

    private inline fun <reified T : Event> PropelPhase.decode(): List<T> =
        events.mapNotNull { traceEvent ->
            traceEvent.payload?.let { DEFAULT_JSON.decodeFromString(Event.serializer(), it) } as? T
        }

    private fun configuration() = AgentConfiguration(
        agentDefinition = AgentDefinition.Custom(
            name = "execute-steps-test",
            description = "AMPR-389 Execute telemetry test agent",
            prompt = "You are a test agent.",
        ),
        aiConfiguration = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        ),
        llmProvider = { prompt ->
            generatedPrompts += prompt
            "{}"
        },
    )

    private fun reasoningWith(runId: String): AgentReasoning =
        AgentReasoning.create(
            config = configuration(),
            executorId = AGENT_ID,
            eventApi = door.api,
            runId = runId,
        ) {
            agentRole = "Test Agent"
            executor = FunctionExecutor.create()
            availableTools = { setOf(greetingTool, failingTool) }
            execution {
                registerStrategy(greetingTool.id, passthroughStrategy)
                registerStrategy(failingTool.id, passthroughStrategy)
            }
        }

    /** Hands the request straight through, so the dispatched tool is the one under test. */
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

    private val greetingTool = FunctionTool<ExecutionContext>(
        id = "write-greeting",
        name = "Write Greeting",
        description = "Writes a greeting",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            val now = Clock.System.now()
            ExecutionOutcome.NoChanges.Success(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = "hello",
            )
        },
    )

    private val failingTool = FunctionTool<ExecutionContext>(
        id = "always-fails",
        name = "Always Fails",
        description = "Fails on purpose",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            val now = Clock.System.now()
            ExecutionOutcome.NoChanges.Failure(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = FAILURE_MESSAGE,
            )
        },
    )

    private fun step(id: String, description: String) = Task.CodeChange(
        id = id,
        status = TaskStatus.Pending,
        description = description,
    )

    private fun planOf(vararg steps: Task): Plan.ForTask = Plan.ForTask(
        task = step("parent-task", "Greet the world"),
        tasks = steps.toList(),
        estimatedComplexity = 1,
    )

    private fun requestFor(task: Task): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                executorId = AGENT_ID,
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Test ticket",
                    description = "Test ticket description",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = AGENT_ID,
                    createdByAgentId = AGENT_ID,
                    createdAt = now,
                    updatedAt = now,
                    dueDate = null,
                ),
                task = task,
                instructions = "Do the thing",
            ),
            constraints = ExecutionConstraints(
                requireTests = false,
                requireLinting = false,
            ),
        )
    }

    private companion object {
        const val AGENT_ID = "executor"
        const val FAILURE_MESSAGE = "always-fails failed on purpose"
    }
}
