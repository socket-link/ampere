package link.socket.ampere.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.db.Database
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android construction smoke test for [Ampere.fromEnvironment].
 *
 * Runs as a Robolectric unit test so it executes on the CI JVM without requiring an
 * emulator, proving that the migrated `fromEnvironment` extension and its
 * `Default*Service` dependencies compile and execute under the Android runtime.
 *
 * The database is backed by an in-memory JDBC driver rather than
 * [createAndroidDriver][link.socket.ampere.data.createAndroidDriver]: Robolectric's SQLite
 * native runtime has no FTS5 module, so `Database.Schema.create` cannot run there, and the
 * bundled FTS5 SQLite that [createAndroidDriver][link.socket.ampere.data.createAndroidDriver]
 * uses is an Android `.so` that a desktop JVM cannot load. Coverage of the real Android
 * driver requires an instrumented test on a device or emulator; see AMPR-324. Unlike the
 * previous version of this test, the schema is genuinely created here instead of being
 * silently skipped by the Android driver's lazy open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AmpereFromEnvironmentAndroidTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: SqlDriver
    private lateinit var database: Database
    private lateinit var environmentService: EnvironmentService
    private lateinit var knowledgeRepository: KnowledgeRepository

    @BeforeTest
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertNotNull(context)
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        environmentService = EnvironmentService.create(database = database, scope = scope)
        knowledgeRepository = KnowledgeRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `fromEnvironment constructs on Android`() {
        // Construction smoke: proves the migrated `fromEnvironment` + `Default*Service`
        // graph wires up and runs under the Android runtime. The full event-bus smoke
        // (pursue -> observe TaskCreated) lives in the JVM and iOS suites.
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
}
