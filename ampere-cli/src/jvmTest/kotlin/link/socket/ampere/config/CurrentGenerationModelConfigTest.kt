package link.socket.ampere.config

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The CLI keeps two model lists: the validator's allowlist in [ConfigParser]
 * and the key-to-model mapping in [ConfigConverter]. Each current-generation
 * key has to pass both (AMPR-326, AMPR-488).
 */
class CurrentGenerationModelConfigTest {

    private val expectedModelNames = listOf(
        "anthropic" to "fable-5.1" to "claude-fable-5-1",
        "anthropic" to "opus-5.5" to "claude-opus-5-5",
        "anthropic" to "sonnet-5.5" to "claude-sonnet-5-5",
        "anthropic" to "haiku-5.5" to "claude-haiku-5-5",
        "anthropic" to "opus-5" to "claude-opus-5",
        "anthropic" to "opus-4.8" to "claude-opus-4-8",
        "anthropic" to "opus-4.7" to "claude-opus-4-7",
        "anthropic" to "opus-4.6" to "claude-opus-4-6",
        "anthropic" to "sonnet-5" to "claude-sonnet-5",
        "anthropic" to "sonnet-4.6" to "claude-sonnet-4-6",
        "openai" to "gpt-6-sol" to "gpt-6-sol",
        "openai" to "gpt-6-luna" to "gpt-6-luna",
        "openai" to "gpt-5.6-sol" to "gpt-5.6-sol",
        "openai" to "gpt-5.6-terra" to "gpt-5.6-terra",
        "openai" to "gpt-5.6-luna" to "gpt-5.6-luna",
        "openai" to "gpt-5.5" to "gpt-5.5",
        "openai" to "gpt-5.4" to "gpt-5.4",
        "openai" to "gpt-5.4-mini" to "gpt-5.4-mini",
        "gemini" to "pro-3.1-preview" to "gemini-3.1-pro-preview",
        "gemini" to "pro-3" to "gemini-3.1-pro-preview",
        "gemini" to "flash-3.8" to "gemini-3.8-flash",
        "gemini" to "argon-4" to "gemini-4-argon",
        "gemini" to "flash-3.6" to "gemini-3.6-flash",
        "gemini" to "flash-3.5-lite" to "gemini-3.5-flash-lite",
    )

    @Test
    fun `current generation model keys pass validation`() {
        expectedModelNames.forEach { (providerAndKey, _) ->
            val (provider, key) = providerAndKey
            val file = File.createTempFile("ampere-config", ".yaml")
            try {
                file.writeText(
                    """
                    ai:
                      provider: $provider
                      model: $key
                    team:
                      - role: engineer
                    """.trimIndent(),
                )
                assertEquals(emptyList(), ConfigParser.validate(file), "$provider/$key")
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `the shut-down o4-mini key is rejected`() {
        val file = File.createTempFile("ampere-config", ".yaml")
        try {
            file.writeText(
                """
                ai:
                  provider: openai
                  model: o4-mini
                team:
                  - role: engineer
                """.trimIndent(),
            )
            val errors = ConfigParser.validate(file)
            assertTrue(errors.any { "o4-mini" in it }, "errors: $errors")
        } finally {
            file.delete()
        }
    }

    @Test
    fun `current generation model keys resolve to their model ids`() {
        expectedModelNames.forEach { (providerAndKey, modelName) ->
            val (provider, key) = providerAndKey
            val configuration = ConfigConverter.toAIConfiguration(AIProviderConfig(provider = provider, model = key))
            assertEquals(modelName, configuration.model.name, "$provider/$key")
        }
    }
}
