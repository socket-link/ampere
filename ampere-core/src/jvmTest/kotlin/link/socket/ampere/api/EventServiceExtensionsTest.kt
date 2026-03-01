package link.socket.ampere.api

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.EventSource
import link.socket.ampere.agents.domain.event.ProviderCallCompletedEvent
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingRule
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.agents.events.relay.EventRelayServiceImpl
import link.socket.ampere.api.internal.DefaultEventService
import link.socket.ampere.api.model.TokenUsage
import link.socket.ampere.api.service.completionEvents
import link.socket.ampere.api.service.routingEvents
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

@OptIn(ExperimentalCoroutinesApi::class)
class EventServiceExtensionsTest {

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
            agentId = "sdk-consumer",
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
    fun `routingEvents exposes relay events through public event service`() = runTest {
        val routingEvents = mutableListOf<RoutingEvent>()
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(
                        CognitivePhase.PLAN,
                        AIConfiguration_Default(
                            provider = AIProvider_Google,
                            model = AIModel_Gemini.Flash_2_5,
                        ),
                    ),
                ),
            ),
            eventBus = eventBus,
        )

        val job = launch {
            eventService.routingEvents()
                .take(1)
                .toList(routingEvents)
        }

        delay(100)

        relay.resolveWithMetadata(
            context = RoutingContext(
                agentId = "planner-agent",
                phase = CognitivePhase.PLAN,
            ),
            fallbackConfiguration = AIConfiguration_Default(
                provider = AIProvider_OpenAI,
                model = AIModel_OpenAI.GPT_4_1,
            ),
        )

        job.join()

        val event = assertIs<RoutingEvent.RouteSelected>(routingEvents.single())
        assertEquals("planner-agent", event.agentId)
        assertEquals(CognitivePhase.PLAN, event.phase)
        assertEquals("Google", event.decision.providerName)
    }

    @Test
    fun `completionEvents exposes provider completion telemetry through public event service`() = runTest {
        val completionEvents = mutableListOf<ProviderCallCompletedEvent>()

        val job = launch {
            eventService.completionEvents()
                .take(1)
                .toList(completionEvents)
        }

        delay(100)

        eventApi.publish(
            ProviderCallCompletedEvent(
                eventId = "llm-complete-1",
                timestamp = Clock.System.now(),
                eventSource = EventSource.Agent("sdk-consumer"),
                workflowId = "wf-telemetry",
                agentId = "planner-agent",
                cognitivePhase = CognitivePhase.PLAN,
                providerId = "openai",
                modelId = AIModel_OpenAI.GPT_4_1.name,
                usage = TokenUsage(
                    inputTokens = 1247,
                    outputTokens = 892,
                    estimatedCost = 0.0031,
                ),
                latencyMs = 340,
                success = true,
            ),
        )

        job.join()

        val event = completionEvents.single()
        assertEquals("wf-telemetry", event.workflowId)
        assertEquals(1247, event.usage.inputTokens)
        assertEquals(892, event.usage.outputTokens)
        assertEquals(0.0031, event.usage.estimatedCost)
        assertEquals(340, event.latencyMs)
    }
}
