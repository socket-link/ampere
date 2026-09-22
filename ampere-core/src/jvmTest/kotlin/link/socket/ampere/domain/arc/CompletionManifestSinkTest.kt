package link.socket.ampere.domain.arc

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import link.socket.ampere.agents.domain.event.ArcRunEvent
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.EventType
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.subscription.Subscription
import link.socket.ampere.agents.events.utils.EventLogger
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.time.MutableClock
import link.socket.ampere.trace.ArcTraceProjection

/**
 * The production manifest sink (AMPR-359): a manifest lands in the event store under its run, and
 * a write that cannot land is counted and logged — never thrown, never left to hang teardown.
 *
 * `runBlocking`, not `runTest`: the sink's timeout would run on virtual time, which `runTest`
 * skips ahead while a write is out on the real IO dispatcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletionManifestSinkTest {

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val closedAt = Instant.fromEpochMilliseconds(7_000)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventApi: AgentEventApi
    private val logger = RecordingLogger()

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        eventApi = AgentEventApi(
            agentId = CompletionManifestSink.DEFAULT_AGENT_ID,
            eventRepository = EventRepository(DEFAULT_JSON, scope, database),
            eventSerialBus = EventSerialBus(scope),
            clock = MutableClock(closedAt),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    private val manifest = CompletionManifest(
        runId = "run-sink",
        endedBy = TerminationReason.CANCELLED,
        failure = null,
        phasesStarted = listOf(ArcPhase.CHARGE, ArcPhase.FLOW),
        phasesCompleted = listOf(ArcPhase.CHARGE),
        phasesNotRun = listOf(ArcPhase.PULSE),
        reachedTick = 2,
        producedOutcomes = emptyMap(),
        completedGoals = emptyList(),
        unmetGoals = listOf(GoalNode(id = "goal-0", description = "Ship it")),
    )

    @Test
    fun `a manifest is stored under its run and folds into that run's trace`() = runBlocking<Unit> {
        val sink = CompletionManifestSink(eventApi = eventApi, logger = logger)

        sink.record(manifest)

        val row = database.eventStoreQueries.getEventsByRunId("run-sink").executeAsList().single()
        assertEquals(ArcRunEvent.CompletionManifestRecorded.EVENT_TYPE, row.event_type)
        assertEquals(CompletionManifestSink.DEFAULT_AGENT_ID, row.source_id)

        val trace = ArcTraceProjection(database).project("run-sink").getOrThrow()
        assertEquals(manifest.toRecord(), trace.completion)
        assertEquals(closedAt, trace.endedAt, "The run ends where its manifest was written")
        assertEquals(
            listOf("RUN"),
            trace.phases.map { it.name },
            "The run's own record sits in the run envelope, not a PROPEL phase",
        )

        assertEquals(0, sink.failures)
        assertTrue(logger.errors.isEmpty())
    }

    @Test
    fun `a write that fails is counted and logged instead of thrown`() = runBlocking<Unit> {
        val sink = sinkPublishing { Result.failure<Unit>(IllegalStateException("disk full")) }

        sink.record(manifest)

        assertEquals(1, sink.failures)
        val (message, cause) = logger.errors.single()
        assertTrue(message.contains("run-sink"), message)
        assertIs<IllegalStateException>(cause)
    }

    @Test
    fun `a write that throws is contained the same way`() = runBlocking<Unit> {
        val sink = sinkPublishing { error("driver closed") }

        sink.record(manifest)

        assertEquals(1, sink.failures)
        assertIs<IllegalStateException>(logger.errors.single().second)
    }

    /** The runtime's teardown path: under NonCancellable, and nothing may hold it open. */
    @Test
    fun `a write that never returns is abandoned at the timeout`() = runBlocking<Unit> {
        val sink = sinkPublishing(timeout = 50.milliseconds) { awaitCancellation() }

        withTimeout(10_000) {
            withContext(NonCancellable) { sink.record(manifest) }
        }

        assertEquals(1, sink.failures)
        assertTrue(logger.errors.single().first.contains("abandoned"), logger.errors.single().first)
    }

    private fun sinkPublishing(
        timeout: Duration = CompletionManifestSink.DEFAULT_TIMEOUT,
        publish: suspend (ArcRunEvent.CompletionManifestRecorded) -> Result<*>,
    ) = CompletionManifestSink(
        publish = publish,
        eventSource = EventSource.Agent(CompletionManifestSink.DEFAULT_AGENT_ID),
        clock = MutableClock(closedAt),
        timeout = timeout,
        logger = logger,
    )

    private class RecordingLogger : EventLogger {
        val errors = mutableListOf<Pair<String, Throwable?>>()

        override fun logPublish(event: Event) = Unit

        override fun logSubscription(eventType: EventType, subscription: Subscription) = Unit

        override fun logUnsubscription(eventType: EventType, subscription: Subscription) = Unit

        override fun logError(message: String, throwable: Throwable?) {
            errors += message to throwable
        }

        override fun logInfo(message: String) = Unit
    }
}
