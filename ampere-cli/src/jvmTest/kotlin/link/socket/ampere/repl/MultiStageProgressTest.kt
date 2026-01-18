package link.socket.ampere.repl

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.ByteArrayOutputStream
import kotlin.test.assertTrue

class MultiStageProgressTest {

    private lateinit var outputStream: ByteArrayOutputStream
    private lateinit var terminal: Terminal

    @BeforeEach
    fun setup() {
        outputStream = ByteArrayOutputStream()
        terminal = TerminalBuilder.builder()
            .system(false)
            .streams(System.`in`, outputStream)
            .build()
    }

    @AfterEach
    fun tearDown() {
        terminal.close()
    }

    @Test
    fun `step renders in-progress format with lightning symbol`() {
        val progress = MultiStageProgress(terminal, totalSteps = 3, useUnicode = true)

        progress.step(1, "Initializing agents...")

        val output = outputStream.toString()
        assertTrue(output.contains("[1/3] ⚡ Initializing agents..."))
    }

    @Test
    fun `complete renders completed format with check symbol`() {
        val progress = MultiStageProgress(terminal, totalSteps = 3, useUnicode = true)

        progress.complete(1, "Step completed")

        val output = outputStream.toString()
        assertTrue(output.contains("[1/3] ✓ Step completed"))
    }

    @Test
    fun `step renders optional time estimation`() {
        val progress = MultiStageProgress(terminal, totalSteps = 4, useUnicode = true)

        progress.step(2, "Processing...", remainingSeconds = 30)

        val output = outputStream.toString()
        assertTrue(output.contains("[2/4] ⚡ Processing... (~30s remaining)"))
    }

    @Test
    fun `complete renders final step message`() {
        val progress = MultiStageProgress(terminal, totalSteps = 3, useUnicode = true)

        progress.complete(3, "System ready")

        val output = outputStream.toString()
        assertTrue(output.contains("[3/3] ✓ System ready"))
    }

    @Test
    fun `ascii fallback uses star and OK markers`() {
        val progress = MultiStageProgress(terminal, totalSteps = 2, useUnicode = false)

        progress.step(1, "Loading")
        progress.complete(1, "Ready")

        val output = outputStream.toString()
        assertTrue(output.contains("[1/2] * Loading"))
        assertTrue(output.contains("[1/2] [OK] Ready"))
    }
}
