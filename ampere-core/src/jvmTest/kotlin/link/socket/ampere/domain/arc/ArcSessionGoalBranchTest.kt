package link.socket.ampere.domain.arc

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.event.GoalEvent
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.arc.bridge.ArcSession
import link.socket.ampere.goal.GoalBranch

/**
 * AMPR-419: a run hosted by a database-backed [ArcSession] puts the goal tree Charge built onto
 * the Field's Goal branch, and the tree it handed Flow is the one the branch reads back.
 */
class ArcSessionGoalBranchTest {

    /** Two clauses, so `GoalTreeBuilder` gives a root and two children. */
    private val userGoal = "Ship login and add tests"

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        driver.close()
    }

    @Test
    fun `a hosted session's run round-trips its goal tree through the Goal branch`() = runBlocking<Unit> {
        val session = ArcSession.create(
            arcConfig = ArcConfig(name = "goal-branch-arc", agents = listOf(ArcAgentConfig(role = "code"))),
            projectDirPath = arcProjectDir().toString(),
            maxFlowTicks = 1,
            database = database,
        )

        val (runId, outcome) = try {
            val handle = session.start(userGoal)
            handle.runId to handle.await()
        } finally {
            session.close()
        }

        val charged = assertNotNull(outcome.chargeResult, "Charge ran, so its tree is on the outcome")
        val repository = EventRepository(DEFAULT_JSON, scope, database)
        val reader = GoalBranch.over(
            database,
            AgentEventApi(agentId = "reader", eventRepository = repository, eventSerialBus = EventSerialBus(scope)),
        )

        assertEquals(charged.goalTree, reader.tree(runId).getOrThrow())
        assertEquals(3, charged.goalTree.allNodes().size)

        val formed = repository.getEventsSinceSequence(0).getOrThrow().filter { it.event is GoalEvent.GoalFormed }
        assertEquals(3, formed.size, "one GoalFormed per node")
        assertTrue(formed.all { it.runId == runId }, "every GoalFormed is under the run that formed it")
    }

    /** A temp dir with the AGENTS.md/README.md that ChargePhase requires to produce a context. */
    private fun arcProjectDir(): java.nio.file.Path {
        val tempDir = createTempDirectory("goal-branch-session")
        tempDir.resolve("README.md").writeText("# GoalBranchProject\n\nA test project whose goals are persisted.")
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
        return tempDir
    }
}
