package link.socket.ampere.agents.events.utils

/**
 * Expect declaration for generating globally-unique event IDs per platform.
 */
expect fun generateUUID(vararg subIDs: String): String
