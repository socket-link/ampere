package link.socket.ampere.agents.events.utils

import java.util.UUID

actual fun generateUUID(vararg subIDs: String): String =
    UUID.randomUUID().toString() + subIDs.joinToString(separator = "/")
