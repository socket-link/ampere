package link.socket.ampere.domain.ai.configuration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import link.socket.ampere.domain.agent.LLMAgent
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_OnDevice
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenRouter
import link.socket.ampere.domain.chat.ConversationHistory
import link.socket.ampere.domain.util.toClientModelId

/**
 * The id a request carries comes from the configuration, not the model (AMPR-434):
 * the maker's id when the model is reached through its maker, the route's id for
 * the same model when it is reached through OpenRouter.
 */
class AIConfigurationWireIdTest {

    @Test
    fun `a direct configuration's wire id is the model's name`() {
        val anthropic = AIConfiguration_Default(AIProvider_Anthropic, AIModel_Claude.Sonnet_5)
        val openAI = AIConfiguration_Default(AIProvider_OpenAI, AIModel_OpenAI.GPT_5_4)

        assertEquals("claude-sonnet-5", anthropic.wireModelId)
        assertEquals("gpt-5.4", openAI.wireModelId)
        assertEquals("claude-sonnet-5", anthropic.toClientModelId().id)
    }

    @Test
    fun `a routed configuration's wire id is OpenRouter's slug for the same model`() {
        val routed = AIConfiguration_Default(AIProvider_OpenRouter, AIModel_Claude.Sonnet_5)

        assertEquals("anthropic/claude-sonnet-5", routed.wireModelId)
        assertEquals("anthropic/claude-sonnet-5", routed.toClientModelId().id)
        assertEquals("claude-sonnet-5", routed.model.name)
    }

    @Test
    fun `a routed configuration refuses a model OpenRouter has no slug for`() {
        val sonnet = AIModel_Claude.Sonnet_5
        val unlisted = AIModel_Claude.Custom(
            name = "claude-unlisted",
            features = sonnet.features,
            limits = sonnet.limits,
        )
        val routed = AIConfiguration_Default(AIProvider_OpenRouter, unlisted)

        val failure = assertFailsWith<IllegalArgumentException> { routed.wireModelId }
        assertTrue(failure.message.orEmpty().contains("claude-unlisted"), failure.message)
    }

    @Test
    fun `a configuration with backups sends the wire id of the configuration it fell back to`() {
        // The on-device provider's client throws on read, so the first backup is the one used.
        val configuration = AIConfiguration_WithBackups(
            listOf(
                AIConfiguration_Default(AIProvider_OnDevice, AIModel_OnDevice.AppleFoundationModels),
                AIConfiguration_Default(AIProvider_OpenRouter, AIModel_Claude.Sonnet_5),
            ),
        )

        assertEquals(AIModel_OnDevice.AppleFoundationModels.name, configuration.wireModelId)

        configuration.provider

        assertEquals(AIModel_Claude.Sonnet_5, configuration.model)
        assertEquals("anthropic/claude-sonnet-5", configuration.wireModelId)
    }

    @Test
    fun `an agent's completion request carries its configuration's wire id`() {
        val routed = AIConfiguration_Default(AIProvider_OpenRouter, AIModel_Claude.Sonnet_5)
        val agent = WireIdAgent(routed)

        val history = ConversationHistory.NonThreaded(emptyList())
        val ownModel = agent.createCompletionRequest(routed.model, history)
        val otherModel = agent.createCompletionRequest(AIModel_OpenAI.GPT_5_4, history)

        assertEquals("anthropic/claude-sonnet-5", ownModel.model.id)
        assertEquals("gpt-5.4", otherModel.model.id)
    }

    private class WireIdAgent(override val config: AIConfiguration) : LLMAgent {
        override val scope: CoroutineScope = CoroutineScope(Job())
    }
}
