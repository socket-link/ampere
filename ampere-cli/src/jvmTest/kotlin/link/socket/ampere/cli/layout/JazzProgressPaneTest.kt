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

    @Test
    fun `escalationOptions returns empty list when no escalation`() {
        val terminal = Terminal()
        val pane = JazzProgressPane(terminal)

        assertTrue(pane.escalationOptions.isEmpty(), "Should return empty list when no escalation")
    }

    @Test
    fun `escalationOptions returns options as key-label pairs`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        pane.setAwaitingHuman(
            question = "Test question?",
            options = listOf("A" to "option A", "B" to "option B")
        )

        val options = pane.escalationOptions
        assertEquals(2, options.size)
        assertEquals("A" to "option A", options[0])
        assertEquals("B" to "option B", options[1])
    }

    @Test
    fun `escalationOptions clears after clearAwaitingHuman`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        pane.setAwaitingHuman(
            question = "Test?",
            options = listOf("X" to "test")
        )
        assertEquals(1, pane.escalationOptions.size)

        pane.clearAwaitingHuman()
        assertTrue(pane.escalationOptions.isEmpty(), "Should be empty after clearing")
    }

    // ==================== Auto-Respond Countdown Tests ====================

    @Test
    fun `setAutoRespondCountdown sets countdown value`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        // Must start demo and set PLAN phase for countdown to display
        pane.startDemo()
        pane.setPhase(JazzProgressPane.Phase.PLAN)
        pane.setAwaitingHuman(
            question = "Test question?",
            options = listOf("A" to "yes", "B" to "no")
        )

        pane.setAutoRespondCountdown(3)

        // Render and verify countdown appears
        val output = pane.render(80, 30)
        val plainOutput = output.map { stripAnsi(it) }.joinToString("\n")

        assertTrue(plainOutput.contains("Auto-responding with [A] in 3s"), "Should show countdown")
    }

    @Test
    fun `setAutoRespondCountdown with null clears countdown`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        // Must start demo and set PLAN phase for countdown to display
        pane.startDemo()
        pane.setPhase(JazzProgressPane.Phase.PLAN)
        pane.setAwaitingHuman(
            question = "Test question?",
            options = listOf("A" to "yes")
        )
        pane.setAutoRespondCountdown(3)

        // Verify countdown is present
        var output = pane.render(80, 30)
        var plainOutput = output.map { stripAnsi(it) }.joinToString("\n")
        assertTrue(plainOutput.contains("Auto-responding"), "Countdown should be present")

        // Clear countdown
        pane.setAutoRespondCountdown(null)

        // Verify countdown is gone
        output = pane.render(80, 30)
        plainOutput = output.map { stripAnsi(it) }.joinToString("\n")
        assertFalse(plainOutput.contains("Auto-responding"), "Countdown should be cleared")
    }

    @Test
    fun `setAutoRespondCountdown does nothing when not awaiting human`() {
        val terminal = Terminal()
        val pane = JazzProgressPane(terminal)

        // Try to set countdown without awaiting human state
        pane.setAutoRespondCountdown(5)

        // Should not crash, and since not awaiting human, no countdown visible
        val output = pane.render(80, 30)
        val plainOutput = output.map { stripAnsi(it) }.joinToString("\n")

        assertFalse(plainOutput.contains("Auto-responding"), "Should not show countdown when not awaiting human")
    }

    @Test
    fun `render shows countdown updates correctly`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        pane.startDemo()
        pane.setPhase(JazzProgressPane.Phase.PLAN)
        pane.setAwaitingHuman(
            question = "Scope decision?",
            options = listOf("A" to "minimal", "B" to "full")
        )

        // Test countdown progression
        pane.setAutoRespondCountdown(3)
        var output = pane.render(80, 30)
        assertTrue(output.map { stripAnsi(it) }.joinToString("\n").contains("3s"))

        pane.setAutoRespondCountdown(2)
        output = pane.render(80, 30)
        assertTrue(output.map { stripAnsi(it) }.joinToString("\n").contains("2s"))

        pane.setAutoRespondCountdown(1)
        output = pane.render(80, 30)
        assertTrue(output.map { stripAnsi(it) }.joinToString("\n").contains("1s"))
    }

    @Test
    fun `EscalationInfo autoRespondSecondsRemaining defaults to null`() {
        val escalation = JazzProgressPane.EscalationInfo(
            question = "Test?",
            options = listOf(JazzProgressPane.EscalationOption("A", "test")),
            startTime = Instant.parse("2024-01-01T00:00:00Z")
        )

        assertNull(escalation.autoRespondSecondsRemaining)
    }

    @Test
    fun `EscalationInfo can store autoRespondSecondsRemaining`() {
        val escalation = JazzProgressPane.EscalationInfo(
            question = "Test?",
            options = listOf(JazzProgressPane.EscalationOption("A", "test")),
            startTime = Instant.parse("2024-01-01T00:00:00Z"),
            autoRespondSecondsRemaining = 5
        )

        assertEquals(5, escalation.autoRespondSecondsRemaining)
    }
}
