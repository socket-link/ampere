package link.socket.ampere.util

import co.touchlab.kermit.Severity
import com.aallam.openai.api.logging.LogLevel

/**
 * Configuration for logging across the application.
 *
 * Controls Kermit logger (application logs), OpenAI client logging (HTTP requests/responses),
 * and EventBus logging (agent event stream).
 */
data class LoggingConfiguration(
    /** Minimum severity for Kermit logger */
    val kermitMinSeverity: Severity = Severity.Info,

    /** Log level for OpenAI client */
    val openAiLogLevel: LogLevel = LogLevel.None,

    /** Show routine events (KnowledgeRecalled, KnowledgeStored, etc.) in EventBus logs */
    val showRoutineEvents: Boolean = false,

    /** Show subscription/unsubscription events (noisy on startup) */
    val showEventSubscriptions: Boolean = false,

    /** Use silent event logger (no EventBus output at all) */
    val silentEventBus: Boolean = false,
) {
    companion object {
        /** Development mode: INFO for app, Headers for OpenAI, filtered EventBus */
        val Development = LoggingConfiguration(
            kermitMinSeverity = Severity.Info,
            openAiLogLevel = LogLevel.Headers,
            showRoutineEvents = false,
            showEventSubscriptions = false,
            silentEventBus = false,
        )

        /** Production mode: WARN for app, None for OpenAI, silent EventBus */
        val Production = LoggingConfiguration(
            kermitMinSeverity = Severity.Warn,
            openAiLogLevel = LogLevel.None,
            showRoutineEvents = false,
            showEventSubscriptions = false,
            silentEventBus = true,
        )

        /** Debug mode: DEBUG for app, All for OpenAI, verbose EventBus */
        val Debug = LoggingConfiguration(
            kermitMinSeverity = Severity.Debug,
            openAiLogLevel = LogLevel.All,
            showRoutineEvents = true,
            showEventSubscriptions = true,
            silentEventBus = false,
        )

        /** Silent mode: ERROR only, silent EventBus */
        val Silent = LoggingConfiguration(
            kermitMinSeverity = Severity.Error,
            openAiLogLevel = LogLevel.None,
            showRoutineEvents = false,
            showEventSubscriptions = false,
            silentEventBus = true,
        )

        /** Dashboard mode: Silent EventBus for clean UI */
        val Dashboard = LoggingConfiguration(
            kermitMinSeverity = Severity.Warn,
            openAiLogLevel = LogLevel.None,
            showRoutineEvents = false,
            showEventSubscriptions = false,
            silentEventBus = true,
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
                "DASHBOARD" -> Dashboard
                null -> Production // Default when env var not set
                else -> Production // Default for unrecognized values
            }
        }
    }

    /**
     * Create an EventLogger instance based on this configuration.
     */
    fun createEventLogger(): link.socket.ampere.agents.events.utils.EventLogger {
        return when {
            silentEventBus -> link.socket.ampere.agents.events.utils.SilentEventLogger()
            else -> link.socket.ampere.agents.events.utils.SignificanceAwareEventLogger(
                showRoutineEvents = showRoutineEvents,
                showSubscriptions = showEventSubscriptions
            )
        }
    }
}

/**
 * Platform-agnostic environment variable reader.
 * Implement in platform-specific source sets.
 */
expect fun getEnvironmentVariable(name: String): String?
