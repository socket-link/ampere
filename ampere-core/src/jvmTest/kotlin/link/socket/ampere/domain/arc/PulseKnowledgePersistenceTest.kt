package link.socket.ampere.domain.arc

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import okio.Path
import okio.Path.Companion.toPath

/**
 * AMPR-402: the success-path half of the PROPEL closing invariant.
 *
 * Pulse used to build a `Knowledge.FromOutcome` per successful outcome, hand it back on
 * [PulseResult], and store nothing — so a completed Arc run left no entry a later Recall could
 * find, and `docs/concepts/propel-loop.md`'s "every successful Arc run ends with at least one
 * Knowledge entry tagged with the run id" was false of every run.
 *
 * The run's agents come from a real [ChargePhase] rather than a fake, because the other half of
 * the defect was wiring: an agent is only given an `AgentMemoryService` when it has *both* a
 * `KnowledgeRepository` and a door, and nothing threaded the repository down to the spawner.
 * Flow is stood in for with a hand-built [FlowResult] — the Arc pipeline produces no
 * `Outcome.Success` without a real executor, and what is under test is what Pulse does with one.
 *
 * `runBlocking`, not `runTest`: every write here crosses the door's `ioDispatcher` hop, which a
 * virtual clock skips past.
 */
class PulseKnowledgePersistenceTest {

    private lateinit var infraScope: CoroutineScope
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventApiFactory: AgentEventApiFactory
    private lateinit var knowledgeRepository: KnowledgeRepository

    @BeforeTest
    fun setUp() {
        infraScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        eventApiFactory = AgentEventApiFactory(
            eventRepository = EventRepository(DEFAULT_JSON, infraScope, database),
            eventSerialBus = EventSerialBus(infraScope),
        )
        knowledgeRepository = KnowledgeRepositoryImpl(database, driver)
    }

    @AfterTest
    fun tearDown() {
        infraScope.cancel()
        driver.close()
    }

    @Test
    fun `pulse stores a learning per successful outcome under the run id`() = runBlocking<Unit> {
        val runId = "pulse-knowledge-run"
        val arcConfig = arcConfig()
        val spawnScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val charge = chargePhase(arcConfig, spawnScope, runId, knowledgeRepository)
                .execute("Add a health check endpoint")
            val agent = charge.agents.single()
            val outcome = codeChangedSuccess(agent.id)

            val pulse = PulsePhase(
                arcConfig = arcConfig,
                flowResult = completedFlow(charge, agent.id, outcome),
                projectContext = charge.projectContext,
                goalTree = charge.goalTree,
                agents = charge.agents,
                runId = runId,
            ).execute()

            assertTrue(pulse.success, "Every goal completed and nothing failed, so Pulse succeeded")
            val learning = pulse.learnings.single()
            assertEquals(agent.id, learning.agentId)
            assertTrue(learning.stored, "The learning must have been persisted, not just built")

            // The ticket's read-back: the entry is reachable by the task type Recall asks for.
            val byTaskType = knowledgeRepository
                .findKnowledgeByTaskType(MemoryTaskTypes.CODE_CHANGE)
                .getOrThrow()
            val entry = assertNotNull(
                byTaskType.singleOrNull { it.outcomeId == outcome.id },
                "findKnowledgeByTaskType(code_change) must return the outcome's entry",
            )
            assertEquals(KnowledgeType.FROM_OUTCOME, entry.knowledgeType)
            assertTrue(
                entry.tags.containsAll(listOf("arc", arcConfig.name, "success")),
                "Expected the Arc's tags on the entry, got ${entry.tags}",
            )

            // The stored row is indexed by run_id, which is what ArcTraceProjection reads it by.
            assertEquals(
                listOf(entry.id),
                database.knowledgeStoreQueries.findKnowledgeByRunId(runId).executeAsList().map { it.id },
                "The entry must be findable by the run that produced it",
            )

            // And the event: persisted through the agent's own door, under this run.
            val stored = knowledgeStoredEvents(runId).single()
            assertEquals(runId, stored.runId, "KnowledgeStored must carry the run id")
            assertEquals(entry.id, stored.knowledgeId)
            assertEquals(
                EventSource.Agent(agent.id),
                stored.eventSource,
                "The write is attributed to the agent that earned it, not to the runtime",
            )
        } finally {
            spawnScope.cancel()
        }
    }

    @Test
    fun `pulse reports learnings unstored when the run has no knowledge repository`() = runBlocking<Unit> {
        val runId = "pulse-no-repository-run"
        val arcConfig = arcConfig()
        val spawnScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val charge = chargePhase(arcConfig, spawnScope, runId, knowledgeRepository = null)
                .execute("Add a health check endpoint")
            val agent = charge.agents.single()

            val pulse = PulsePhase(
                arcConfig = arcConfig,
                flowResult = completedFlow(charge, agent.id, codeChangedSuccess(agent.id)),
                projectContext = charge.projectContext,
                goalTree = charge.goalTree,
                agents = charge.agents,
                runId = runId,
            ).execute()

            // The cell is still built and still reported — the phase ran. It just has nowhere to go.
            assertFalse(
                pulse.learnings.single().stored,
                "An agent with no memory service cannot store its learning",
            )
            assertTrue(
                knowledgeRepository.findKnowledgeByTaskType(MemoryTaskTypes.CODE_CHANGE).getOrThrow().isEmpty(),
            )
            assertTrue(knowledgeStoredEvents(runId).isEmpty(), "Nothing stored means nothing published")
        } finally {
            spawnScope.cancel()
        }
    }

    private fun arcConfig(): ArcConfig = ArcConfig(
        name = "pulse-knowledge-arc",
        agents = listOf(ArcAgentConfig(role = "code")),
        orchestration = OrchestrationConfig(
            type = OrchestrationType.SEQUENTIAL,
            order = listOf("code"),
        ),
    )

    private fun chargePhase(
        arcConfig: ArcConfig,
        spawnScope: CoroutineScope,
        runId: String,
        knowledgeRepository: KnowledgeRepository?,
    ): ChargePhase = ChargePhase(
        arcConfig = arcConfig,
        projectDir = arcProjectDir(),
        agentScope = spawnScope,
        runId = runId,
        eventApiFactory = { agentId -> eventApiFactory.create(agentId) },
        knowledgeRepository = knowledgeRepository,
    )

    /** A Flow that met every goal, with one successful outcome attributed to [agentId]. */
    private fun completedFlow(
        charge: ChargeResult,
        agentId: String,
        outcome: ExecutionOutcome.CodeChanged.Success,
    ): FlowResult = FlowResult(
        completedGoals = charge.goalTree.allNodes(),
        finalTick = 1,
        agentOutcomes = mapOf(agentId to listOf(outcome)),
        terminationReason = TerminationReason.GOAL_COMPLETE,
    )

    private fun codeChangedSuccess(agentId: String): ExecutionOutcome.CodeChanged.Success =
        ExecutionOutcome.CodeChanged.Success(
            executorId = agentId,
            ticketId = "ticket-pulse-knowledge",
            taskId = "task-pulse-knowledge",
            executionStartTimestamp = Instant.fromEpochMilliseconds(1_000),
            executionEndTimestamp = Instant.fromEpochMilliseconds(2_000),
            changedFiles = listOf("HealthCheck.kt"),
            validation = ExecutionResult(
                codeChanges = null,
                compilation = null,
                linting = null,
                tests = null,
            ),
        )

    /**
     * Every `KnowledgeStored` the store holds for [runId], read back from the rows rather than
     * from the bus — the row's `run_id` column is the durable half of "carrying the run id".
     *
     * Undecodable rows are skipped rather than failing the read: this build is not the only
     * writer to the store, and the assertion is about the events it can see.
     */
    private fun knowledgeStoredEvents(runId: String): List<MemoryEvent.KnowledgeStored> =
        database.eventStoreQueries
            .getEventsByRunId(runId)
            .executeAsList()
            .mapNotNull { row ->
                runCatching { DEFAULT_JSON.decodeFromString(Event.serializer(), row.payload) }.getOrNull()
            }
            .filterIsInstance<MemoryEvent.KnowledgeStored>()

    /** A temp dir with the AGENTS.md/README.md that ChargePhase requires to produce a context. */
    private fun arcProjectDir(): Path {
        val tempDir = createTempDirectory("pulse-knowledge-project")
        tempDir.resolve("README.md").writeText("# PulseKnowledgeProject\n\nA test project for Pulse's writes.")
        tempDir.resolve("AGENTS.md").writeText(
            """
            # AGENTS

            ## Dependencies
            - Kotlin

            ## Conventions
            - Use suspend functions

            ## Architecture
            - Clean architecture
            """.trimIndent(),
        )
        return tempDir.toString().toPath()
    }
}
