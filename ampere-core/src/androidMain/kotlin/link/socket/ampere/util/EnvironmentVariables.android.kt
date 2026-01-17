package link.socket.ampere.util

/**
 * Android implementation of environment variable reader.
 */
actual fun getEnvironmentVariable(name: String): String? = System.getenv(name)
