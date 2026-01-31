package link.socket.ampere.util

/**
 * WasmJS implementation of environment variable reader.
 * In browser environments, environment variables are not available.
 */
actual fun getEnvironmentVariable(name: String): String? {
    // WasmJS browser environments do not have access to environment variables
    return null
}
