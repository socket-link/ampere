package link.socket.ampere.util

import platform.Foundation.NSUUID

actual fun randomUUID(): String = NSUUID().UUIDString()
