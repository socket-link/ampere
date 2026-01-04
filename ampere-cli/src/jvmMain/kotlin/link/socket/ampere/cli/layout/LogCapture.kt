package link.socket.ampere.cli.layout

import java.io.OutputStream
import java.io.PrintStream

/**
 * Utility for capturing stdout/stderr and routing to a LogPane.
 *
 * This allows println() and printStackTrace() calls to appear in the
 * interactive TUI logging panel when verbose mode is enabled.
 */
object LogCapture {

    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var logPane: LogPane? = null
    private var isCapturing = false
    private var silentMode = false

    /**
     * Start capturing stdout/stderr and routing to the provided LogPane.
     *
     * @param pane The LogPane to write captured logs to
     * @param silent If true, logs are ONLY sent to LogPane and not echoed to terminal.
     *               Use this in TUI mode to prevent logs from interfering with rendering.
     * @param captureStdout If false, only capture stderr (useful when TUI renders to stdout)
     */
    fun start(pane: LogPane, silent: Boolean = false, captureStdout: Boolean = true) {
        if (isCapturing) {
            stop() // Stop previous capture
        }

        logPane = pane
        silentMode = silent
        originalOut = System.out
        originalErr = System.err

        // Create capturing print streams
        // Only redirect stdout if requested (TUI needs stdout for rendering)
        if (captureStdout) {
            val outStream = CapturingPrintStream(originalOut!!, pane, LogPane.LogLevel.INFO, silent)
            System.setOut(outStream)
        }

        // Always redirect stderr (where Kermit and EventBus logs go)
        // Use INFO level since logs already have their own severity markers ([🟢 EVENT], [🔴 CRITICAL], etc.)
        val errStream = CapturingPrintStream(originalErr!!, pane, LogPane.LogLevel.INFO, silent)
        System.setErr(errStream)

        isCapturing = true
    }

    /**
     * Stop capturing and restore original stdout/stderr.
     */
    fun stop() {
        if (!isCapturing) return

        // Restore stdout if it was redirected
        if (System.out != originalOut) {
            originalOut?.let { System.setOut(it) }
        }

        // Restore stderr (always redirected)
        originalErr?.let { System.setErr(it) }

        isCapturing = false
        logPane = null
        originalOut = null
        originalErr = null
    }

    /**
     * Check if currently capturing.
     */
    fun isCapturing(): Boolean = isCapturing

    /**
     * PrintStream that captures output and sends to LogPane.
     *
     * @param silent If true, only send to LogPane and don't echo to terminal
     */
    private class CapturingPrintStream(
        private val original: PrintStream,
        private val logPane: LogPane,
        private val level: LogPane.LogLevel,
        private val silent: Boolean
    ) : PrintStream(TeeOutputStream(original, logPane, level)) {

        private val lineBuffer = StringBuilder()

        override fun println(x: String?) {
            val message = x ?: ""

            // Send non-empty messages to log pane
            if (message.isNotEmpty()) {
                logPane.log(level, message)
            }

            // Only write to original stream if not in silent mode
            if (!silent) {
                original.println(x)
            }
        }

        override fun println() {
            // Empty line
            if (lineBuffer.isNotEmpty()) {
                logPane.log(level, lineBuffer.toString())
                lineBuffer.clear()
            }

            // Only write to original stream if not in silent mode
            if (!silent) {
                original.println()
            }
        }

        override fun print(x: String?) {
            x?.let { lineBuffer.append(it) }

            // Only write to original stream if not in silent mode
            if (!silent) {
                original.print(x)
            }
        }
    }

    /**
     * OutputStream that just delegates to the original stream.
     * Actual logging is handled in CapturingPrintStream.
     */
    private class TeeOutputStream(
        private val original: PrintStream,
        @Suppress("UNUSED_PARAMETER") private val logPane: LogPane,
        @Suppress("UNUSED_PARAMETER") private val level: LogPane.LogLevel
    ) : OutputStream() {

        override fun write(b: Int) {
            original.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            original.write(b, off, len)
        }

        override fun flush() {
            original.flush()
        }
    }
}
