package link.socket.ampere.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import java.io.File

/**
 * Parser for Ampere YAML configuration files.
 *
 * Example usage:
 * ```kotlin
 * val config = ConfigParser.parse(File("ampere.yaml"))
 * val team = config.toTeamConfig()
 * ```
 */
object ConfigParser {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,  // Allow unknown keys for forward compatibility
        )
    )

    /**
     * Parse an Ampere configuration from a YAML file.
     *
     * @param file The YAML configuration file
     * @return Parsed configuration
     * @throws IllegalArgumentException if file doesn't exist or is invalid
     */
    fun parse(file: File): AmpereConfig {
        require(file.exists()) { "Configuration file not found: ${file.absolutePath}" }
        require(file.canRead()) { "Cannot read configuration file: ${file.absolutePath}" }

        val content = file.readText()
        return parse(content)
    }

    /**
     * Parse an Ampere configuration from a YAML string.
     *
     * @param content YAML content
     * @return Parsed configuration
     */
    fun parse(content: String): AmpereConfig {
        return yaml.decodeFromString(AmpereConfig.serializer(), content)
    }

    /**
     * Validate a configuration without fully parsing it.
     * Returns a list of validation errors, or empty list if valid.
     */
    fun validate(file: File): List<String> {
        val errors = mutableListOf<String>()

        if (!file.exists()) {
            errors.add("Configuration file not found: ${file.absolutePath}")
            return errors
        }

        try {
            val config = parse(file)
            errors.addAll(validateConfig(config))
        } catch (e: Exception) {
            errors.add("Failed to parse configuration: ${e.message}")
        }

        return errors
    }

    private fun validateConfig(config: AmpereConfig): List<String> {
        val errors = mutableListOf<String>()

        // Validate AI provider
        if (config.ai.provider !in SUPPORTED_PROVIDERS) {
            errors.add("Unknown AI provider '${config.ai.provider}'. Supported: ${SUPPORTED_PROVIDERS.joinToString()}")
        }

        // Validate model for provider
        val models = MODELS_BY_PROVIDER[config.ai.provider]
        if (models != null && config.ai.model !in models) {
            errors.add("Unknown model '${config.ai.model}' for provider '${config.ai.provider}'. Supported: ${models.joinToString()}")
        }

        // Validate team roles
        config.team.forEach { agent ->
            if (agent.role !in SUPPORTED_ROLES) {
                errors.add("Unknown role '${agent.role}'. Supported: ${SUPPORTED_ROLES.joinToString()}")
            }

            // Validate personality values
            agent.personality?.let { p ->
                if (p.directness !in 0.0..1.0) errors.add("directness must be between 0.0 and 1.0")
                if (p.creativity !in 0.0..1.0) errors.add("creativity must be between 0.0 and 1.0")
                if (p.thoroughness !in 0.0..1.0) errors.add("thoroughness must be between 0.0 and 1.0")
                if (p.formality !in 0.0..1.0) errors.add("formality must be between 0.0 and 1.0")
                if (p.riskTolerance !in 0.0..1.0) errors.add("risk-tolerance must be between 0.0 and 1.0")
            }
        }

        // Validate backup providers recursively
        config.ai.backups.forEachIndexed { index, backup ->
            if (backup.provider !in SUPPORTED_PROVIDERS) {
                errors.add("Unknown provider '${backup.provider}' in backup[$index]")
            }
        }

        return errors
    }

    private val SUPPORTED_PROVIDERS = setOf("anthropic", "openai", "gemini")

    private val SUPPORTED_ROLES = setOf(
        "product-manager",
        "engineer",
        "qa-tester",
        "architect",
        "security-reviewer",
        "technical-writer",
    )

    private val MODELS_BY_PROVIDER = mapOf(
        "anthropic" to setOf(
            "opus-4.5", "opus-4.1", "opus-4",
            "sonnet-4.5", "sonnet-4", "sonnet-3.7",
            "haiku-4.5", "haiku-3.5", "haiku-3",
        ),
        "openai" to setOf(
            "gpt-5.1", "gpt-5.1-instant", "gpt-5.1-codex-max",
            "gpt-5", "gpt-5-mini", "gpt-5-nano",
            "gpt-4.1", "gpt-4.1-mini",
            "gpt-4o", "gpt-4o-mini",
            "o4-mini", "o3", "o3-mini",
        ),
        "gemini" to setOf(
            "pro-3", "pro-2.5",
            "flash-2.5", "flash-2.5-lite",
            "flash-2", "flash-2-lite",
        ),
    )
}
