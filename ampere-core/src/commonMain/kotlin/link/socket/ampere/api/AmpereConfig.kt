package link.socket.ampere.api

/**
 * Configuration for creating an [AmpereInstance].
 *
 * Use the builder DSL via [Ampere.create]:
 * ```
 * val ampere = Ampere.create {
 *     provider("anthropic", "sonnet-4")
 *     workspace("/path/to/project")
 *     database("/path/to/ampere.db")
 * }
 * ```
 */
data class AmpereConfig(
    /** AI provider name (e.g., "anthropic", "openai", "gemini") */
    val providerName: String,
    /** Model name (e.g., "sonnet-4", "gpt-4.1") */
    val modelName: String,
    /** Workspace directory for file operations, null to disable workspace monitoring */
    val workspace: String? = null,
    /** Override default database location */
    val databasePath: String? = null,
) {
    class Builder {
        private var providerName: String = "anthropic"
        private var modelName: String = "sonnet-4"
        private var workspace: String? = null
        private var databasePath: String? = null

        /** Configure the AI provider and model. */
        fun provider(name: String, model: String) {
            providerName = name
            modelName = model
        }

        /** Set the workspace directory for file operations. */
        fun workspace(path: String) {
            workspace = path
        }

        /** Override default database location. */
        fun database(path: String) {
            databasePath = path
        }

        fun build(): AmpereConfig = AmpereConfig(
            providerName = providerName,
            modelName = modelName,
            workspace = workspace,
            databasePath = databasePath,
        )
    }
}
