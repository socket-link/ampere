package link.socket.ampere.config

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import link.socket.ampere.domain.ai.configuration.AIConfiguration_WithBackups
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_OpenRouter
import link.socket.ampere.dsl.config.OpenRouterConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * `provider: openrouter` is a route, so it reads `model` from the table of the
 * `maker` it names (AMPR-434).
 */
class ConfigConverterOpenRouterTest {

    @Test
    fun `openrouter reads its model from the maker's table and sends OpenRouter's slug`() {
        val configuration = ConfigConverter.toAIConfiguration(
            AIProviderConfig(
                provider = "anthropic",
                model = "sonnet-5",
                backups = listOf(AIProviderConfig(provider = "openrouter", maker = "anthropic", model = "sonnet-5")),
            ),
        )

        val withBackups = assertIs<AIConfiguration_WithBackups>(configuration)
        assertEquals(
            listOf(
                AIProvider_Anthropic.id to AIModel_Claude.Sonnet_5,
                AIProvider_OpenRouter.id to AIModel_Claude.Sonnet_5,
            ),
            withBackups.getAvailableModels().map { (provider, model) -> provider.id to model },
        )
        assertEquals("anthropic/claude-sonnet-5", withBackups.configurations[1].wireModelId)

        val primary = ConfigConverter.toProviderConfig(
            AIProviderConfig(provider = "openrouter", maker = "gemini", model = "flash-3.8"),
        )
        assertEquals(OpenRouterConfig(model = AIModel_Gemini.Flash_3_8), primary)
        assertEquals("google/gemini-3.8-flash", primary.toAIConfiguration().wireModelId)
    }

    @Test
    fun `openrouter without a maker is an error that names the field`() {
        val missingMaker = AIProviderConfig(provider = "openrouter", model = "sonnet-5")

        val conversion = assertThrows<IllegalArgumentException> { ConfigConverter.toAIConfiguration(missingMaker) }
        assertTrue(conversion.message.orEmpty().contains("'maker'"), conversion.message)
        assertThrows<IllegalArgumentException> { ConfigConverter.toProviderConfig(missingMaker) }

        val file = File.createTempFile("ampere-config", ".yaml")
        try {
            file.writeText(
                """
                ai:
                  provider: openrouter
                  model: sonnet-5
                team:
                  - role: engineer
                """.trimIndent(),
            )
            val errors = ConfigParser.validate(file)
            assertEquals(1, errors.size, "$errors")
            assertTrue(errors.single().contains("'maker'"), errors.single())

            file.writeText(
                """
                ai:
                  provider: openrouter
                  maker: anthropic
                  model: sonnet-5
                  backups:
                    - provider: openrouter
                      maker: openai
                      model: gpt-5.4
                team:
                  - role: engineer
                """.trimIndent(),
            )
            assertEquals(emptyList(), ConfigParser.validate(file))
        } finally {
            file.delete()
        }
    }
}
