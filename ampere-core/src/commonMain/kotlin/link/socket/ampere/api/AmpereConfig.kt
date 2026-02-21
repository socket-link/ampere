package link.socket.ampere.api

import link.socket.ampere.dsl.config.ProviderConfig
import link.socket.ampere.dsl.events.Escalated

/**
 * Configuration for creating an [AmpereInstance].
 *
 * Use the builder DSL via [Ampere.create]:
 * ```
 * // Mode 1: Programmatic configuration
 * val ampere = Ampere.create {
 *     provider(AnthropicConfig(apiKey = "...", model = Claude.Sonnet4))
 *     workspace("/path/to/project")
 *     onEscalation { event -> notifySlack(event) }
 * }
 *
 * // Mode 2: File-based configuration (JVM only)
 * val ampere = Ampere.create {
 *     fromYaml("/path/to/ampere.yaml")
 * }
 * ```
 *
 * @param provider Type-safe provider configuration (Anthropic, OpenAI, or Gemini)
 * @param workspace Workspace directory for file operations
 * @param databasePath Override default database location
 * @param onEscalation Callback invoked when an agent escalates to human
 */
data class AmpereConfig(
    val provider: ProviderConfig,
    val workspace: String? = null,
    val databasePath: String? = null,
    val onEscalation: ((Escalated) -> Unit)? = null,
) {
    class Builder {
        private var providerConfig: ProviderConfig? = null
        private var workspace: String? = null
        private var databasePath: String? = null
        private var escalationHandler: ((Escalated) -> Unit)? = null

        /**
         * Set the AI provider configuration.
         *
         * ```
         * provider(AnthropicConfig(model = Claude.Sonnet4))
         * provider(OpenAIConfig(apiKey = "...", model = GPT.GPT_4_1))
         * provider(GeminiConfig(model = Gemini.Flash_2_5))
         * ```
         */
        fun provider(config: ProviderConfig) {
            providerConfig = config
        }

        /** Set the workspace directory for file operations. */
        fun workspace(path: String) {
            workspace = path
        }

        /** Override default database location. */
        fun database(path: String) {
            databasePath = path
        }

        /**
         * Register a callback for agent escalation events.
         *
         * ```
         * onEscalation { event ->
         *     println("${event.agent} needs help: ${event.reason}")
         * }
         * ```
         */
        fun onEscalation(handler: (Escalated) -> Unit) {
            escalationHandler = handler
        }

        fun build(): AmpereConfig {
            val provider = requireNotNull(providerConfig) {
                "Provider is required. Use provider(AnthropicConfig()) or similar."
            }
            return AmpereConfig(
                provider = provider,
                workspace = workspace,
                databasePath = databasePath,
                onEscalation = escalationHandler,
            )
        }
    }
}
