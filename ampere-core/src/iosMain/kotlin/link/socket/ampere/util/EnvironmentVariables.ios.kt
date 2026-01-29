package link.socket.ampere.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * iOS implementation of environment variable reader.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun getEnvironmentVariable(name: String): String? = getenv(name)?.toKString()
