package link.socket.ampere.repl

import com.github.ajalt.mordant.rendering.AnsiLevel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.terminal.Size
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgressIndicatorTest {

    private lateinit var outputStream: ByteArrayOutputStream
    private lateinit var terminal: Terminal
    private lateinit var writer: PrintWriter

    private fun stripAnsiSequences(output: String): String {
        return output.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"), "")
    }

    private fun lastProgressBar(output: String): String {
        val stripped = stripAnsiSequences(output)
        val start = stripped.lastIndexOf('[')
        val end = stripped.indexOf(']', start + 1)
        assertTrue(start >= 0 && end > start, "Expected to find progress bar brackets in output")
        return stripped.substring(start + 1, end)
    }

    @BeforeEach
    fun setup() {
        outputStream = ByteArrayOutputStream()
        terminal = TerminalBuilder.builder()
            .system(false)
            .streams(System.`in`, outputStream)
            .build()
        writer = terminal.writer()

        // Reset TerminalFactory for consistent tests
        TerminalFactory.reset()
    }

    @AfterEach
    fun tearDown() {
        terminal.close()
        TerminalFactory.reset()
    }

    @Test
    fun `frame interval is 50ms for 20fps max`() {
        assertEquals(50L, BaseProgressIndicator.FRAME_INTERVAL_MS)
    }

    @Test
    fun `builder creates spinner indicator by default`() {
        val indicator = ProgressIndicatorBuilder(terminal).build()
        assertIs<SpinnerIndicator>(indicator)
    }

    @Test
    fun `builder creates progress bar indicator when mode is PROGRESS_BAR`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.PROGRESS_BAR)
            .build()
        assertIs<ProgressBarIndicator>(indicator)
    }

    @Test
    fun `builder creates state indicator when mode is STATE`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.STATE)
            .build()
        assertIs<StateIndicator>(indicator)
    }

    @Test
    fun `builder applies message correctly`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .message("Test message")
            .build() as SpinnerIndicator

        indicator.start()
        Thread.sleep(60) // Wait for at least one frame
        indicator.stop()

        val output = outputStream.toString()
        assertTrue(output.contains("Test message"), "Output should contain the message")
    }

    @Test
    fun `builder applies capabilities correctly`() {
        val capabilities = TerminalFactory.TerminalCapabilities(
            supportsUnicode = false,
            supportsColors = false,
            colorLevel = AnsiLevel.NONE,
            isInteractive = true,
            width = 80,
            height = 24
        )

        val indicator = ProgressIndicatorBuilder(terminal)
            .withCapabilities(capabilities)
            .mode(IndicatorMode.SPINNER)
            .build() as SpinnerIndicator

        indicator.start()
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        // Should use ASCII frames (|, /, -, \) instead of Braille characters
        assertTrue(
            output.contains("|") || output.contains("/") || output.contains("-") || output.contains("\\"),
            "Should use ASCII spinner characters when Unicode is disabled"
        )
    }

    @Test
    fun `spinner uses static indicator when non-interactive`() {
        val capabilities = TerminalFactory.TerminalCapabilities(
            supportsUnicode = false,
            supportsColors = false,
            colorLevel = AnsiLevel.NONE,
            isInteractive = false,
            width = 80,
            height = 24
        )

        val indicator = ProgressIndicatorBuilder(terminal)
            .withCapabilities(capabilities)
            .mode(IndicatorMode.SPINNER)
            .message("Loading")
            .build()

        indicator.start()
        indicator.stop()

        val output = outputStream.toString()
        assertTrue(output.contains(TerminalSymbols.Spinner.staticIndicator), "Should show static indicator")
        assertTrue(output.contains("Loading"), "Should include the message")
    }

    @Test
    fun `spinner complete shows success symbol with colors`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.SPINNER)
            .message("Done")
            .useColors(true)
            .useUnicode(true)
            .build()

        indicator.complete(success = true)

        val output = outputStream.toString()
        assertTrue(output.contains("Done"), "Output should contain the message")
    }

    @Test
    fun `spinner complete shows error symbol on failure`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.SPINNER)
            .message("Failed")
            .useColors(false)
            .useUnicode(false)
            .build()

        indicator.complete(success = false)

        val output = outputStream.toString()
        assertTrue(output.contains("[X]"), "Should show ASCII error symbol when Unicode disabled")
        assertTrue(output.contains("Failed"), "Output should contain the message")
    }

    @Test
    fun `progress bar shows percentage in determinate mode`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.PROGRESS_BAR)
            .message("Downloading")
            .useColors(false)
            .useUnicode(false)
            .build()

        indicator.start()
        indicator.update(progress = 0.5f)
        Thread.sleep(60) // Wait for render
        indicator.stop()

        val output = outputStream.toString()
        assertTrue(output.contains("50%"), "Should show 50% progress")
    }

    @Test
    fun `progress bar clamps progress to valid range`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.PROGRESS_BAR)
            .message("Test")
            .useColors(false)
            .useUnicode(false)
            .build()

        indicator.start()
        indicator.update(progress = 1.5f) // Should clamp to 1.0
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        assertTrue(output.contains("100%"), "Progress should be clamped to 100%")
    }

    @Test
    fun `progress bar shows indeterminate animation when no progress`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.PROGRESS_BAR)
            .message("Loading")
            .useColors(false)
            .useUnicode(false)
            .build()

        indicator.start()
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        // Indeterminate mode doesn't show percentage
        assertTrue(output.contains("["), "Should show progress bar brackets")
        assertTrue(output.contains("]"), "Should show progress bar brackets")
    }

    @Test
    fun `state indicator shows waiting state`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.STATE)
            .message("Waiting")
            .useColors(false)
            .useUnicode(false)
            .build() as StateIndicator

        indicator.start()
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        assertTrue(output.contains("Waiting"), "Should show the message")
    }

    @Test
    fun `state indicator can change state`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.STATE)
            .message("Processing")
            .useColors(false)
            .useUnicode(false)
            .build() as StateIndicator

        indicator.start()
        indicator.setState(IndicatorState.SUCCESS)
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        assertTrue(output.contains("[OK]"), "Should show success symbol")
    }

    @Test
    fun `state indicator complete shows success symbol`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.STATE)
            .message("Complete")
            .useColors(false)
            .useUnicode(true)
            .build()

        indicator.complete(success = true)

        val output = outputStream.toString()
        assertTrue(output.contains("Complete"), "Should show the message")
    }

    @Test
    fun `state indicator complete shows error symbol on failure`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.STATE)
            .message("Failed")
            .useColors(false)
            .useUnicode(false)
            .build()

        indicator.complete(success = false)

        val output = outputStream.toString()
        assertTrue(output.contains("[X]"), "Should show error symbol")
    }

    @Test
    fun `update changes message for spinner`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.SPINNER)
            .message("Initial")
            .useColors(false)
            .useUnicode(false)
            .build()

        indicator.start()
        Thread.sleep(60)
        indicator.update(message = "Updated")
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        assertTrue(output.contains("Updated"), "Should contain updated message")
    }

    @Test
    fun `stop clears the line`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.SPINNER)
            .message("Test")
            .build()

        indicator.start()
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        // Should contain clear line sequence
        assertTrue(output.contains("\u001b[2K"), "Should contain clear line ANSI sequence")
    }

    @Test
    fun `stop shows cursor`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.SPINNER)
            .message("Test")
            .build()

        indicator.start()
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        // Should contain show cursor sequence
        assertTrue(output.contains("\u001b[?25h"), "Should contain show cursor ANSI sequence")
    }

    @Test
    fun `SimpleSpinner provides backward compatibility`() {
        val spinner = SimpleSpinner(terminal)
        spinner.start("Loading...")
        Thread.sleep(60)
        spinner.stop()

        val output = outputStream.toString()
        assertTrue(output.contains("Loading..."), "SimpleSpinner should work with old API")
    }

    @Test
    fun `extension function createSpinner creates spinner`() {
        val indicator = terminal.createSpinner("Test")
        assertIs<SpinnerIndicator>(indicator)
    }

    @Test
    fun `extension function createProgressBar creates progress bar`() {
        val indicator = terminal.createProgressBar("Test")
        assertIs<ProgressBarIndicator>(indicator)
    }

    @Test
    fun `extension function createStateIndicator creates state indicator`() {
        val indicator = terminal.createStateIndicator("Test")
        assertIs<StateIndicator>(indicator)
    }

    @Test
    fun `indicator modes enum has all expected values`() {
        val modes = IndicatorMode.entries
        assertEquals(3, modes.size)
        assertTrue(modes.contains(IndicatorMode.SPINNER))
        assertTrue(modes.contains(IndicatorMode.PROGRESS_BAR))
        assertTrue(modes.contains(IndicatorMode.STATE))
    }

    @Test
    fun `indicator states enum has all expected values`() {
        val states = IndicatorState.entries
        assertEquals(3, states.size)
        assertTrue(states.contains(IndicatorState.WAITING))
        assertTrue(states.contains(IndicatorState.SUCCESS))
        assertTrue(states.contains(IndicatorState.ERROR))
    }

    @Test
    fun `progress bar uses unicode characters when enabled`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.PROGRESS_BAR)
            .message("Test")
            .useColors(false)
            .useUnicode(true)
            .build()

        indicator.start()
        indicator.update(progress = 0.5f)
        Thread.sleep(60)
        indicator.complete(success = true)

        val output = outputStream.toString()
        // Unicode progress bar uses block characters
        assertTrue(
            output.contains("█") || output.contains("░") || output.contains("▓"),
            "Should use Unicode block characters"
        )
    }

    @Test
    fun `progress bar uses ascii characters when unicode disabled`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.PROGRESS_BAR)
            .message("Test")
            .useColors(false)
            .useUnicode(false)
            .build()

        indicator.start()
        indicator.update(progress = 0.5f)
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        // ASCII progress bar uses # and -
        assertTrue(
            output.contains("#") || output.contains("-"),
            "Should use ASCII characters for progress bar"
        )
    }

    @Test
    fun `progress bar width scales with terminal size and caps at 40`() {
        terminal.setSize(Size(120, 24))
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.PROGRESS_BAR)
            .message("")
            .useColors(false)
            .useUnicode(false)
            .build()

        indicator.start()
        indicator.update(progress = 0.25f)
        Thread.sleep(60)
        indicator.stop()

        val wideOutput = outputStream.toString()
        val wideBar = lastProgressBar(wideOutput)
        assertEquals(40, wideBar.length, "Bar width should cap at 40 characters")

        outputStream.reset()
        terminal.setSize(Size(30, 24))
        indicator.start()
        indicator.update(progress = 0.25f)
        Thread.sleep(60)
        indicator.stop()

        val narrowOutput = outputStream.toString()
        val narrowBar = lastProgressBar(narrowOutput)
        assertEquals(22, narrowBar.length, "Bar width should scale down for small terminals")
    }

    @Test
    fun `progress bar indeterminate mode uses scanning block character`() {
        val indicator = ProgressIndicatorBuilder(terminal)
            .mode(IndicatorMode.PROGRESS_BAR)
            .message("")
            .useColors(false)
            .useUnicode(true)
            .build()

        indicator.start()
        Thread.sleep(60)
        indicator.stop()

        val output = outputStream.toString()
        assertTrue(output.contains("▓"), "Indeterminate bar should use scanning block character")
    }
}
