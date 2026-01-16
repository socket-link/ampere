package link.socket.ampere.cli.layout

import java.io.OutputStream
import java.io.PrintStream

/**
 * Utility for capturing stdout/stderr and routing to a LogPane.
 *
 * This allows println() and printStackTrace() calls to appear in the
 * interactive TUI logging panel when verbose mode is enabled.
 *
 * When suppressOriginalOutput is true (default during TUI mode), output is
 * captured to the LogPane only and NOT written to the original stdout/stderr.
 * This prevents token counts and other debug output from leaking below the TUI.
 */
object LogCapture {

    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var logPane: LogPane? = null
    private var isCapturing = false
    private var suppressOriginal = true

    /**
     * Start capturing stdout/stderr and routing to the provided LogPane.
     *
     * @param pane The LogPane to route output to
     * @param suppressOriginalOutput If true (default), output is NOT written to the
     *        original stdout/stderr, only to the LogPane. This prevents output from
     *        leaking below the TUI.
     */
    fun start(pane: LogPane, suppressOriginalOutput: Boolean = true) {
        if (isCapturing) {
            stop() // Stop previous capture
        }

        logPane = pane
        originalOut = System.out
        originalErr = System.err
        suppressOriginal = suppressOriginalOutput

        // Create capturing print streams
        val outStream = CapturingPrintStream(originalOut!!, pane, LogPane.LogLevel.INFO, suppressOriginal)
        val errStream = CapturingPrintStream(originalErr!!, pane, LogPane.LogLevel.ERROR, suppressOriginal)

        System.setOut(outStream)
        System.setErr(errStream)

        isCapturing = true
    }

    /**
     * Stop capturing and restore original stdout/stderr.
     */
    fun stop() {
        if (!isCapturing) return

        originalOut?.let { System.setOut(it) }
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
     * @param original The original PrintStream to optionally forward to
     * @param logPane The LogPane to send captured output to
     * @param level The log level to use for captured output
     * @param suppressOriginal If true, don't write to the original stream (TUI mode)
     */
    private class CapturingPrintStream(
        private val original: PrintStream,
        private val logPane: LogPane,
        private val level: LogPane.LogLevel,
        private val suppressOriginal: Boolean
    ) : PrintStream(SuppressibleOutputStream(original, suppressOriginal)) {

        private val lineBuffer = StringBuilder()

        override fun println(x: String?) {
            val message = x ?: ""

            // Send non-empty messages to log pane
            if (message.isNotEmpty()) {
                logPane.log(level, message)
            }

            // Only write to original if not suppressed
            if (!suppressOriginal) {
                original.println(x)
            }
        }

        override fun println() {
            // Empty line
            if (lineBuffer.isNotEmpty()) {
                logPane.log(level, lineBuffer.toString())
                lineBuffer.clear()
            }
            if (!suppressOriginal) {
                original.println()
            }
        }

        override fun print(x: String?) {
            x?.let { lineBuffer.append(it) }
            if (!suppressOriginal) {
                original.print(x)
            }
        }
    }

    /**
     * OutputStream that optionally suppresses output to the original stream.
     * When suppressed, write operations are no-ops (output only goes to LogPane via println/print).
     */
    private class SuppressibleOutputStream(
        private val original: PrintStream,
        private val suppress: Boolean
    ) : OutputStream() {

        override fun write(b: Int) {
            if (!suppress) {
                original.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (!suppress) {
                original.write(b, off, len)
            }
        }

        override fun flush() {
            if (!suppress) {
                original.flush()
            }
        }
    }
}
