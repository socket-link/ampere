package link.socket.ampere.dsl.config

import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.configuration.AIConfiguration_WithBackups
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

/**
 * Base interface for provider-specific configurations in the DSL.
 */
sealed interface ProviderConfig {
    /**
     * Convert this DSL config to an internal AIConfiguration.
     */
    fun toAIConfiguration(): AIConfiguration
}

/**
 * Configuration for Anthropic/Claude models.
 *
 * Example:
 * ```kotlin
 * val config = AnthropicConfig(
 *     apiKey = "your-api-key",
 *     model = Claude.Sonnet4
 * )
 * ```
 *
 * @param apiKey Optional API key (falls back to environment variable if not provided)
 * @param model The Claude model to use (defaults to Sonnet 4)
 */
data class AnthropicConfig(
    val apiKey: String? = null,
    val model: AIModel_Claude = AIModel_Claude.Sonnet_4,
    private val backups: List<AIConfiguration> = emptyList(),
) : ProviderConfig {

    override fun toAIConfiguration(): AIConfiguration {
        val primary = AIConfiguration_Default(
            provider = AIProvider_Anthropic,
            model = model,
        )

        return if (backups.isEmpty()) {
            primary
        } else {
            AIConfiguration_WithBackups(listOf(primary) + backups)
        }
    }

    /**
     * Add a backup configuration that will be used if the primary fails.
     */
    fun withBackup(config: ProviderConfig): AnthropicConfig = copy(
        backups = backups + config.toAIConfiguration()
    )
}

/**
 * Configuration for OpenAI GPT models.
 *
 * Example:
 * ```kotlin
 * val config = OpenAIConfig(
 *     apiKey = "your-api-key",
 *     model = GPT.GPT5
 * )
 * ```
 *
 * @param apiKey Optional API key (falls back to environment variable if not provided)
 * @param model The OpenAI model to use (defaults to GPT-4.1)
 */
data class OpenAIConfig(
    val apiKey: String? = null,
    val model: AIModel_OpenAI = AIModel_OpenAI.GPT_4_1,
    private val backups: List<AIConfiguration> = emptyList(),
) : ProviderConfig {

    override fun toAIConfiguration(): AIConfiguration {
        val primary = AIConfiguration_Default(
            provider = AIProvider_OpenAI,
            model = model,
        )

        return if (backups.isEmpty()) {
            primary
        } else {
            AIConfiguration_WithBackups(listOf(primary) + backups)
        }
    }

    /**
     * Add a backup configuration that will be used if the primary fails.
     */
    fun withBackup(config: ProviderConfig): OpenAIConfig = copy(
        backups = backups + config.toAIConfiguration()
    )
}

/**
 * Configuration for Google Gemini models.
 *
 * Example:
 * ```kotlin
 * val config = GeminiConfig(
 *     apiKey = "your-api-key",
 *     model = Gemini.Pro2_5
 * )
 * ```
 *
 * @param apiKey Optional API key (falls back to environment variable if not provided)
 * @param model The Gemini model to use (defaults to Flash 2.5)
 */
data class GeminiConfig(
    val apiKey: String? = null,
    val model: AIModel_Gemini = AIModel_Gemini.Flash_2_5,
    private val backups: List<AIConfiguration> = emptyList(),
) : ProviderConfig {

    override fun toAIConfiguration(): AIConfiguration {
        val primary = AIConfiguration_Default(
            provider = AIProvider_Google,
            model = model,
        )

        return if (backups.isEmpty()) {
            primary
        } else {
            AIConfiguration_WithBackups(listOf(primary) + backups)
        }
    }

    /**
     * Add a backup configuration that will be used if the primary fails.
     */
    fun withBackup(config: ProviderConfig): GeminiConfig = copy(
        backups = backups + config.toAIConfiguration()
    )
}
