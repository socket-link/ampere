package link.socket.ampere.util

import co.touchlab.kermit.Logger
import link.socket.ampere.domain.ai.provider.AIProvider

/**
 * Configure global logging based on the provided configuration.
 *
 * This function:
 * 1. Sets up Kermit logger with severity filtering
 * 2. Configures OpenAI client logging globally
 *
 * @param config The logging configuration to apply
 */
fun configureLogging(config: LoggingConfiguration) {
    // Configure Kermit logger with severity filtering
    Logger.setLogWriters(FilteredLogWriter(config.kermitMinSeverity))

    // Configure OpenAI client logging globally
    AIProvider.globalLogLevel = config.openAiLogLevel
}
