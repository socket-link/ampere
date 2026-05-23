package link.socket.ampere.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepository
import link.socket.ampere.agents.domain.knowledge.KnowledgeRepositoryImpl
import link.socket.ampere.agents.domain.outcome.OutcomeMemoryRepositoryImpl
import link.socket.ampere.agents.environment.EnvironmentService
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.llm.BundledUpstreamLlmClient
import link.socket.ampere.llm.UpstreamLlmClient
import link.socket.ampere.memory.MemoryStore
import link.socket.ampere.memory.memoryStoreOf

/**
 * Construction test for [Ampere.fromEnvironment], the cross-platform light
 * construction path Socket consumes.
 *
 * This test currently lives in `jvmTest` because the in-memory SQLite
 * driver factory is JVM-specific. The cross-platform smoke test in Task 8
 * exercises the same path on iOS and Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AmpereFromEnvironmentTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var environmentService: EnvironmentService
    private lateinit var knowledgeRepository: KnowledgeRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        database = Database(driver)
        environmentService = EnvironmentService.create(
            database = database,
            scope = scope,
        )
        knowledgeRepository = KnowledgeRepositoryImpl(database)
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `fromEnvironment composes all SDK services`() {
        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
        )

        // The light construction path exposes every public service surface,
        // backed by the caller-owned environment.
        assertNotNull(instance.agents)
        assertNotNull(instance.tickets)
        assertNotNull(instance.threads)
        assertNotNull(instance.events)
        assertNotNull(instance.outcomes)
        assertNotNull(instance.pricing)
        assertNotNull(instance.knowledge)
        assertNotNull(instance.status)

        // close() must be a no-op because the caller owns lifecycle.
        instance.close()
    }

    @Test
    fun `fromEnvironment honors workspace argument`() {
        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
            workspace = "/path/to/project",
        )
        assertNotNull(instance.status)
    }

    @Test
    fun `fromEnvironment with memoryStore override routes through MemoryStore repositories`() {
        val customKnowledge: KnowledgeRepository = KnowledgeRepositoryImpl(database)
        val customOutcomes = OutcomeMemoryRepositoryImpl(database)
        val store: MemoryStore = memoryStoreOf(
            knowledge = customKnowledge,
            outcomes = customOutcomes,
        )

        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
            memoryStore = store,
        )

        // We can't directly observe the wired-through repositories without
        // reflection (DefaultKnowledgeService / DefaultOutcomeService are
        // internal), but we can verify the construction succeeded and the
        // services exist. The behavioral assertion that MemoryStore wins
        // over the legacy params lives in MemoryStoreTest contract coverage.
        assertNotNull(instance.knowledge)
        assertNotNull(instance.outcomes)
        // Static structural check on the store itself:
        assertSame(customKnowledge, store.knowledge)
        assertSame(customOutcomes, store.outcomes)
    }

    @Test
    fun `fromEnvironment exposes the supplied upstreamLlmClient as the runtime default`() {
        val recorder = RecordingUpstream()
        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
            upstreamLlmClient = recorder,
        )

        // The runtime default on the instance is the one the caller passed.
        // Callers constructing agents off this instance read this property
        // and pass it into the agent factory so every agent shares the
        // same upstream routing.
        assertSame(recorder, instance.upstreamLlmClient)
    }

    @Test
    fun `fromEnvironment defaults upstreamLlmClient to BundledUpstreamLlmClient when omitted`() {
        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
        )
        assertSame(BundledUpstreamLlmClient, instance.upstreamLlmClient)
    }

    @Test
    fun `runtime default flows through SparkBasedAgent factory to AgentLLMService`() {
        val recorder = RecordingUpstream()
        val instance = Ampere.fromEnvironment(
            environmentService = environmentService,
            knowledgeRepository = knowledgeRepository,
            upstreamLlmClient = recorder,
        )

        // Stamp the instance's runtime default onto the agent — the call
        // site Socket replicates for each agent it constructs off the
        // shared AmpereInstance.
        val agent = runBlocking {
            link.socket.ampere.agents.definition.SparkBasedAgent.Code(
                sparkRegistry = DefaultPhaseSparkLibrary.load(),
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_4,
                ),
                upstreamLlmClient = instance.upstreamLlmClient,
            )
        }

        // The agent's computed AgentConfiguration must carry the runtime
        // default forward, so AgentReasoning + AgentLLMService both see it.
        assertSame(recorder, agent.agentConfiguration.upstreamLlmClient)

        // Drive an LLM call via a fresh AgentLLMService built from the
        // agent's config to verify the seam end-to-end (does not start the
        // full PROPEL loop; that's exercised in the smoke tests).
        val llmService = link.socket.ampere.agents.domain.reasoning.AgentLLMService(
            agentConfiguration = agent.agentConfiguration,
        )
        val response = runBlocking { llmService.call(prompt = "ping") }
        assertEquals("from-runtime-client", response)
        assertNotNull(recorder.lastRequest)
    }

    @Test
    fun `smoke - pursue emits TaskCreated through the event bus`() = runTest {
        // End-to-end smoke: construct via fromEnvironment with an in-memory
        // MemoryStore, then drive one event-bus cycle via agents.pursue and
        // verify the TaskCreated event surfaces through events.query.
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

        // Query the event repository directly (history snapshot, no live
        // subscription needed — keeps the smoke deterministic across
        // dispatchers).
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

private class RecordingUpstream : UpstreamLlmClient {
    var lastRequest: ChatCompletionRequest? = null
        private set

    override suspend fun call(
        request: ChatCompletionRequest,
        configuration: AIConfiguration,
    ): ChatCompletion {
        lastRequest = request
        return ChatCompletion(
            id = "rec",
            created = 0L,
            model = ModelId(configuration.model.name),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(
                        role = ChatRole.Assistant,
                        content = "from-runtime-client",
                    ),
                ),
            ),
        )
    }
}
