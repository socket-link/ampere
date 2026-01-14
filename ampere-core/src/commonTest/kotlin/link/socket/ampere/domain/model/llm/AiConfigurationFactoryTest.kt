package link.socket.ampere.domain.model.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import link.socket.ampere.domain.ai.configuration.AIConfigurationFactory
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

class AiConfigurationFactoryTest {

    @Test
    fun `aiConfiguration for Gemini Flash_2_5 has Google provider`() {
        val config = AIConfigurationFactory.aiConfiguration(AIModel_Gemini.Flash_2_5)
        assertSame(AIModel_Gemini.Flash_2_5, config.model)
        assertSame(AIProvider_Google, config.provider)
        assertEquals("gemini-2.5-flash", config.model.name)
    }

    @Test
    fun `aiConfiguration for Claude Opus_4_1 model uses Anthropic provider`() {
        val config = AIConfigurationFactory.aiConfiguration(AIModel_Claude.Opus_4_1)
        assertSame(AIModel_Claude.Opus_4_1, config.model)
        assertSame(AIProvider_Anthropic, config.provider)
        assertEquals("claude-opus-4-1", config.model.name)
    }

    @Test
    fun `aiConfiguration for OpenAI GPT_5 model uses OpenAI provider`() {
        val config = AIConfigurationFactory.aiConfiguration(AIModel_OpenAI.GPT_5)
        assertSame(AIModel_OpenAI.GPT_5, config.model)
        assertSame(AIProvider_OpenAI, config.provider)
        assertEquals("gpt-5", config.model.name)
    }
}
