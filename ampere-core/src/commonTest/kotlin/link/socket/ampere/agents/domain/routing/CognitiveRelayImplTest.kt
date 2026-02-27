package link.socket.ampere.agents.domain.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.event.Event
import link.socket.ampere.agents.domain.event.RoutingEvent
import link.socket.ampere.agents.events.api.EventHandler
import link.socket.ampere.agents.events.bus.EventSerialBus
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class CognitiveRelayImplTest {

    private val claudeConfig = AIConfiguration_Default(
        provider = AIProvider_Anthropic,
        model = AIModel_Claude.Sonnet_4,
    )

    private val geminiConfig = AIConfiguration_Default(
        provider = AIProvider_Google,
        model = AIModel_Gemini.Flash_2_5,
    )

    private val openaiConfig = AIConfiguration_Default(
        provider = AIProvider_OpenAI,
        model = AIModel_OpenAI.GPT_4_1,
    )

    @Test
    fun `resolves first matching rule`() = runTest {
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.PERCEIVE, geminiConfig),
                    RoutingRule.ByPhase(CognitivePhase.EXECUTE, claudeConfig),
                ),
            ),
        )

        val context = RoutingContext(phase = CognitivePhase.PERCEIVE)
        val result = relay.resolve(context, openaiConfig)

        assertEquals(AIModel_Gemini.Flash_2_5, result.model)
    }

    @Test
    fun `second rule matches when first does not`() = runTest {
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.PERCEIVE, geminiConfig),
                    RoutingRule.ByPhase(CognitivePhase.EXECUTE, claudeConfig),
                ),
            ),
        )

        val context = RoutingContext(phase = CognitivePhase.EXECUTE)
        val result = relay.resolve(context, openaiConfig)

        assertEquals(AIModel_Claude.Sonnet_4, result.model)
    }

    @Test
    fun `falls back to default config when no rule matches`() = runTest {
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.PERCEIVE, geminiConfig),
                ),
                defaultConfiguration = claudeConfig,
            ),
        )

        val context = RoutingContext(phase = CognitivePhase.LEARN)
        val result = relay.resolve(context, openaiConfig)

        assertEquals(AIModel_Claude.Sonnet_4, result.model)
    }

    @Test
    fun `falls back to agent config when no default configured`() = runTest {
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.PERCEIVE, geminiConfig),
                ),
            ),
        )

        val context = RoutingContext(phase = CognitivePhase.EXECUTE)
        val result = relay.resolve(context, openaiConfig)

        assertEquals(AIModel_OpenAI.GPT_4_1, result.model)
    }

    @Test
    fun `emits RouteSelected event through EventBus`() = runTest {
        val scope = CoroutineScope(Dispatchers.Default)
        val eventBus = EventSerialBus(scope)
        val receivedEvents = mutableListOf<Event>()

        eventBus.subscribe(
            agentId = "test-subscriber",
            eventType = RoutingEvent.RouteSelected.EVENT_TYPE,
            handler = EventHandler { event, _ ->
                receivedEvents.add(event)
            },
        )

        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.PERCEIVE, geminiConfig),
                ),
            ),
            eventBus = eventBus,
        )

        val context = RoutingContext(
            phase = CognitivePhase.PERCEIVE,
            agentId = "test-agent",
        )
        relay.resolve(context, openaiConfig)

        // Give coroutine time to process
        kotlinx.coroutines.delay(100)

        assertTrue(receivedEvents.isNotEmpty(), "Expected at least one routing event")
        val event = receivedEvents.first() as RoutingEvent.RouteSelected
        assertEquals("test-agent", event.agentId)
        assertEquals(CognitivePhase.PERCEIVE, event.phase)
        assertEquals("Google", event.decision.providerName)
    }

    @Test
    fun `hot-swap updates config atomically`() = runTest {
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.EXECUTE, geminiConfig),
                ),
            ),
        )

        val context = RoutingContext(phase = CognitivePhase.EXECUTE)

        // Before hot-swap: routes to Gemini
        val before = relay.resolve(context, openaiConfig)
        assertEquals(AIModel_Gemini.Flash_2_5, before.model)

        // Hot-swap to Claude
        relay.updateConfig(
            RelayConfig(
                rules = listOf(
                    RoutingRule.ByPhase(CognitivePhase.EXECUTE, claudeConfig),
                ),
            ),
        )

        // After hot-swap: routes to Claude
        val after = relay.resolve(context, openaiConfig)
        assertEquals(AIModel_Claude.Sonnet_4, after.model)
    }

    @Test
    fun `empty rules always falls back`() = runTest {
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(),
        )

        val context = RoutingContext(phase = CognitivePhase.PERCEIVE)
        val result = relay.resolve(context, claudeConfig)

        assertEquals(AIModel_Claude.Sonnet_4, result.model)
    }

    @Test
    fun `mixed rule types evaluate correctly`() = runTest {
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(
                    RoutingRule.ByRole("CodeAgent", claudeConfig),
                    RoutingRule.ByPhase(CognitivePhase.PERCEIVE, geminiConfig),
                    RoutingRule.ByTag("summarization", openaiConfig),
                ),
            ),
        )

        // Role rule matches first
        val codeContext = RoutingContext(
            agentRole = "CodeAgent",
            phase = CognitivePhase.PERCEIVE,
        )
        assertEquals(AIModel_Claude.Sonnet_4, relay.resolve(codeContext, openaiConfig).model)

        // Phase rule matches for non-CodeAgent
        val perceiveContext = RoutingContext(
            agentRole = "ProductAgent",
            phase = CognitivePhase.PERCEIVE,
        )
        assertEquals(AIModel_Gemini.Flash_2_5, relay.resolve(perceiveContext, openaiConfig).model)

        // Tag rule matches for unmatched phase/role
        val tagContext = RoutingContext(
            agentRole = "ProductAgent",
            phase = CognitivePhase.EXECUTE,
            tags = setOf("summarization"),
        )
        assertEquals(AIModel_OpenAI.GPT_4_1, relay.resolve(tagContext, claudeConfig).model)
    }
}
