package link.socket.ampere.util

/**
 * Expects a platform-specific implementation to generate a random UUID string.
 *
 * This function is expected to be implemented for each platform (e.g., JVM, JS, Native) to provide
 * a unique identifier in the form of a string. The actual implementation will vary depending on the
 * platform's capabilities and libraries.
 *
 * @return String A randomly generated UUID.
 */
expect fun randomUUID(): String
