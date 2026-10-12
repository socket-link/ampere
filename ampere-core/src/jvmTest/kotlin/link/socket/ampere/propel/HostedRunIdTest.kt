package link.socket.ampere.propel

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.TaskEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf
import link.socket.ampere.roster.PromptRef
import link.socket.ampere.roster.RoleConfig
import link.socket.ampere.roster.RoleId
import link.socket.ampere.roster.RosterConfig
import link.socket.ampere.roster.SeatTool

/**
 * Task 2 of AMPR-423: the caller may name the run (AMPR-385 row H27).
 *
 * A consumer that reserved budget, opened its own trace or filed records under an id
 * before the run existed hands that id to `open`, and every place a hosted run puts its
 * id has to be that one — otherwise the consumer is back to two ids and a map between
 * them, which is what the parameter exists to remove.
 *
 * `runBlocking`, not `runTest`, for the same reason as [HostedRunTest].
 */
class HostedRunIdTest {

    private lateinit var scope: CoroutineScope
    private lateinit var door: InMemoryEventDoor.Handle
    private lateinit var store: MemoryStore

    private val dispatched = CopyOnWriteArrayList<ExecutionRequest<*>>()

    /** Every seat door the host built, in order — how a test tells whether seats were built. */
    private val doorsBuilt = CopyOnWriteArrayList<AgentId>()

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        door = InMemoryEventDoor.open(agentId = SDK_ID, scope = scope)
        store = memoryStoreOf(
            knowledge = KnowledgeRepositoryImpl(door.database, door.driver),
            outcomes = OutcomeMemoryRepositoryImpl(door.database),
        )
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        door.close()
    }

    @Test
    fun `a supplied run id is the run's id, the result's id, and the run id on every published event`() =
        runBlocking<Unit> {
            val runHost = runHost()

            val run = runHost.open(
                roster = roster(),
                seats = seats(),
                goal = goal(GOAL_TEXT),
                tools = setOf(searchTool),
                runId = CALLER_RUN_ID,
            )
            assertEquals(CALLER_RUN_ID, run.runId, "the run is known by the id its caller chose")

            val result = run.execute()
            run.close()
            assertEquals(CALLER_RUN_ID, result.runId)

            // --- every event any seat published, read without filtering by run --------
            val seatRows = door.database.eventStoreQueries.getAllEvents().executeAsList()
                .filter { it.source_id in setOf(HOST_ID, DOER_ID) }
            assertTrue(seatRows.isNotEmpty(), "the seats published something")
            val strays = seatRows.filter { it.run_id != CALLER_RUN_ID }
            assertTrue(
                strays.isEmpty(),
                "every envelope a seat published carries the supplied id; these do not: " +
                    strays.map { "${it.event_type}=${it.run_id}" },
            )

            // --- the run's task lifecycle -------------------------------------------
            val events = seatRows.map(::decode)
            val created = events.filterIsInstance<Event.TaskCreated>().map { it.taskId }
            assertTrue(
                CALLER_RUN_ID in created,
                "the run's task is created under the supplied id; got $created",
            )
            assertEquals(
                CALLER_RUN_ID,
                events.filterIsInstance<TaskEvent.TaskCompleted>().single().taskId,
                "and ends under it",
            )

            // --- the tool, and LEARN's outcome --------------------------------------
            assertEquals(CALLER_RUN_ID, dispatched.single().runId, "the tool was dispatched under it")
            // The engine records the tool's own outcome under the run too (AMPR-406); the
            // run's is the one keyed by the run id as its ticket.
            val underRun = door.database.outcomeMemoryStoreQueries
                .getOutcomesByRunId(CALLER_RUN_ID)
                .executeAsList()
            val learned = underRun.filter { it.ticket_id == CALLER_RUN_ID }
            assertEquals(
                1,
                learned.size,
                "LEARN records one outcome for the run, under the supplied id as both its " +
                    "run id and its ticket id; under the run: ${underRun.map { it.ticket_id }}",
            )
            assertEquals(HOST_ID, learned.single().actor_id, "decided by the host seat")
            assertEquals(
                RosterHostedRun.HOSTED_RUN_EXECUTOR_ID,
                learned.single().executor_id,
                "and performed by the run, not filed under the seat's id as an executor (AMPR-443)",
            )
        }

    @Test
    fun `a blank run id is refused`() = runBlocking<Unit> {
        val runHost = runHost()

        listOf("", "   ").forEach { blank ->
            val failure = runCatching {
                runHost.open(roster = roster(), seats = seats(), goal = goal(GOAL_TEXT), runId = blank)
            }.exceptionOrNull()

            assertTrue(
                failure is IllegalArgumentException && failure.message.orEmpty().contains("blank"),
                "a blank id files the run under nothing; got $failure for '$blank'",
            )
        }
        assertTrue(doorsBuilt.isEmpty(), "refused before any seat was built; built $doorsBuilt")
        assertTrue(runHost.openRunSeats().isEmpty(), "and nothing was left registered")
    }

    @Test
    fun `an id already open on this host is refused, and accepted again after that run closes`() =
        runBlocking<Unit> {
            val runHost = runHost()

            val first = runHost.open(roster = roster(), seats = seats(), goal = goal(GOAL_TEXT), runId = CALLER_RUN_ID)
            val doorsForFirst = doorsBuilt.size
            val hostPlanCallsForFirst = hostTransport.prompts.size

            val failure = runCatching {
                runHost.openSeats(
                    roster = roster(),
                    seats = seats().values.toList(),
                    goal = goal(GOAL_TEXT),
                    runId = CALLER_RUN_ID,
                )
            }.exceptionOrNull()
            assertTrue(
                failure is IllegalArgumentException && failure.message.orEmpty().contains(CALLER_RUN_ID),
                "two open runs cannot share an id; got $failure",
            )
            assertEquals(doorsForFirst, doorsBuilt.size, "the refused run built no seat")
            assertEquals(hostPlanCallsForFirst, hostTransport.prompts.size, "and charged no phase")
            assertEquals(
                setOf(CALLER_RUN_ID),
                runHost.openRunSeats().mapTo(mutableSetOf()) { it.runId },
                "the first run is still open, and is the only one",
            )

            first.execute()
            first.close()
            assertTrue(runHost.openRunSeats().isEmpty(), "the first run closed")

            val second = runHost.open(roster = roster(), seats = seats(), goal = goal(GOAL_TEXT), runId = CALLER_RUN_ID)
            assertEquals(CALLER_RUN_ID, second.runId, "a closed run's id is free again")
            second.close()
        }

    @Test
    fun `with no id supplied one is minted, and two runs get different ids`() = runBlocking<Unit> {
        val runHost = runHost()

        val first = runHost.open(roster = roster(), seats = seats(), goal = goal(GOAL_TEXT))
        val second = runHost.open(roster = roster(), seats = seats(), goal = goal(GOAL_TEXT))

        assertTrue(first.runId.isNotBlank() && second.runId.isNotBlank())
        assertNotEquals(first.runId, second.runId, "every minted id is its own run's")
        assertEquals(
            setOf(first.runId, second.runId),
            runHost.openRunSeats().mapTo(mutableSetOf()) { it.runId },
            "both are open at once, under their own ids",
        )

        first.close()
        second.close()
    }

    // ========================================================================
    // Fixture
    // ========================================================================

    private val hostTransport = CountingUpstreamLlmClient(
        hostAnswers { planJson(planStep("Search", DOER_TOOL.seatId(), DOER_ROLE.value)) },
    )

    private fun seats(): Map<RoleId, HostedAgent> = mapOf(
        HOST_ROLE to HostedAgent(
            id = HOST_ID,
            role = HOST_ROLE,
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = hostTransport,
            memory = store,
        ),
        DOER_ROLE to HostedAgent(
            id = DOER_ID,
            role = DOER_ROLE,
            aiConfiguration = AI_CONFIGURATION,
            upstreamLlmClient = CountingUpstreamLlmClient(fixedAnswer { TOOL_RESULT }),
            memory = store,
        ),
    )

    /** A host whose door factory records each seat it is asked to build a door for. */
    private fun runHost(): RunHost = rosterRunHost(
        createEventApi = { id: AgentId ->
            doorsBuilt += id
            door.doorFor(id)
        },
        agentScope = scope,
    )

    private fun roster(): RosterConfig = RosterConfig(
        host = HOST_ROLE,
        roles = listOf(
            RoleConfig(id = HOST_ROLE, title = "Host", instructions = PROMPT),
            RoleConfig(id = DOER_ROLE, title = "Doer", instructions = PROMPT, tools = setOf(DOER_TOOL)),
        ),
    )

    private val searchTool = recordingTool(DOER_TOOL, TOOL_RESULT, dispatched)

    private fun decode(row: EventStore): Event =
        DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload)

    /** The id this run offers the planner for a tool of the doer seat: `<seat>/<tool>`. */
    private fun String.seatId(): String = SeatTool(DOER_ROLE, this).id

    private companion object {
        const val SDK_ID = "hosted-run-id-test-sdk"
        const val HOST_ID = "seat-host"
        const val DOER_ID = "seat-doer"
        const val DOER_TOOL = "search"
        const val TOOL_RESULT = "found the retry policy in Uploader.kt"
        const val GOAL_TEXT = "Add a retry to the uploader"
        const val CALLER_RUN_ID = "consumer-run-2026-10-11-0001"

        val HOST_ROLE = RoleId("planner")
        val DOER_ROLE = RoleId("scout")
        val PROMPT = PromptRef(id = "test-prompt", version = 1)
        val AI_CONFIGURATION = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = AIModel_Claude.Sonnet_5,
        )
    }
}
