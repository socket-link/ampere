package link.socket.ampere.trace

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
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApiFactory
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.arc.AmpereRuntime
import link.socket.ampere.domain.arc.ArcAgentConfig
import link.socket.ampere.domain.arc.ArcConfig
import link.socket.ampere.llm.UpstreamLlmClient
import okio.Path.Companion.toPath

/**
 * Proves AMPR-240's run-identity threading end to end: a real
 * [AmpereRuntime.execute] run, with a real (fake-network) [UpstreamLlmClient]
 * and a real [EventRepository], produces `ProviderCallStartedEvent`/
 * `ProviderCallCompletedEvent` rows carrying the run's [ArcRunId] as
 * `workflowId`, and [ArcTraceProjection.project] reconstructs them into a
 * non-empty trace with model invocations.
 *
 * Lives in `jvmTest` (not `commonTest`) because the in-memory SQLDelight
 * driver used here (`JdbcSqliteDriver`) is JVM-only — every other
 * Database-backed test in this repo (`ArcTraceProjectionTest`,
 * `AmpereFromEnvironmentTest`) makes the same choice for the same reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArcRunIdentityIntegrationTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var eventBus: EventSerialBus
    private lateinit var eventApiFactory: AgentEventApiFactory
    private lateinit var projection: ArcTraceProjection

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        eventBus = EventSerialBus(scope)
        eventApiFactory = AgentEventApiFactory(
            eventRepository = EventRepository(DEFAULT_JSON, scope, database),
            eventSerialBus = eventBus,
        )
        projection = ArcTraceProjection(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a real Arc run's telemetry projects into a non-empty trace by runId`() = runTest {
        val tempDir = createTempDirectory("arc-run-identity")
        tempDir.resolve("README.md").writeText(
            """
            # ArcRunIdentityProject

            A test project proving run-id threading end to end.
            """.trimIndent(),
        )
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

        val arcConfig = ArcConfig(
            name = "run-identity-arc",
            agents = listOf(ArcAgentConfig(role = "code")),
        )

        val runtime = AmpereRuntime(
            arcConfig = arcConfig,
            projectDir = tempDir.toString().toPath(),
            agentScope = backgroundScope,
            maxFlowTicks = 1,
            upstreamLlmClient = FakeUpstreamLlmClient,
            eventApiFactory = { agentId -> eventApiFactory.create(agentId) },
        )

        val runId = "arc-run-identity-test-1"
        runtime.execute("Add a health check endpoint", runId = runId)

        val trace = projection.project(runId).getOrThrow()

        assertTrue(trace.phases.isNotEmpty(), "Expected at least one phase in the projected trace")
        val modelInvocations = trace.phases.flatMap { it.modelInvocations }
        assertTrue(
            modelInvocations.isNotEmpty(),
            "Expected the real Arc run's LLM calls to project into model invocations",
        )
        assertTrue(
            modelInvocations.all { it.wattCost.inputTokens >= 0 },
            "Model invocations should carry usage attributed to this run",
        )
    }
}

/** Returns a fixed, syntactically-irrelevant completion — telemetry is recorded before response parsing. */
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
