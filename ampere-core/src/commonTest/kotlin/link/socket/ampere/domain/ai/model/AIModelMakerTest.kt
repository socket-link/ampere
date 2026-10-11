package link.socket.ampere.domain.ai.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OnDevice
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_OpenRouter

/**
 * A model names its maker, independently of the route a call to it travels
 * (AMPR-434): the provider that lists it in its catalogue is the one that makes it.
 */
class AIModelMakerTest {

    @Test
    fun `every bundled model names the provider that lists it as its maker`() {
        (AIProvider.ALL_PROVIDERS + AIProvider_OnDevice).forEach { provider ->
            provider.availableModels.forEach { model ->
                assertEquals(provider.id, model.makerId, model.name)
            }
        }
    }

    @Test
    fun `a custom entry inherits its family's maker`() {
        val sonnet = AIModel_Claude.Sonnet_5
        val gpt = AIModel_OpenAI.GPT_5_4
        val flash = AIModel_Gemini.Flash_3_8

        assertEquals(
            AIProvider_Anthropic.id,
            AIModel_Claude.Custom(name = "claude-next", features = sonnet.features, limits = sonnet.limits).makerId,
        )
        assertEquals(
            AIProvider_OpenAI.id,
            AIModel_OpenAI.Custom(name = "gpt-next", features = gpt.features, limits = gpt.limits).makerId,
        )
        assertEquals(
            AIProvider_Google.id,
            AIModel_Gemini.Custom(name = "gemini-next", features = flash.features, limits = flash.limits).makerId,
        )
    }

    @Test
    fun `a model reached through OpenRouter keeps its maker`() {
        AIProvider_OpenRouter.availableModels.forEach { model ->
            assertTrue(
                model.makerId in AIProvider.ALL_PROVIDERS.map { it.id },
                "${model.name} names ${model.makerId}",
            )
        }
    }
}
