package link.socket.ampere.util

/**
 * JVM implementation of environment variable reader.
 */
actual fun getEnvironmentVariable(name: String): String? = System.getenv(name)
