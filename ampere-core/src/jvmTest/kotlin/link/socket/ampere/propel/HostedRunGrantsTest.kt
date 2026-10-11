package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.PermissionDeniedEvent
import link.socket.ampere.agents.domain.event.PermissionDeniedReason
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.StepOutcome
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionContext
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.FunctionTool
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.plug.PlugId
import link.socket.ampere.plug.PlugManifest
import link.socket.ampere.plug.permission.PlugPermission
import link.socket.ampere.plug.permission.UserGrants
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * Task 1 of AMPR-423: a hosted seat carries its own consent grants (AMPR-385 row H26).
 *
 * The gate itself is `ToolExecutionEngine`'s and is tested there; what is asserted here
 * is the wiring — that what a consumer hands a [HostedAgent] is what the seat's engine
 * checks a Plug-backed tool against, that leaving it out denies, and that it is asked
 * again on every dispatch rather than read once.
 *
 * `runBlocking`, not `runTest`, for the same reason as [HostedRunTest]: a door's publish
 * hops to the IO dispatcher and a virtual clock walks straight past it.
 */
class HostedRunGrantsTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle

    /** Every dispatch that reached a tool's own function, Plug-backed or not. */
    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

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

    /**
     * The host seat is given a provider that grants everything the tool needs, and the
     * doer — the seat that runs it — is given none. The doer is still denied: a seat
     * without grants falls back to the engine's deny-all, never to a neighbour's consent.
     */
    @Test
    fun `a seat with no grant provider is denied a Plug-backed tool, and the tool's function never ran`() =
        runBlocking<Unit> {
            val run = openRun(
                hostGrants = { UserGrants.granted(PERMISSION) },
                doerGrants = null,
                steps = listOf(planStep("Notify the owner", PLUG_TOOL.seatId(), DOER_ROLE.value)),
            )
            val result = run.execute()
            run.close()

            assertTrue(dispatched.isEmpty(), "the gate refused before dispatch; got ${dispatched.size}")
            assertEquals(RunStatus.Failed, result.status, "a denied step is a failed step")
            val failure = assertIs<StepOutcome.Failure>(result.stepOutcomes.single())
            assertTrue(
                failure.error.contains("Permission denied"),
                "the step says why it failed; got '${failure.error}'",
            )

            val denied = permissionDenials()
            assertEquals(1, denied.size, "the denial is on the record")
            assertEquals(
                DOER_ID,
                denied.single().eventSource.getIdentifier(),
                "through the seat that was denied",
            )
            assertEquals(PermissionDeniedReason.MISSING_GRANT, denied.single().reason)
            assertEquals(PERMISSION, denied.single().permission)
        }

    @Test
    fun `a seat whose provider grants the permission runs the tool`() = runBlocking<Unit> {
        val consent = Consent()
        val run = openRun(
            doerGrants = consent::grantsFor,
            steps = listOf(planStep("Notify the owner", PLUG_TOOL.seatId(), DOER_ROLE.value)),
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status)
        assertEquals(1, dispatched.size, "the tool's own function ran")
        assertEquals(run.runId, dispatched.single().runId, "under the run")
        assertEquals(
            listOf(MANIFEST),
            consent.askedFor,
            "the provider was asked once, for the manifest of the tool being dispatched",
        )
        assertTrue(permissionDenials().isEmpty(), "and nothing was denied")
    }

    /**
     * Two steps on one tool, and the person revokes between them — modelled by the tool
     * itself revoking on its first run, which is the only way to land exactly between
     * two dispatches of one plan. The second step is refused because the provider was
     * asked again, not remembered.
     */
    @Test
    fun `a provider that stops granting between two steps denies the second`() = runBlocking<Unit> {
        val consent = Consent()
        val run = openRun(
            doerGrants = consent::grantsFor,
            onPlugToolRun = consent::revoke,
            steps = listOf(
                planStep("Notify the owner", PLUG_TOOL.seatId(), DOER_ROLE.value),
                planStep("Notify the owner again", PLUG_TOOL.seatId(), DOER_ROLE.value),
            ),
        )
        val result = run.execute()
        run.close()

        assertEquals(2, consent.askedFor.size, "asked on every dispatch, never cached")
        assertEquals(1, dispatched.size, "only the first step's tool ran")
        assertEquals(2, result.stepOutcomes.size)
        assertIs<StepOutcome.Success>(result.stepOutcomes[0])
        assertIs<StepOutcome.Failure>(result.stepOutcomes[1])
        assertEquals(RunStatus.Failed, result.status)

        val denied = permissionDenials()
        assertEquals(1, denied.size)
        assertEquals(
            PermissionDeniedReason.REVOKED_GRANT,
            denied.single().reason,
            "a revoked grant reads as revoked, not as never granted",
        )
    }

    @Test
    fun `a tool with no manifest runs with no provider`() = runBlocking<Unit> {
        val run = openRun(
            doerGrants = null,
            steps = listOf(planStep("Search", PLAIN_TOOL.seatId(), DOER_ROLE.value)),
        )
        val result = run.execute()
        run.close()

        assertEquals(RunStatus.Succeeded, result.status, "the gate is consulted only for a manifest")
        assertEquals(1, dispatched.size)
        assertTrue(permissionDenials().isEmpty())
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    /**
     * A consumer's consent, read the way a `UserGrantStore`-backed provider reads it:
     * fresh on every call. [askedFor] is every manifest it was asked about, in order.
     */
    private class Consent {
        private val revoked = AtomicBoolean(false)
        val askedFor: MutableList<PlugManifest> = CopyOnWriteArrayList()

        @Suppress("RedundantSuspendModifier")
        suspend fun grantsFor(manifest: PlugManifest): UserGrants {
            askedFor += manifest
            return if (revoked.get()) UserGrants.revoked(PERMISSION) else UserGrants.granted(PERMISSION)
        }

        fun revoke() {
            revoked.set(true)
        }
    }

    private suspend fun openRun(
        steps: List<String>,
        hostGrants: (suspend (PlugManifest) -> UserGrants)? = null,
        doerGrants: (suspend (PlugManifest) -> UserGrants)?,
        onPlugToolRun: () -> Unit = {},
    ): HostedRun {
        val host = HostedAgent(
            id = HOST_ID,
            role = HOST_ROLE,
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = CountingUpstreamLlmClient(hostAnswers { planJson(*steps.toTypedArray()) }),
            userGrants = hostGrants,
        )
        val doer = HostedAgent(
            id = DOER_ID,
            role = DOER_ROLE,
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = CountingUpstreamLlmClient(fixedAnswer { TOOL_RESULT }),
            userGrants = doerGrants,
        )
        return rosterRunHost(createEventApi = door::doorFor, agentScope = scope).open(
            roster = roster(),
            seats = mapOf(HOST_ROLE to host, DOER_ROLE to doer),
            goal = goal(GOAL_TEXT),
            tools = setOf(plugTool(onPlugToolRun), recordingTool(PLAIN_TOOL, TOOL_RESULT, dispatched)),
        )
    }

    /** A tool behind [MANIFEST], which requires [PERMISSION]. Records, runs [onRun], succeeds. */
    private fun plugTool(onRun: () -> Unit): Tool<*> = FunctionTool<ExecutionContext.NoChanges>(
        id = PLUG_TOOL,
        name = "Notify",
        description = "sends a notification through a Plug",
        requiredAgentAutonomy = AgentActionAutonomy.FULLY_AUTONOMOUS,
        plugManifest = MANIFEST,
        executionFunction = { request ->
            dispatched += request
            onRun()
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

    private fun roster(): RosterConfig = RosterConfig(
        host = HOST_ROLE,
        roles = listOf(
            RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
            RoleConfig(
                id = DOER_ROLE,
                title = "Doer",
                instructions = PROMPT,
                tools = setOf(PLUG_TOOL, PLAIN_TOOL),
            ),
        ),
    )

    /**
     * Every `PermissionDeniedEvent` in the store, from any door.
     *
     * Read from the whole store rather than by run id: the engine publishes the denial
     * without one on its envelope, so a `run_id` query would not find it.
     */
    private fun permissionDenials(): List<PermissionDeniedEvent> =
        door.database.eventStoreQueries.getAllEvents().executeAsList()
            .map { DEFAULT_JSON.decodeFromString(Event.serializer(), it.payload) }
            .filterIsInstance<PermissionDeniedEvent>()

    /** The id this run offers the planner for a tool of the doer seat: `<seat>/<tool>`. */
    private fun String.seatId(): String = SeatTool(DOER_ROLE, this).id

    private companion object {
        const val SDK_ID = "hosted-run-grants-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val PLUG_TOOL = "notify"
        const val PLAIN_TOOL = "search"
        const val TOOL_RESULT = "done"
        const val GOAL_TEXT = "Tell the owner the upload finished"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )

        val PERMISSION: PlugPermission = PlugPermission.NativeAction("send-notification")
        val MANIFEST = PlugManifest(
            id = PlugId("example-notifier"),
            name = "Example Notifier",
            version = "1.0.0",
            requiredPermissions = listOf(PERMISSION),
        )
    }
}
