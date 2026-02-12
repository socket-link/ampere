package link.socket.ampere.config

import link.socket.ampere.domain.ai.configuration.AIConfiguration
import link.socket.ampere.domain.ai.configuration.AIConfiguration_Default
import link.socket.ampere.domain.ai.configuration.AIConfiguration_WithBackups
import link.socket.ampere.domain.ai.model.AIModel
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.domain.ai.provider.AIProvider
import link.socket.ampere.domain.ai.provider.AIProvider_Anthropic
import link.socket.ampere.domain.ai.provider.AIProvider_Google
import link.socket.ampere.domain.ai.provider.AIProvider_OpenAI
import link.socket.ampere.dsl.agent.AgentRole
import link.socket.ampere.dsl.agent.Architect
import link.socket.ampere.dsl.agent.Engineer
import link.socket.ampere.dsl.agent.Personality
import link.socket.ampere.dsl.agent.ProductManager
import link.socket.ampere.dsl.agent.QATester
import link.socket.ampere.dsl.agent.SecurityReviewer
import link.socket.ampere.dsl.agent.TechnicalWriter
import link.socket.ampere.dsl.config.AnthropicConfig
import link.socket.ampere.dsl.config.GeminiConfig
import link.socket.ampere.dsl.config.OpenAIConfig
import link.socket.ampere.dsl.config.ProviderConfig
import link.socket.ampere.dsl.team.AgentTeam
import link.socket.ampere.dsl.team.TeamMember

/**
 * Converts parsed YAML configuration into DSL objects.
 *
 * This bridges the gap between the serializable config format (for file storage)
 * and the type-safe DSL objects (for runtime use).
 *
 * Example:
 * ```kotlin
 * val config = ConfigParser.parse(File("ampere.yaml"))
 * val team = ConfigConverter.toAgentTeam(config)
 * team.pursue(config.goal ?: "Default goal")
 * ```
 */
object ConfigConverter {

    /**
     * Convert a full config to an AgentTeam using the DSL.
     */
    fun toAgentTeam(config: AmpereConfig): AgentTeam {
        return AgentTeam.create {
            // Set the AI provider configuration
            config(toProviderConfig(config.ai))

            // Add each team member
            config.team.forEach { agentConfig ->
                val role = toAgentRole(agentConfig.role)
                val personality = agentConfig.personality?.let { toPersonality(it) }

                agent(role) {
                    personality?.let {
                        personality {
                            directness = it.directness
                            creativity = it.creativity
                            thoroughness = it.thoroughness
                            formality = it.formality
                            riskTolerance = it.riskTolerance
                        }
                    }
                }
            }
        }
    }

    /**
     * Convert AI provider config to a DSL ProviderConfig.
     */
    fun toProviderConfig(config: AIProviderConfig): ProviderConfig {
        val baseConfig = when (config.provider.lowercase()) {
            "anthropic" -> AnthropicConfig(model = toClaudeModel(config.model))
            "openai" -> OpenAIConfig(model = toOpenAIModel(config.model))
            "gemini" -> GeminiConfig(model = toGeminiModel(config.model))
            else -> throw IllegalArgumentException("Unknown provider: ${config.provider}")
        }

        // Add backups if present
        return config.backups.fold(baseConfig) { acc, backup ->
            when (acc) {
                is AnthropicConfig -> acc.withBackup(toProviderConfig(backup))
                is OpenAIConfig -> acc.withBackup(toProviderConfig(backup))
                is GeminiConfig -> acc.withBackup(toProviderConfig(backup))
            }
        }
    }

    /**
     * Convert AI provider config directly to an AIConfiguration.
     * Useful when you need the internal representation directly.
     */
    fun toAIConfiguration(config: AIProviderConfig): AIConfiguration {
        val primary = AIConfiguration_Default(
            provider = toProvider(config.provider),
            model = toModel(config.provider, config.model),
        )

        return if (config.backups.isEmpty()) {
            primary
        } else {
            AIConfiguration_WithBackups(
                configurations = listOf(primary) + config.backups.map { toAIConfiguration(it) }
            )
        }
    }

    /**
     * Convert a role string to an AgentRole.
     */
    fun toAgentRole(role: String): AgentRole = when (role.lowercase()) {
        "product-manager" -> ProductManager
        "engineer" -> Engineer
        "qa-tester" -> QATester
        "architect" -> Architect
        "security-reviewer" -> SecurityReviewer
        "technical-writer" -> TechnicalWriter
        else -> throw IllegalArgumentException("Unknown role: $role. Supported: product-manager, engineer, qa-tester, architect, security-reviewer, technical-writer")
    }

    /**
     * Convert a PersonalityConfig to a Personality.
     */
    fun toPersonality(config: PersonalityConfig): Personality = Personality(
        directness = config.directness,
        creativity = config.creativity,
        thoroughness = config.thoroughness,
        formality = config.formality,
        riskTolerance = config.riskTolerance,
    )

    /**
     * Get team members from config (for inspection without creating full team).
     */
    fun toTeamMembers(config: AmpereConfig): List<TeamMember> {
        return config.team.map { agentConfig ->
            TeamMember(
                role = toAgentRole(agentConfig.role),
                personality = agentConfig.personality?.let { toPersonality(it) },
            )
        }
    }

    // Provider mapping
    private fun toProvider(provider: String): AIProvider<*, *> = when (provider.lowercase()) {
        "anthropic" -> AIProvider_Anthropic
        "openai" -> AIProvider_OpenAI
        "gemini" -> AIProvider_Google
        else -> throw IllegalArgumentException("Unknown provider: $provider")
    }

    // Model mapping
    private fun toModel(provider: String, model: String): AIModel = when (provider.lowercase()) {
        "anthropic" -> toClaudeModel(model)
        "openai" -> toOpenAIModel(model)
        "gemini" -> toGeminiModel(model)
        else -> throw IllegalArgumentException("Unknown provider: $provider")
    }

    private fun toClaudeModel(model: String): AIModel_Claude = when (model.lowercase()) {
        "opus-4.5" -> AIModel_Claude.Opus_4_5
        "opus-4.1" -> AIModel_Claude.Opus_4_1
        "opus-4" -> AIModel_Claude.Opus_4
        "sonnet-4.5" -> AIModel_Claude.Sonnet_4_5
        "sonnet-4" -> AIModel_Claude.Sonnet_4
        "sonnet-3.7" -> AIModel_Claude.Sonnet_3_7
        "haiku-4.5" -> AIModel_Claude.Haiku_4_5
        "haiku-3.5" -> AIModel_Claude.Haiku_3_5
        "haiku-3" -> AIModel_Claude.Haiku_3
        else -> throw IllegalArgumentException("Unknown Claude model: $model. Supported: opus-4.5, opus-4.1, opus-4, sonnet-4.5, sonnet-4, sonnet-3.7, haiku-4.5, haiku-3.5, haiku-3")
    }

    private fun toOpenAIModel(model: String): AIModel_OpenAI = when (model.lowercase()) {
        "gpt-5.1" -> AIModel_OpenAI.GPT_5_1
        "gpt-5.1-instant" -> AIModel_OpenAI.GPT_5_1_Chat_Latest
        "gpt-5.1-codex-max" -> AIModel_OpenAI.GPT_5_1_Codex_Max
        "gpt-5" -> AIModel_OpenAI.GPT_5
        "gpt-5-mini" -> AIModel_OpenAI.GPT_5_mini
        "gpt-5-nano" -> AIModel_OpenAI.GPT_5_nano
        "gpt-4.1" -> AIModel_OpenAI.GPT_4_1
        "gpt-4.1-mini" -> AIModel_OpenAI.GPT_4_1_mini
        "gpt-4o" -> AIModel_OpenAI.GPT_4o
        "gpt-4o-mini" -> AIModel_OpenAI.GPT_4o_mini
        "o4-mini" -> AIModel_OpenAI.o4_mini
        "o3" -> AIModel_OpenAI.o3
        "o3-mini" -> AIModel_OpenAI.o3_mini
        else -> throw IllegalArgumentException("Unknown OpenAI model: $model. Supported: gpt-5.1, gpt-5, gpt-5-mini, gpt-5-nano, gpt-4.1, gpt-4.1-mini, gpt-4o, gpt-4o-mini, o4-mini, o3, o3-mini")
    }

    private fun toGeminiModel(model: String): AIModel_Gemini = when (model.lowercase()) {
        "pro-3" -> AIModel_Gemini.Pro_3_0
        "pro-2.5" -> AIModel_Gemini.Pro_2_5
        "flash-2.5" -> AIModel_Gemini.Flash_2_5
        "flash-2.5-lite" -> AIModel_Gemini.Flash_Lite_2_5
        "flash-2" -> AIModel_Gemini.Flash_2_0
        "flash-2-lite" -> AIModel_Gemini.Flash_Lite_2_0
        else -> throw IllegalArgumentException("Unknown Gemini model: $model. Supported: pro-3, pro-2.5, flash-2.5, flash-2.5-lite, flash-2, flash-2-lite")
    }
}
