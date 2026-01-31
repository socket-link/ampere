package link.socket.ampere.util

/**
 * JS implementation of environment variable reader.
 * In browser environments, environment variables are not available.
 * In Node.js, process.env can be accessed.
 */
actual fun getEnvironmentVariable(name: String): String? {
    return try {
        // Try Node.js process.env
        val processEnv = js("typeof process !== 'undefined' && process.env")
        if (processEnv != null && processEnv != false) {
            val value = js("process.env[name]")
            if (value != null && value != undefined) {
                value as String
            } else {
                null
            }
        } else {
            null
        }
    } catch (e: dynamic) {
        null
    }
}
