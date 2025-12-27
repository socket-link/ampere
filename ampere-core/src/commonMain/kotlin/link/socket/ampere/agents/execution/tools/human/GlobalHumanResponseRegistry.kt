package link.socket.ampere.agents.execution.tools.human

/**
 * Global singleton instance of HumanResponseRegistry.
 *
 * This provides a central point for tools to register human interaction
 * requests and for CLIs/UIs to provide responses.
 *
 * Usage in tools:
 * ```
 * val requestId = generateUUID()
 * val response = GlobalHumanResponseRegistry.instance.waitForResponse(requestId)
 * ```
 *
 * Usage in CLI:
 * ```
 * GlobalHumanResponseRegistry.instance.provideResponse(requestId, "user input")
 * ```
 */
object GlobalHumanResponseRegistry {
    val instance: HumanResponseRegistry = HumanResponseRegistry()
}
