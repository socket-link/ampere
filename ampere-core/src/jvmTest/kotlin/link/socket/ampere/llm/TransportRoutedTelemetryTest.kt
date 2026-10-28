package link.socket.ampere.llm

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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
import link.socket.ampere.agents.domain.event.TelemetryEvent
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingResolution
import link.socket.ampere.agents.events.EventRepository
import link.socket.ampere.agents.events.api.AgentEventApi
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.data.DEFAULT_JSON
import link.socket.ampere.db.Database
import link.socket.ampere.domain.agent.bundled.WriteCodeAgent
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Google

/**
 * What the persisted `ProviderCall*` pair says when the relay picks no model and the
 * transport routes server-side (AMPR-503).
 *
 * The configuration such a relay returns is a placeholder the request has to carry. The
 * started row is published before the transport runs, so the only honest thing it can say
 * is that the transport chooses; and a completion nothing served must not name the
 * placeholder as the model that failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportRoutedTelemetryTest {

    private val scope = TestScope(UnconfinedTestDispatcher())

    private val placeholder = AIConfiguration_Default(AIProvider_Google, AIModel_Gemini.Flash_3_8)

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var eventRepository: EventRepository
    private lateinit var eventApi: AgentEventApi

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        eventRepository = EventRepository(DEFAULT_JSON, scope, Database(driver))
        eventApi = AgentEventApi(
            agentId = "transport-routed-agent",
            eventRepository = eventRepository,
            eventSerialBus = EventSerialBus(scope),
        )
    }

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `a transport-routed start carries the flag and its summary names no model`() = runTest {
        val service = serviceWith(TierOnlyRelay(transportChoosesModel = true), ServingProxy(SERVED))

        service.call(prompt = "Hello", routingContext = routingContext())

        val started = assertSingleStarted()
        assertTrue(started.transportChoosesModel)
        assertEquals("proxy:tier=standard", started.routingReason)

        val summary = summaryOf(started)
        assertTrue(summary.startsWith("LLM call started: model chosen by transport"), summary)
        assertFalse(placeholder.model.name in summary, summary)
        assertFalse(AIProvider_Google.id in summary, summary)
    }

    @Test
    fun `a transport-routed completion that names what served is an ordinary completion`() = runTest {
        val service = serviceWith(TierOnlyRelay(transportChoosesModel = true), ServingProxy(SERVED))

        service.call(prompt = "Hello", routingContext = routingContext())

        val completed = assertSingleCompleted()
        assertFalse(completed.transportChoosesModel)
        assertEquals(SERVED.providerId, completed.providerId)
        assertEquals(SERVED.modelId, completed.modelId)
        assertTrue(summaryOf(completed).startsWith("LLM call completed: anthropic/claude-haiku-5-5"))
    }

    @Test
    fun `a transport-routed call that fails before anything served names no model`() = runTest {
        val service = serviceWith(TierOnlyRelay(transportChoosesModel = true), FailingProxy())

        assertFailsWith<IllegalStateException> {
            service.call(prompt = "Hello", routingContext = routingContext())
        }

        val completed = assertSingleCompleted()
        assertFalse(completed.success)
        assertTrue(completed.transportChoosesModel)
        // The ids stay non-null on the stable contract; they are the placeholder, flagged as such.
        assertEquals(placeholder.model.name, completed.modelId)

        val summary = summaryOf(completed)
        assertTrue(summary.startsWith("LLM call failed: no model served"), summary)
        assertFalse(placeholder.model.name in summary, summary)
    }

    @Test
    fun `a transport-routed success that never says what served stays flagged`() = runTest {
        val service = serviceWith(TierOnlyRelay(transportChoosesModel = true), SilentProxy())

        service.call(prompt = "Hello", routingContext = routingContext())

        val completed = assertSingleCompleted()
        assertTrue(completed.success)
        assertTrue(completed.transportChoosesModel)
        assertTrue(summaryOf(completed).startsWith("LLM call completed: model not reported by transport"))
    }

    @Test
    fun `a relay that chooses the model books the call exactly as before`() = runTest {
        val service = serviceWith(TierOnlyRelay(transportChoosesModel = false), FailingProxy())

        assertFailsWith<IllegalStateException> {
            service.call(prompt = "Hello", routingContext = routingContext())
        }

        val started = assertSingleStarted()
        val completed = assertSingleCompleted()
        assertFalse(started.transportChoosesModel)
        assertFalse(completed.transportChoosesModel)
        assertTrue(summaryOf(started).startsWith("LLM call started: google/${placeholder.model.name}"))
        assertTrue(summaryOf(completed).startsWith("LLM call failed: google/${placeholder.model.name}"))

        // The stored rows carry no trace of the new field.
        storedJson().forEach { row -> assertFalse("transportChoosesModel" in row, row) }
    }

    private fun serviceWith(relay: CognitiveRelay, client: UpstreamLlmClient): AgentLLMService = AgentLLMService(
        agentConfiguration = AgentConfiguration(
            agentDefinition = WriteCodeAgent,
            aiConfiguration = placeholder,
            cognitiveConfig = CognitiveConfig(),
            cognitiveRelay = relay,
            upstreamLlmClient = client,
        ),
        eventApi = eventApi,
    )

    private fun routingContext(): RoutingContext = RoutingContext(
        phase = CognitivePhase.PLAN,
        agentId = eventApi.agentId,
        workflowId = "wf-transport-routed",
    )

    private fun summaryOf(event: TelemetryEvent): String =
        event.getSummary(formatUrgency = { it.name }, formatSource = { it.toString() })

    private suspend fun assertSingleStarted(): ProviderCallStartedEvent =
        storedEvents().filterIsInstance<ProviderCallStartedEvent>().single()

    private suspend fun assertSingleCompleted(): ProviderCallCompletedEvent =
        storedEvents().filterIsInstance<ProviderCallCompletedEvent>().single()

    private suspend fun storedEvents(): List<Event> =
        eventRepository.getEventsSinceSequence(0L).getOrThrow().map { it.event }

    private suspend fun storedJson(): List<String> =
        storedEvents().map { DEFAULT_JSON.encodeToString(Event.serializer(), it) }

    /** A consumer's relay that decides only a tier and hands back the agent's configuration. */
    private class TierOnlyRelay(private val transportChoosesModel: Boolean) : CognitiveRelay {
        override val config: RelayConfig = RelayConfig()

        override suspend fun resolve(
            context: RoutingContext,
            fallbackConfiguration: AIConfiguration,
        ): AIConfiguration = fallbackConfiguration

        override suspend fun resolveWithMetadata(
            context: RoutingContext,
            fallbackConfiguration: AIConfiguration,
        ): RoutingResolution = RoutingResolution.Success(
            configuration = fallbackConfiguration,
            reason = "proxy:tier=standard",
            transportChoosesModel = transportChoosesModel,
        )

        override suspend fun updateConfig(newConfig: RelayConfig) {}
    }

    /** A proxy that picks the model server-side and says which. */
    private class ServingProxy(private val served: ServedBy) : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = callDetailed(request, configuration).completion

        override suspend fun callDetailed(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): UpstreamCompletion = UpstreamCompletion(completion = completion(served.modelId), served = served)
    }

    /** A proxy that answers without saying what served. */
    private class SilentProxy : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = completion("unreported")
    }

    /** A proxy that times out before any model answers. */
    private class FailingProxy : UpstreamLlmClient {
        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion = throw IllegalStateException("backend timed out")
    }

    private companion object {
        val SERVED = ServedBy(providerId = "anthropic", modelId = "claude-haiku-5-5")

        fun completion(modelName: String): ChatCompletion = ChatCompletion(
            id = "transport-routed-test",
            created = 0L,
            model = ModelId(modelName),
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatMessage(role = ChatRole.Assistant, content = "answer"),
                ),
            ),
        )
    }
}
