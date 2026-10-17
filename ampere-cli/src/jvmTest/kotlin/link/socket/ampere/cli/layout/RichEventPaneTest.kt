package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import link.socket.ampere.cli.watch.presentation.EventSignificance
import link.socket.ampere.cli.watch.presentation.SignificantEventSummary
import link.socket.ampere.terminal.TerminalSymbols
import org.junit.jupiter.api.Test

/** The event pane's on-device marker (AMPR-375). */
class RichEventPaneTest {

    @Test
    fun `a call that ran on this device carries the on-device marker and its own words`() {
        TerminalSymbols.reset()
        val pane = RichEventPane(Terminal())
        pane.updateEvents(
            listOf(
                summary(id = "on-device", text = "Answered on this device · 812 ms", onDevice = true),
                summary(id = "cloud", text = "ProviderCallCompleted", onDevice = false),
            ),
        )

        val lines = pane.render(width = 60, height = 20).map(::stripAnsi)
        val marked = lines.filter { it.contains(TerminalSymbols.Status.onDevice) }

        assertEquals(1, marked.size, lines.joinToString("\n"))
        assertTrue(marked.single().contains("1."), "the marker is the first row's icon")
        assertTrue(lines.any { it.contains("Answered on this device · 812 ms") }, lines.joinToString("\n"))
    }

    private fun summary(id: String, text: String, onDevice: Boolean) = SignificantEventSummary(
        eventId = id,
        timestamp = Clock.System.now(),
        eventType = "ProviderCallCompleted",
        sourceAgentName = "on-device-assistant",
        summaryText = text,
        significance = EventSignificance.SIGNIFICANT,
        onDevice = onDevice,
    )

    private fun stripAnsi(text: String): String = text.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
}
