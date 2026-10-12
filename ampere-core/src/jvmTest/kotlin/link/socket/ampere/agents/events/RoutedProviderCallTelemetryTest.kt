package link.socket.ampere.agents.events

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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OpenRouter
import link.socket.ampere.llm.ServedBy
import link.socket.ampere.llm.UpstreamCompletion
import link.socket.ampere.llm.UpstreamLlmClient

/**
 * What the persisted `ProviderCall*` pair says about a call routed through a gateway
 * (AMPR-436): the maker in `providerId`, the gateway in `via`, and a price looked up
 * under the maker — so a routed call costs what the same call made directly costs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutedProviderCallTelemetryTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private val directConfig = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)
    private val routedConfig = AIConfiguration_Default(AIProvider_OpenRouter, AIModel_Claude.Sonnet_5)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventApi: AgentEventApi

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, Database(driver))
        eventApi = AgentEventApi(
            agentId = "routed-agent",
            eventRepository = eventRepository,
            eventSerialBus = EventSerialBus(scope),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a routed call's pair names the maker and model and the gateway it travelled`() = runTest {
        callThrough(routedConfig, CannedClient(usage = TOKENS))

        val started = storedEvents().filterIsInstance<ProviderCallStartedEvent>().single()
        val completed = storedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()

        assertEquals(AIProvider_Anthropic.id, started.providerId, "providerId must name the maker, never the gateway")
        assertEquals(AIProvider_Anthropic.id, completed.providerId, "providerId must name the maker, never the gateway")
        assertEquals(AIModel_Claude.Sonnet_5.name, started.modelId, "modelId must be the model's own name")
        assertEquals(AIModel_Claude.Sonnet_5.name, completed.modelId, "modelId must be the model's own name")
        assertEquals(AIProvider_OpenRouter.id, started.via)
        assertEquals(AIProvider_OpenRouter.id, completed.via)
    }

    @Test
    fun `a direct call's pair is what it was before the route existed`() = runTest {
        callThrough(directConfig, CannedClient(usage = TOKENS))

        val started = storedEvents().filterIsInstance<ProviderCallStartedEvent>().single()
        val completed = storedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()

        // Before AMPR-436 both rows named the configuration's provider id, which for a
        // direct configuration is the maker's.
        assertEquals(directConfig.provider.id, started.providerId)
        assertEquals(directConfig.provider.id, completed.providerId)
        assertEquals(AIModel_Claude.Sonnet_5.name, started.modelId)
        assertEquals(AIModel_Claude.Sonnet_5.name, completed.modelId)
        assertEquals("agent_configuration", started.routingReason)
        assertNull(started.via)
        assertNull(completed.via)
        assertNull(completed.servedRoutingReason)
        assertEquals(1_000, completed.usage.inputTokens)
        assertEquals(500, completed.usage.outputTokens)
        // anthropic/claude-sonnet-5 at $2/$10 per million: 1000 in + 500 out = $0.007.
        assertEquals(0.007, assertNotNull(completed.usage.estimatedCost), absoluteTolerance = 0.0000001)
    }

    @Test
    fun `a routed call with no reported cost is priced as the direct call is`() = runTest {
        callThrough(directConfig, CannedClient(usage = TOKENS))
        callThrough(routedConfig, CannedClient(usage = TOKENS))

        val completions = storedEvents().filterIsInstance<ProviderCallCompletedEvent>()
        val direct = completions.single { it.via == null }
        val routed = completions.single { it.via == AIProvider_OpenRouter.id }

        val directCost = assertNotNull(direct.usage.estimatedCost, "the bundled catalog prices the direct call")
        assertEquals(directCost, routed.usage.estimatedCost, "a routed call is priced under its maker")
    }

    @Test
    fun `a routed call's reported cost outranks the maker's list price`() = runTest {
        val client = CannedClient(
            usage = TOKENS,
            served = ServedBy(
                providerId = AIProvider_Anthropic.id,
                modelId = AIModel_Claude.Sonnet_5.name,
                estimatedCostUsd = 0.0042,
            ),
        )

        callThrough(routedConfig, client)

        val completed = storedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()
        assertEquals(0.0042, completed.usage.estimatedCost)
        assertEquals(AIProvider_OpenRouter.id, completed.via)
    }

    private fun serviceWith(configuration: AIConfiguration, client: UpstreamLlmClient): AgentLLMService =
        AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = WriteCodeAgent,
                aiConfiguration = configuration,
                cognitiveConfig = CognitiveConfig(),
                upstreamLlmClient = client,
            ),
            eventApi = eventApi,
        )

    private suspend fun callThrough(configuration: AIConfiguration, client: UpstreamLlmClient) {
        serviceWith(configuration, client).call(prompt = "Hello", routingContext = routingContext())
    }

    private fun routingContext(): RoutingContext = RoutingContext(
        phase = CognitivePhase.PLAN,
        agentId = eventApi.agentId,
        workflowId = "wf-routed",
    )

    private suspend fun storedEvents(): List<Event> =
        eventRepository.getEventsSinceSequence(0L).getOrThrow().map { it.event }

    /** Answers every call with the same text and token counts, and reports [served] when given. */
    private class CannedClient(
        private val usage: Usage,
        private val served: ServedBy? = null,
    ) : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = callDetailed(request, configuration).completion

        override suspend fun callDetailed(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): UpstreamCompletion = UpstreamCompletion(
            completion = ChatCompletion(
                id = "routed-telemetry-test",
                created = 0L,
                model = ModelId(request.model.id),
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(role = ChatRole.Assistant, content = "answer"),
                    ),
                ),
                usage = usage,
            ),
            served = served,
        )
    }

    private companion object {
        val TOKENS = Usage(promptTokens = 1_000, completionTokens = 500, totalTokens = 1_500)
    }
}
