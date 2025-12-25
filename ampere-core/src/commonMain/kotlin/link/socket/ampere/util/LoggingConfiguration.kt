package link.socket.ampere.util

import co.touchlab.kermit.Severity
import com.aallam.openai.api.logging.LogLevel

/**
 * Configuration for logging across the application.
 *
 * Controls both Kermit logger (application logs) and OpenAI client logging (HTTP requests/responses).
 */
data class LoggingConfiguration(
    /** Minimum severity for Kermit logger */
    val kermitMinSeverity: Severity = Severity.Info,

    /** Log level for OpenAI client */
    val openAiLogLevel: LogLevel = LogLevel.None,
) {
    companion object {
        /** Development mode: INFO for app, Headers for OpenAI */
        val Development = LoggingConfiguration(
            kermitMinSeverity = Severity.Info,
            openAiLogLevel = LogLevel.Headers,
        )

        /** Production mode: WARN for app, None for OpenAI */
        val Production = LoggingConfiguration(
            kermitMinSeverity = Severity.Warn,
            openAiLogLevel = LogLevel.None,
        )

        /** Debug mode: DEBUG for app, All for OpenAI */
        val Debug = LoggingConfiguration(
            kermitMinSeverity = Severity.Debug,
            openAiLogLevel = LogLevel.All,
        )

        /** Silent mode: ERROR only */
        val Silent = LoggingConfiguration(
            kermitMinSeverity = Severity.Error,
            openAiLogLevel = LogLevel.None,
        )

        /**
         * Parse logging configuration from environment variable.
         *
         * @param envVar Environment variable name to read (default: AMPERE_LOG_LEVEL)
         * @return Parsed configuration or Production default
         */
        fun fromEnvironment(envVar: String = "AMPERE_LOG_LEVEL"): LoggingConfiguration {
            val value = getEnvironmentVariable(envVar)
            return when (value?.uppercase()) {
                "DEBUG" -> Debug
                "INFO" -> Development
                "WARN", "WARNING" -> Production
                "ERROR" -> Silent
                null -> Production // Default when env var not set
                else -> Production // Default for unrecognized values
            }
        }
    }
}

/**
 * Platform-agnostic environment variable reader.
 * Implement in platform-specific source sets.
 */
expect fun getEnvironmentVariable(name: String): String?
