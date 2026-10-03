package link.socket.ampere.llm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.config.CognitiveConfig
import link.socket.ampere.agents.domain.Urgency
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.ProviderCallStartedEvent
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.relay.EventRelayServiceImpl
import link.socket.ampere.api.internal.DefaultEventService
import link.socket.ampere.api.service.EventStreamFilter
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.OnDeviceAssistantAgent
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.llm.LlmProvider

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLlmTelemetryTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventBus: EventSerialBus
    private lateinit var eventApi: AgentEventApi
    private lateinit var eventService: DefaultEventService

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(driver)

        eventRepository = EventRepository(DEFAULT_JSON, scope, database)
        eventBus = EventSerialBus(scope)
        eventApi = AgentEventApi(
            agentId = "telemetry-agent",
            eventRepository = eventRepository,
            eventSerialBus = eventBus,
        )
        eventService = DefaultEventService(
            eventRelayService = EventRelayServiceImpl(eventBus, eventRepository),
            eventRepository = eventRepository,
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `custom provider telemetry reaches SDK event filter`() = runTest {
        val telemetryEvents = mutableListOf<Event>()
        val provider: LlmProvider = {
            delay(10)
            "telemetry-response"
        }
        val llmService = AgentLLMService(
            agentConfiguration = telemetryConfig(provider),
            eventApi = eventApi,
        )

        val job = launch {
            eventService.observe(EventStreamFilter.TELEMETRY)
                .take(2)
                .toList(telemetryEvents)
        }

        delay(100)
        eventApi.publishTaskCreated(
            taskId = "task-ignored",
            urgency = Urgency.LOW,
            description = "This should not appear in telemetry filter",
        )

        val response = llmService.call(
            prompt = "Say hello",
            systemMessage = "You are a test provider.",
            routingContext = RoutingContext(
                phase = CognitivePhase.PLAN,
                agentId = eventApi.agentId,
                agentRole = "Telemetry Test Agent",
                workflowId = "wf-123",
            ),
        )

        job.join()

        assertEquals("telemetry-response", response)
        assertEquals(2, telemetryEvents.size)
        assertTrue(telemetryEvents.none { it is Event.TaskCreated })

        val started = telemetryEvents[0] as ProviderCallStartedEvent
        val completed = telemetryEvents[1] as ProviderCallCompletedEvent

        assertEquals("wf-123", started.workflowId)
        assertEquals(CognitivePhase.PLAN, started.cognitivePhase)
        assertEquals("telemetry-agent", started.agentId)
        assertEquals("openai", started.providerId)
        assertEquals(AIModel_OpenAI.GPT_4_1.name, started.modelId)
        assertEquals("custom_provider", started.routingReason)

        assertTrue(completed.success)
        assertEquals("wf-123", completed.workflowId)
        assertEquals(CognitivePhase.PLAN, completed.cognitivePhase)
        assertEquals("telemetry-agent", completed.agentId)
        assertEquals("openai", completed.providerId)
        assertEquals(AIModel_OpenAI.GPT_4_1.name, completed.modelId)
        assertEquals(null, completed.usage.inputTokens)
        assertEquals(null, completed.usage.outputTokens)
        assertTrue(completed.latencyMs >= 0)
    }

    @Test
    fun `failed provider emits failed completion telemetry`() = runTest {
        val telemetryEvents = mutableListOf<Event>()
        val provider: LlmProvider = {
            delay(5)
            throw IllegalStateException("boom")
        }
        val llmService = AgentLLMService(
            agentConfiguration = telemetryConfig(provider),
            eventApi = eventApi,
        )

        val job = launch {
            eventService.observe(EventStreamFilter.TELEMETRY)
                .take(2)
                .toList(telemetryEvents)
        }

        delay(100)

        val error = assertFailsWith<IllegalStateException> {
            llmService.call(
                prompt = "Fail",
                routingContext = RoutingContext(
                    phase = CognitivePhase.EXECUTE,
                    agentId = eventApi.agentId,
                    agentRole = "Telemetry Test Agent",
                    workflowId = "wf-fail",
                ),
            )
        }

        job.join()

        assertEquals("boom", error.message)
        val completed = telemetryEvents.filterIsInstance<ProviderCallCompletedEvent>().single()
        assertFalse(completed.success)
        assertEquals("wf-fail", completed.workflowId)
        assertEquals(CognitivePhase.EXECUTE, completed.cognitivePhase)
        assertEquals("IllegalStateException", completed.errorType)
    }

    @Test
    fun `refused on-device call is recorded as a failed completion and never reaches the transport`() = runTest {
        // AMPR-371: an agent whose static configuration is the on-device one, on a host
        // with no engine bound (every JVM host). The dispatching client must refuse
        // before the transport, and the service must book the refusal like any other
        // transport error.
        val telemetryEvents = mutableListOf<Event>()
        val transport = CountingClient()
        val llmService = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = OnDeviceAssistantAgent,
                aiConfiguration = AIConfiguration_Default(
                    provider = AIProvider_OnDevice,
                    model = AIModel_OnDevice.AppleFoundationModels,
                ),
                cognitiveConfig = CognitiveConfig(),
                upstreamLlmClient = DispatchingUpstreamLlmClient(
                    registry = InMemoryModelDescriptorRegistry(),
                    localEngine = null,
                    bundled = transport,
                ),
            ),
            eventApi = eventApi,
        )

        val job = launch {
            eventService.observe(EventStreamFilter.TELEMETRY)
                .take(2)
                .toList(telemetryEvents)
        }

        delay(100)

        assertFailsWith<LocalEngineNotBoundException> {
            llmService.call(
                prompt = "This must stay on the device.",
                routingContext = RoutingContext(
                    phase = CognitivePhase.EXECUTE,
                    agentId = eventApi.agentId,
                    workflowId = "wf-on-device",
                ),
            )
        }

        job.join()

        assertEquals(0, transport.callCount, "the bundled transport must not be called")
        val completed = telemetryEvents.filterIsInstance<ProviderCallCompletedEvent>().single()
        assertFalse(completed.success)
        assertEquals("wf-on-device", completed.workflowId)
        assertEquals(AIProvider_OnDevice.id, completed.providerId)
        assertEquals(AIModel_OnDevice.AppleFoundationModels.name, completed.modelId)
        assertEquals("LocalEngineNotBoundException", completed.errorType)
    }

    private class CountingClient : UpstreamLlmClient {
        var callCount: Int = 0
            private set

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            callCount++
            error("the transport must never be reached in this test")
        }
    }

    private fun telemetryConfig(provider: LlmProvider): AgentConfiguration =
        AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = AIConfiguration_Default(
                provider = AIProvider_OpenAI,
                model = AIModel_OpenAI.GPT_4_1,
            ),
            cognitiveConfig = CognitiveConfig(),
            llmProvider = provider,
        )
}
