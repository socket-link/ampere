package link.socket.ampere.agents.events.utils

import platform.Foundation.NSUUID

actual fun generateUUID(vararg subIDs: String): String =
    NSUUID().UUIDString() + subIDs.joinToString(separator = "/")
