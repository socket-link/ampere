package link.socket.ampere.llm

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.reasoning.AgentReasoning
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

/**
 * AMPR-273: `AgentReasoning.callLLM` gains a `phase` parameter so a caller can
 * tag an ad-hoc invocation with a [CognitivePhase] without dropping to
 * `AgentLLMService` directly. These tests exercise the seam end to end and
 * pin the "omitting it changes nothing" contract.
 */
class PerCallCognitivePhaseTest {

    private val fallbackConfiguration = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1)

    private class CapturingRelay : CognitiveRelay {
        val capturedContext = AtomicReference<RoutingContext?>(null)
        override val config: RelayConfig = RelayConfig()

        override suspend fun resolve(
            context: RoutingContext,
            fallbackConfiguration: AIConfiguration,
        ): AIConfiguration {
            capturedContext.set(context)
            return fallbackConfiguration
        }

        override suspend fun updateConfig(newConfig: RelayConfig) {}
    }

    private fun reasoningWith(relay: CognitiveRelay): AgentReasoning {
        val config = AgentConfiguration(
            agentDefinition = AgentDefinition.Custom(
                name = "phase-tagging-test",
                description = "AMPR-273 phase-tagging test agent",
                prompt = "You are a test agent.",
            ),
            aiConfiguration = fallbackConfiguration,
            cognitiveRelay = relay,
            // The relay never gets as far as the transport; it hands back the
            // fallback and the recorded context is what these tests assert on.
            upstreamLlmClient = UnreachableUpstream,
        )
        return AgentReasoning.create(
            config = config,
            executorId = "phase-tagging-test",
        ) {
            agentRole = "Test Agent"
        }
    }

    private fun capturedPhase(phase: CognitivePhase?): RoutingContext? {
        val relay = CapturingRelay()
        val reasoning = reasoningWith(relay)
        try {
            runBlocking { reasoning.callLLM("do the thing", phase = phase) }
        } catch (_: Throwable) {
            // The fake configuration blows up at the transport; by then the
            // relay has already recorded the context we care about.
        }
        return relay.capturedContext.get()
    }

    @Test
    fun `a call tagged with a phase reaches the relay under that phase`() {
        val captured = capturedPhase(CognitivePhase.EXECUTE)

        assertEquals(CognitivePhase.EXECUTE, captured?.phase)
    }

    @Test
    fun `a call omitting the phase leaves it null, exactly as before`() {
        val captured = capturedPhase(phase = null)

        assertNull(captured?.phase)
    }

    private object UnreachableUpstream : UpstreamLlmClient {
        override suspend fun call(
            request: com.aallam.openai.api.chat.ChatCompletionRequest,
            configuration: AIConfiguration,
        ) = throw NotImplementedError("These tests never get as far as the transport")
    }
}
