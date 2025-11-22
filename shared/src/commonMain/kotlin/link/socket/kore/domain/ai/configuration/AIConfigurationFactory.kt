package link.socket.kore.domain.ai.configuration

import link.socket.kore.domain.ai.model.AIModel_Claude
import link.socket.kore.domain.ai.model.AIModel_Gemini
import link.socket.kore.domain.ai.model.AIModel_OpenAI
import link.socket.kore.domain.ai.provider.AIProvider_Anthropic
import link.socket.kore.domain.ai.provider.AIProvider_Google
import link.socket.kore.domain.ai.provider.AIProvider_OpenAI

class AIConfigurationFactory {

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
