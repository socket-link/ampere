package link.socket.ampere.api

import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.data.createIosDriver
import link.socket.ampere.db.Database
import link.socket.ampere.memory.memoryStoreOf

/**
 * iOS construction smoke test for [Ampere.fromEnvironment].
 *
 * Exercises [createIosDriver] —
 * [NativeSqliteDriver][app.cash.sqldelight.driver.native.NativeSqliteDriver]
 * end-to-end on the iOS simulator — proving the migrated `fromEnvironment`
 * extension and its `Default*Service` dependencies compile and execute on
 * iOS Native.
 *
 * Runs as part of `iosSimulatorArm64Test` in CI on the macOS runner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AmpereFromEnvironmentIosTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var environmentService: EnvironmentService
    private lateinit var knowledgeRepository: KnowledgeRepository

    @BeforeTest
    fun setUp() {
        driver = createIosDriver(dbName = "ampere-ios-test.db")
        database = Database(driver)
        environmentService = EnvironmentService.create(database = database, scope = scope)
        knowledgeRepository = KnowledgeRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `fromEnvironment constructs on iOS with NativeSqliteDriver`() {
        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
        )

        assertNotNull(instance.agents)
        assertNotNull(instance.tickets)
        assertNotNull(instance.threads)
        assertNotNull(instance.events)
        assertNotNull(instance.outcomes)
        assertNotNull(instance.pricing)
        assertNotNull(instance.knowledge)
        assertNotNull(instance.status)

        instance.close()
    }

    @Test
    fun `smoke - pursue emits TaskCreated through the event bus on iOS`() = runTest {
        environmentService.start()

        val store = memoryStoreOf(
            knowledge = KnowledgeRepositoryImpl(database),
            outcomes = OutcomeMemoryRepositoryImpl(database),
        )

        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
            memoryStore = store,
        )

        val pursueResult = instance.agents.pursue("smoke-test-goal")
        assertTrue(pursueResult.isSuccess, "pursue must succeed in clean environment")

        val now = kotlinx.datetime.Clock.System.now()
        val events = instance.events.query(
            fromTime = now - kotlin.time.Duration.parse("PT1M"),
            toTime = now + kotlin.time.Duration.parse("PT1M"),
        ).getOrThrow()
        assertTrue(
            events.any { it is Event.TaskCreated && it.description == "smoke-test-goal" },
            "TaskCreated event for 'smoke-test-goal' must be on the bus after pursue",
        )

        instance.close()
    }
}
