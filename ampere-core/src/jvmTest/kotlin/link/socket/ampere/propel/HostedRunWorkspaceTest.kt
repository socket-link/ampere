package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.environment.workspace.ExecutionWorkspace
import link.socket.ampere.agents.environment.workspace.WorkspaceLease
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.Roster
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * AMPR-496: a hosted run carries its workspace.
 *
 * The workspace is a parameter of `RunHost.open`, one per run, and every seat of the run is
 * built pinned to it — so every step request any seat builds carries it, and a code tool or a
 * harness finds its checkout on the request. A run opened without one stamps null, which stays
 * null (AMPR-300). The record a held run leaves carries it, and a record with none encodes
 * exactly as one written before the field existed.
 *
 * `runBlocking`, not `runTest`: a door's publish hops to the IO dispatcher.
 */
class HostedRunWorkspaceTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    private val noted = CopyOnWriteArrayList<ExecutionRequest<*>>()
    private val filed = CopyOnWriteArrayList<ExecutionRequest<*>>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    // ========================================================================
    // open
    // ========================================================================

    @Test
    fun `every seat of a run opened with a workspace stamps it on its step requests`() = runBlocking<Unit> {
        val run = runHost().open(
            roster = roster(),
            seats = seats().associateBy { it.role },
            goal = goal(GOAL_TEXT),
            tools = tools(),
            workspace = CHECKOUT,
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status, "both steps ran: ${result.stepOutcomes}")
        assertEquals(CHECKOUT, noted.single().workspace, "the host seat's step carries the run's checkout")
        assertEquals(CHECKOUT, filed.single().workspace, "and so does another seat's")
    }

    @Test
    fun `a run opened without a workspace stamps null on every step request`() = runBlocking<Unit> {
        val run = runHost().open(
            roster = roster(),
            seats = seats().associateBy { it.role },
            goal = goal(GOAL_TEXT),
            tools = tools(),
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status, "both steps ran: ${result.stepOutcomes}")
        assertNull(noted.single().workspace, "no default workspace (AMPR-300)")
        assertNull(filed.single().workspace)
    }

    @Test
    fun `openSeats hands its workspace to the run it opens`() = runBlocking<Unit> {
        val run = runHost().openSeats(
            roster = roster(),
            seats = seats(),
            goal = goal(GOAL_TEXT),
            tools = tools(),
            workspace = CHECKOUT,
        )
        run.execute()
        run.close()

        assertEquals(CHECKOUT, noted.single().workspace)
        assertEquals(CHECKOUT, filed.single().workspace)
    }

    // ========================================================================
    // The held-run record
    // ========================================================================

    @Test
    fun `a held run record with no workspace writes no workspace key and re-encodes byte for byte`() {
        val encoded = DEFAULT_JSON.encodeToString(HeldRun.serializer(), record(workspace = null))

        assertFalse("\"workspace\"" in encoded, "a null workspace is not encoded: $encoded")
        val decoded = DEFAULT_JSON.decodeFromString(HeldRun.serializer(), encoded)
        assertNull(decoded.workspace, "a record from before the field decodes with none")
        assertEquals(encoded, DEFAULT_JSON.encodeToString(HeldRun.serializer(), decoded))
    }

    @Test
    fun `a held run record carries its workspace through an encode and a decode`() {
        val leased = CHECKOUT.copy(
            repository = "socket-link/ampere",
            branch = "main",
            lease = WorkspaceLease(holder = "run-1", acquiredAt = Instant.fromEpochMilliseconds(0)),
        )
        val encoded = DEFAULT_JSON.encodeToString(HeldRun.serializer(), record(workspace = leased))

        assertTrue("\"workspace\"" in encoded, encoded)
        assertEquals(leased, DEFAULT_JSON.decodeFromString(HeldRun.serializer(), encoded).workspace)
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private fun runHost(): RunHost = rosterRunHost(createEventApi = door::doorFor, agentScope = scope)

    private fun roster(): Roster = RosterConfig(
        host = HOST_ROLE,
        roles = listOf(
            RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT, tools = setOf(NOTE_TOOL)),
            RoleConfig(id = CLERK_ROLE, title = "Clerk", instructions = PROMPT, tools = setOf(FILE_TOOL)),
        ),
    )

    /** The host plans one step on its own seat and one on the clerk's. */
    private fun seats(): List<HostedAgent> = listOf(
        HostedAgent(
            id = HOST_ID,
            role = HOST_ROLE,
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = CountingUpstreamLlmClient(
                hostAnswers {
                    planJson(
                        planStep("Note the request", SeatTool(HOST_ROLE, NOTE_TOOL).id, HOST_ROLE.value),
                        planStep("File the change", SeatTool(CLERK_ROLE, FILE_TOOL).id, CLERK_ROLE.value),
                    )
                },
            ),
        ),
        HostedAgent(
            id = CLERK_ID,
            role = CLERK_ROLE,
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = CountingUpstreamLlmClient(fixedAnswer { "unused" }),
        ),
    )

    private fun tools() = setOf(
        recordingTool(NOTE_TOOL, "noted", noted),
        recordingTool(FILE_TOOL, "filed", filed),
    )

    private fun record(workspace: ExecutionWorkspace?): HeldRun = HeldRun(
        runId = "run-1",
        hostRole = HOST_ROLE.value,
        hostAgentId = HOST_ID,
        goal = goal(GOAL_TEXT),
        cycle = 1,
        plan = Plan.blank,
        results = listOf(
            StepOutcome.Held(
                id = "step-1",
                stepDescription = "Send the reply",
                startTimestamp = Instant.fromEpochMilliseconds(500),
                endTimestamp = Instant.fromEpochMilliseconds(900),
                reason = "waiting on a person",
            ),
        ),
        heldStepId = "step-1",
        reason = "waiting on a person",
        heldAt = Instant.fromEpochMilliseconds(1_000),
        workspace = workspace,
    )

    private companion object {
        const val SDK_ID = "hosted-run-workspace-test-sdk"
        const val HOST_ID = "seat-host"
        const val CLERK_ID = "seat-clerk"
        const val NOTE_TOOL = "note"
        const val FILE_TOOL = "file"
        const val GOAL_TEXT = "Record the change request"

        val HOST_ROLE = RoleId("planner")
        val CLERK_ROLE = RoleId("clerk")
        val CHECKOUT = ExecutionWorkspace(baseDirectory = "/abs/checkout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
