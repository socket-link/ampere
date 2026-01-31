package link.socket.ampere.agents.events.utils

import link.socket.ampere.util.randomUUID

actual fun generateUUID(vararg subIDs: String): String =
    randomUUID() + subIDs.joinToString(separator = "/")
