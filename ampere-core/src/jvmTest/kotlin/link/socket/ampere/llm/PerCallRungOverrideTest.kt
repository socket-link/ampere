package link.socket.ampere.llm

import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import link.socket.ampere.agents.definition.SparkBasedAgent
import link.socket.ampere.agents.domain.cognition.sparks.DefaultPhaseSparkLibrary
import link.socket.ampere.agents.domain.cognition.sparks.PhaseSparkLibrary
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.capability.CapabilityRequirement
import link.socket.ampere.agents.domain.routing.capability.CapabilityRung
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

/**
 * AMPR-232: the per-call rung override, driven through a real bundled agent.
 *
 * `AgentLLMService` has composed the two floors as `maxOf` since AMPR-229, but no
 * production caller could reach the call-site half of it — `AgentReasoning` built
 * the `RoutingContext` itself with no seam for requirements. These tests exercise
 * that seam end to end and pin the semantics in both directions: a floor declared
 * per call can only ever *raise* the agent's own.
 */
class PerCallRungOverrideTest {

    private val sparkLibrary: PhaseSparkLibrary = runBlocking { DefaultPhaseSparkLibrary.load() }

    private val agentFallback = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1)

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

    private fun agentWith(
        relay: CognitiveRelay,
        agentFloor: CapabilityRung?,
    ): SparkBasedAgent<*> = SparkBasedAgent.Code(
        sparkRegistry = sparkLibrary,
        agentId = "per-call-override",
        aiConfiguration = agentFallback,
        // The relay never gets as far as the transport; it hands back the
        // fallback and the recorded context is what these tests assert on.
        upstreamLlmClient = UnreachableUpstream,
        cognitiveRelay = relay,
        minimumRung = agentFloor,
        observabilityScope = CoroutineScope(Dispatchers.Default),
    )

    private fun capturedMinRung(
        agentFloor: CapabilityRung?,
        requirements: CapabilityRequirement?,
    ): RoutingContext? {
        val relay = CapturingRelay()
        val agent = agentWith(relay, agentFloor)
        try {
            agent.callLLM("do the thing", requirements = requirements)
        } catch (_: Throwable) {
            // The fake configuration blows up at the transport; by then the
            // relay has already recorded the context we care about.
        }
        return relay.capturedContext.get()
    }

    @Test
    fun `a call-site floor above the agent's resolves against the call-site floor`() {
        val captured = capturedMinRung(
            agentFloor = CapabilityRung.THREE,
            requirements = CapabilityRequirement(minRung = CapabilityRung.FOUR),
        )

        assertEquals(CapabilityRung.FOUR, captured?.requirements?.minRung)
    }

    @Test
    fun `a call-site floor below the agent's does not downgrade it`() {
        val captured = capturedMinRung(
            agentFloor = CapabilityRung.FOUR,
            requirements = CapabilityRequirement(minRung = CapabilityRung.THREE),
        )

        assertEquals(CapabilityRung.FOUR, captured?.requirements?.minRung)
    }

    @Test
    fun `a call-site floor applies to an agent declaring none`() {
        val captured = capturedMinRung(
            agentFloor = null,
            requirements = CapabilityRequirement(minRung = CapabilityRung.FOUR),
        )

        assertEquals(CapabilityRung.FOUR, captured?.requirements?.minRung)
    }

    @Test
    fun `a call supplying no requirements keeps the agent's own floor`() {
        val captured = capturedMinRung(
            agentFloor = CapabilityRung.THREE,
            requirements = null,
        )

        assertEquals(CapabilityRung.THREE, captured?.requirements?.minRung)
    }

    @Test
    fun `neither an agent floor nor a call-site floor leaves the call unconstrained`() {
        val captured = capturedMinRung(agentFloor = null, requirements = null)

        assertNull(captured?.requirements?.minRung)
    }

    @Test
    fun `a per-call requirement carries its non-rung axes through to the relay`() {
        val captured = capturedMinRung(
            agentFloor = CapabilityRung.THREE,
            requirements = CapabilityRequirement(minContextTokens = 128_000),
        )

        assertEquals(CapabilityRung.THREE, captured?.requirements?.minRung)
        assertEquals(128_000, captured?.requirements?.minContextTokens)
    }

    private object UnreachableUpstream : UpstreamLlmClient {
        override suspend fun call(
            request: com.aallam.openai.api.chat.ChatCompletionRequest,
            configuration: AIConfiguration,
        ) = throw NotImplementedError("These tests never get as far as the transport")
    }
}
