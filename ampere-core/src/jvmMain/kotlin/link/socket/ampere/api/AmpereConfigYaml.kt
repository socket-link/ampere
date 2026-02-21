package link.socket.ampere.api

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import link.socket.ampere.domain.ai.model.AIModel_Claude
import link.socket.ampere.domain.ai.model.AIModel_Gemini
import link.socket.ampere.domain.ai.model.AIModel_OpenAI
import link.socket.ampere.dsl.config.AnthropicConfig
import link.socket.ampere.dsl.config.GeminiConfig
import link.socket.ampere.dsl.config.OpenAIConfig
import link.socket.ampere.dsl.config.ProviderConfig

/**
 * Load configuration from a YAML file.
 *
 * Supports the standard `ampere.yaml` format:
 * ```yaml
 * ai:
 *   provider: anthropic
 *   model: sonnet-4
 *   backups:
 *     - provider: openai
 *       model: gpt-4.1
 * ```
 *
 * @param path Absolute or relative path to the YAML configuration file
 * @throws IllegalArgumentException if the file doesn't exist or can't be parsed
 */
fun AmpereConfig.Builder.fromYaml(path: String) {
    val file = File(path)
    require(file.exists()) { "Configuration file not found: ${file.absolutePath}" }
    require(file.canRead()) { "Cannot read configuration file: ${file.absolutePath}" }

    val content = file.readText()
    val yamlConfig = yamlParser.decodeFromString(YamlAmpereConfig.serializer(), content)

    provider(yamlConfig.ai.toProviderConfig())
}

// ==================== Internal YAML model ====================

private val yamlParser = Yaml(
    configuration = YamlConfiguration(strictMode = false),
)

@Serializable
internal data class YamlAmpereConfig(
    val ai: YamlAIProviderConfig,
)

@Serializable
internal data class YamlAIProviderConfig(
    val provider: String,
    val model: String,
    val backups: List<YamlAIProviderConfig> = emptyList(),
) {
    fun toProviderConfig(): ProviderConfig {
        val baseConfig = when (provider.lowercase()) {
            "anthropic" -> AnthropicConfig(model = toClaudeModel(model))
            "openai" -> OpenAIConfig(model = toOpenAIModel(model))
            "gemini" -> GeminiConfig(model = toGeminiModel(model))
            else -> throw IllegalArgumentException(
                "Unknown provider: $provider. Supported: anthropic, openai, gemini"
            )
        }

        return backups.fold(baseConfig) { acc, backup ->
            when (acc) {
                is AnthropicConfig -> acc.withBackup(backup.toProviderConfig())
                is OpenAIConfig -> acc.withBackup(backup.toProviderConfig())
                is GeminiConfig -> acc.withBackup(backup.toProviderConfig())
            }
        }
    }
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
    else -> throw IllegalArgumentException("Unknown Claude model: $model")
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
    else -> throw IllegalArgumentException("Unknown OpenAI model: $model")
}

private fun toGeminiModel(model: String): AIModel_Gemini = when (model.lowercase()) {
    "pro-3" -> AIModel_Gemini.Pro_3_0
    "pro-2.5" -> AIModel_Gemini.Pro_2_5
    "flash-2.5" -> AIModel_Gemini.Flash_2_5
    "flash-2.5-lite" -> AIModel_Gemini.Flash_Lite_2_5
    "flash-2" -> AIModel_Gemini.Flash_2_0
    "flash-2-lite" -> AIModel_Gemini.Flash_Lite_2_0
    else -> throw IllegalArgumentException("Unknown Gemini model: $model")
}
