package link.socket.ampere

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Test
import kotlin.test.assertContains

/**
 * Tests for WatchCommand.
 *
 * Note: These tests only verify command-line help and option parsing.
 * Full integration testing with live event streaming is done through manual testing
 * or would require complex mocking of the EventRelayService.
 */
class WatchCommandTest {

    @Test
    fun `watch command help text shows usage`() {
        val command = WatchCommand()
        val result = command.test("--help")

        assertContains(result.output, "Watch events streaming")
        assertContains(result.output, "Examples")
        assertContains(result.output, "ampere watch")
        assertContains(result.output, "--filter")
        assertContains(result.output, "--agent")
    }

    @Test
    fun `watch command help shows filter option description`() {
        val command = WatchCommand()
        val result = command.test("--help")

        assertContains(result.output, "Filter by event type")
        assertContains(result.output, "Repeatable")
    }

    @Test
    fun `watch command help shows agent option description`() {
        val command = WatchCommand()
        val result = command.test("--help")

        assertContains(result.output, "Filter by agent ID")
        assertContains(result.output, "agent-pm")
    }

    @Test
    fun `watch command help shows examples for filtering`() {
        val command = WatchCommand()
        val result = command.test("--help")

        assertContains(result.output, "TaskCreated")
        assertContains(result.output, "QuestionRaised")
    }
}
