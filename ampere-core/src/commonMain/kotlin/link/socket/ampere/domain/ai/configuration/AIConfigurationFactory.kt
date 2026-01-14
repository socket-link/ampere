package link.socket.ampere.domain.ai.configuration

import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI

/**
 * Factory object for creating AI configurations.
 *
 * This object provides convenience methods for creating configurations with
 * backup providers. For user-facing API, prefer using the DSL classes:
 * - [link.socket.ampere.dsl.config.AnthropicConfig]
 * - [link.socket.ampere.dsl.config.OpenAIConfig]
 * - [link.socket.ampere.dsl.config.GeminiConfig]
 *
 * Example using DSL (preferred):
 * ```kotlin
 * val config = AnthropicConfig(model = Claude.Sonnet4)
 *     .withBackup(OpenAIConfig(model = GPT.GPT4_1))
 * ```
 *
 * Example using factory (internal use):
 * ```kotlin
 * val config = AIConfigurationFactory.aiConfiguration(
 *     AIModel_Claude.Sonnet_4,
 *     AIConfigurationFactory.aiConfiguration(AIModel_OpenAI.GPT_4_1),
 * )
 * ```
 */
object AIConfigurationFactory {

    /**
     * Creates a default configuration with multiple provider fallbacks.
     * Order: Gemini Flash -> Claude Sonnet -> GPT-4.1
     */
    fun getDefaultConfiguration(): AIConfiguration =
        AIConfiguration_WithBackups(
            configurations = listOf(
                AIConfiguration_Default(
                    provider = AIProvider_Google,
                    model = AIModel_Gemini.Flash_2_5,
                ),
                AIConfiguration_Default(
                    provider = AIProvider_Anthropic,
                    model = AIModel_Claude.Sonnet_4,
                ),
                AIConfiguration_Default(
                    provider = AIProvider_OpenAI,
                    model = AIModel_OpenAI.GPT_4_1,
                ),
            ),
        )

    /**
     * Creates a configuration for a Claude model with optional backups.
     */
    fun aiConfiguration(
        model: AIModel_Claude,
        vararg backups: AIConfiguration,
    ): AIConfiguration_WithBackups =
        AIConfiguration_WithBackups(
            configurations = AIConfiguration_Default(
                provider = AIProvider_Anthropic,
                model = model,
            ).let(::listOf) + backups.toList(),
        )

    /**
     * Creates a configuration for a Gemini model with optional backups.
     */
    fun aiConfiguration(
        model: AIModel_Gemini,
        vararg backups: AIConfiguration,
    ): AIConfiguration_WithBackups =
        AIConfiguration_WithBackups(
            configurations = AIConfiguration_Default(
                provider = AIProvider_Google,
                model = model,
            ).let(::listOf) + backups.toList(),
        )

    /**
     * Creates a configuration for an OpenAI model with optional backups.
     */
    fun aiConfiguration(
        model: AIModel_OpenAI,
        vararg backups: AIConfiguration,
    ): AIConfiguration_WithBackups =
        AIConfiguration_WithBackups(
            configurations = AIConfiguration_Default(
                provider = AIProvider_OpenAI,
                model = model,
            ).let(::listOf) + backups.toList(),
        )
}
