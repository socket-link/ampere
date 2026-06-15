package link.socket.ampere.agents.definition

import com.aallam.openai.api.chat.ChatChoice
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.domain.routing.CapabilityRoutingDefaults
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingFloorUnmetException
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.agents.domain.routing.capability.InMemoryModelDescriptorRegistry
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.llm.UpstreamLlmClient

/**
 * AMPR-219 (T6): end-to-end activation of the [CognitiveRelay] in the one
 * production agent path it was built for — the `SparkBasedAgent.Code` factory
 * (the agent `AgentFactory` builds for [AgentType.CODE]).
 *
 * Unlike the lower-level relay unit tests, these drive a **real bundled agent**
 * (`SparkBasedAgent.Code`) end to end through its own `callLLM` seam, so the full
 * chain executes live: `AgentDefinition.minimumRung` floor → `RoutingContext` →
 * `CognitiveRelayImpl.resolveWithMetadata` → model-keyed cost-aware selection →
 * the outbound client. This is exactly the "tier system with a live consumer"
 * the ticket exists to validate.
 *
 * The relay + registry mirror what `AgentFactory` wires for the CODE path:
 * [CapabilityRoutingDefaults.cloudCapabilityRules] over the default
 * [InMemoryModelDescriptorRegistry] seed.
 */
class SparkBasedAgentRelayActivationTest {

    private val sparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }

    // The agent's static fallback, used only if the relay matches no rule. With
    // the full cloud catalog and a satisfiable floor a capability rule always
    // matches, so this is never the selected route in the happy-path test.
    private val agentFallback = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1)

    private fun productionLikeRelay(eventBus: EventSerialBus): CognitiveRelay =
        CognitiveRelayImpl(
            initialConfig = RelayConfig(rules = CapabilityRoutingDefaults.cloudCapabilityRules()),
            eventBus = eventBus,
            registry = InMemoryModelDescriptorRegistry(),
        )

    @Test
    fun `Code factory wires the relay and rung floor onto its AgentConfiguration`() {
        val eventBus = EventSerialBus(CoroutineScope(Dispatchers.Default))
        val relay = productionLikeRelay(eventBus)

        val agent = SparkBasedAgent.Code(
            sparkRegistry = sparkLibrary,
            agentId = "code-relay-wiring",
            aiConfiguration = agentFallback,
            cognitiveRelay = relay,
            minimumRung = CapabilityRung.THREE,
        )

        val config: AgentConfiguration = agent.agentConfiguration
        assertNotNull(config.cognitiveRelay, "Code factory must set the cognitive relay on the config")
        assertEquals(
            CapabilityRung.THREE,
            config.agentDefinition.minimumRung,
            "Code factory must declare the rung floor on the agent definition",
        )
    }

    @Test
    fun `a real Code-agent run resolves through the relay, respects the floor, and emits route events`() {
        val eventBus = EventSerialBus(CoroutineScope(Dispatchers.Default))
        val routeSelected = subscribe(eventBus, RoutingEvent.RouteSelected.EVENT_TYPE)
        val routeResolved = subscribe(eventBus, RoutingEvent.RouteResolved.EVENT_TYPE)
        val client = RecordingUpstreamClient("code-response")

        val agent = SparkBasedAgent.Code(
            sparkRegistry = sparkLibrary,
            agentId = "code-relay-run",
            aiConfiguration = agentFallback,
            upstreamLlmClient = client,
            cognitiveRelay = productionLikeRelay(eventBus),
            minimumRung = CapabilityRung.THREE,
        )

        // A genuine call through the agent's own LLM path (not the relay directly).
        val response = agent.callLLM("Refactor this function.")

        assertEquals("code-response", response)

        // Floor respected: the relay selected the cheapest catalog model whose
        // rung clears THREE — Gemini 2.5 Pro (Google, the cheapest provider,
        // rung THREE). It must NOT have downgraded to a sub-floor model.
        val selectedConfig = client.lastConfig.get()
        assertNotNull(selectedConfig, "the outbound client must have been called with a resolved config")
        assertEquals(
            AIModel_Gemini.Pro_2_5.name,
            selectedConfig.model.name,
            "relay should resolve to the cheapest rung>=THREE model in the catalog",
        )

        runBlocking { delay(EVENT_DELIVERY_MS) }

        assertEquals(1, routeSelected.size, "exactly one RouteSelected must fire on the live relay path")
        val decision = (routeSelected.first() as RoutingEvent.RouteSelected).decision
        assertEquals(AIProvider_Google.name, decision.providerName)
        assertEquals(AIModel_Gemini.Pro_2_5.name, decision.modelName)

        assertTrue(
            routeResolved.isNotEmpty(),
            "cost-aware capability selection must emit RouteResolved",
        )
        val resolved = routeResolved.first() as RoutingEvent.RouteResolved
        assertEquals(AIModel_Gemini.Pro_2_5.name, resolved.decision.modelName)
        assertTrue(resolved.candidateCount > 1, "multiple catalog models clear THREE, so >1 candidate")
    }

    @Test
    fun `an unsatisfiable floor yields a typed failure and RouteFloorUnmet rather than a downgrade`() {
        val eventBus = EventSerialBus(CoroutineScope(Dispatchers.Default))
        val floorUnmet = subscribe(eventBus, RoutingEvent.RouteFloorUnmet.EVENT_TYPE)
        val client = RecordingUpstreamClient("must-not-be-called")

        // A rung above anything in the bundled catalog (which tops out at FOUR):
        // no model can satisfy it, so the relay must fail terminally.
        val unsatisfiableFloor = CapabilityRung(CapabilityRung.FOUR.ordinal + 1)

        val agent = SparkBasedAgent.Code(
            sparkRegistry = sparkLibrary,
            agentId = "code-relay-floor-unmet",
            aiConfiguration = agentFallback,
            upstreamLlmClient = client,
            cognitiveRelay = productionLikeRelay(eventBus),
            minimumRung = unsatisfiableFloor,
        )

        val failure = assertFailsWith<RoutingFloorUnmetException> {
            agent.callLLM("Refactor this function.")
        }
        assertEquals(unsatisfiableFloor, failure.requestedFloor)
        assertEquals(CapabilityRung.FOUR, failure.bestAvailableRung)

        // No downgrade: the outbound client must never have been invoked.
        assertNull(client.lastConfig.get(), "an unmet floor must not fall through to any model")

        runBlocking { delay(EVENT_DELIVERY_MS) }
        assertEquals(1, floorUnmet.size, "exactly one RouteFloorUnmet must fire")
        val event = floorUnmet.first() as RoutingEvent.RouteFloorUnmet
        assertEquals(unsatisfiableFloor, event.requestedFloor)
        assertEquals(CapabilityRung.FOUR, event.bestAvailableRung)
    }

    private fun subscribe(eventBus: EventSerialBus, eventType: String): MutableList<Event> {
        val received = CopyOnWriteArrayList<Event>()
        eventBus.subscribe(
            agentId = "test-subscriber-$eventType",
            eventType = eventType,
            handler = EventHandler { event, _ -> received.add(event) },
        )
        return received
    }

    private class RecordingUpstreamClient(
        private val cannedResponse: String,
    ) : UpstreamLlmClient {
        val lastConfig = AtomicReference<AIConfiguration?>(null)

        override suspend fun call(
            request: ChatCompletionRequest,
            configuration: AIConfiguration,
        ): ChatCompletion {
            lastConfig.set(configuration)
            return ChatCompletion(
                id = "rec",
                created = 0L,
                model = ModelId(configuration.model.name),
                choices = listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage(role = ChatRole.Assistant, content = cannedResponse),
                    ),
                ),
            )
        }
    }

    companion object {
        private const val EVENT_DELIVERY_MS = 150L
    }
}
