package link.socket.ampere.roster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import link.socket.ampere.agents.config.AgentConfiguration
import link.socket.ampere.agents.domain.cognition.sparks.CognitivePhase
import link.socket.ampere.agents.domain.reasoning.AgentLLMService
import link.socket.ampere.agents.domain.routing.CognitiveRelay
import link.socket.ampere.agents.domain.routing.CognitiveRelayImpl
import link.socket.ampere.agents.domain.routing.ExecutionTags
import link.socket.ampere.agents.domain.routing.RelayConfig
import link.socket.ampere.agents.domain.routing.RoutingContext
import link.socket.ampere.agents.domain.routing.RoutingRule
import link.socket.ampere.agents.domain.task.EffortLevel
import link.socket.ampere.agents.domain.task.ExecutionAssignment
import link.socket.ampere.domain.agent.bundled.AgentDefinition
import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.llm.UpstreamLlmClient

/**
 * AMPR-413: a seat's [ExecutionAssignment] reaches the relay as tags on the
 * [RoutingContext] of calls made as that seat, and of no other seat's.
 */
class SeatRoutingTest {

    /** A consumer's own alias for a model, which is all Ampere ever sees of one. */
    private val bigModel = "the-big-one"

    private val scout = RoleConfig(
        id = RoleId("scout"),
        title = "Scout",
        instructions = PromptRef("consumer.scout", 1),
        tools = setOf("web_search"),
        execution = ExecutionAssignment(model = bigModel, effort = EffortLevel.HIGH),
    )

    private val clerk = RoleConfig(
        id = RoleId("clerk"),
        title = "Clerk",
        instructions = PromptRef("consumer.clerk", 1),
    )

    private val fallbackConfiguration = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_4_1)
    private val bigConfiguration = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)

    @Test
    fun `a seat's assignment reaches the routing context as tags`() {
        val tagged = RoutingContext().withSeat(scout)

        assertEquals(
            setOf(ExecutionTags.model(bigModel), ExecutionTags.effort(EffortLevel.HIGH)),
            tagged.tags,
        )
    }

    @Test
    fun `a seat that declares no assignment leaves the context untouched`() {
        val call = RoutingContext(phase = CognitivePhase.EXECUTE, agentId = "agent-1", tags = setOf("code-generation"))

        assertEquals(call, call.withSeat(clerk))
    }

    @Test
    fun `a seat's tags join the tags the call already carries`() {
        val call = RoutingContext(phase = CognitivePhase.PLAN, tags = setOf("code-generation"))

        val tagged = call.withSeat(scout)

        assertTrue(ExecutionTags.model(bigModel) in tagged.tags)
        assertTrue("code-generation" in tagged.tags)
        assertEquals(CognitivePhase.PLAN, tagged.phase, "the call's own facts survive the seat")
    }

    @Test
    fun `only the properties an assignment sets become tags`() {
        val effortOnly = clerk.copy(execution = ExecutionAssignment(effort = EffortLevel.LOW))

        assertEquals(setOf(ExecutionTags.effort(EffortLevel.LOW)), RoutingContext().withSeat(effortOnly).tags)
        assertEquals(emptySet<String>(), RoutingContext().withSeat(clerk.copy(execution = ExecutionAssignment())).tags)
    }

    @Test
    fun `the relay routes a seat's call by its model tag and another seat's by the fallback`() = runTest {
        val relay = CognitiveRelayImpl(
            initialConfig = RelayConfig(
                rules = listOf(RoutingRule.ByTag(ExecutionTags.model(bigModel), bigConfiguration)),
            ),
        )

        assertEquals(
            AIModel_Claude.Sonnet_5,
            relay.resolve(RoutingContext().withSeat(scout), fallbackConfiguration).model,
            "the Scout declared the big model and a rule names that tag",
        )
        assertEquals(
            AIModel_OpenAI.GPT_4_1,
            relay.resolve(RoutingContext().withSeat(clerk), fallbackConfiguration).model,
            "the Clerk declared nothing and keeps the route the call would have taken",
        )
    }

    @Test
    fun `a seat's call reaches the relay through AgentLLMService under that seat's tags`() = runTest {
        assertTrue(ExecutionTags.model(bigModel) in assertNotNull(tagsSeenByTheRelayFor(scout)))
        assertFalse(ExecutionTags.model(bigModel) in assertNotNull(tagsSeenByTheRelayFor(clerk)))
    }

    /** What the relay was handed for a call made as [seat], or null if it was never asked. */
    private suspend fun tagsSeenByTheRelayFor(seat: RoleConfig): Set<String>? {
        val relay = CapturingRelay()
        val service = AgentLLMService(
            agentConfiguration = AgentConfiguration(
                agentDefinition = AgentDefinition.Custom(
                    name = "seat-routing-test",
                    description = "AMPR-413 seat routing test agent",
                    prompt = "You are a test agent.",
                ),
                aiConfiguration = fallbackConfiguration,
                cognitiveRelay = relay,
                upstreamLlmClient = UnreachableUpstream,
            ),
        )

        try {
            service.call(prompt = "do the thing", routingContext = RoutingContext().withSeat(seat))
        } catch (_: Throwable) {
            // The transport is unreachable on purpose; by the time it throws, the relay
            // has already been asked, which is what this test reads.
        }
        return relay.captured?.tags
    }

    private class CapturingRelay : CognitiveRelay {
        var captured: RoutingContext? = null
        override val config: RelayConfig = RelayConfig()

        override suspend fun resolve(
            context: RoutingContext,
            fallbackConfiguration: AIConfiguration,
        ): AIConfiguration {
            captured = context
            return fallbackConfiguration
        }

        override suspend fun updateConfig(newConfig: RelayConfig) = Unit
    }

    private object UnreachableUpstream : UpstreamLlmClient {
        override suspend fun call(
            request: com.aallam.openai.api.chat.ChatCompletionRequest,
            configuration: AIConfiguration,
        ) = throw NotImplementedError("This test never gets as far as the transport")
    }
}
