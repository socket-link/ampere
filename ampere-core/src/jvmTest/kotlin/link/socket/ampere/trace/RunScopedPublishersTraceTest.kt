package link.socket.ampere.trace

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.definition.AgentId
import link.socket.ampere.agents.definition.AutonomousAgent
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.domain.RunId
import link.socket.ampere.agents.domain.cognition.CognitiveAffinity
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkManager
import link.socket.ampere.agents.domain.event.CognitivePhaseEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.SparkAppliedEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.memory.AgentMemoryService
import link.socket.ampere.agents.domain.memory.MemoryContext
import link.socket.ampere.agents.domain.memory.MemoryTaskTypes
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.Outcome
import link.socket.ampere.agents.domain.reasoning.Idea
import link.socket.ampere.agents.domain.reasoning.Perception
import link.socket.ampere.agents.domain.reasoning.Plan
import link.socket.ampere.agents.domain.state.AgentState
import link.socket.ampere.agents.domain.status.TaskStatus
import link.socket.ampere.agents.domain.task.AssignedTo
import link.socket.ampere.agents.domain.task.Task
import link.socket.ampere.agents.events.InMemoryEventDoor
import link.socket.ampere.agents.execution.request.ExecutionRequest
import link.socket.ampere.agents.execution.tools.Tool
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.data.DatabaseSchemaManager
import link.socket.ampere.db.Database
import link.socket.ampere.db.events.EventStore
import link.socket.ampere.db.memory.KnowledgeStore
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.stubAgentConfiguration

/**
 * H2 (AMPR-386): the three publishers that run *inside* a run — [PhaseSparkManager],
 * [link.socket.ampere.agents.definition.ObservableAgent] and [AgentMemoryService] — put the run
 * id on the envelope, so `ArcTraceProjection.project(runId)` returns a run's phase brackets,
 * spark applications and recall.
 *
 * The projection's row query is `getEventsByRunIdOrPayload`, whose `payload LIKE` arm keeps
 * legacy run-less rows reachable. To show the envelope alone is enough, the fallback-free
 * `getEventsByRunId` rows are copied into a second database and the trace is folded over *that*:
 * every row in it matches on `run_id`, so the `LIKE` arm cannot contribute anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunScopedPublishersTraceTest {

    private val doorScope = TestScope(UnconfinedTestDispatcher())

    /** The runtime loop sleeps between iterations, so it needs a real clock, not a virtual one. */
    private val loopScope = CoroutineScope(Dispatchers.Default)
    private lateinit var door: InMemoryEventDoor.Handle

    @BeforeTest
    fun setUp() {
        door = InMemoryEventDoor.open(agentId = AGENT_ID, scope = doorScope)
    }

    @AfterTest
    fun tearDown() {
        loopScope.cancel()
        door.close()
    }

    @Test
    fun `a bracketed phase a pushed spark and a recall are all on the run's trace without the payload fallback`() =
        runBlocking<Unit> {
            val memory = AgentMemoryService(
                agentId = AGENT_ID,
                knowledgeRepository = KnowledgeRepositoryImpl(door.database, door.driver),
                eventApi = door.api,
            )
            // Seeded outside the run, so the recall has something to score and the entry's own
            // `KnowledgeStored` cannot be mistaken for one of this run's rows.
            memory.storeKnowledge(
                knowledge = knowledge("seeded approach"),
                tags = listOf("code"),
                taskType = TASK_TYPE,
                runId = null,
            ).getOrThrow()

            val agent = SparkBasedAgent(
                agentId = AGENT_ID,
                cognitiveAffinity = CognitiveAffinity.ANALYTICAL,
                initialState = AgentState(),
                _eventApi = door.api,
                _memoryService = memory,
                _aiConfiguration = AI_CONFIGURATION,
                _runId = RUN_ID,
            )
            assertEquals(RUN_ID, agent.currentRunId, "the Arc's run id is the agent's own")

            // Phase brackets are off by default (H3), so the manager is built enabled here; the
            // run id it stamps comes from the agent, not from this call site.
            val manager = PhaseSparkManager.internalCreate(
                agent = agent,
                enabled = true,
                eventApi = door.api,
            )
            val recalled = manager.withPhase(CognitivePhase.PLAN) {
                agent.recallRelevantKnowledge(
                    context = MemoryContext(
                        taskType = TASK_TYPE,
                        tags = setOf("code"),
                        description = "seeded approach",
                    ),
                ).getOrThrow()
            }
            assertTrue(recalled.isNotEmpty(), "the seeded entry should be recalled")

            // `SparkAppliedEvent` leaves through ObservableAgent's own observability scope, so
            // the row lands a moment after `withPhase` returns.
            val rows = awaitEnvelopeRows(
                CognitivePhaseEvent.PhaseEntered.EVENT_TYPE,
                CognitivePhaseEvent.PhaseExited.EVENT_TYPE,
                SparkAppliedEvent.EVENT_TYPE,
                MemoryEvent.KnowledgeRecalled.EVENT_TYPE,
            )
            assertTrue(
                rows.none { it.event_type == MemoryEvent.KnowledgeStored.EVENT_TYPE },
                "the entry seeded outside the run is not on the run's envelope",
            )

            val recall = rows.single { it.event_type == MemoryEvent.KnowledgeRecalled.EVENT_TYPE }
            val decoded = DEFAULT_JSON.decodeFromString(Event.serializer(), recall.payload)
            assertEquals(
                RUN_ID,
                (decoded as MemoryEvent.KnowledgeRecalled).runId,
                "KnowledgeRecalled carries the run in its own field too",
            )

            withFallbackFreeDatabase(rows) { database ->
                val trace = ArcTraceProjection(database).project(runId = RUN_ID, arcId = ARC_ID).getOrThrow()

                val plan = assertNotNull(
                    trace.phases.singleOrNull { it.name == CognitivePhase.PLAN.name },
                    "the bracketed phase is a phase of the run; got ${trace.phases.map { it.name }}",
                )
                val planTypes = plan.events.map { it.eventType }
                assertTrue(
                    CognitivePhaseEvent.PhaseEntered.EVENT_TYPE in planTypes &&
                        CognitivePhaseEvent.PhaseExited.EVENT_TYPE in planTypes,
                    "both brackets are filed under PLAN; got $planTypes",
                )
                assertTrue(
                    SparkAppliedEvent.EVENT_TYPE in planTypes,
                    "the pushed phase spark is filed under PLAN; got $planTypes",
                )

                val recallPhase = assertNotNull(
                    trace.phases.singleOrNull { it.name == RECALL_PHASE },
                    "the recall is a phase of the run; got ${trace.phases.map { it.name }}",
                )
                assertTrue(
                    MemoryEvent.KnowledgeRecalled.EVENT_TYPE in recallPhase.events.map { it.eventType },
                    "KnowledgeRecalled is filed under RECALL",
                )
            }
        }

    /**
     * Task 2: the Learn phase stamps the agent's run on `KnowledgeStored`. Before AMPR-386 it
     * stamped `task.id`, which put a task id in the `run_id` column and orphaned the entry from
     * its run.
     */
    @Test
    fun `the runtime loop stores knowledge under the agent's run and not under the task id`() = runBlocking<Unit> {
        val memory = AgentMemoryService(
            agentId = AGENT_ID,
            knowledgeRepository = KnowledgeRepositoryImpl(door.database, door.driver),
            eventApi = door.api,
        )
        val agent = LoopAgent(memory = memory, run = RUN_ID)

        // The loop idles without an assignment (AMPR-397), so hand it one. Its id is what the
        // pre-AMPR-386 code would have stamped as the run.
        agent.rememberNewTask(LOOP_TASK)
        agent.initialize(loopScope)
        try {
            withTimeout(TIMEOUT_MS) {
                while (knowledgeUnder(RUN_ID).isEmpty()) {
                    delay(POLL_MS)
                }
            }
        } finally {
            agent.shutdownAgent()
        }

        assertTrue(knowledgeUnder(RUN_ID).isNotEmpty(), "the loop closes through Knowledge tagged with the run")
        assertTrue(knowledgeUnder(TASK_ID).isEmpty(), "the task id is not a run id")

        val rows = envelopeRows()
        assertTrue(
            rows.any { it.event_type == MemoryEvent.KnowledgeStored.EVENT_TYPE },
            "the KnowledgeStored envelope carries the run; got ${rows.map { it.event_type }}",
        )
    }

    /** Polls the fallback-free `run_id` query until every one of [eventTypes] has been recorded. */
    private suspend fun awaitEnvelopeRows(vararg eventTypes: String): List<EventStore> {
        val expected = eventTypes.toSet()
        withTimeout(TIMEOUT_MS) {
            while (!expected.all { it in envelopeRows().map { row -> row.event_type }.toSet() }) {
                delay(POLL_MS)
            }
        }
        return envelopeRows()
    }

    /** The run's rows with the projection's `payload LIKE` arm left out. */
    private fun envelopeRows(): List<EventStore> =
        door.database.eventStoreQueries.getEventsByRunId(RUN_ID).executeAsList()

    /**
     * Runs [block] against a database holding only [rows] — the rows the envelope-only query
     * returned. The projection's `payload LIKE` arm has nothing extra to find here, so whatever
     * the trace shows came off `run_id`.
     */
    private suspend fun withFallbackFreeDatabase(
        rows: List<EventStore>,
        block: suspend (Database) -> Unit,
    ) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            DatabaseSchemaManager.ensure(driver).getOrThrow()
            val database = Database(driver)
            rows.forEach { row ->
                database.eventStoreQueries.insertEvent(
                    event_id = row.event_id,
                    event_type = row.event_type,
                    source_id = row.source_id,
                    timestamp = row.timestamp,
                    payload = row.payload,
                    run_id = row.run_id,
                    sequence = row.sequence,
                    caused_by = row.caused_by,
                    recorded_at = row.recorded_at,
                    truncated = row.truncated,
                )
            }
            block(database)
        } finally {
            driver.close()
        }
    }

    private fun knowledgeUnder(runId: RunId): List<KnowledgeStore> =
        door.database.knowledgeStoreQueries.findKnowledgeByRunId(runId).executeAsList()

    private fun knowledge(approach: String): Knowledge = Knowledge.FromOutcome(
        outcomeId = approach,
        approach = approach,
        learnings = "learned from $approach",
        timestamp = Clock.System.now(),
    )

    /** A minimal loop agent: real `runtimeLoop`, stubbed reasoning, one declared run. */
    private class LoopAgent(
        memory: AgentMemoryService,
        run: RunId,
        override val agentConfiguration: AgentConfiguration = stubAgentConfiguration(),
    ) : AutonomousAgent<AgentState>() {
        override val id: AgentId = AGENT_ID
        override val initialState: AgentState = AgentState()
        override val memoryService: AgentMemoryService = memory
        override val currentRunId: RunId = run

        override val runLLMToEvaluatePerception: (Perception<AgentState>) -> Idea = { Idea.blank }
        override val runLLMToPlan: (Task, List<Idea>) -> Plan = { _, _ -> LOOP_PLAN }
        override val runLLMToExecuteTask: (Task) -> Outcome = { LOOP_OUTCOME }
        override val runLLMToExecuteTool: (Tool<*>, ExecutionRequest<*>) -> ExecutionOutcome = { _, _ -> LOOP_OUTCOME }
        override val runLLMToEvaluateOutcomes: (List<Outcome>) -> Idea = { Idea.blank }

        override fun extractKnowledgeFromOutcome(outcome: Outcome, task: Task, plan: Plan): Knowledge =
            Knowledge.FromOutcome(
                outcomeId = outcome.id,
                approach = "loop approach",
                learnings = "loop learnings",
                timestamp = Clock.System.now(),
            )
    }

    private companion object {
        const val AGENT_ID = "run-scoped-publisher"
        const val RUN_ID = "run-ampr-386"
        const val ARC_ID = "arc-ampr-386"
        const val TASK_ID = "task-ampr-386"
        val TASK_TYPE = MemoryTaskTypes.CODE_CHANGE
        const val RECALL_PHASE = "RECALL"
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L

        val AI_CONFIGURATION: AIConfiguration =
            AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

        val LOOP_TASK = Task.CodeChange(
            id = TASK_ID,
            status = TaskStatus.InProgress,
            description = "stamp the run not the task",
            assignedTo = AssignedTo.Agent(AGENT_ID),
        )

        val LOOP_OUTCOME = ExecutionOutcome.NoChanges.Success(
            executorId = AGENT_ID,
            ticketId = "ticket-ampr-386",
            taskId = TASK_ID,
            executionStartTimestamp = Clock.System.now(),
            executionEndTimestamp = Clock.System.now(),
            message = "ok",
        )

        val LOOP_PLAN = Plan.ForIdea(
            idea = Idea(name = "loop idea"),
            estimatedComplexity = 1,
            tasks = listOf(LOOP_TASK),
        )
    }
}
