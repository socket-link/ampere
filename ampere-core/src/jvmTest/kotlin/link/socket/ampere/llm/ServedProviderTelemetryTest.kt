package link.socket.ampere.llm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.core.Usage
import com.aallam.openai.api.model.ModelId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

/**
 * What the persisted `ProviderCall*` pair says when a transport reports what
 * served the call (AMPR-391).
 *
 * The started row is published before the transport runs, so it can only name
 * the configuration the relay resolved. The completion names what answered. The
 * two are therefore joined by the envelope's `caused_by`, not by provider+model
 * equality — which is what makes a diverging pair one call rather than two
 * half-traces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServedProviderTelemetryTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventApi: AgentEventApi

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)

        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        eventApi = AgentEventApi(
            agentId = "served-agent",
            eventRepository = eventRepository,
            eventSerialBus = EventSerialBus(scope),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `the completion names the served provider model usage and cost`() = runTest {
        val service = serviceWith(
            ReroutingProxy(
                response = "from the model the proxy picked",
                served = ServedBy(
                    providerId = "anthropic",
                    modelId = "claude-sonnet-5",
                    usage = TokenUsage(inputTokens = 310, outputTokens = 44),
                    estimatedCostUsd = 0.0021,
                    routingReason = "tier=premium",
                    latencyMs = 870,
                ),
                // The response's own counts are the proxy's upstream echo and are
                // deliberately wrong here: the ServedBy is the authority.
                responseUsage = Usage(promptTokens = 1, completionTokens = 1, totalTokens = 2),
            ),
        )

        service.call(prompt = "Hello", routingContext = routingContext())

        val started = assertSingleStarted()
        val completed = assertSingleCompleted()

        // Published before the call: the resolved configuration is all it can know.
        assertEquals(AIProvider_OpenAI.id, started.providerId)
        assertEquals(AIModel_OpenAI.GPT_4_1.name, started.modelId)
        assertEquals("agent_configuration", started.routingReason)

        assertEquals("anthropic", completed.providerId)
        assertEquals("claude-sonnet-5", completed.modelId)
        assertEquals(310, completed.usage.inputTokens)
        assertEquals(44, completed.usage.outputTokens)
        assertEquals(0.0021, completed.usage.estimatedCost)
        assertEquals("tier=premium", completed.servedRoutingReason)
        assertEquals(870, completed.latencyMs)
        assertTrue(completed.success)
    }

    @Test
    fun `the completion is caused by its own start even when the two name different models`() = runTest {
        val service = serviceWith(
            ReroutingProxy(
                response = "answer",
                served = ServedBy(providerId = "anthropic", modelId = "claude-sonnet-5"),
            ),
        )

        service.call(prompt = "Hello", routingContext = routingContext())

        val started = assertSingleStarted()
        val completed = assertSingleCompleted()

        assertNotEquals(started.modelId, completed.modelId, "the fixture must exercise a diverging pair")

        val caused = eventRepository.getEventsCausedBy(started.eventId).getOrThrow()
        assertEquals(listOf(completed.eventId), caused.map { it.event.eventId })
    }

    @Test
    fun `a transport that reports nothing books the call exactly as before`() = runTest {
        val service = serviceWith(
            CallOnlyClient(
                response = "answer",
                responseUsage = Usage(promptTokens = 1_000, completionTokens = 500, totalTokens = 1_500),
            ),
        )

        service.call(prompt = "Hello", routingContext = routingContext())

        val started = assertSingleStarted()
        val completed = assertSingleCompleted()

        assertEquals(started.providerId, completed.providerId)
        assertEquals(started.modelId, completed.modelId)
        assertEquals(AIProvider_OpenAI.id, completed.providerId)
        assertEquals(AIModel_OpenAI.GPT_4_1.name, completed.modelId)
        // Counts from the response, cost from the bundled catalog for the requested model
        // (openai/gpt-4.1 at $2/$8 per million: 1000 in + 500 out = $0.006).
        assertEquals(1_000, completed.usage.inputTokens)
        assertEquals(500, completed.usage.outputTokens)
        assertEquals(0.006, assertNotNull(completed.usage.estimatedCost), absoluteTolerance = 0.0000001)
        assertNull(completed.servedRoutingReason, "no transport reason means the relay's reason stands alone")
        assertTrue(completed.latencyMs >= 0)
    }

    @Test
    fun `a served cost outranks the bundled catalog for the same model`() = runTest {
        // Same provider and model the catalog prices, so the only reason the figure can
        // differ is that the transport's own number won — which is the point: a consumer
        // with negotiated rates knows a price the catalog cannot reconstruct.
        val service = serviceWith(
            ReroutingProxy(
                response = "answer",
                served = ServedBy(
                    providerId = AIProvider_OpenAI.id,
                    modelId = AIModel_OpenAI.GPT_4_1.name,
                    usage = TokenUsage(inputTokens = 1_000, outputTokens = 500),
                    estimatedCostUsd = 0.00001,
                ),
            ),
        )

        service.call(prompt = "Hello", routingContext = routingContext())

        assertEquals(0.00001, assertSingleCompleted().usage.estimatedCost)
    }

    private fun serviceWith(client: UpstreamLlmClient): AgentLLMService = AgentLLMService(
        agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_OpenAI,
                model = AIModel_OpenAI.GPT_4_1,
            ),
            cognitiveConfig = CognitiveConfig(),
            upstreamLlmClient = client,
        ),
        eventApi = eventApi,
    )

    private fun routingContext(): RoutingContext = RoutingContext(
        phase = CognitivePhase.PLAN,
        agentId = eventApi.agentId,
        workflowId = "wf-served",
    )

    private suspend fun assertSingleStarted(): ProviderCallStartedEvent =
        storedEvents().filterIsInstance<ProviderCallStartedEvent>().single()

    private suspend fun assertSingleCompleted(): ProviderCallCompletedEvent =
        storedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()

    private suspend fun storedEvents(): List<Event> =
        eventRepository.getEventsSinceSequence(0L).getOrThrow().map { it.event }

    /** A transport written before the served seam existed: it overrides [call] and nothing else. */
    private class CallOnlyClient(
        private val response: String,
        private val responseUsage: Usage? = null,
    ) : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = completion(response, configuration.model.name, responseUsage)
    }

    /** A consumer's proxy that routes server-side and says so. */
    private class ReroutingProxy(
        private val response: String,
        private val served: ServedBy,
        private val responseUsage: Usage? = null,
    ) : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = callDetailed(request, configuration).completion

        override suspend fun callDetailed(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): UpstreamCompletion = UpstreamCompletion(
            completion = completion(response, served.modelId, responseUsage),
            served = served,
        )
    }

    private companion object {
        fun completion(
            text: String,
            modelName: String,
            usage: Usage?,
        ): ChatCompletion = ChatCompletion(
            id = "served-telemetry-test",
            created = 0L,
            model = ModelId(modelName),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = ChatRole.Assistant, content = text),
                ),
            ),
            usage = usage,
        )
    }
}
