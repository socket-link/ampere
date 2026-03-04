package link.socket.ampere.dsl.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import link.socket.ampere.domain.ai.configuration.AIConfiguration_WithBackups
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class ProviderConfigTest {

    @Test
    fun `AnthropicConfig apiKey creates runtime provider with injected token`() {
        val configuration = AnthropicConfig(
            apiKey = "anthropic-runtime-key",
            model = AIModel_Claude.Opus_4_1,
        ).toAIConfiguration()

        assertEquals("anthropic-runtime-key", configuration.provider.apiToken)
        assertEquals(AIProvider_Anthropic.id, configuration.provider.id)
        assertEquals(AIProvider_Anthropic.name, configuration.provider.name)
        assertSame(AIModel_Claude.Opus_4_1, configuration.model)
        assertNotSame(AIProvider_Anthropic, configuration.provider)
    }

    @Test
    fun `OpenAIConfig apiKey creates runtime provider with injected token`() {
        val configuration = OpenAIConfig(
            apiKey = "openai-runtime-key",
            model = AIModel_OpenAI.GPT_5,
        ).toAIConfiguration()

        assertEquals("openai-runtime-key", configuration.provider.apiToken)
        assertEquals(AIProvider_OpenAI.id, configuration.provider.id)
        assertEquals(AIProvider_OpenAI.name, configuration.provider.name)
        assertSame(AIModel_OpenAI.GPT_5, configuration.model)
        assertNotSame(AIProvider_OpenAI, configuration.provider)
    }

    @Test
    fun `GeminiConfig apiKey creates runtime provider with injected token`() {
        val configuration = GeminiConfig(
            apiKey = "google-runtime-key",
            model = AIModel_Gemini.Pro_2_5,
        ).toAIConfiguration()

        assertEquals("google-runtime-key", configuration.provider.apiToken)
        assertEquals(AIProvider_Google.id, configuration.provider.id)
        assertEquals(AIProvider_Google.name, configuration.provider.name)
        assertSame(AIModel_Gemini.Pro_2_5, configuration.model)
        assertNotSame(AIProvider_Google, configuration.provider)
    }

    @Test
    fun `provider configs without apiKey keep singleton providers and preserve backup tokens`() {
        val configuration = AnthropicConfig(model = AIModel_Claude.Sonnet_4)
            .withBackup(
                OpenAIConfig(
                    apiKey = "backup-openai-key",
                    model = AIModel_OpenAI.GPT_4_1,
                ),
            )
            .toAIConfiguration()

        val withBackups = assertIs<AIConfiguration_WithBackups>(configuration)

        assertSame(AIProvider_Anthropic, withBackups.configurations.first().provider)
        assertEquals(
            listOf(AIProvider_Anthropic.id, AIProvider_OpenAI.id),
            withBackups.configurations.map { it.provider.id },
        )
        assertEquals(
            listOf(AIProvider_Anthropic.apiToken, "backup-openai-key"),
            withBackups.configurations.map { it.provider.apiToken },
        )
    }
}
