package link.socket.ampere.api

/**
 * Primary entry point for the AMPERE SDK.
 *
 * ```
 * // Programmatic configuration
 * val ampere = Ampere.create {
 *     provider(AnthropicConfig(model = Claude.Sonnet4))
 *     workspace("/path/to/project")
 *     onEscalation { event -> println("${event.agent} needs help: ${event.reason}") }
 * }
 *
 * ampere.agents.pursue("Build authentication system")
 * ampere.events.observe().collect { event -> ... }
 *
 * ampere.close()
 *
 * // File-based configuration (JVM only)
 * val ampere = Ampere.create {
 *     fromYaml("/path/to/ampere.yaml")
 * }
 * ```
 */
object Ampere {

    /**
     * Create a new AMPERE instance with the given configuration.
     *
     * The instance manages its own coroutine scope and database connection.
     * Call [AmpereInstance.close] when done to release resources.
     *
     * @throws IllegalArgumentException if required configuration (provider) is missing
     */
    fun create(configure: AmpereConfig.Builder.() -> Unit): AmpereInstance {
        val config = AmpereConfig.Builder().apply(configure).build()
        return createInstance(config)
    }

    /**
     * Create a new AMPERE instance from an existing config.
     */
    fun create(config: AmpereConfig): AmpereInstance {
        return createInstance(config)
    }

    /**
     * Create a stub AMPERE instance for testing and parallel development.
     *
     * No database, no network, no real infrastructure — just correct types
     * and sensible defaults. Use this when coding against the API surface
     * before real implementations are available.
     *
     * ```
     * val ampere = Ampere.createStub()
     * val ticket = ampere.tickets.create("Test").getOrThrow()
     * ampere.close()
     * ```
     */
    fun createStub(): AmpereInstance {
        return link.socket.ampere.api.service.stub.StubAmpereInstance()
    }
}

/**
 * Platform-specific factory for creating [AmpereInstance].
 *
 * Each platform provides its own implementation that wires up
 * the appropriate database driver and dependencies.
 */
internal expect fun createInstance(config: AmpereConfig): AmpereInstance
