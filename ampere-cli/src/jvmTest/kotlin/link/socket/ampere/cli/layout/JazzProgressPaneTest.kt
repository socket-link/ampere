package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JazzProgressPaneTest {

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
    }

    private class FakeClock(private var currentTime: Instant) : Clock {
        override fun now(): Instant = currentTime
        fun advance(millis: Long) {
            currentTime = Instant.fromEpochMilliseconds(currentTime.toEpochMilliseconds() + millis)
        }
    }

    @Test
    fun `isAwaitingHuman returns false when no escalation is set`() {
        val terminal = Terminal()
        val pane = JazzProgressPane(terminal)

        assertFalse(pane.isAwaitingHuman)
    }

    @Test
    fun `setAwaitingHuman sets escalation state`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        pane.setPhase(JazzProgressPane.Phase.PLAN)
        pane.setAwaitingHuman(
            question = "Keep 'Verbose' only or add 'Minimal'?",
            options = listOf("A" to "keep minimal", "B" to "add both")
        )

        assertTrue(pane.isAwaitingHuman)
    }

    @Test
    fun `clearAwaitingHuman clears escalation state`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        pane.setPhase(JazzProgressPane.Phase.PLAN)
        pane.setAwaitingHuman(
            question = "Keep 'Verbose' only or add 'Minimal'?",
            options = listOf("A" to "keep minimal", "B" to "add both")
        )
        assertTrue(pane.isAwaitingHuman)

        pane.clearAwaitingHuman()
        assertFalse(pane.isAwaitingHuman)
    }

    @Test
    fun `render shows awaiting human status during PLAN phase`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        pane.startDemo()
        pane.setPhase(JazzProgressPane.Phase.PLAN)
        clock.advance(3000) // 3 seconds
        pane.setAwaitingHuman(
            question = "Keep 'Verbose' only or add 'Minimal'?",
            options = listOf("A" to "keep minimal", "B" to "add both")
        )

        val output = pane.render(80, 30)
        val plainOutput = output.map { stripAnsi(it) }.joinToString("\n")

        assertTrue(plainOutput.contains("Awaiting human input"), "Output should contain 'Awaiting human input'")
        assertTrue(plainOutput.contains("Keep 'Verbose' only or add 'Minimal'?"), "Output should contain the escalation question")
        assertTrue(plainOutput.contains("[A] keep minimal"), "Output should contain option A")
        assertTrue(plainOutput.contains("[B] add both"), "Output should contain option B")
    }

    @Test
    fun `render shows hourglass indicator when awaiting human`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        pane.startDemo()
        pane.setPhase(JazzProgressPane.Phase.PLAN)
        pane.setAwaitingHuman(
            question = "Test question?",
            options = listOf("A" to "option a")
        )

        val output = pane.render(80, 30)
        val plainOutput = output.map { stripAnsi(it) }.joinToString("\n")

        // Check that hourglass indicator is present
        assertTrue(plainOutput.contains("⏳"), "Output should contain hourglass indicator")
    }

    @Test
    fun `JazzState isAwaitingHuman property works correctly`() {
        val stateWithoutEscalation = JazzProgressPane.JazzState(
            phase = JazzProgressPane.Phase.PLAN
        )
        assertFalse(stateWithoutEscalation.isAwaitingHuman)

        val stateWithEscalation = JazzProgressPane.JazzState(
            phase = JazzProgressPane.Phase.PLAN,
            escalation = JazzProgressPane.EscalationInfo(
                question = "Test?",
                options = listOf(JazzProgressPane.EscalationOption("A", "test")),
                startTime = Instant.parse("2024-01-01T00:00:00Z")
            )
        )
        assertTrue(stateWithEscalation.isAwaitingHuman)
    }

    @Test
    fun `EscalationInfo stores question and options correctly`() {
        val escalation = JazzProgressPane.EscalationInfo(
            question = "Should I proceed?",
            options = listOf(
                JazzProgressPane.EscalationOption("A", "yes"),
                JazzProgressPane.EscalationOption("B", "no")
            ),
            startTime = Instant.parse("2024-01-01T00:00:00Z")
        )

        assertEquals("Should I proceed?", escalation.question)
        assertEquals(2, escalation.options.size)
        assertEquals("A", escalation.options[0].key)
        assertEquals("yes", escalation.options[0].label)
        assertEquals("B", escalation.options[1].key)
        assertEquals("no", escalation.options[1].label)
    }

    @Test
    fun `render shows normal PLAN phase when not awaiting human`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        pane.startDemo()
        pane.setPhase(JazzProgressPane.Phase.PLAN, "Creating plan")

        val output = pane.render(80, 30)
        val plainOutput = output.map { stripAnsi(it) }.joinToString("\n")

        // Should show normal PLAN phase status, not awaiting human
        assertFalse(plainOutput.contains("Awaiting human input"), "Should not show awaiting human when not set")
        assertTrue(plainOutput.contains("PLAN"), "Should show PLAN phase")
        assertTrue(plainOutput.contains("creating plan..."), "Should show creating plan details")
    }
}
