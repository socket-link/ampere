package link.socket.ampere.api

/**
 * Primary entry point for the AMPERE SDK.
 *
 * Usage:
 * ```
 * val ampere = Ampere.create {
 *     provider("anthropic", "sonnet-4")
 *     workspace("/path/to/project")
 * }
 *
 * ampere.agents.pursue("Build authentication system")
 * ampere.events.observe().collect { event -> ... }
 *
 * ampere.close()
 * ```
 */
object Ampere {

    /**
     * Create a new AMPERE instance with the given configuration.
     *
     * The instance manages its own coroutine scope and database connection.
     * Call [AmpereInstance.close] when done to release resources.
     */
    fun create(configure: AmpereConfig.Builder.() -> Unit = {}): AmpereInstance {
        val config = AmpereConfig.Builder().apply(configure).build()
        return createInstance(config)
    }

    /**
     * Create a new AMPERE instance from an existing config.
     */
    fun create(config: AmpereConfig): AmpereInstance {
        return createInstance(config)
    }
}

/**
 * Platform-specific factory for creating [AmpereInstance].
 *
 * Each platform provides its own implementation that wires up
 * the appropriate database driver and dependencies.
 */
internal expect fun createInstance(config: AmpereConfig): AmpereInstance
