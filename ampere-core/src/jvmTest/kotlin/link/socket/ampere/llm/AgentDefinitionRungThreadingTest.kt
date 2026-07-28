package link.socket.ampere.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider

class AgentDefinitionRungThreadingTest {

    private object UnreachableUpstream : UpstreamLlmClient {
        override suspend fun call(
            request: com.aallam.openai.api.chat.ChatCompletionRequest,
            configuration: AIConfiguration,
        ) = throw NotImplementedError("These tests never get as far as the transport")
    }

    private class FakeAIConfiguration : AIConfiguration {
        override val provider: AIProvider<*, *>
            get() = throw NotImplementedError()
        override val model: AIModel
            get() = AIModel_OpenAI.GPT_4_1

        override fun getAvailableModels(): List<Pair<AIProvider<*, *>, AIModel>> = emptyList()
    }

    private fun makeDefinition(rung: CapabilityRung?): AgentDefinition =
        object : AgentDefinition.Custom(
            name = "test",
            description = "test agent",
            suggestedAIConfigurationBuilder = { AIConfigurationFactory.getDefaultConfiguration() },
        ) {
            override val minimumRung: CapabilityRung? = rung
        }

    private inner class CapturingRelay : CognitiveRelay {
        var capturedContext: RoutingContext? = null
        override val config: RelayConfig = RelayConfig()

        override suspend fun resolve(
            context: RoutingContext,
            fallbackConfiguration: AIConfiguration,
        ): AIConfiguration {
            capturedContext = context
            return fallbackConfiguration
        }

        override suspend fun updateConfig(newConfig: RelayConfig) {}
    }

    @Test
    fun `AgentDefinition defaults minimumRung to null`() {
        val definition = makeDefinition(null)
        assertNull(definition.minimumRung)
    }

    @Test
    fun `AgentDefinition Custom factory produces null minimumRung by default`() {
        val definition = AgentDefinition.Custom(
            name = "default",
            description = "desc",
            prompt = "prompt",
        )
        assertNull(definition.minimumRung)
    }

    @Test
    fun `AgentDefinition with minimumRung exposes that rung`() {
        val definition = makeDefinition(CapabilityRung.TWO)
        assertEquals(CapabilityRung.TWO, definition.minimumRung)
    }

    @Test
    fun `agent with minimumRung threads it into RoutingContext requirements`() = runTest {
        val relay = CapturingRelay()
        val definition = makeDefinition(CapabilityRung.THREE)
        val config = AgentConfiguration(
            agentDefinition = definition,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveRelay = relay,
            // A transport must be present for `call` to reach the relay at
            // all (AMPR-236); the FakeAIConfiguration is what blows up after.
            upstreamLlmClient = UnreachableUpstream,
        )
        val service = AgentLLMService(agentConfiguration = config)

        try {
            service.call(
                prompt = "test",
                routingContext = RoutingContext(agentId = "agent-1"),
            )
        } catch (_: Throwable) {}

        assertEquals(CapabilityRung.THREE, relay.capturedContext?.requirements?.minRung)
    }

    @Test
    fun `agent without minimumRung leaves RoutingContext requirements minRung null`() = runTest {
        val relay = CapturingRelay()
        val definition = makeDefinition(null)
        val config = AgentConfiguration(
            agentDefinition = definition,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveRelay = relay,
            // A transport must be present for `call` to reach the relay at
            // all (AMPR-236); the FakeAIConfiguration is what blows up after.
            upstreamLlmClient = UnreachableUpstream,
        )
        val service = AgentLLMService(agentConfiguration = config)

        try {
            service.call(
                prompt = "test",
                routingContext = RoutingContext(agentId = "agent-1"),
            )
        } catch (_: Throwable) {}

        assertNull(relay.capturedContext?.requirements?.minRung)
    }

    @Test
    fun `minimumRung is applied even when call-site routingContext has no requirements`() = runTest {
        val relay = CapturingRelay()
        val definition = makeDefinition(CapabilityRung.FOUR)
        val config = AgentConfiguration(
            agentDefinition = definition,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveRelay = relay,
            // A transport must be present for `call` to reach the relay at
            // all (AMPR-236); the FakeAIConfiguration is what blows up after.
            upstreamLlmClient = UnreachableUpstream,
        )
        val service = AgentLLMService(agentConfiguration = config)

        try {
            service.call(
                prompt = "test",
                routingContext = RoutingContext(),
            )
        } catch (_: Throwable) {}

        assertEquals(CapabilityRung.FOUR, relay.capturedContext?.requirements?.minRung)
    }

    @Test
    fun `a stricter call-site floor survives an agent declaring a lower one`() = runTest {
        val relay = CapturingRelay()
        val definition = makeDefinition(CapabilityRung.THREE)
        val config = AgentConfiguration(
            agentDefinition = definition,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveRelay = relay,
            // A transport must be present for `call` to reach the relay at
            // all (AMPR-236); the FakeAIConfiguration is what blows up after.
            upstreamLlmClient = UnreachableUpstream,
        )
        val service = AgentLLMService(agentConfiguration = config)

        try {
            service.call(
                prompt = "test",
                routingContext = RoutingContext(
                    agentId = "agent-1",
                    requirements = CapabilityRequirement(minRung = CapabilityRung.FOUR),
                ),
            )
        } catch (_: Throwable) {}

        assertEquals(CapabilityRung.FOUR, relay.capturedContext?.requirements?.minRung)
    }

    @Test
    fun `the agent floor wins when it is stricter than the call-site floor`() = runTest {
        val relay = CapturingRelay()
        val definition = makeDefinition(CapabilityRung.THREE)
        val config = AgentConfiguration(
            agentDefinition = definition,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveRelay = relay,
            // A transport must be present for `call` to reach the relay at
            // all (AMPR-236); the FakeAIConfiguration is what blows up after.
            upstreamLlmClient = UnreachableUpstream,
        )
        val service = AgentLLMService(agentConfiguration = config)

        try {
            service.call(
                prompt = "test",
                routingContext = RoutingContext(
                    agentId = "agent-1",
                    requirements = CapabilityRequirement(minRung = CapabilityRung.TWO),
                ),
            )
        } catch (_: Throwable) {}

        assertEquals(CapabilityRung.THREE, relay.capturedContext?.requirements?.minRung)
    }

    @Test
    fun `merging the agent floor preserves the call-site's other requirement axes`() = runTest {
        val relay = CapturingRelay()
        val definition = makeDefinition(CapabilityRung.THREE)
        val config = AgentConfiguration(
            agentDefinition = definition,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveRelay = relay,
            // A transport must be present for `call` to reach the relay at
            // all (AMPR-236); the FakeAIConfiguration is what blows up after.
            upstreamLlmClient = UnreachableUpstream,
        )
        val service = AgentLLMService(agentConfiguration = config)

        try {
            service.call(
                prompt = "test",
                routingContext = RoutingContext(
                    agentId = "agent-1",
                    requirements = CapabilityRequirement(minContextTokens = 128_000),
                ),
            )
        } catch (_: Throwable) {}

        assertEquals(CapabilityRung.THREE, relay.capturedContext?.requirements?.minRung)
        assertEquals(128_000, relay.capturedContext?.requirements?.minContextTokens)
    }

    @Test
    fun `minimumRung is applied even when routingContext is null`() = runTest {
        val relay = CapturingRelay()
        val definition = makeDefinition(CapabilityRung.ONE)
        val config = AgentConfiguration(
            agentDefinition = definition,
            aiConfiguration = FakeAIConfiguration(),
            cognitiveRelay = relay,
            // A transport must be present for `call` to reach the relay at
            // all (AMPR-236); the FakeAIConfiguration is what blows up after.
            upstreamLlmClient = UnreachableUpstream,
        )
        val service = AgentLLMService(agentConfiguration = config)

        try {
            service.call(
                prompt = "test",
                routingContext = null,
            )
        } catch (_: Throwable) {}

        assertEquals(CapabilityRung.ONE, relay.capturedContext?.requirements?.minRung)
    }
}
