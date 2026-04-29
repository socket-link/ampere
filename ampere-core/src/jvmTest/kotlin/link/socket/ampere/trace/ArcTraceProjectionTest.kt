package link.socket.ampere.trace

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.MemoryEvent
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.event.ToolEvent
import link.socket.ampere.agents.domain.knowledge.KnowledgeType
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database

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

        assertTrue(
            elapsed.inWholeMilliseconds < 50,
            "Projection took ${elapsed.inWholeMilliseconds}ms",
        )
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
