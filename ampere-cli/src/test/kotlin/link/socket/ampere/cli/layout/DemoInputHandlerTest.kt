package link.socket.ampere.cli.layout

import com.github.ajalt.mordant.terminal.Terminal
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DemoInputHandlerTest {

    private val terminal = Terminal()

    // ==================== Escalation Response Tests ====================

    @Test
    fun `processKey returns EscalationResponse with A when a key pressed in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-123"
        )

        val result = handler.processKey('a', config)

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("test-request-123", result.requestId)
        assertEquals("A", result.response)
        assertEquals(DemoInputHandler.InputMode.NORMAL, result.newConfig.inputMode)
        assertNull(result.newConfig.escalationRequestId)
    }

    @Test
    fun `processKey returns EscalationResponse with A when uppercase A key pressed in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-456"
        )

        val result = handler.processKey('A', config)

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("A", result.response)
    }

    @Test
    fun `processKey returns EscalationResponse with A when 1 key pressed in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-789"
        )

        val result = handler.processKey('1', config)

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("A", result.response)
    }

    @Test
    fun `processKey returns EscalationResponse with B when b key pressed in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-abc"
        )

        val result = handler.processKey('b', config)

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("test-request-abc", result.requestId)
        assertEquals("B", result.response)
        assertEquals(DemoInputHandler.InputMode.NORMAL, result.newConfig.inputMode)
        assertNull(result.newConfig.escalationRequestId)
    }

    @Test
    fun `processKey returns EscalationResponse with B when uppercase B key pressed in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-def"
        )

        val result = handler.processKey('B', config)

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("B", result.response)
    }

    @Test
    fun `processKey returns EscalationResponse with B when 2 key pressed in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-ghi"
        )

        val result = handler.processKey('2', config)

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("B", result.response)
    }

    @Test
    fun `ESC key returns EscalationResponse with default A in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-esc"
        )

        val result = handler.processKey(27.toChar(), config) // ESC = 27

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("test-request-esc", result.requestId)
        assertEquals("A", result.response)
        assertEquals(DemoInputHandler.InputMode.NORMAL, result.newConfig.inputMode)
        assertNull(result.newConfig.escalationRequestId)
    }

    @Test
    fun `processKey returns NoChange for invalid keys in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-invalid"
        )

        // Test various keys that should be ignored
        val invalidKeys = listOf('c', 'd', 'x', '3', '9', '@', ' ')
        invalidKeys.forEach { key ->
            val result = handler.processKey(key, config)
            assertIs<DemoInputHandler.KeyResult.NoChange>(result, "Key '$key' should return NoChange in escalation mode")
        }
    }

    @Test
    fun `processKey returns NoChange if escalationRequestId is null in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = null // No request ID
        )

        val result = handler.processKey('a', config)

        assertIs<DemoInputHandler.KeyResult.NoChange>(result)
    }

    @Test
    fun `Ctrl+C returns Exit even in escalation mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "test-request-ctrlc"
        )

        val result = handler.processKey(3.toChar(), config) // Ctrl+C = 3

        assertIs<DemoInputHandler.KeyResult.Exit>(result)
    }

    // ==================== A/B keys do NOT trigger escalation in normal mode ====================

    @Test
    fun `a key enters agent selection mode in NORMAL mode (not escalation response)`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.NORMAL
        )

        val result = handler.processKey('a', config)

        assertIs<DemoInputHandler.KeyResult.ConfigChange>(result)
        assertEquals(DemoInputHandler.InputMode.AWAITING_AGENT, result.newConfig.inputMode)
    }

    @Test
    fun `b key does nothing in NORMAL mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.NORMAL
        )

        val result = handler.processKey('b', config)

        assertIs<DemoInputHandler.KeyResult.NoChange>(result)
    }

    @Test
    fun `1 key does nothing in NORMAL mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.NORMAL
        )

        val result = handler.processKey('1', config)

        assertIs<DemoInputHandler.KeyResult.NoChange>(result)
    }

    @Test
    fun `2 key does nothing in NORMAL mode`() {
        val handler = DemoInputHandler(terminal)
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.NORMAL
        )

        val result = handler.processKey('2', config)

        assertIs<DemoInputHandler.KeyResult.NoChange>(result)
    }

    // ==================== DemoViewConfig escalation fields ====================

    @Test
    fun `DemoViewConfig has escalationRequestId field`() {
        val config = DemoInputHandler.DemoViewConfig(
            escalationRequestId = "my-request-id"
        )

        assertEquals("my-request-id", config.escalationRequestId)
    }

    @Test
    fun `DemoViewConfig escalationRequestId defaults to null`() {
        val config = DemoInputHandler.DemoViewConfig()

        assertNull(config.escalationRequestId)
    }

    @Test
    fun `InputMode includes AWAITING_ESCALATION`() {
        val mode = DemoInputHandler.InputMode.AWAITING_ESCALATION

        assertEquals("AWAITING_ESCALATION", mode.name)
    }

    // ==================== EscalationResponse data class ====================

    @Test
    fun `EscalationResponse holds requestId, response, and newConfig`() {
        val newConfig = DemoInputHandler.DemoViewConfig()
        val escalationResponse = DemoInputHandler.KeyResult.EscalationResponse(
            requestId = "request-123",
            response = "A",
            newConfig = newConfig
        )

        assertEquals("request-123", escalationResponse.requestId)
        assertEquals("A", escalationResponse.response)
        assertEquals(newConfig, escalationResponse.newConfig)
    }
}
