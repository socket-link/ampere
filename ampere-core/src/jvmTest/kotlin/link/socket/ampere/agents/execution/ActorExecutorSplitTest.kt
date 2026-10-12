package link.socket.ampere.agents.execution

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.definition.code.CodeState
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.emission.EmissionReplyRegistry
import link.socket.ampere.agents.domain.event.EmissionEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.HumanInteractionEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.reasoning.StepResult
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.ASK_HUMAN_TOOL_ID
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.ToolAskHuman
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.db.fts.FtsSchema
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.util.randomUUID

/**
 * AMPR-443 (F17): actor ≠ executor. The agent that decides a tool call and the `Executor.id`
 * that performs it are two values, on the context, on the tool-event pair and on the outcome
 * row — and the agent's id is never found in an executor's field.
 *
 * Drives one tool step through `SparkBasedAgent.executePlanStep`, the step function the Arc
 * path and a roster-hosted run share, on a real [FunctionExecutor] with a real outcome store.
 * The tool is [ToolAskHuman], the reader that most needed the agent and was reading the
 * executor's field to get it; it is wrapped only to keep the context it was dispatched with.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher and a virtual clock
 * walks straight past it.
 */
class ActorExecutorSplitTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    private val registry = EmissionReplyRegistry()

    /** Every request the tool was dispatched with, as the tool received it. */
    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<ExecutionContext.NoChanges>>()

    /** What the tool returned for each dispatch. */
    private val returned = CopyOnWriteArrayList<ExecutionOutcome>()

    /** Every ask the tool put to a person. */
    private val asks = CopyOnWriteArrayList<HumanInteractionEvent.InputRequested>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = scope)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        FtsSchema.install(driver)
        database = Database(driver)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
        driver.close()
    }

    @Test
    fun `one tool step names the agent as actor and the FunctionExecutor as executor everywhere`() =
        runBlocking<Unit> {
            val agent = agent(executor = FunctionExecutor.create())

            val result = agent.executePlanStep(STEP, parentTask = STEP, priorResults = emptyList())
            assertIs<StepResult.Success>(result, "the person answered, so the step succeeded; got $result")

            // --- the context the tool was dispatched with -------------------------------
            val context = dispatched.single().context
            assertEquals(AGENT_ID, context.actorId, "the agent decided the step")
            assertEquals(FunctionExecutor.DEFAULT_ID, context.executorId, "the FunctionExecutor performed it")

            // --- ToolAskHuman asks as the agent -----------------------------------------
            val ask = asks.single()
            assertEquals(EventSource.Agent(AGENT_ID), ask.eventSource, "the escalation's source is the agent")
            assertEquals(AGENT_ID, ask.agentId, "and so is the ask's agent")

            // --- the tool-event pair names both -----------------------------------------
            val events = door.repository.getAllEvents().getOrThrow()
            val started = events.filterIsInstance<ToolEvent.ToolExecutionStarted>().single()
            val completed = events.filterIsInstance<ToolEvent.ToolExecutionCompleted>().single()
            assertEquals(AGENT_ID, started.eventSource.getIdentifier())
            assertEquals(AGENT_ID, completed.eventSource.getIdentifier())
            assertEquals(FunctionExecutor.DEFAULT_ID, started.executorId)
            assertEquals(FunctionExecutor.DEFAULT_ID, completed.executorId)

            // --- the outcome row names both ---------------------------------------------
            val row = database.outcomeMemoryStoreQueries
                .getOutcomesByTicket("spark-task-${STEP.id}")
                .executeAsList()
                .single()
            assertEquals(AGENT_ID, row.actor_id)
            assertEquals(FunctionExecutor.DEFAULT_ID, row.executor_id)

            // --- and nothing puts the agent in an executor's field ----------------------
            val executorFields = mapOf(
                "context.executorId" to context.executorId,
                "tool outcome executorId" to returned.single().executorId,
                "ToolExecutionStarted.executorId" to started.executorId,
                "ToolExecutionCompleted.executorId" to completed.executorId,
                "OutcomeMemoryStore.executor_id" to row.executor_id,
            )
            executorFields.forEach { (field, value) ->
                assertFalse(value == AGENT_ID, "$field holds the agent's id")
            }
        }

    @Test
    fun `an agent with no executor names that fact and never its own id`() = runBlocking<Unit> {
        val agent = agent(executor = null)

        val outcome = agent.runLLMToExecuteTool(recordingAskHuman(), directRequest())

        val failure = assertIs<ExecutionOutcome.NoChanges.Failure>(outcome)
        assertEquals(ExecutionContext.NO_EXECUTOR_ID, failure.executorId)
        assertTrue(dispatched.isEmpty(), "with no executor nothing was dispatched")
    }

    @Test
    fun `a stored tool-event pair without the executor decodes and re-encodes byte-identically`() {
        // What every pair written before AMPR-443 looks like: the same fields, no `executorId`.
        val legacyStarted = DEFAULT_JSON.encodeToString(Event.serializer(), started(executorId = null))
        val legacyCompleted = DEFAULT_JSON.encodeToString(Event.serializer(), completed(executorId = null))
        assertFalse(legacyStarted.contains("executorId"), legacyStarted)
        assertFalse(legacyCompleted.contains("executorId"), legacyCompleted)

        val decodedStarted = assertIs<ToolEvent.ToolExecutionStarted>(
            DEFAULT_JSON.decodeFromString(Event.serializer(), legacyStarted),
        )
        val decodedCompleted = assertIs<ToolEvent.ToolExecutionCompleted>(
            DEFAULT_JSON.decodeFromString(Event.serializer(), legacyCompleted),
        )
        assertNull(decodedStarted.executorId)
        assertNull(decodedCompleted.executorId)
        assertEquals(legacyStarted, DEFAULT_JSON.encodeToString(Event.serializer(), decodedStarted))
        assertEquals(legacyCompleted, DEFAULT_JSON.encodeToString(Event.serializer(), decodedCompleted))
    }

    @Test
    fun `a new tool-event pair carries the executor`() {
        val encodedStarted = DEFAULT_JSON.encodeToString(Event.serializer(), started(executorId = "function"))
        val encodedCompleted = DEFAULT_JSON.encodeToString(Event.serializer(), completed(executorId = "function"))
        assertTrue(encodedStarted.contains("\"executorId\":\"function\""), encodedStarted)
        assertTrue(encodedCompleted.contains("\"executorId\":\"function\""), encodedCompleted)

        val decodedStarted = DEFAULT_JSON.decodeFromString(Event.serializer(), encodedStarted)
        val decodedCompleted = DEFAULT_JSON.decodeFromString(Event.serializer(), encodedCompleted)
        assertEquals("function", assertIs<ToolEvent.ToolExecutionStarted>(decodedStarted).executorId)
        assertEquals("function", assertIs<ToolEvent.ToolExecutionCompleted>(decodedCompleted).executorId)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun agent(executor: Executor?): SparkBasedAgent<CodeState> =
        SparkBasedAgent(
            agentId = AGENT_ID,
            cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
            initialState = CodeState.blank,
            _additionalTools = setOf(recordingAskHuman()),
            _eventApi = door.api,
            _aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = AIModel_Claude.Sonnet_5,
            ),
            // No strategy and no schema on the tool, so the engine dispatches it as it came and
            // asks the model nothing; the provider is only here so the engine is built.
            _llmProvider = { "{}" },
            _observabilityScope = scope,
            _executor = executor,
            _outcomeRepository = OutcomeMemoryRepositoryImpl(database, driver),
        )

    /**
     * A real [ToolAskHuman], under its own id, that keeps the request it was handed and what it
     * returned. Each ask is answered as soon as the tool is waiting for the answer.
     */
    private fun recordingAskHuman(): FunctionTool<ExecutionContext.NoChanges> {
        val askHuman = ToolAskHuman(
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            eventApi = door.api,
            replyRegistry = registry,
            onInputRequested = { requested ->
                asks += requested
                scope.launch { answer(requested) }
            },
        )
        return FunctionTool(
            id = ASK_HUMAN_TOOL_ID,
            name = askHuman.name,
            description = askHuman.description,
            requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
            executionFunction = { request ->
                dispatched += request
                (askHuman.execute(request) as ExecutionOutcome).also { returned += it }
            },
        )
    }

    /** `onInputRequested` runs before the tool registers its wait, so the reply waits for it. */
    private suspend fun answer(requested: HumanInteractionEvent.InputRequested) {
        withTimeout(5_000) {
            while (requested.emissionId !in registry.getPendingEmissionIds()) delay(5)
        }
        registry.deliver(
            EmissionEvent.BaseResolved(
                eventId = randomUUID(),
                timestamp = Clock.System.now(),
                eventSource = EventSource.Human,
                urgency = Urgency.HIGH,
                emissionId = requested.emissionId,
                affordanceId = "free-text",
                replyContext = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("free-text"),
                        "text" to JsonPrimitive("Go ahead"),
                    ),
                ),
            ),
        )
    }

    private fun directRequest(): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = AGENT_ID,
                executorId = ExecutionContext.NO_EXECUTOR_ID,
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Ask before sending",
                    description = "Ask before sending",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = AGENT_ID,
                    createdByAgentId = AGENT_ID,
                    createdAt = now,
                    updatedAt = now,
                    dueDate = null,
                ),
                task = STEP,
                instructions = STEP.description,
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
        )
    }

    private fun started(executorId: String?) = ToolEvent.ToolExecutionStarted(
        eventId = "event-started",
        timestamp = AT,
        eventSource = EventSource.Agent(AGENT_ID),
        urgency = Urgency.LOW,
        invocationId = "invocation-1",
        toolId = ASK_HUMAN_TOOL_ID,
        toolName = "Ask a Human",
        runId = "run-1",
        executorId = executorId,
    )

    private fun completed(executorId: String?) = ToolEvent.ToolExecutionCompleted(
        eventId = "event-completed",
        timestamp = AT,
        eventSource = EventSource.Agent(AGENT_ID),
        urgency = Urgency.LOW,
        invocationId = "invocation-1",
        toolId = ASK_HUMAN_TOOL_ID,
        toolName = "Ask a Human",
        success = true,
        durationMs = 12,
        runId = "run-1",
        executorId = executorId,
    )

    private companion object {
        const val AGENT_ID = "split-test-agent"
        val AT: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000)

        val STEP = Task.CodeChange(
            id = "step-1",
            status = TaskStatus.Pending,
            description = "Ask the owner before sending",
            toolId = ASK_HUMAN_TOOL_ID,
        )
    }
}
