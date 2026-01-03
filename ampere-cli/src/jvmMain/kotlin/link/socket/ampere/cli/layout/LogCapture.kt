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

    /**
     * Start capturing stdout/stderr and routing to the provided LogPane.
     */
    fun start(pane: LogPane) {
        if (isCapturing) {
            stop() // Stop previous capture
        }

        logPane = pane
        originalOut = System.out
        originalErr = System.err

        // Create capturing print streams
        val outStream = CapturingPrintStream(originalOut!!, pane, LogPane.LogLevel.INFO)
        val errStream = CapturingPrintStream(originalErr!!, pane, LogPane.LogLevel.ERROR)

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
     */
    private class CapturingPrintStream(
        private val original: PrintStream,
        private val logPane: LogPane,
        private val level: LogPane.LogLevel
    ) : PrintStream(TeeOutputStream(original, logPane, level)) {

        private val lineBuffer = StringBuilder()

        override fun println(x: String?) {
            val message = x ?: ""

            // Send non-empty messages to log pane
            if (message.isNotEmpty()) {
                logPane.log(level, message)
            }

            // Also write to original stream
            original.println(x)
        }

        override fun println() {
            // Empty line
            if (lineBuffer.isNotEmpty()) {
                logPane.log(level, lineBuffer.toString())
                lineBuffer.clear()
            }
            original.println()
        }

        override fun print(x: String?) {
            x?.let { lineBuffer.append(it) }
            original.print(x)
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
