package link.socket.ampere.domain.arc

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepository
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.api.internal.DefaultOutcomeService
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.arc.bridge.ArcSession
import link.socket.ampere.llm.UpstreamLlmClient
import okio.Path.Companion.toPath

/**
 * AMPR-406 (F20), the run half: a run's close records the run's own outcome, so
 * `OutcomeService.forTicket(runId)` can be asked about a run by name.
 *
 * The row is deliberately keyed by the run id in the ticket column — an Arc run has a goal,
 * not a ticket, and the repository offers no read by run — so that is what these tests assert.
 * Real dispatchers and a real store: this is the wiring, not the arithmetic.
 */
class ArcRunOutcomeRecordingTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var outcomes: OutcomeMemoryRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        outcomes = OutcomeMemoryRepositoryImpl(database, driver)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a settled run records one outcome under its own run id`() = runBlocking<Unit> {
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val runId = "arc-run-settled"
        val goal = "Add a health check endpoint"

        val arcOutcome = try {
            val runtime = AmpereRuntime(
                arcConfig = arcConfig("outcome-recording-arc"),
                projectDir = arcProjectDir("arc-outcome-recording").toString().toPath(),
                agentScope = callerScope,
                maxFlowTicks = 1,
                upstreamLlmClient = FakeUpstreamLlmClient,
                outcomeRepository = outcomes,
            )
            runtime.execute(goal, runId = runId)
        } finally {
            callerScope.cancel()
        }

        val recorded = DefaultOutcomeService(outcomes).forTicket(runId).getOrThrow().single()

        assertEquals(runId, recorded.ticketId)
        assertEquals(goal, recorded.approach, "the goal is what was tried")
        assertEquals(
            "arc:outcome-recording-arc",
            recorded.executorId,
            "no single agent performed the run; the Arc did",
        )
        assertEquals(
            arcOutcome is ArcOutcome.Completed && arcOutcome.success,
            recorded.success,
            "the recorded verdict must be the run's own",
        )
        assertTrue(recorded.executionDurationMs >= 0, "the run's duration is measured from its start")

        // Also visible to the trace, which folds on the run column rather than the ticket one.
        val byRun = database.outcomeMemoryStoreQueries.getOutcomesByRunId(runId).executeAsList()
        assertEquals(listOf(recorded.id), byRun.map { it.id })
    }

    @Test
    fun `a hosted session's run records its outcome in the session's database`() = runBlocking<Unit> {
        val session = ArcSession.create(
            arcConfig = arcConfig("hosted-outcome-arc"),
            projectDirPath = arcProjectDir("hosted-outcome-recording").toString(),
            maxFlowTicks = 1,
            database = database,
        )

        val (runId, arcOutcome) = try {
            val handle = session.start("Add a health check endpoint")
            // The run must have settled before the store is read — that is when it writes.
            handle.runId to handle.await()
        } finally {
            session.close()
        }

        val recorded = DefaultOutcomeService(outcomes).forTicket(runId).getOrThrow().single()
        assertEquals(runId, recorded.ticketId)
        assertEquals(
            "arc:hosted-outcome-arc",
            recorded.executorId,
            "a hosted run is attributed to the Arc the session hosts",
        )
        assertEquals(
            arcOutcome is ArcOutcome.Completed && arcOutcome.success,
            recorded.success,
            "the recorded verdict must be the run's own",
        )
        // A run that ends badly is the one most worth being able to look up, so the message
        // behind an unsuccessful verdict has to name what ran.
        if (!recorded.success) {
            assertTrue(
                recorded.errorMessage?.contains("hosted-outcome-arc") == true,
                "the failure message names the Arc: ${recorded.errorMessage}",
            )
        }
    }

    private fun arcConfig(name: String) = ArcConfig(
        name = name,
        agents = listOf(ArcAgentConfig(role = "code")),
        orchestration = OrchestrationConfig(
            type = OrchestrationType.SEQUENTIAL,
            order = listOf("code"),
        ),
    )

    /** A temp dir with the AGENTS.md/README.md that ChargePhase requires to produce a context. */
    private fun arcProjectDir(prefix: String): java.nio.file.Path {
        val tempDir = createTempDirectory(prefix)
        tempDir.resolve("README.md").writeText("# OutcomeProject\n\nA test project for outcome recording.")
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

/** Returns a fixed, syntactically-irrelevant completion, so the run settles without a network. */
private object FakeUpstreamLlmClient : UpstreamLlmClient {
    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion = ChatCompletion(
        id = "fake-completion",
        created = 0L,
        model = ModelId(configuration.model.name),
        choices = listOf(
            ChatChoice(
                index = 0,
                message = ChatMessage(
                    role = ChatRole.Assistant,
                    content = "{}",
                ),
            ),
        ),
    )
}
