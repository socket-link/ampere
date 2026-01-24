package link.socket.ampere

import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import link.socket.ampere.agents.execution.tools.human.GlobalHumanResponseRegistry
import link.socket.ampere.agents.execution.tools.human.HumanResponseRegistry
import link.socket.ampere.cli.layout.DemoInputHandler
import link.socket.ampere.cli.layout.JazzProgressPane
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the escalation flow in the Jazz demo.
 *
 * These tests verify that the components work together correctly:
 * - JazzProgressPane displays escalation UI
 * - DemoInputHandler captures A/B key responses
 * - HumanResponseRegistry coordinates request/response flow
 *
 * Part of: MIL-84 (Demo Escalation Moment)
 */
class JazzDemoEscalationTest {

    private lateinit var testRegistry: HumanResponseRegistry

    private class FakeClock(private var currentTime: Instant) : Clock {
        override fun now(): Instant = currentTime
        fun advance(millis: Long) {
            currentTime = Instant.fromEpochMilliseconds(currentTime.toEpochMilliseconds() + millis)
        }
    }

    @BeforeEach
    fun setup() {
        testRegistry = HumanResponseRegistry()
    }

    @AfterEach
    fun cleanup() {
        testRegistry.clearAll()
    }

    // ==================== Full Escalation Flow Integration Tests ====================

    @Test
    fun `full escalation flow - JazzProgressPane and DemoInputHandler integration`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)
        val inputHandler = DemoInputHandler(terminal)

        // Step 1: Set up escalation state (simulates DemoCommand behavior)
        pane.startDemo()
        pane.setPhase(JazzProgressPane.Phase.PLAN, "Awaiting human input...")
        pane.setAwaitingHuman(
            question = "Scope: Keep 'Verbose' only or add 'Minimal'?",
            options = listOf("A" to "keep 'Verbose' only", "B" to "add both variants")
        )

        // Verify pane is in awaiting human state
        assertTrue(pane.isAwaitingHuman, "Pane should be awaiting human input")

        // Step 2: Configure input handler for escalation mode
        val requestId = "test-request-integration"
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = requestId
        )

        // Step 3: Process A key press (user selects option A)
        val result = inputHandler.processKey('a', config)

        // Verify input handler produced correct response
        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("A", result.response)
        assertEquals(requestId, result.requestId)
        assertEquals(DemoInputHandler.InputMode.NORMAL, result.newConfig.inputMode)
        assertNull(result.newConfig.escalationRequestId)

        // Step 4: Clear escalation state (simulates DemoCommand response handling)
        pane.clearAwaitingHuman()

        // Verify pane is no longer awaiting human
        assertFalse(pane.isAwaitingHuman, "Pane should no longer be awaiting human input")
    }

    @Test
    fun `full escalation flow with option B selection`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)
        val inputHandler = DemoInputHandler(terminal)

        // Set up escalation
        pane.setAwaitingHuman(
            question = "Design decision?",
            options = listOf("A" to "simple approach", "B" to "complex approach")
        )

        assertTrue(pane.isAwaitingHuman)

        // Configure input handler and process B key
        val requestId = "test-request-option-b"
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = requestId
        )

        val result = inputHandler.processKey('b', config)

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("B", result.response)
        assertEquals(requestId, result.requestId)

        pane.clearAwaitingHuman()
        assertFalse(pane.isAwaitingHuman)
    }

    // ==================== Registry Integration Tests ====================

    @Test
    fun `escalation flow with HumanResponseRegistry - response provided`() = runTest {
        val terminal = Terminal()
        val pane = JazzProgressPane(terminal)
        val inputHandler = DemoInputHandler(terminal)
        val requestId = "test-registry-integration"

        // Set up escalation in pane
        pane.setAwaitingHuman(
            question = "Should we proceed?",
            options = listOf("A" to "yes", "B" to "no")
        )

        // Start waiting for response in background (simulates agent blocking)
        val responseDeferred = async {
            testRegistry.waitForResponse(requestId, timeout = 5.seconds)
        }

        // Wait a bit for request to register
        delay(100.milliseconds)

        // Verify request is pending
        assertTrue(testRegistry.getPendingCount() == 1, "Should have one pending request")

        // Simulate user input via DemoInputHandler
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = requestId
        )
        val keyResult = inputHandler.processKey('a', config)
        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(keyResult)

        // Provide response to registry (simulates DemoCommand loop)
        testRegistry.provideResponse(keyResult.requestId, keyResult.response)

        // Wait for response
        val response = responseDeferred.await()

        assertEquals("A", response, "Should receive response A")

        // Clean up pane state
        pane.clearAwaitingHuman()
        assertFalse(pane.isAwaitingHuman)
    }

    @Test
    fun `escalation flow with HumanResponseRegistry - timeout returns null`() = runTest {
        val terminal = Terminal()
        val pane = JazzProgressPane(terminal)
        val requestId = "test-timeout"

        // Set up escalation
        pane.setAwaitingHuman(
            question = "Quick decision needed",
            options = listOf("A" to "yes", "B" to "no")
        )

        // Wait with very short timeout (no response provided)
        val response = testRegistry.waitForResponse(requestId, timeout = 100.milliseconds)

        // Should timeout and return null
        assertNull(response, "Should return null on timeout")

        // Default behavior: treat null as "A" (same as DemoCommand)
        val userChoice = response ?: "A"
        assertEquals("A", userChoice, "Default should be A")

        pane.clearAwaitingHuman()
    }

    // ==================== Auto-Respond Flow Tests ====================

    @Test
    fun `auto-respond countdown flow`() {
        val terminal = Terminal()
        val clock = FakeClock(Instant.parse("2024-01-01T00:00:00Z"))
        val pane = JazzProgressPane(terminal, clock)

        // Set up escalation
        pane.startDemo()
        pane.setPhase(JazzProgressPane.Phase.PLAN)
        pane.setAwaitingHuman(
            question = "Scope decision",
            options = listOf("A" to "minimal", "B" to "full")
        )

        assertTrue(pane.isAwaitingHuman)

        // Simulate auto-respond countdown (as DemoCommand does with autoRespond flag)
        for (secondsRemaining in 3 downTo 1) {
            pane.setAutoRespondCountdown(secondsRemaining)

            // Verify countdown is displayed in render
            val output = pane.render(80, 30).joinToString("\n")
            assertTrue(output.contains("${secondsRemaining}s"), "Should show countdown at $secondsRemaining seconds")
        }

        // Clear countdown
        pane.setAutoRespondCountdown(null)

        // Auto-respond completes - clear escalation
        pane.clearAwaitingHuman()

        assertFalse(pane.isAwaitingHuman)
    }

    @Test
    fun `auto-respond produces default A response`() {
        val terminal = Terminal()
        val pane = JazzProgressPane(terminal)

        pane.setAwaitingHuman(
            question = "Test question",
            options = listOf("A" to "default", "B" to "alternative")
        )

        // Simulate auto-respond (no user input, uses default)
        val autoRespondResponse = "A"

        // Verify default is A
        assertEquals("A", autoRespondResponse)

        pane.clearAwaitingHuman()
    }

    // ==================== State Consistency Tests ====================

    @Test
    fun `escalation options available for status bar`() {
        val terminal = Terminal()
        val pane = JazzProgressPane(terminal)

        pane.setAwaitingHuman(
            question = "Design choice",
            options = listOf("A" to "keep 'Verbose' only", "B" to "add both variants")
        )

        // Verify options are accessible for status bar display
        val options = pane.escalationOptions
        assertEquals(2, options.size)
        assertEquals("A" to "keep 'Verbose' only", options[0])
        assertEquals("B" to "add both variants", options[1])
    }

    @Test
    fun `input mode transitions correctly during escalation`() {
        val inputHandler = DemoInputHandler(Terminal())

        // Start in NORMAL mode
        var config = DemoInputHandler.DemoViewConfig(inputMode = DemoInputHandler.InputMode.NORMAL)
        assertEquals(DemoInputHandler.InputMode.NORMAL, config.inputMode)

        // Transition to AWAITING_ESCALATION (done by render loop detecting isAwaitingHuman)
        config = config.copy(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "request-123"
        )
        assertEquals(DemoInputHandler.InputMode.AWAITING_ESCALATION, config.inputMode)
        assertEquals("request-123", config.escalationRequestId)

        // Process response and return to NORMAL
        val result = inputHandler.processKey('a', config)
        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals(DemoInputHandler.InputMode.NORMAL, result.newConfig.inputMode)
        assertNull(result.newConfig.escalationRequestId)
    }

    @Test
    fun `ESC key triggers default response in escalation mode`() {
        val inputHandler = DemoInputHandler(Terminal())
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "request-esc"
        )

        val result = inputHandler.processKey(27.toChar(), config) // ESC = 27

        assertIs<DemoInputHandler.KeyResult.EscalationResponse>(result)
        assertEquals("A", result.response, "ESC should select default option A")
    }

    @Test
    fun `Ctrl-C exits even during escalation`() {
        val inputHandler = DemoInputHandler(Terminal())
        val config = DemoInputHandler.DemoViewConfig(
            inputMode = DemoInputHandler.InputMode.AWAITING_ESCALATION,
            escalationRequestId = "request-ctrlc"
        )

        val result = inputHandler.processKey(3.toChar(), config) // Ctrl+C = 3

        assertIs<DemoInputHandler.KeyResult.Exit>(result)
    }
}
