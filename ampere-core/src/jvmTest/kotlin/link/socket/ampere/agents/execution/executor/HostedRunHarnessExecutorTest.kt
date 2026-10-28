package link.socket.ampere.agents.execution.executor

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.cognition.FileAccessScope
import link.socket.ampere.agents.domain.cognition.Spark
import link.socket.ampere.agents.domain.event.AdmissionRecord
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.PlanEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.environment.EnvironmentFileOperation
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.events.StoredEvent
import link.socket.ampere.agents.execution.executor.acp.InMemoryAcpTransport
import link.socket.ampere.agents.execution.executor.acp.PermissionToolCall
import link.socket.ampere.agents.execution.executor.acp.ScriptedAcpAgent
import link.socket.ampere.agents.execution.executor.acp.SessionUpdate
import link.socket.ampere.agents.execution.executor.acp.StopReason
import link.socket.ampere.agents.execution.executor.acp.ToolCallLocation
import link.socket.ampere.agents.execution.executor.acp.ToolKind
import link.socket.ampere.agents.execution.executor.acp.WalkingWorkspaceObserver
import link.socket.ampere.agents.execution.tools.HARNESS_CODE_CHANGE_TOOL_ID
import link.socket.ampere.agents.execution.tools.ToolHarnessCodeChange
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.propel.CountingUpstreamLlmClient
import link.socket.ampere.propel.HostedAgent
import link.socket.ampere.propel.HostedRun
import link.socket.ampere.propel.RunStatus
import link.socket.ampere.propel.fixedAnswer
import link.socket.ampere.propel.goal
import link.socket.ampere.propel.hostAnswers
import link.socket.ampere.propel.planJson
import link.socket.ampere.propel.planStep
import link.socket.ampere.propel.rosterRunHost
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool
import link.socket.ampere.util.systemFileSystem
import okio.Path.Companion.toPath

/**
 * AMPR-444's objective on the production wiring (AMPR-494): a plan step nominating
 * `harness_code_change` is admitted by the Breaker on the record, runs through a
 * [RoutedExecutor] to an [AcpCodeExecutor], and its tool pair and outcome name the harness
 * as the performer (AMPR-443). The agent is scripted over an in-memory transport; the
 * workspace is a real directory the agent writes into from its own process.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher.
 */
class HostedRunHarnessExecutorTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var checkout: File
    private lateinit var agent: ScriptedAcpAgent
    private lateinit var harness: AcpCodeExecutor

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        checkout = Files.createTempDirectory("hosted-harness-test").toFile().also { File(it, "src").mkdirs() }
    }

    @AfterTest
    fun tearDown() = runBlocking<Unit> {
        runCatching { harness.close() }
        scope.cancel()
        door.close()
        checkout.deleteRecursively()
    }

    @Test
    fun `a harness step is admitted on the record and its pair and outcome name the harness`() = runBlocking<Unit> {
        val run = openRun(
            doerSparks = emptyList(),
            script = {
                update(
                    SessionUpdate.ToolCall(
                        toolCallId = "c1",
                        title = "Edit greeting",
                        kind = ToolKind.EDIT,
                        locations = listOf(ToolCallLocation(path("src/Greeting.kt"))),
                    ),
                )
                val answer = askPermission(
                    PermissionToolCall(
                        toolCallId = "c1",
                        kind = ToolKind.EDIT,
                        locations = listOf(ToolCallLocation(path("src/Greeting.kt"))),
                    ),
                )
                if (answer.outcome.optionId == "allow") {
                    File(checkout, "src/Greeting.kt").writeText("fun greet() = \"hi\"\n")
                }
                say("Added a greeting.")
                StopReason.END_TURN
            },
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status, "the step ran: ${result.stepOutcomes}")
        val step = assertIs<StepOutcome.Success>(result.stepOutcomes.single())
        assertTrue(
            step.details.contains("tool=$HARNESS_TOOL outcome=Success; changed files: src/Greeting.kt"),
            step.details,
        )
        assertTrue(step.details.contains("Added a greeting."), step.details)

        val record = record(result.runId)
        val admitted = record.events<PlanEvent.StepAdmitted>().single()
        assertEquals(AdmissionRecord.Pass, admitted.admission)
        assertEquals(DOER_ID, admitted.eventSource.getIdentifier(), "admitted through the doer's door")
        assertEquals(result.runId, admitted.runId)

        val started = record.events<ToolEvent.ToolExecutionStarted>().single()
        val completed = record.events<ToolEvent.ToolExecutionCompleted>().single()
        assertEquals(HARNESS_ID, started.executorId, "the pair names the harness, not the router")
        assertEquals(HARNESS_ID, completed.executorId)
        assertEquals(HARNESS_TOOL, started.toolId)
        assertTrue(completed.success)
        assertEquals(DOER_ID, started.eventSource.getIdentifier(), "the agent that decided is the source")
        assertEquals(started.invocationId, completed.invocationId)

        // AMPR-495: what the harness did between the pair is on the run's record, as the harness.
        val toolEvents = record.events<ToolEvent>()
        val writing = record.events<ToolEvent.ToolExecutionProgress>().single { it.kind == "Writing" }
        assertTrue(
            toolEvents.indexOf(started) < toolEvents.indexOf(writing) &&
                toolEvents.indexOf(writing) < toolEvents.indexOf(completed),
            "the write sits between the pair: ${toolEvents.map { it.eventType }}",
        )
        assertEquals("src/Greeting.kt", writing.filePath)
        assertEquals(EnvironmentFileOperation.MODIFY, writing.operation)
        assertEquals(HARNESS_ID, writing.executorId, "attributed to the harness, not the router")
        assertEquals(DOER_ID, writing.eventSource.getIdentifier(), "through the deciding seat's door")
        assertEquals(started.invocationId, writing.invocationId)
        assertEquals(result.runId, writing.runId)

        val prompt = agent.lastPromptText()
        assertTrue(prompt.contains("Add a greeting"), prompt)
        assertTrue(prompt.contains(checkout.absolutePath), prompt)
        assertEquals(checkout.absolutePath, agent.sessions.single().cwd)
    }

    @Test
    fun `a write outside the doer's file access scope fails the step and the pair says so`() = runBlocking<Unit> {
        val run = openRun(
            doerSparks = listOf(kotlinSourcesOnly),
            script = {
                File(checkout, "notes").mkdirs()
                File(checkout, "notes/out.txt").writeText("sneaky\n")
                say("Wrote some notes.")
                StopReason.END_TURN
            },
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Failed, result.status)
        val step = assertIs<StepOutcome.Failure>(result.stepOutcomes.single())
        assertTrue(step.error.contains("notes/out.txt"), step.error)
        assertTrue(step.error.contains("outside the file access scope"), step.error)

        val record = record(result.runId)
        assertEquals(
            AdmissionRecord.Pass,
            record.events<PlanEvent.StepAdmitted>().single().admission,
            "the Breaker admitted; the fence failed it",
        )
        val completed = record.events<ToolEvent.ToolExecutionCompleted>().single()
        assertFalse(completed.success)
        assertEquals(HARNESS_ID, completed.executorId)
        assertTrue(completed.errorMessage!!.contains("notes/out.txt"), completed.errorMessage)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private suspend fun openRun(
        doerSparks: List<Spark>,
        script: suspend ScriptedAcpAgent.ScriptedTurn.() -> StopReason,
    ): HostedRun {
        val transport = InMemoryAcpTransport()
        agent = ScriptedAcpAgent(transport, script = script).also { it.start(scope) }
        harness = AcpCodeExecutor(
            id = HARNESS_ID,
            displayName = "Fake ACP",
            transportFactory = { transport },
            observerFactory = { WalkingWorkspaceObserver(systemFileSystem, checkout.absolutePath.toPath()) },
            workspace = ExecutionWorkspace(baseDirectory = checkout.absolutePath),
            readerScope = scope,
        )
        val executor = RoutedExecutor(
            default = FunctionExecutor.create(),
            bindings = mapOf(HARNESS_CODE_CHANGE_TOOL_ID to harness),
        )
        val host = HostedAgent(
            id = HOST_ID,
            role = HOST_ROLE,
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = CountingUpstreamLlmClient(
                hostAnswers { planJson(planStep("Add a greeting", HARNESS_TOOL, DOER_ROLE.value)) },
            ),
        )
        val doer = HostedAgent(
            id = DOER_ID,
            role = DOER_ROLE,
            sparks = doerSparks,
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = CountingUpstreamLlmClient(fixedAnswer { "unused" }),
        )
        return rosterRunHost(createEventApi = door::doorFor, agentScope = scope, executor = executor).open(
            roster = RosterConfig(
                host = HOST_ROLE,
                roles = listOf(
                    RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
                    RoleConfig(
                        id = DOER_ROLE,
                        title = "Doer",
                        instructions = PROMPT,
                        tools = setOf(HARNESS_CODE_CHANGE_TOOL_ID),
                    ),
                ),
            ),
            seats = mapOf(HOST_ROLE to host, DOER_ROLE to doer),
            goal = goal("Greet the user on start-up"),
            tools = setOf(ToolHarnessCodeChange()),
        )
    }

    private fun path(relative: String): String = File(checkout, relative).absolutePath

    private suspend fun record(runId: String): List<StoredEvent> =
        door.repository.getEventsByRunId(runId).getOrThrow()

    private inline fun <reified T : Event> List<StoredEvent>.events(): List<T> = mapNotNull { it.event as? T }

    /** A spark that lets the doer write only Kotlin sources under `src`. */
    private val kotlinSourcesOnly = object : Spark {
        override val name: String = "Role:KotlinSourcesOnly"
        override val promptContribution: String = "Only Kotlin sources under src may change."
        override val allowedTools: Set<String>? = null
        override val fileAccessScope: FileAccessScope = FileAccessScope(
            readPatterns = setOf(FileAccessScope.AnyPath),
            writePatterns = setOf("src/**/*.kt"),
        )
    }

    private companion object {
        const val SDK_ID = "hosted-harness-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val HARNESS_ID = "fake-acp"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("doer")
        val HARNESS_TOOL = SeatTool(DOER_ROLE, HARNESS_CODE_CHANGE_TOOL_ID).id
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
