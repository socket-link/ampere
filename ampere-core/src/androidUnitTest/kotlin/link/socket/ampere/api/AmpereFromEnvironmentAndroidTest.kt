package link.socket.ampere.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.data.createAndroidDriver
import link.socket.ampere.db.Database
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android construction smoke test for [Ampere.fromEnvironment].
 *
 * Runs as a Robolectric unit test so it executes on the CI JVM without
 * requiring an emulator. Exercises [createAndroidDriver] —
 * [AndroidSqliteDriver][app.cash.sqldelight.driver.android.AndroidSqliteDriver]
 * end-to-end — proving that the migrated `fromEnvironment` extension and
 * its `Default*Service` dependencies compile and execute on Android.
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
        driver = createAndroidDriver(context = context, dbName = "ampere-android-test.db")
        database = Database(driver)
        environmentService = EnvironmentService.create(database = database, scope = scope)
        knowledgeRepository = KnowledgeRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `fromEnvironment constructs on Android with AndroidSqliteDriver`() {
        // Construction-only smoke: AndroidSqliteDriver is lazily initialized,
        // so this proves the migrated `fromEnvironment` + `Default*Service`
        // graph wires up under Android. The full event-bus smoke (pursue ->
        // observe TaskCreated) lives in the JVM and iOS suites; Robolectric's
        // bundled SQLite native runtime lacks FTS5, which Ampere's knowledge
        // schema requires. A proper Android instrumented test (real device /
        // emulator with full SQLite) is tracked as a follow-up.
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
