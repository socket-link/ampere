package link.socket.ampere.agents.execution

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentActionAutonomy
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.status.TicketStatus
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.environment.EnvironmentFileOperation
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.events.tickets.Ticket
import link.socket.ampere.agents.events.tickets.TicketPriority
import link.socket.ampere.agents.events.tickets.TicketType
import link.socket.ampere.agents.execution.executor.AcpCodeExecutor
import link.socket.ampere.agents.execution.executor.Executor
import link.socket.ampere.agents.execution.executor.FunctionExecutor
import link.socket.ampere.agents.execution.executor.RoutedExecutor
import link.socket.ampere.agents.execution.executor.acp.InMemoryAcpTransport
import link.socket.ampere.agents.execution.executor.acp.ScriptedAcpAgent
import link.socket.ampere.agents.execution.executor.acp.SessionUpdate
import link.socket.ampere.agents.execution.executor.acp.StopReason
import link.socket.ampere.agents.execution.executor.acp.ToolCallLocation
import link.socket.ampere.agents.execution.executor.acp.ToolKind
import link.socket.ampere.agents.execution.executor.acp.WalkingWorkspaceObserver
import link.socket.ampere.agents.execution.request.ExecutionConstraints
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.HARNESS_CODE_CHANGE_TOOL_ID
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.util.systemFileSystem
import okio.Path.Companion.toPath

/**
 * AMPR-495: what an executor streams between the tool pair reaches the record. The engine
 * publishes one `ToolExecutionProgress` per non-terminal status other than `Started`, through
 * the agent's door, under the pair's invocation, run and performer — and the pair itself is
 * what it was.
 *
 * The harness is an [AcpCodeExecutor] over a scripted agent and an in-memory transport,
 * behind the [RoutedExecutor] production uses, writing into a real directory. It streams
 * its own `Planning`, then one `Writing` per edit the agent reports, then `Completed`.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher.
 */
class ToolExecutionProgressTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var checkout: File
    private var harness: AcpCodeExecutor? = null

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = scope)
        checkout = Files.createTempDirectory("tool-progress-test").toFile().also { File(it, "src").mkdirs() }
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        harness?.let { runCatching { it.close() } }
        scope.cancel()
        door.close()
        checkout.deleteRecursively()
    }

    @Test
    fun `a harness's plan and each file it wrote are published between the pair`() = runBlocking<Unit> {
        val executor = RoutedExecutor(
            default = FunctionExecutor.create(),
            bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness { turn -> editTwoFiles(turn) }),
        )

        val outcome = engine(executor).execute(ToolHarnessCodeChange(), request())

        val success = assertIs<ExecutionOutcome.CodeChanged.Success>(outcome, "the harness changed two files")
        assertEquals(listOf("src/Greeting.kt", "src/Main.kt"), success.changedFiles.sorted())

        val events = toolEvents()
        assertEquals(
            listOf(STARTED, PROGRESS, PROGRESS, PROGRESS, COMPLETED),
            events.map { it.eventType },
            "three progress events between the pair, and nothing else",
        )
        val started = assertIs<ToolEvent.ToolExecutionStarted>(events.first())
        val completed = assertIs<ToolEvent.ToolExecutionCompleted>(events.last())
        val progress = events.filterIsInstance<ToolEvent.ToolExecutionProgress>()

        assertEquals(listOf("Planning", "Writing", "Writing"), progress.map { it.kind })
        assertEquals(listOf(null, "src/Greeting.kt", "src/Main.kt"), progress.map { it.filePath })
        assertEquals(
            listOf(null, EnvironmentFileOperation.MODIFY, EnvironmentFileOperation.MODIFY),
            progress.map { it.operation },
        )
        assertEquals(listOf("src/Greeting.kt", "src/Main.kt"), progress.drop(1).map { it.detail })
        assertTrue(progress.first().detail.contains(checkout.absolutePath), progress.first().detail)

        for (event in progress) {
            assertEquals(started.invocationId, event.invocationId, "joined to its pair")
            assertEquals(HARNESS_ID, event.executorId, "the harness performed it, not the router")
            assertEquals(RUN_ID, event.runId)
            assertEquals(AGENT_ID, event.eventSource.getIdentifier(), "published by the deciding agent's door")
            assertEquals(Urgency.LOW, event.urgency)
            assertEquals(HARNESS_CODE_CHANGE_TOOL_ID, event.toolId)
        }
        assertEquals(
            setOf(RUN_ID),
            runRecord().filter { it.event is ToolEvent.ToolExecutionProgress }.map { it.runId }.toSet(),
            "every progress event is on the run's envelope too",
        )
        assertEquals(progress.size, progress.map { it.eventId }.toSet().size, "each has its own id")

        assertEquals(HARNESS_ID, started.executorId)
        assertEquals(HARNESS_ID, completed.executorId)
        assertEquals(started.invocationId, completed.invocationId)
        assertTrue(completed.success, "progress does not change the pair's verdict")
        assertNull(completed.errorMessage)
    }

    @Test
    fun `a FunctionExecutor dispatch publishes its one Planning and leaves the pair as it was`() = runBlocking<Unit> {
        val outcome = engine(FunctionExecutor.create()).execute(succeedingTool(), request())

        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        val events = toolEvents()
        assertEquals(listOf(STARTED, PROGRESS, COMPLETED), events.map { it.eventType })

        val started = assertIs<ToolEvent.ToolExecutionStarted>(events[0])
        val planning = assertIs<ToolEvent.ToolExecutionProgress>(events[1])
        val completed = assertIs<ToolEvent.ToolExecutionCompleted>(events[2])
        assertEquals("Planning", planning.kind)
        assertEquals("Executing function tool '$TOOL_NAME'", planning.detail)
        assertNull(planning.filePath)
        assertNull(planning.operation)
        assertEquals(started.invocationId, planning.invocationId)
        assertEquals(FunctionExecutor.DEFAULT_ID, planning.executorId)

        assertEquals(FunctionExecutor.DEFAULT_ID, started.executorId)
        assertEquals(FunctionExecutor.DEFAULT_ID, completed.executorId)
        assertTrue(completed.success)
        assertEquals(Urgency.LOW, completed.urgency)
        assertNull(completed.errorMessage)
        assertEquals(RUN_ID, completed.runId)
    }

    @Test
    fun `an engine with no door dispatches the same and publishes nothing`() = runBlocking<Unit> {
        val outcome = engine(FunctionExecutor.create(), withDoor = false).execute(succeedingTool(), request())

        assertIs<ExecutionOutcome.NoChanges.Success>(outcome)
        assertEquals(emptyList<ToolEvent>(), toolEvents(), "no door, no events, as for the pair")
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /** Reports an edit to each of two files, then writes them from its own process. */
    private suspend fun editTwoFiles(turn: ScriptedAcpAgent.ScriptedTurn): StopReason = with(turn) {
        for ((index, relative) in listOf("src/Greeting.kt", "src/Main.kt").withIndex()) {
            update(
                SessionUpdate.ToolCall(
                    toolCallId = "c$index",
                    title = "Edit $relative",
                    kind = ToolKind.EDIT,
                    locations = listOf(ToolCallLocation(File(checkout, relative).absolutePath)),
                ),
            )
            File(checkout, relative).writeText("// $relative\n")
        }
        say("Edited two files.")
        StopReason.END_TURN
    }

    private fun harness(script: suspend (ScriptedAcpAgent.ScriptedTurn) -> StopReason): AcpCodeExecutor {
        val transport = InMemoryAcpTransport()
        ScriptedAcpAgent(transport, script = { script(this) }).also { it.start(scope) }
        return AcpCodeExecutor(
            id = HARNESS_ID,
            displayName = "Fake ACP",
            transportFactory = { transport },
            observerFactory = { WalkingWorkspaceObserver(systemFileSystem, checkout.absolutePath.toPath()) },
            workspace = ExecutionWorkspace(baseDirectory = checkout.absolutePath),
            readerScope = scope,
        ).also { harness = it }
    }

    private fun engine(executor: Executor, withDoor: Boolean = true) = ToolExecutionEngine(
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
        actorId = AGENT_ID,
        eventApi = if (withDoor) door.api else null,
    )

    private fun succeedingTool() = FunctionTool<ExecutionContext.NoChanges>(
        id = "lookup",
        name = TOOL_NAME,
        description = "Looks something up",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        executionFunction = { request ->
            val now = Clock.System.now()
            ExecutionOutcome.NoChanges.Success(
                executorId = request.context.executorId,
                ticketId = request.context.ticket.id,
                taskId = request.context.task.id,
                executionStartTimestamp = now,
                executionEndTimestamp = now,
                message = "found it",
            )
        },
    )

    private fun request(): ExecutionRequest<ExecutionContext.NoChanges> {
        val now = Clock.System.now()
        return ExecutionRequest(
            context = ExecutionContext.NoChanges(
                actorId = AGENT_ID,
                executorId = "unstamped",
                ticket = Ticket(
                    id = "ticket-1",
                    title = "Greet the user",
                    description = "Greet the user on start-up",
                    type = TicketType.TASK,
                    priority = TicketPriority.MEDIUM,
                    status = TicketStatus.InProgress,
                    assignedAgentId = AGENT_ID,
                    createdByAgentId = AGENT_ID,
                    createdAt = now,
                    updatedAt = now,
                ),
                task = Task.CodeChange(
                    id = "task-1",
                    status = TaskStatus.Pending,
                    description = "Add a greeting",
                ),
                instructions = "Add a greeting",
            ),
            constraints = ExecutionConstraints(requireTests = false, requireLinting = false),
            runId = RUN_ID,
        )
    }

    private suspend fun runRecord(): List<StoredEvent> = door.repository.getEventsByRunId(RUN_ID).getOrThrow()

    /** Every tool event on the door, oldest first. */
    private suspend fun toolEvents(): List<ToolEvent> =
        door.repository.getAllEvents().getOrThrow().filterIsInstance<ToolEvent>().reversed()

    private companion object {
        const val AGENT_ID = "progress-agent"
        const val HARNESS_ID = "fake-acp"
        const val RUN_ID = "run-progress"
        const val TOOL_NAME = "Lookup"

        const val STARTED = ToolEvent.ToolExecutionStarted.EVENT_TYPE
        const val PROGRESS = ToolEvent.ToolExecutionProgress.EVENT_TYPE
        const val COMPLETED = ToolEvent.ToolExecutionCompleted.EVENT_TYPE
    }
}
