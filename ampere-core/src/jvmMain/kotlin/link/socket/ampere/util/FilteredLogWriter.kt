package link.socket.ampere.util

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter

/**
 * LogWriter that filters messages below a minimum severity.
 *
 * This wrapper allows runtime control of log verbosity by filtering
 * messages before they reach the underlying platform logger.
 *
 * @param minSeverity Minimum severity level to log (messages below this are discarded)
 * @param delegate Underlying log writer (defaults to platform logger)
 */
class FilteredLogWriter(
    private val minSeverity: Severity,
    private val delegate: LogWriter = platformLogWriter(),
) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        if (severity >= minSeverity) {
            delegate.log(severity, message, tag, throwable)
        }
    }
}
