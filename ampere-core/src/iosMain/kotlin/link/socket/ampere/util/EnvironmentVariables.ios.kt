package link.socket.ampere.util

import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * iOS implementation of environment variable reader.
 */
actual fun getEnvironmentVariable(name: String): String? = getenv(name)?.toKString()
