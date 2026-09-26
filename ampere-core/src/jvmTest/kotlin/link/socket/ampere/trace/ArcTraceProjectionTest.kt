package link.socket.ampere.trace

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.ArcRunEvent
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.Knowledge
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.domain.outcome.ExecutionOutcome
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.domain.routing.capability.CostPolicy
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.domain.routing.capability.ModelDescriptor
import link.socket.ampere.agents.domain.routing.capability.ProviderCapability
import link.socket.ampere.agents.events.EventEnvelope
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.execution.results.ExecutionResult
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.model.AIModelFeatures.RelativeReasoning
import link.socket.ampere.domain.ai.model.AIModelFeatures.SupportedInputs
import link.socket.ampere.domain.arc.ArcPhase
import link.socket.ampere.domain.arc.CompletionRecord
import link.socket.ampere.domain.arc.TerminationReason

@OptIn(ExperimentalCoroutinesApi::class)
class ArcTraceProjectionTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventRepository: EventRepository
    private lateinit var projection: ArcTraceProjection

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        projection = ArcTraceProjection(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `projects run events and memory rows into phase trace`() = runTest {
        val runId = "run-trace-1"
        val arcId = "startup-saas"

        seedTrace(runId)

        val trace = projection.project(runId = runId, arcId = arcId).getOrThrow()

        assertEquals(runId, trace.runId)
        assertEquals(arcId, trace.arcId)
        assertEquals(Instant.fromEpochMilliseconds(1_000), trace.startedAt)

        val plan = assertNotNull(trace.phases.firstOrNull { it.name == "PLAN" })
        assertEquals(1, plan.modelInvocations.size)
        assertEquals(1_000, plan.modelInvocations.single().inputTokens)
        assertEquals(1.5, plan.wattCost.watts, absoluteTolerance = 0.0000001)

        val execute = assertNotNull(trace.phases.firstOrNull { it.name == "EXECUTE" })
        assertEquals(1, execute.toolCalls.size)
        assertEquals("write-code", execute.toolCalls.single().toolId)
        assertEquals(1, execute.modelInvocations.size)

        val learn = assertNotNull(trace.phases.firstOrNull { it.name == "LEARN" })
        assertEquals(1, learn.memoryWrites.size)
        assertEquals("knowledge-1", learn.memoryWrites.single().id)
        assertEquals("Use trace projections", learn.memoryWrites.single().approach)
    }

    @Test
    fun `projecting a run window matches projecting its runId`() = runTest {
        val runId = "run-trace-window"
        val arcId = "startup-saas"

        seedTrace(runId)

        val byWindow = projection.project(ReplayWindow.ArcRun(runId), arcId = arcId).getOrThrow()
        val byRunId = projection.project(runId = runId, arcId = arcId).getOrThrow()

        assertEquals(byRunId, byWindow)
        assertEquals(ReplayWindow.ArcRun(runId), byWindow.window)
    }

    @Test
    fun `call routed to a Free descriptor records zero Watts`() = runTest {
        val runId = "run-free-provider"
        // Keyed by the completed call's model id, since cost resolution is now
        // model-granular (AMPR-214).
        val registry = InMemoryModelDescriptorRegistry(
            seed = listOf(
                ModelDescriptor(
                    modelName = "local-llm",
                    providerId = "local",
                    capabilities = setOf(ProviderCapability.WORLD_KNOWLEDGE),
                    reasoning = RelativeReasoning.NORMAL,
                    maxContextTokens = 8_000,
                    supportedInputs = SupportedInputs.TEXT,
                    cost = CostPolicy.Free,
                    availabilityGated = true,
                ),
            ),
        )
        val freeAwareProjection = ArcTraceProjection(
            database = database,
            modelDescriptorRegistry = registry,
        )

        eventRepository.saveEvent(
            ProviderCallCompletedEvent(
                eventId = "free-complete",
                timestamp = Instant.fromEpochMilliseconds(1_000),
                eventSource = EventSource.Agent("planner-agent"),
                workflowId = runId,
                agentId = "planner-agent",
                cognitivePhase = CognitivePhase.PLAN,
                providerId = "local",
                modelId = "local-llm",
                usage = TokenUsage(
                    inputTokens = 1_000,
                    outputTokens = 500,
                ),
                latencyMs = 250,
                success = true,
            ),
        ).getOrThrow()

        val trace = freeAwareProjection.project(runId).getOrThrow()
        val plan = assertNotNull(trace.phases.firstOrNull { it.name == "PLAN" })

        val invocation = plan.modelInvocations.single()
        assertEquals(0.0, invocation.wattCost.watts, absoluteTolerance = 0.0000001)
        assertEquals(1_000, invocation.wattCost.inputTokens)
        assertEquals(500, invocation.wattCost.outputTokens)
        assertEquals(0.0, plan.wattCost.watts, absoluteTolerance = 0.0000001)
    }

    @Test
    fun `projects one hundred event run within target budget`() = runTest {
        val runId = "run-trace-benchmark"
        repeat(100) { index ->
            eventRepository.saveEvent(
                ProviderCallCompletedEvent(
                    eventId = "complete-$index",
                    timestamp = Instant.fromEpochMilliseconds(1_000L + index),
                    eventSource = EventSource.Agent("agent-benchmark"),
                    workflowId = runId,
                    agentId = "agent-benchmark",
                    cognitivePhase = CognitivePhase.PLAN,
                    providerId = "openai",
                    modelId = "gpt-4.1",
                    usage = TokenUsage(
                        inputTokens = 10,
                        outputTokens = 5,
                    ),
                    latencyMs = 10,
                    success = true,
                ),
            ).getOrThrow()
        }

        projection.project(runId).getOrThrow()

        val mark = TimeSource.Monotonic.markNow()
        projection.project(runId).getOrThrow()
        val elapsed = mark.elapsedNow()

        // Generous enough to absorb shared-CI-runner noise (GC pauses, cold
        // caches) while still catching an accidental quadratic regression,
        // which would blow well past this on 100 events.
        assertTrue(
            elapsed.inWholeMilliseconds < 300,
            "Projection took ${elapsed.inWholeMilliseconds}ms",
        )
    }

    @Test
    fun `projects memory rows written through repositories with runId`() = runTest {
        val runId = "run-memory-repository"
        val timestamp = Instant.fromEpochMilliseconds(2_000)
        val knowledgeRepository = KnowledgeRepositoryImpl(database)
        val outcomeRepository = OutcomeMemoryRepositoryImpl(database)

        val storedKnowledge = knowledgeRepository.storeKnowledge(
            knowledge = Knowledge.FromOutcome(
                outcomeId = "outcome-memory-repository",
                approach = "Write through repository",
                learnings = "Run-attributed rows project into the trace",
                timestamp = timestamp,
            ),
            tags = listOf("trace"),
            taskType = "trace",
            runId = runId,
        ).getOrThrow()

        val executionOutcome = ExecutionOutcome.CodeChanged.Success(
            executorId = "agent-memory-repository",
            ticketId = "ticket-memory-repository",
            taskId = runId,
            executionStartTimestamp = timestamp,
            executionEndTimestamp = Instant.fromEpochMilliseconds(2_500),
            changedFiles = listOf("Trace.kt"),
            validation = ExecutionResult(
                codeChanges = null,
                compilation = null,
                linting = null,
                tests = null,
            ),
        )

        val storedOutcome = outcomeRepository.recordOutcome(
            ticketId = executionOutcome.ticketId,
            executorId = executionOutcome.executorId,
            approach = "Run tests after writing trace code",
            outcome = executionOutcome,
            timestamp = executionOutcome.executionEndTimestamp,
            runId = runId,
        ).getOrThrow()

        val trace = projection.project(runId).getOrThrow()
        val memoryWrites = trace.phases.flatMap { it.memoryWrites }

        val projectedKnowledge = assertNotNull(memoryWrites.firstOrNull { it.id == storedKnowledge.id })
        assertEquals("LEARN", projectedKnowledge.phaseName)
        assertEquals("Write through repository", projectedKnowledge.approach)
        assertEquals(listOf("trace"), projectedKnowledge.tags)

        val projectedOutcome = assertNotNull(memoryWrites.firstOrNull { it.id == storedOutcome.id })
        assertEquals("EXECUTE", projectedOutcome.phaseName)
        assertEquals("Run tests after writing trace code", projectedOutcome.approach)
    }

    @Test
    fun `a completion manifest folds into the run it closes out and leaves its phases alone`() = runTest {
        val runId = "run-trace-cancelled"
        seedTrace(runId)
        val record = cancelledRecord(runId)
        saveManifest(record, at = Instant.fromEpochMilliseconds(2_000))

        val trace = projection.project(runId).getOrThrow()

        assertEquals(record, trace.completion)
        assertEquals(Instant.fromEpochMilliseconds(2_000), trace.endedAt, "The run ended where it closed out")

        val run = assertNotNull(trace.phases.firstOrNull { it.name == "RUN" })
        assertEquals(listOf("manifest-$runId"), run.events.map { it.eventId })
        assertEquals(
            listOf("PLAN", "EXECUTE", "LEARN", "RUN"),
            trace.phases.map { it.name },
            "The run's own record joins the run envelope; the PROPEL phases are as they were",
        )
    }

    @Test
    fun `a run that wrote no manifest has no completion`() = runTest {
        seedTrace("run-trace-completed")

        assertNull(projection.project("run-trace-completed").getOrThrow().completion)
    }

    @Test
    fun `a run does not borrow the manifest of a run whose id contains its own`() = runTest {
        // The legacy payload match finds `run-short` inside `run-short-2`'s manifest row.
        seedTrace("run-short")
        saveManifest(cancelledRecord("run-short-2"), at = Instant.fromEpochMilliseconds(2_000))

        assertNull(projection.project("run-short").getOrThrow().completion)
        assertEquals("run-short-2", projection.project("run-short-2").getOrThrow().completion?.runId)
    }

    @Test
    fun `an event this build cannot decode is dropped but counted on the trace`() = runTest {
        val runId = "run-unknown-discriminator"
        seedTrace(runId)
        // A row written by a newer build: a discriminator this build has no serializer for.
        // The projection must still return the run's trace, not fail the whole window.
        insertUnknownEventRow(runId = runId, eventId = "future-1", sequence = 9_001)
        insertUnknownEventRow(runId = runId, eventId = "future-2", sequence = 9_002)

        val trace = projection.project(runId).getOrThrow()

        assertEquals(2, trace.undecodedEventCount)
        // Everything this build *could* read is still projected.
        assertEquals(1, assertNotNull(trace.phases.firstOrNull { it.name == "PLAN" }).modelInvocations.size)
    }

    @Test
    fun `a window this build reads in full reports no undecoded events`() = runTest {
        val runId = "run-fully-decodable"
        seedTrace(runId)

        assertEquals(0, projection.project(runId).getOrThrow().undecodedEventCount)
    }

    /**
     * Writes an `EventStore` row straight through the queries, bypassing `EventRepository`:
     * the repository can only write events this build can serialize, and the point of the row
     * is that it is one this build cannot read back.
     */
    private fun insertUnknownEventRow(runId: String, eventId: String, sequence: Long) {
        database.eventStoreQueries.insertEvent(
            event_id = eventId,
            event_type = "EventFromTheFuture",
            source_id = "agent:planner-agent",
            timestamp = 1_500,
            payload = """{"type":"link.socket.ampere.agents.domain.event.EventFromTheFuture","eventId":"$eventId"}""",
            run_id = runId,
            sequence = sequence,
            caused_by = null,
            recorded_at = 1_500,
            truncated = 0,
        )
    }

    private fun cancelledRecord(runId: String) = CompletionRecord(
        runId = runId,
        endedBy = TerminationReason.CANCELLED,
        phasesStarted = listOf(ArcPhase.CHARGE, ArcPhase.FLOW),
        phasesCompleted = listOf(ArcPhase.CHARGE),
        phasesNotRun = listOf(ArcPhase.PULSE),
        reachedTick = 3,
        unmetGoals = listOf(CompletionRecord.Goal(id = "goal-0", description = "Ship it")),
    )

    private suspend fun saveManifest(record: CompletionRecord, at: Instant) {
        eventRepository.saveEvent(
            ArcRunEvent.CompletionManifestRecorded(
                eventId = "manifest-${record.runId}",
                timestamp = at,
                eventSource = EventSource.Agent("ampere.arc-runtime"),
                record = record,
            ),
            envelope = EventEnvelope(runId = record.runId),
        ).getOrThrow()
    }

    private suspend fun seedTrace(runId: String) {
        eventRepository.saveEvent(
            ProviderCallStartedEvent(
                eventId = "plan-start",
                timestamp = Instant.fromEpochMilliseconds(1_000),
                eventSource = EventSource.Agent("planner-agent"),
                workflowId = runId,
                agentId = "planner-agent",
                cognitivePhase = CognitivePhase.PLAN,
                providerId = "openai",
                modelId = "gpt-4.1",
                routingReason = "agent_configuration",
            ),
        ).getOrThrow()

        eventRepository.saveEvent(
            ProviderCallCompletedEvent(
                eventId = "plan-complete",
                timestamp = Instant.fromEpochMilliseconds(1_250),
                eventSource = EventSource.Agent("planner-agent"),
                workflowId = runId,
                agentId = "planner-agent",
                cognitivePhase = CognitivePhase.PLAN,
                providerId = "openai",
                modelId = "gpt-4.1",
                usage = TokenUsage(
                    inputTokens = 1_000,
                    outputTokens = 500,
                    estimatedCost = 0.006,
                ),
                latencyMs = 250,
                success = true,
            ),
        ).getOrThrow()

        eventRepository.saveEvent(
            ProviderCallStartedEvent(
                eventId = "execute-start",
                timestamp = Instant.fromEpochMilliseconds(1_500),
                eventSource = EventSource.Agent("code-agent"),
                workflowId = runId,
                agentId = "code-agent",
                cognitivePhase = CognitivePhase.EXECUTE,
                providerId = "openai",
                modelId = "gpt-4.1",
                routingReason = "agent_configuration",
            ),
        ).getOrThrow()

        eventRepository.saveEvent(
            ToolEvent.ToolExecutionStarted(
                eventId = "tool-start",
                timestamp = Instant.fromEpochMilliseconds(1_600),
                eventSource = EventSource.Agent("code-agent"),
                urgency = Urgency.LOW,
                invocationId = "tool-call-1",
                toolId = "write-code",
                toolName = "Write Code",
                runId = runId,
            ),
        ).getOrThrow()

        eventRepository.saveEvent(
            ToolEvent.ToolExecutionCompleted(
                eventId = "tool-complete",
                timestamp = Instant.fromEpochMilliseconds(1_700),
                eventSource = EventSource.Agent("code-agent"),
                urgency = Urgency.LOW,
                invocationId = "tool-call-1",
                toolId = "write-code",
                toolName = "Write Code",
                success = true,
                durationMs = 100,
                runId = runId,
            ),
        ).getOrThrow()

        eventRepository.saveEvent(
            ProviderCallCompletedEvent(
                eventId = "execute-complete",
                timestamp = Instant.fromEpochMilliseconds(1_800),
                eventSource = EventSource.Agent("code-agent"),
                workflowId = runId,
                agentId = "code-agent",
                cognitivePhase = CognitivePhase.EXECUTE,
                providerId = "openai",
                modelId = "gpt-4.1",
                usage = TokenUsage(
                    inputTokens = 600,
                    outputTokens = 400,
                    estimatedCost = 0.004,
                ),
                latencyMs = 300,
                success = true,
            ),
        ).getOrThrow()

        database.knowledgeStoreQueries.insertKnowledgeWithRunId(
            id = "knowledge-1",
            knowledge_type = KnowledgeType.FROM_OUTCOME.name,
            approach = "Use trace projections",
            learnings = "Group events by cognitive phase",
            timestamp = 1_900L,
            run_id = runId,
            idea_id = null,
            outcome_id = "outcome-1",
            perception_id = null,
            plan_id = null,
            task_id = null,
            task_type = "trace",
            complexity_level = "MODERATE",
        )
        database.knowledgeStoreQueries.insertKnowledgeTag("knowledge-1", "trace")

        eventRepository.saveEvent(
            MemoryEvent.KnowledgeStored(
                eventId = "knowledge-stored",
                timestamp = Instant.fromEpochMilliseconds(1_900),
                eventSource = EventSource.Agent("code-agent"),
                knowledgeId = "knowledge-1",
                knowledgeType = KnowledgeType.FROM_OUTCOME,
                taskType = "trace",
                tags = listOf("trace"),
                approach = "Use trace projections",
                learnings = "Group events by cognitive phase",
                sourceId = "outcome-1",
                runId = runId,
            ),
        ).getOrThrow()
    }
}
