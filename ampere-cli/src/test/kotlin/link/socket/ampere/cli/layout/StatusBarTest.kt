package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusBarTest {

    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
    }

    @Test
    fun `escalationShortcuts creates shortcuts from options`() {
        val options = listOf(
            "A" to "Option A",
            "B" to "Option B"
        )

        val shortcuts = StatusBar.escalationShortcuts(options)

        assertEquals(3, shortcuts.size, "Should have 3 shortcuts (2 options + ESC)")
        assertEquals("A", shortcuts[0].key)
        assertEquals("Option A", shortcuts[0].label)
        assertEquals("B", shortcuts[1].key)
        assertEquals("Option B", shortcuts[1].label)
        assertEquals("ESC", shortcuts[2].key)
        assertEquals("skip", shortcuts[2].label)
    }

    @Test
    fun `escalationShortcuts always adds ESC skip at end`() {
        val options = listOf("X" to "Single option")

        val shortcuts = StatusBar.escalationShortcuts(options)

        assertEquals(2, shortcuts.size)
        assertEquals("ESC", shortcuts.last().key)
        assertEquals("skip", shortcuts.last().label)
    }

    @Test
    fun `escalationShortcuts handles empty options`() {
        val shortcuts = StatusBar.escalationShortcuts(emptyList())

        assertEquals(1, shortcuts.size, "Should only have ESC shortcut")
        assertEquals("ESC", shortcuts[0].key)
        assertEquals("skip", shortcuts[0].label)
    }

    @Test
    fun `render with escalation shortcuts displays them instead of normal shortcuts`() {
        val terminal = Terminal()
        val statusBar = StatusBar(terminal)

        val normalShortcuts = StatusBar.defaultShortcuts("events")
        val escalationShortcuts = listOf(
            StatusBar.EscalationShortcut("A", "keep Verbose"),
            StatusBar.EscalationShortcut("B", "add both"),
            StatusBar.EscalationShortcut("ESC", "skip")
        )

        val output = statusBar.render(
            width = 100,
            shortcuts = normalShortcuts,
            status = StatusBar.SystemStatus.WAITING,
            escalationShortcuts = escalationShortcuts
        )
        val plainOutput = stripAnsi(output)

        // Should show escalation shortcuts
        assertTrue(plainOutput.contains("[A]"), "Should contain [A] shortcut")
        assertTrue(plainOutput.contains("keep Verbose"), "Should contain 'keep Verbose' label")
        assertTrue(plainOutput.contains("[B]"), "Should contain [B] shortcut")
        assertTrue(plainOutput.contains("add both"), "Should contain 'add both' label")
        assertTrue(plainOutput.contains("[ESC]"), "Should contain [ESC] shortcut")
        assertTrue(plainOutput.contains("skip"), "Should contain 'skip' label")

        // Should NOT show normal shortcuts
        assertFalse(plainOutput.contains("[d]"), "Should not contain [d] shortcut")
        assertFalse(plainOutput.contains("[e]"), "Should not contain [e] shortcut")
        assertFalse(plainOutput.contains("[m]"), "Should not contain [m] shortcut")
        assertFalse(plainOutput.contains("[q]"), "Should not contain [q] shortcut")
    }

    @Test
    fun `render without escalation shortcuts shows normal shortcuts`() {
        val terminal = Terminal()
        val statusBar = StatusBar(terminal)

        val normalShortcuts = StatusBar.defaultShortcuts("events")

        val output = statusBar.render(
            width = 100,
            shortcuts = normalShortcuts,
            status = StatusBar.SystemStatus.WORKING,
            escalationShortcuts = null
        )
        val plainOutput = stripAnsi(output)

        // Should show normal shortcuts
        assertTrue(plainOutput.contains("[d]"), "Should contain [d] shortcut")
        assertTrue(plainOutput.contains("[e]"), "Should contain [e] shortcut")
        assertTrue(plainOutput.contains("[m]"), "Should contain [m] shortcut")
        assertTrue(plainOutput.contains("[q]"), "Should contain [q] shortcut")

        // Should NOT show escalation-style shortcuts
        assertFalse(plainOutput.contains("[A]"), "Should not contain [A] shortcut")
        assertFalse(plainOutput.contains("[B]"), "Should not contain [B] shortcut")
    }

    @Test
    fun `render with escalation shortcuts shows WAITING status`() {
        val terminal = Terminal()
        val statusBar = StatusBar(terminal)

        val escalationShortcuts = StatusBar.escalationShortcuts(listOf("A" to "opt A"))

        val output = statusBar.render(
            width = 100,
            shortcuts = emptyList(),
            status = StatusBar.SystemStatus.WAITING,
            escalationShortcuts = escalationShortcuts
        )
        val plainOutput = stripAnsi(output)

        assertTrue(plainOutput.contains("WAITING"), "Should contain WAITING status")
    }

    @Test
    fun `render with empty escalation shortcuts list falls back to normal behavior`() {
        val terminal = Terminal()
        val statusBar = StatusBar(terminal)

        val normalShortcuts = StatusBar.defaultShortcuts()

        val output = statusBar.render(
            width = 100,
            shortcuts = normalShortcuts,
            status = StatusBar.SystemStatus.IDLE,
            escalationShortcuts = emptyList()
        )
        val plainOutput = stripAnsi(output)

        // Empty list should be treated as null, showing normal shortcuts
        assertTrue(plainOutput.contains("[d]"), "Should contain [d] shortcut with empty escalation list")
    }

    @Test
    fun `EscalationShortcut data class stores key and label`() {
        val shortcut = StatusBar.EscalationShortcut("A", "Test Label")

        assertEquals("A", shortcut.key)
        assertEquals("Test Label", shortcut.label)
    }
}
